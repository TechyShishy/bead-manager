package com.techyshishy.beadmanager.data.firestore

import com.google.firebase.auth.FirebaseAuth
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
 * Firestore source for the user's catalog comparison pin list.
 *
 * Document path: users/{uid}/pins/main  (release)
 *                users_debug/{uid}/pins/main  (debug)
 *
 * The document holds a single field, `pinnedBeadCodes: List<String>`, written with
 * [SetOptions.merge] so it never overwrites other fields if the document gains neighbours.
 *
 * The snapshot listener is attached only while a collector is active and detached via
 * [awaitClose] when the Flow is cancelled. Returns [flowOf] with an empty list when no
 * user is signed in.
 */
@Singleton
class FirestoreCatalogPinsSource @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val auth: FirebaseAuth,
) {
    private val usersCollection = if (BuildConfig.DEBUG) "users_debug" else "users"

    private fun pinsDocRef(uid: String) =
        firestore.collection(usersCollection).document(uid).collection("pins").document("main")

    private fun requireUid(): String =
        auth.currentUser?.uid ?: error("No signed-in user — pins access requires authentication.")

    /**
     * Live stream of the ordered pin list for the current user.
     *
     * Emits an empty list immediately when no user is signed in or when the document does
     * not yet exist. Retains the last emitted list on listener errors rather than clearing
     * (consistent with the pattern used by other Firestore sources in this codebase).
     */
    fun pinnedCodesStream(): Flow<List<String>> {
        val uid = auth.currentUser?.uid ?: return flowOf(emptyList())
        return callbackFlow {
            val registration = pinsDocRef(uid).addSnapshotListener { snapshot, error ->
                if (error != null) {
                    android.util.Log.e("FirestorePins", "Snapshot listener error", error)
                    return@addSnapshotListener
                }
                if (snapshot == null) return@addSnapshotListener
                @Suppress("UNCHECKED_CAST")
                val codes = snapshot.get("pinnedBeadCodes") as? List<String> ?: emptyList()
                trySend(codes)
            }
            awaitClose { registration.remove() }
        }
    }

    /**
     * Persists [codes] as the authoritative ordered pin list.
     *
     * Uses [SetOptions.merge] so no other fields in the document are disturbed.
     */
    suspend fun setPinnedCodes(codes: List<String>) {
        pinsDocRef(requireUid())
            .set(mapOf("pinnedBeadCodes" to codes), SetOptions.merge())
            .await()
    }
}
