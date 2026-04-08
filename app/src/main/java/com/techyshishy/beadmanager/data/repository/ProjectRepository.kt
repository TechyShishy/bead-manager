package com.techyshishy.beadmanager.data.repository

import com.techyshishy.beadmanager.data.firestore.FirestoreOrderSource
import com.techyshishy.beadmanager.data.firestore.FirestoreProjectSource
import com.techyshishy.beadmanager.data.firestore.ProjectEntry
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProjectRepository @Inject constructor(
    private val source: FirestoreProjectSource,
    private val orderSource: FirestoreOrderSource,
) {
    fun projectsStream(): Flow<List<ProjectEntry>> =
        source.projectsStream()

    suspend fun createProject(entry: ProjectEntry): String =
        source.createProject(entry)

    suspend fun updateProject(entry: ProjectEntry) =
        source.updateProject(entry)

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
