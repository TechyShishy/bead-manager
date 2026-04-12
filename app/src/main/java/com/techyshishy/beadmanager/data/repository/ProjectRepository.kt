package com.techyshishy.beadmanager.data.repository

import com.techyshishy.beadmanager.data.firestore.FirestoreOrderSource
import com.techyshishy.beadmanager.data.firestore.FirestoreProjectSource
import com.techyshishy.beadmanager.data.firestore.ProjectBeadEntry
import com.techyshishy.beadmanager.data.firestore.ProjectEntry
import com.techyshishy.beadmanager.di.AppScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.shareIn
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProjectRepository @Inject constructor(
    private val source: FirestoreProjectSource,
    private val orderSource: FirestoreOrderSource,
    @AppScope private val appScope: CoroutineScope,
) {
    // Single shared listener: replays the last known state to late subscribers and
    // tears down the underlying Firestore listener 5 s after all subscribers leave.
    private val sharedProjects =
        source.projectsStream()
            .shareIn(appScope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    fun projectsStream(): Flow<List<ProjectEntry>> = sharedProjects

    fun projectStream(projectId: String): Flow<ProjectEntry?> =
        source.projectStream(projectId)

    suspend fun createProject(entry: ProjectEntry): String =
        source.createProject(entry)

    suspend fun updateProject(entry: ProjectEntry) =
        source.updateProject(entry)

    /**
     * Replaces the bead list for a project.
     * The caller is responsible for duplicate-code enforcement before calling this.
     */
    suspend fun updateBeads(projectId: String, beads: List<ProjectBeadEntry>) {
        source.updateBeads(projectId, beads)
    }

    /**
     * Removes a bead from the project bead list by bead code.
     * Does not check for vendor-less order items referencing this bead; the caller is
     * responsible for confirming with the user before calling.
     */
    suspend fun removeBead(projectId: String, beadCode: String, existingBeads: List<ProjectBeadEntry>) {
        val updated = existingBeads.filter { it.beadCode != beadCode }
        source.updateBeads(projectId, updated)
    }

    /**
     * Deletes the project and all its child orders.
     * Orders are deleted first so a crash between the two leaves the project
     * visible (recoverable) rather than leaving invisible orphan documents.
     */
    suspend fun deleteProject(projectId: String) {
        orderSource.deleteOrdersForProject(projectId)
        source.deleteProject(projectId)
    }
}
