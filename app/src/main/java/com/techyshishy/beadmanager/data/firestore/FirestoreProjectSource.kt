package com.techyshishy.beadmanager.data.firestore

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.CollectionReference
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
            val registration = projectsRef(uid).addSnapshotListener { snapshot, error ->
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

    suspend fun deleteProject(projectId: String) {
        val uid = requireUid()
        projectsRef(uid).document(projectId).delete().await()
    }
}
