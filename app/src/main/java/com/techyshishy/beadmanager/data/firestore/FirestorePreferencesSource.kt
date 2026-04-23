package com.techyshishy.beadmanager.data.firestore

import android.util.Log
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.techyshishy.beadmanager.BuildConfig
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Firestore data source for the user's synced preferences document.
 *
 * Document path: users/{uid}/preferences/main  (release)
 *                users_debug/{uid}/preferences/main  (debug)
 *
 * Uses SetOptions.merge() on all writes so partial field updates do not
 * clobber unrelated fields — forward-compatible with new preference fields
 * added in future releases.
 */
@Singleton
class FirestorePreferencesSource @Inject constructor(
    private val firestore: FirebaseFirestore,
) {
    private val usersCollection = if (BuildConfig.DEBUG) "users_debug" else "users"

    private fun preferencesRef(uid: String): DocumentReference =
        firestore.collection(usersCollection).document(uid)
            .collection("preferences").document("main")

    /**
     * Live stream of the user's preferences document.
     * Emits `null` when the document does not yet exist (e.g. on first sign-in
     * before [bootstrapIfAbsent] has had a chance to write the document).
     * Emits a new value whenever any field in the document changes.
     */
    fun preferencesStream(uid: String): Flow<PreferencesEntry?> = callbackFlow {
        val registration = preferencesRef(uid).addSnapshotListener { snapshot, error ->
            if (error != null) {
                Log.e("FirestorePreferences", "Snapshot listener error", error)
                return@addSnapshotListener
            }
            if (snapshot == null) return@addSnapshotListener
            trySend(
                if (snapshot.exists()) snapshot.toObject(PreferencesEntry::class.java) else null,
            )
        }
        awaitClose { registration.remove() }
    }

    /**
     * Partially update one or more preference fields for [uid].
     *
     * [fields] contains field names and their new values; a server timestamp
     * for `lastUpdated` is added automatically. Uses SetOptions.merge() so
     * fields not included in [fields] are left unchanged on the server.
     */
    suspend fun setPreferences(uid: String, fields: Map<String, Any>) {
        preferencesRef(uid)
            .set(fields + mapOf("lastUpdated" to FieldValue.serverTimestamp()), SetOptions.merge())
            .await()
    }

    /**
     * Uploads [entry] as the initial preferences document if no document currently
     * exists for [uid].
     *
     * Bootstrap semantics: the first device to sign in wins. Uses a Firestore
     * transaction to atomically check-and-set, so two devices signing in at the
     * same time cannot both see a missing document and both write — only one
     * write proceeds, preserving first-device-wins semantics. Subsequent devices
     * find the document already present and skip the write.
     *
     * Idempotent: calling this multiple times for the same uid is safe.
     */
    suspend fun bootstrapIfAbsent(uid: String, entry: PreferencesEntry) {
        val ref = preferencesRef(uid)
        firestore.runTransaction { transaction ->
            val snapshot = transaction.get(ref)
            if (!snapshot.exists()) {
                transaction.set(ref, entry, SetOptions.merge())
            }
        }.await()
    }
}
