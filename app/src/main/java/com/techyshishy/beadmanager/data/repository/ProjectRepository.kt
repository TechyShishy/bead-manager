package com.techyshishy.beadmanager.data.repository

import com.techyshishy.beadmanager.data.firestore.FirestoreOrderSource
import com.techyshishy.beadmanager.data.firestore.FirestoreProjectSource
import com.techyshishy.beadmanager.data.firestore.ProjectBeadEntry
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
     * Appends a bead to the project bead list.
     * Silently no-ops if a bead with the same [ProjectBeadEntry.beadCode] already exists.
     */
    suspend fun addBead(projectId: String, entry: ProjectBeadEntry, existingBeads: List<ProjectBeadEntry>) {
        if (existingBeads.any { it.beadCode == entry.beadCode }) return
        source.updateBeads(projectId, existingBeads + entry)
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
