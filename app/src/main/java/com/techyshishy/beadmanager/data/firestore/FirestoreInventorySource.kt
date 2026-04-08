package com.techyshishy.beadmanager.data.firestore

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
 * Wraps Firestore operations for the user's inventory collection.
 *
 * The collection is scoped to the signed-in user: `users/{uid}/inventory` in release builds
 * and `users_debug/{uid}/inventory` in debug builds, keeping debug data isolated from
 * production inventory.
 * Firestore's offline persistence (enabled at SDK initialisation in
 * [FirebaseModule]) ensures the latest state is available even without a
 * network connection, and syncs automatically when connectivity resumes.
 */
@Singleton
class FirestoreInventorySource @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val auth: FirebaseAuth,
) {

    private val usersCollection = if (BuildConfig.DEBUG) "users_debug" else "users"

    private fun inventoryRef(uid: String): CollectionReference =
        firestore.collection(usersCollection).document(uid).collection("inventory")

    private fun inventoryCollection() = inventoryRef(requireUid())

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
            val ref = inventoryRef(uid)
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
     * Atomically increment the quantity for [beadCode] by [deltaGrams].
     *
     * Uses [FieldValue.increment] so the delta is applied server-side without a
     * prior read. Concurrent calls from multiple devices cannot race — the server
     * serialises the increments, so all deltas are applied correctly.
     *
     * [deltaGrams] must be positive; negative deltas (decrements) are not supported — use
     * [upsert] with an explicit [InventoryEntry.quantityGrams] value for absolute sets.
     *
     * If the inventory document does not yet exist it is created with
     * quantityGrams = deltaGrams — [FieldValue.increment] treats a missing field as 0.
     * [SetOptions.merge] is independent: it prevents the write from erasing other fields
     * (`notes`, `lowStockThresholdGrams`) that are not present in the update map.
     */
    suspend fun adjustQuantity(beadCode: String, deltaGrams: Double) {
        require(deltaGrams != 0.0) { "deltaGrams must be non-zero; use upsert() for absolute sets." }
        inventoryCollection()
            .document(beadCode)
            .set(
                mapOf(
                    "quantityGrams" to FieldValue.increment(deltaGrams),
                    "lastUpdated" to FieldValue.serverTimestamp(),
                ),
                SetOptions.merge(),
            )
            .await()
    }

    /**
     * One-time migration: resets [InventoryEntry.lowStockThresholdGrams] from the legacy
     * factory default (5.0 g) to the sentinel value (0.0 g, meaning "use global default").
     *
     * Only documents whose stored threshold is exactly 5.0 are touched — values the user
     * set explicitly to a different number are left unchanged. The caller is responsible
     * for recording that the migration has run so it is not repeated.
     */
    suspend fun migrateLegacyThresholds() {
        val uid = auth.currentUser?.uid ?: return
        // Note: uses default Source (server-first, cache-fallback). If the server is
        // unreachable and there is no local cache (new device, first launch), this throws.
        // The caller must NOT treat that exception as success — the migration must retry.
        val snapshot = inventoryRef(uid).get().await()
        val matching = snapshot.documents.filter {
            it.getDouble("lowStockThresholdGrams") == 5.0
        }
        // Firestore enforces a hard limit of 500 writes per batch; chunk accordingly.
        matching.chunked(500).forEach { chunk ->
            val batch = firestore.batch()
            chunk.forEach { doc ->
                batch.update(doc.reference, "lowStockThresholdGrams", 0.0)
            }
            batch.commit().await()
        }
    }
}
