package com.techyshishy.beadmanager.data.firestore

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
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

    private fun projectsRef(uid: String): CollectionReference =
        firestore.collection(usersCollection).document(uid).collection("projects")

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
        projectsRef(uid).document(entry.projectId).set(entry, SetOptions.merge()).await()
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
     * Writes the synthesized [rows] and [colorMapping] to the project document and atomically
     * deletes the legacy [beads] field in the same [update] call.
     *
     * [rows] and [colorMapping] are written as plain Firestore-compatible primitive maps rather
     * than POJOs so that the update map can include [FieldValue.delete] for [beads] alongside them.
     */
    suspend fun migrateProjectToGrid(
        projectId: String,
        rows: List<ProjectRgpRow>,
        colorMapping: Map<String, String>,
    ) {
        val uid = requireUid()
        val serializedRows: List<Map<String, Any>> = rows.map { row ->
            mapOf(
                "id" to row.id,
                "steps" to row.steps.map { step ->
                    mapOf<String, Any>("id" to step.id, "count" to step.count, "description" to step.description)
                },
            )
        }
        projectsRef(uid).document(projectId)
            .update(
                mapOf<String, Any>(
                    "rows" to serializedRows,
                    "colorMapping" to colorMapping,
                    "beads" to FieldValue.delete(),
                )
            )
            .await()
    }

    suspend fun deleteProject(projectId: String) {
        val uid = requireUid()
        projectsRef(uid).document(projectId).delete().await()
    }
}
