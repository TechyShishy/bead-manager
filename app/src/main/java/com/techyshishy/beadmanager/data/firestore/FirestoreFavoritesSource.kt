package com.techyshishy.beadmanager.data.firestore

import com.google.firebase.auth.FirebaseAuth
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
 * Firestore source for the user's catalog favorites list.
 *
 * Document path: users/{uid}/favorites/main  (release)
 *                users_debug/{uid}/favorites/main  (debug)
 *
 * The document holds a single field, `favoritedBeadCodes: List<String>`. Toggle mutations
 * use [FieldValue.arrayUnion] and [FieldValue.arrayRemove] rather than a full list overwrite,
 * making concurrent favorites from multiple devices atomic and conflict-free. This differs
 * from the pins path, which must overwrite the whole list to preserve display order.
 */
@Singleton
class FirestoreFavoritesSource @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val auth: FirebaseAuth,
) {
    private val usersCollection = if (BuildConfig.DEBUG) "users_debug" else "users"

    private fun favoritesDocRef(uid: String) =
        firestore.collection(usersCollection).document(uid)
            .collection("favorites").document("main")

    private fun requireUid(): String =
        auth.currentUser?.uid ?: error("No signed-in user — favorites access requires authentication.")

    /**
     * Live stream of the current user's favorited bead codes as a [Set].
     *
     * Emits an empty set when no user is signed in or when the document does not yet exist.
     * Retains the last emitted set on listener errors rather than clearing (consistent with
     * the pattern used by other Firestore sources in this codebase).
     */
    fun favoritedCodesStream(): Flow<Set<String>> {
        val uid = auth.currentUser?.uid ?: return flowOf(emptySet())
        return callbackFlow {
            val registration = favoritesDocRef(uid).addSnapshotListener { snapshot, error ->
                if (error != null) {
                    android.util.Log.e("FirestoreFavorites", "Snapshot listener error", error)
                    return@addSnapshotListener
                }
                if (snapshot == null) return@addSnapshotListener
                @Suppress("UNCHECKED_CAST")
                val codes = snapshot.get("favoritedBeadCodes") as? List<String> ?: emptyList()
                trySend(codes.toSet())
            }
            awaitClose { registration.remove() }
        }
    }

    /**
     * Marks [code] as a favorite using [FieldValue.arrayUnion], creating the document if absent.
     */
    suspend fun favorite(code: String) {
        favoritesDocRef(requireUid())
            .set(mapOf("favoritedBeadCodes" to FieldValue.arrayUnion(code)), SetOptions.merge())
            .await()
    }

    /**
     * Removes [code] from favorites using [FieldValue.arrayRemove].
     * No-op if [code] is not currently favorited.
     */
    suspend fun unfavorite(code: String) {
        favoritesDocRef(requireUid())
            .set(mapOf("favoritedBeadCodes" to FieldValue.arrayRemove(code)), SetOptions.merge())
            .await()
    }
}
