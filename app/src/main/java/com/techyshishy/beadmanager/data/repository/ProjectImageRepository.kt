package com.techyshishy.beadmanager.data.repository

import android.net.Uri
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageException
import com.techyshishy.beadmanager.BuildConfig
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles Firebase Storage reads and writes for project cover images.
 *
 * Storage path (release): users/{uid}/projects/{projectId}/cover
 * Storage path (debug):   users_debug/{uid}/projects/{projectId}/cover
 *
 * Mirrors the debug/release isolation convention used by Firestore collections.
 */
@Singleton
class ProjectImageRepository @Inject constructor(
    private val storage: FirebaseStorage,
    private val auth: FirebaseAuth,
) {
    private val usersRoot = if (BuildConfig.DEBUG) "users_debug" else "users"

    private fun coverRef(projectId: String) =
        storage.reference
            .child(usersRoot)
            .child(requireUid())
            .child("projects")
            .child(projectId)
            .child("cover")

    private fun requireUid(): String =
        auth.currentUser?.uid ?: error("No signed-in user — project image access requires authentication.")

    /**
     * Uploads [uri] as the cover image for [projectId] and returns the HTTPS download URL.
     * The content URI must be openable at call time — callers must invoke this from the
     * `onResult` callback of the image picker launcher while the URI grant is still valid.
     */
    suspend fun uploadCover(projectId: String, uri: Uri): String {
        val ref = coverRef(projectId)
        ref.putFile(uri).await()
        return ref.downloadUrl.await().toString()
    }

    /**
     * Uploads raw [bytes] as the cover image for [projectId] and returns the HTTPS download URL.
     * Intended for programmatically generated images (e.g. rendered preview bitmaps) where no
     * content URI is available.
     */
    suspend fun uploadCoverBytes(projectId: String, bytes: ByteArray): String {
        val ref = coverRef(projectId)
        ref.putBytes(bytes).await()
        return ref.downloadUrl.await().toString()
    }

    /**
     * Deletes the cover image for [projectId]. Silently succeeds if no image exists.
     * All other [StorageException] codes (permission, network, etc.) are rethrown so the
     * caller can surface them to the user.
     */
    suspend fun deleteCover(projectId: String) {
        try {
            coverRef(projectId).delete().await()
        } catch (e: StorageException) {
            if (e.errorCode != StorageException.ERROR_OBJECT_NOT_FOUND) throw e
        }
    }
}
