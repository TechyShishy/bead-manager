package com.techyshishy.beadmanager.data.firestore

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wraps Firestore operations for the user's inventory collection.
 *
 * The collection is scoped to the signed-in user: users/{uid}/inventory.
 * Firestore's offline persistence (enabled at SDK initialisation in
 * [FirebaseModule]) ensures the latest state is available even without a
 * network connection, and syncs automatically when connectivity resumes.
 */
@Singleton
class FirestoreInventorySource @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val auth: FirebaseAuth,
) {

    private fun inventoryCollection() =
        firestore.collection("users").document(requireUid()).collection("inventory")

    private fun requireUid(): String =
        auth.currentUser?.uid ?: error("No signed-in user — inventory access requires authentication.")

    /**
     * Live stream of all inventory entries for the current user.
     * Emits a new map whenever any document in the collection changes.
     * Keyed by [InventoryEntry.beadCode] for O(1) lookups in the ViewModel.
     */
    fun inventoryStream(): Flow<Map<String, InventoryEntry>> {
        val uid = auth.currentUser?.uid ?: return flowOf(emptyMap())
        return callbackFlow {
            val ref = firestore
                .collection("users")
                .document(uid)
                .collection("inventory")
            val registration = ref.addSnapshotListener { snapshot, error ->
                if (error != null) {
                    // Do not emit emptyMap() — retain the last good cached value.
                    // The error surfaces here on auth revocation, permission gaps,
                    // or Firestore index misses; logging is sufficient for now.
                    android.util.Log.e("FirestoreInventory", "Snapshot listener error", error)
                    return@addSnapshotListener
                }
                if (snapshot == null) return@addSnapshotListener
                val entries = snapshot.documents.mapNotNull { doc ->
                    doc.toObject(InventoryEntry::class.java)
                }.associateBy { it.beadCode }
                trySend(entries)
            }
            awaitClose { registration.remove() }
        }
    }

    /**
     * Write or update an inventory entry.
     * Uses merge so fields not present in [entry] are not overwritten —
     * this is safe for concurrent updates from multiple devices.
     */
    suspend fun upsert(entry: InventoryEntry) {
        inventoryCollection()
            .document(entry.beadCode)
            .set(entry, SetOptions.merge())
            .await()
    }

    /**
     * Adjust quantity by [deltaGrams] (can be negative) and write the result.
     * Clamps the result to a minimum of 0.
     */
    suspend fun adjustQuantity(beadCode: String, deltaGrams: Double, currentEntry: InventoryEntry?) {
        val current = currentEntry ?: InventoryEntry(beadCode = beadCode)
        val updated = current.copy(
            quantityGrams = maxOf(0.0, current.quantityGrams + deltaGrams),
            lastUpdated = null, // cleared so @ServerTimestamp fills it
        )
        upsert(updated)
    }
}
