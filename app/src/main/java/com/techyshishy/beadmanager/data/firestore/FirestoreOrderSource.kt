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
 * Firestore data source for the user's order collection.
 *
 * Collection path: users/{uid}/orders  (release)
 *                  users_debug/{uid}/orders  (debug)
 *
 * Orders are always read as whole documents — line items are an embedded array,
 * not a subcollection, so every operation is a single Firestore read/write.
 */
@Singleton
class FirestoreOrderSource @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val auth: FirebaseAuth,
) {
    private val usersCollection = if (BuildConfig.DEBUG) "users_debug" else "users"

    private fun ordersRef(uid: String): CollectionReference =
        firestore.collection(usersCollection).document(uid).collection("orders")

    private fun requireUid(): String =
        auth.currentUser?.uid ?: error("No signed-in user — order access requires authentication.")

    /**
     * Live stream of all orders associated with [projectId].
     * Queries the [projectIds] array field. Only returns orders whose [projectIds] list
     * contains [projectId]; documents that have not yet been migrated (empty [projectIds])
     * are excluded. The migration in MigrationViewModel backfills all existing documents
     * before this query runs on first launch after upgrade.
     *
     * Emits a new list whenever any order document in the result set changes.
     */
    fun ordersStream(projectId: String): Flow<List<OrderEntry>> {
        val uid = auth.currentUser?.uid ?: return flowOf(emptyList())
        return callbackFlow {
            val query = ordersRef(uid)
                .whereArrayContains("projectIds", projectId)
                .orderBy("createdAt", Query.Direction.ASCENDING)
            val registration = query.addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e("FirestoreOrder", "Snapshot listener error", error)
                    return@addSnapshotListener
                }
                if (snapshot == null) return@addSnapshotListener
                val entries = snapshot.documents.mapNotNull { it.toObject(OrderEntry::class.java) }
                trySend(entries)
            }
            awaitClose { registration.remove() }
        }
    }

    /**
     * Live stream of all orders for the signed-in user, across all projects.
     * Sorted newest-first. Intended for the unified Orders tab.
     */
    fun allOrdersStream(): Flow<List<OrderEntry>> {
        val uid = auth.currentUser?.uid ?: return flowOf(emptyList())
        return callbackFlow {
            val query = ordersRef(uid)
                .orderBy("createdAt", Query.Direction.DESCENDING)
            val registration = query.addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e("FirestoreOrder", "All-orders stream error", error)
                    return@addSnapshotListener
                }
                if (snapshot == null) return@addSnapshotListener
                val entries = snapshot.documents.mapNotNull { it.toObject(OrderEntry::class.java) }
                trySend(entries)
            }
            awaitClose { registration.remove() }
        }
    }

    /**
     * Creates a new order with an auto-generated ID.
     * Returns the assigned document ID.
     */
    suspend fun createOrder(entry: OrderEntry): String {
        val uid = requireUid()
        val ref = ordersRef(uid).document()
        ref.set(entry, SetOptions.merge()).await()
        return ref.id
    }

    /**
     * Replaces the entire items array on an existing order and refreshes [lastUpdated].
     *
     * Uses [SetOptions.merge] so only [items] and [lastUpdated] are written; all other
     * fields — including [createdAt] and [projectIds] — are preserved.
     * The server fills in [lastUpdated] via FieldValue.serverTimestamp().
     */
    suspend fun updateItems(orderId: String, items: List<OrderItemEntry>) {
        val uid = requireUid()
        ordersRef(uid).document(orderId)
            .set(
                mapOf(
                    "items" to items,
                    "lastUpdated" to FieldValue.serverTimestamp(),
                ),
                SetOptions.merge(),
            )
            .await()
    }

    /**
     * Live stream of a single order document by [orderId].
     * Emits null if the document does not exist.
     */
    fun orderStream(orderId: String): Flow<OrderEntry?> {
        val uid = auth.currentUser?.uid ?: return flowOf(null)
        return callbackFlow {
            val ref = ordersRef(uid).document(orderId)
            val registration = ref.addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e("FirestoreOrder", "Single order stream error for $orderId", error)
                    return@addSnapshotListener
                }
                if (snapshot == null) return@addSnapshotListener
                trySend(snapshot.toObject(OrderEntry::class.java))
            }
            awaitClose { registration.remove() }
        }
    }

    suspend fun deleteOrder(orderId: String) {
        val uid = requireUid()
        ordersRef(uid).document(orderId).delete().await()
    }

    /**
     * Deletes all order documents for a given project.
     * Called by [com.techyshishy.beadmanager.data.repository.ProjectRepository] before
     * deleting the parent project document, since Firestore has no server-side cascades.
     *
     * Queries both [projectIds] (current field) and the legacy [projectId] field so that
     * pre-migration documents are caught regardless of whether the migration has run yet.
     * The union is deduplicated by document ID before batching.
     */
    suspend fun deleteOrdersForProject(projectId: String) {
        val uid = requireUid()
        val byArray = ordersRef(uid).whereArrayContains("projectIds", projectId).get().await()
        val byLegacy = ordersRef(uid).whereEqualTo("projectId", projectId).get().await()
        val toDelete = (byArray.documents + byLegacy.documents).distinctBy { it.id }
        // Batch deletions; cap at 500 per the Firestore batch write limit.
        toDelete.chunked(500).forEach { chunk ->
            val batch = firestore.batch()
            chunk.forEach { doc -> batch.delete(doc.reference) }
            batch.commit().await()
        }
    }

    /**
     * One-shot read of all order documents for the signed-in user, fetched from the server.
     * Used by the migration to backfill [OrderEntry.projectIds] from [OrderEntry.projectId].
     *
     * [Source.SERVER] is required: a cache-only read might return an incomplete set of
     * documents, which would cause the migration to silently mark itself done while leaving
     * some documents un-backfilled. Failing with an exception when offline is preferable —
     * the completion flag is not set and the migration retries on the next launch.
     */
    suspend fun getAllOrdersSnapshot(): List<OrderEntry> {
        val uid = requireUid()
        return ordersRef(uid).get(Source.SERVER).await()
            .documents.mapNotNull { it.toObject(OrderEntry::class.java) }
    }

    /**
     * Writes [projectIds] to an order document using arrayUnion, so concurrent writes
     * accumulate rather than overwrite. Does not touch any other fields.
     */
    suspend fun addProjectIdToOrder(orderId: String, projectId: String) {
        val uid = requireUid()
        ordersRef(uid).document(orderId)
            .update("projectIds", FieldValue.arrayUnion(projectId))
            .await()
    }

    /**
     * Removes [projectId] from an order's [projectIds] array using arrayRemove.
     * If [projectId] is not present, the call is a no-op.
     */
    suspend fun removeProjectIdFromOrder(orderId: String, projectId: String) {
        val uid = requireUid()
        ordersRef(uid).document(orderId)
            .update("projectIds", FieldValue.arrayRemove(projectId))
            .await()
    }

    /**
     * One-shot fetch of a single order document, using the Firestore default source
     * (local cache first, falls back to server). Returns null if the document does not exist.
     * Used when an immediate snapshot is needed without subscribing to a stream.
     */
    suspend fun orderSnapshot(orderId: String): OrderEntry? {
        val uid = requireUid()
        return ordersRef(uid).document(orderId).get().await()
            .toObject(OrderEntry::class.java)
    }
}
