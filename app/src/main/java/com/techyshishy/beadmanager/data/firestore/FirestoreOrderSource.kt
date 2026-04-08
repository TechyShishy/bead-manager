package com.techyshishy.beadmanager.data.firestore

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
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
     * Live stream of all orders belonging to [projectId].
     * Emits a new list whenever any order document in the result set changes.
     */
    fun ordersStream(projectId: String): Flow<List<OrderEntry>> {
        val uid = auth.currentUser?.uid ?: return flowOf(emptyList())
        return callbackFlow {
            val query = ordersRef(uid).whereEqualTo("projectId", projectId)
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
     * Uses [SetOptions.merge] so [createdAt] and [projectId] are not touched.
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

    suspend fun deleteOrder(orderId: String) {
        val uid = requireUid()
        ordersRef(uid).document(orderId).delete().await()
    }

    /**
     * Deletes all order documents for a given project.
     * Called by [com.techyshishy.beadmanager.data.repository.ProjectRepository] before
     * deleting the parent project document, since Firestore has no server-side cascades.
     */
    suspend fun deleteOrdersForProject(projectId: String) {
        val uid = requireUid()
        val snapshot = ordersRef(uid).whereEqualTo("projectId", projectId).get().await()
        // Batch deletions; cap at 500 per the Firestore batch write limit.
        snapshot.documents.chunked(500).forEach { chunk ->
            val batch = firestore.batch()
            chunk.forEach { doc -> batch.delete(doc.reference) }
            batch.commit().await()
        }
    }
}
