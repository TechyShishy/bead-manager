package com.techyshishy.beadmanager.data.firestore

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Source
import com.techyshishy.beadmanager.BuildConfig
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Firestore data source for the user's project collection.
 *
 * Collection path: users/{uid}/projects  (release)
 *                  users_debug/{uid}/projects  (debug)
 *
 * Mirrors the collection path convention from [FirestoreInventorySource].
 */
@Singleton
class FirestoreProjectSource @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val auth: FirebaseAuth,
) {
    private val usersCollection = if (BuildConfig.DEBUG) "users_debug" else "users"

    companion object {
        /** Maximum number of [ProjectRgpRow]s stored per grid subcollection chunk document. */
        const val GRID_CHUNK_SIZE = 200
    }

    private fun projectsRef(uid: String): CollectionReference =
        firestore.collection(usersCollection).document(uid).collection("projects")

    private fun gridRef(uid: String, projectId: String): CollectionReference =
        projectsRef(uid).document(projectId).collection("grid")

    private fun requireUid(): String =
        auth.currentUser?.uid ?: error("No signed-in user — project access requires authentication.")

    fun projectsStream(): Flow<List<ProjectEntry>> {
        val uid = auth.currentUser?.uid ?: return flowOf(emptyList())
        return callbackFlow {
            val registration = projectsRef(uid)
                .orderBy("createdAt", Query.Direction.ASCENDING)
                .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e("FirestoreProject", "Snapshot listener error", error)
                    return@addSnapshotListener
                }
                if (snapshot == null) return@addSnapshotListener
                val entries = snapshot.documents.mapNotNull { it.toObject(ProjectEntry::class.java) }
                trySend(entries)
            }
            awaitClose { registration.remove() }
        }
    }

    fun projectStream(projectId: String): Flow<ProjectEntry?> {
        val uid = auth.currentUser?.uid ?: return flowOf(null)
        return callbackFlow {
            val registration = projectsRef(uid).document(projectId)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        Log.e("FirestoreProject", "Single project stream error for $projectId", error)
                        return@addSnapshotListener
                    }
                    if (snapshot == null) return@addSnapshotListener
                    trySend(snapshot.toObject(ProjectEntry::class.java))
                }
            awaitClose { registration.remove() }
        }
    }

    /**
     * Creates a new project document with an auto-generated ID.
     * Returns the assigned document ID.
     */
    suspend fun createProject(entry: ProjectEntry): String {
        val uid = requireUid()
        val ref = projectsRef(uid).document()
        ref.set(entry, SetOptions.merge()).await()
        return ref.id
    }

    suspend fun updateProject(entry: ProjectEntry) {
        val uid = requireUid()
        // Null out lastUpdated so @ServerTimestamp fires on every merge write. If we pass the
        // non-null Timestamp that Firestore returned on the last read, the annotation has no
        // effect and the field is written back with its stale value.
        projectsRef(uid).document(entry.projectId).set(entry.copy(lastUpdated = null), SetOptions.merge()).await()
    }

    /**
     * Updates only the [imageUrl] field of the project document.
     *
     * Uses [DocumentReference.update] rather than [SetOptions.merge] so that only the
     * single field is touched — no other fields (including [ProjectEntry.createdAt]) are
     * affected. Safe to call concurrently with other merge-writes on the same document.
     */
    suspend fun setProjectImageUrl(projectId: String, imageUrl: String) {
        val uid = requireUid()
        projectsRef(uid).document(projectId).update("imageUrl", imageUrl).await()
    }

    /**
     * Deletes [keys] from both the `colorMapping` and `originalColorMapping` fields in a single
     * `update()` call using targeted `FieldValue.delete()` paths. Both fields must be cleared
     * together so that [ProjectEntry.isAllOriginalColors] does not remain `false` after a
     * swapped bead is removed.
     *
     * `FieldValue.delete()` on a path that does not exist in the document is a Firestore no-op,
     * so keys absent from `originalColorMapping` are silently skipped.
     *
     * This is required because `set(pojo, SetOptions.merge())` builds a leaf-level field mask —
     * absent keys are simply omitted from the mask and left untouched on the server, so the
     * POJO merge approach cannot delete existing map entries.
     *
     * Requires the project document to already exist; `update()` throws `NOT_FOUND` otherwise.
     */
    suspend fun deleteColorMappingEntries(projectId: String, keys: Set<String>) {
        if (keys.isEmpty()) return
        val uid = requireUid()
        val updates: Map<String, Any> = keys.flatMap { k ->
            listOf(
                "colorMapping.$k" to FieldValue.delete(),
                "originalColorMapping.$k" to FieldValue.delete(),
            )
        }.toMap()
        projectsRef(uid).document(projectId).update(updates).await()
    }

    /**
     * Writes [rows] as chunked documents into the `projects/{id}/grid/` subcollection and
     * atomically updates [ProjectEntry.rowCount] in the main document.
     *
     * Each chunk holds at most [GRID_CHUNK_SIZE] rows and is stored under the document ID equal
     * to its 0-based chunk index. All writes (chunk documents + rowCount update) are committed
     * in a single Firestore batch.
     *
     * This function does not delete pre-existing chunks; callers must ensure stale chunks do not
     * exist (true for project creation and one-time migrations, where no prior grid exists).
     *
     * Keeping each chunk small keeps every grid document well below Firestore's 1 MB limit and
     * prevents the local SQLite mutation overlay from exceeding the 2 MB CursorWindow limit that
     * causes a fatal [android.database.sqlite.SQLiteBlobTooBigException].
     */
    suspend fun writeProjectGrid(projectId: String, rows: List<ProjectRgpRow>) {
        // Firestore batches are capped at 500 operations. One operation is reserved for the
        // rowCount update, leaving 499 slots for chunk documents.
        require(rows.size <= 499 * GRID_CHUNK_SIZE) {
            "Grid too large: ${rows.size} rows exceeds safe batch limit of ${499 * GRID_CHUNK_SIZE}"
        }
        val uid = requireUid()
        val chunks = rows.chunked(GRID_CHUNK_SIZE)
        // Each chunk + the rowCount update counts as one batch operation.
        // At CHUNK_SIZE = 200, a batch can hold up to 499 chunks (99,800 rows) — far beyond
        // any realistic project. No need to split into multiple batches.
        val batch = firestore.batch()
        chunks.forEachIndexed { index, chunkRows ->
            batch.set(
                gridRef(uid, projectId).document(index.toString()),
                ProjectGridChunk(rows = chunkRows),
            )
        }
        batch.update(projectsRef(uid).document(projectId), "rowCount", rows.size)
        batch.commit().await()
    }

    /**
     * Reads all documents from the `projects/{id}/grid/` subcollection and returns the
     * flattened row list. Chunk documents are sorted by their integer document ID before
     * concatenation so the row order is always correct regardless of Firestore's storage order.
     */
    suspend fun readProjectGrid(projectId: String): List<ProjectRgpRow> {
        val uid = auth.currentUser?.uid ?: return emptyList()
        val snapshot = gridRef(uid, projectId).get().await()
        return snapshot.documents
            .sortedBy { it.id.toIntOrNull() ?: 0 }
            .flatMap { doc -> doc.toObject(ProjectGridChunk::class.java)?.rows ?: emptyList() }
    }

    /**
     * Reads all project documents **from the server**, bypassing the local SQLite cache, and
     * returns those that still contain an inline `rows` array from the old storage format.
     *
     * Each result is a triple of `(projectId, rows, colorMapping)`. [Source.SERVER] is
     * intentional: a large inline `rows` blob in the local cache is what causes the
     * [android.database.sqlite.SQLiteBlobTooBigException] crash. Bypassing the cache lets the
     * migration run even when the local cache is corrupt, and overwriting the main document with
     * an empty `rows` field replaces the large overlay with a small one, unblocking subsequent
     * app starts.
     */
    @Suppress("UNCHECKED_CAST")
    suspend fun getProjectsWithInlineRowsFromServer(): List<Triple<String, List<ProjectRgpRow>, Map<String, String>>> {
        val uid = auth.currentUser?.uid ?: return emptyList()
        val snapshot = projectsRef(uid).get(Source.SERVER).await()
        return snapshot.documents.mapNotNull { doc ->
            val rawRows = doc.get("rows") as? List<Map<String, Any>> ?: return@mapNotNull null
            if (rawRows.isEmpty()) return@mapNotNull null
            val rows = rawRowsToProjectRgpRows(rawRows)
            if (rows.isEmpty()) return@mapNotNull null
            val colorMapping = (doc.get("colorMapping") as? Map<String, String>) ?: emptyMap()
            Triple(doc.id, rows, colorMapping)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun rawRowsToProjectRgpRows(rawRows: List<Map<String, Any>>): List<ProjectRgpRow> =
        rawRows.mapNotNull { rowMap ->
            val rowId = (rowMap["id"] as? Number)?.toInt() ?: return@mapNotNull null
            val stepsRaw = rowMap["steps"] as? List<Map<String, Any>> ?: emptyList()
            val steps = stepsRaw.mapNotNull { stepMap ->
                val stepId = (stepMap["id"] as? Number)?.toInt() ?: return@mapNotNull null
                val count = (stepMap["count"] as? Number)?.toInt() ?: return@mapNotNull null
                val description = stepMap["description"] as? String ?: return@mapNotNull null
                ProjectRgpStep(id = stepId, count = count, description = description)
            }
            ProjectRgpRow(id = rowId, steps = steps)
        }

    /**
     * Reads all project documents whose [beads] array is non-empty and whose [rows] list is
     * absent or empty. These are legacy flat-list projects that have not yet been migrated
     * to the RGP grid format.
     *
     * The raw document data is accessed directly so this method remains correct even after
     * [ProjectEntry] no longer declares a [beads] field — Firestore deserialization would
     * silently drop unknown fields, so POJO reads cannot be used here.
     *
     * Returns a list of (projectId, List<(beadCode, targetGrams)>) pairs for each flat project.
     */
    suspend fun getFlatProjectsForMigration(): List<Pair<String, List<Pair<String, Double>>>> {
        val uid = auth.currentUser?.uid ?: return emptyList()
        val snapshot = projectsRef(uid).get().await()
        return snapshot.documents.mapNotNull { doc ->
            // Skip documents that already have a grid.
            val rows = doc.get("rows") as? List<*>
            if (!rows.isNullOrEmpty()) return@mapNotNull null

            @Suppress("UNCHECKED_CAST")
            val beadsRaw = doc.get("beads") as? List<Map<String, Any>>
            if (beadsRaw.isNullOrEmpty()) return@mapNotNull null

            val beads = beadsRaw.mapNotNull { map ->
                val code = map["beadCode"] as? String ?: return@mapNotNull null
                val grams = (map["targetGrams"] as? Number)?.toDouble() ?: return@mapNotNull null
                code to grams
            }
            if (beads.isEmpty()) return@mapNotNull null
            doc.id to beads
        }
    }

    /**
     * Writes the synthesized [rows] to the `grid/` subcollection, updates [colorMapping] in the
     * main document, and atomically removes the legacy `beads` and `rows` fields from the main
     * document.
     *
     * Used by one-time migrations: flat-project-to-grid (removes `beads`) and inline-rows-to-
     * subcollection (removes `rows`). Both legacy fields are deleted unconditionally; Firestore
     * silently ignores a [FieldValue.delete] for a field that does not exist.
     */
    suspend fun migrateProjectToGrid(
        projectId: String,
        rows: List<ProjectRgpRow>,
        colorMapping: Map<String, String>,
    ) {
        val uid = requireUid()
        // Write grid to subcollection (also sets rowCount in main doc).
        writeProjectGrid(projectId, rows)
        // Update colorMapping and strip any legacy inline array fields from the main document.
        projectsRef(uid).document(projectId)
            .update(
                mapOf<String, Any>(
                    "colorMapping" to colorMapping,
                    "beads" to FieldValue.delete(),
                    "rows" to FieldValue.delete(),
                )
            )
            .await()
    }

    suspend fun deleteProject(projectId: String) {
        val uid = requireUid()
        projectsRef(uid).document(projectId).delete().await()
    }
}
