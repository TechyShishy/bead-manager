package com.techyshishy.beadmanager.data.repository

import com.techyshishy.beadmanager.data.firestore.FirestoreOrderSource
import com.techyshishy.beadmanager.data.firestore.FirestoreProjectSource
import com.techyshishy.beadmanager.data.firestore.ProjectEntry
import com.techyshishy.beadmanager.data.firestore.ProjectRgpRow
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
     * Writes [rows] as chunked documents into the `grid/` subcollection of [projectId].
     * Also atomically updates [ProjectEntry.rowCount] in the main project document.
     */
    suspend fun writeProjectGrid(projectId: String, rows: List<ProjectRgpRow>) =
        source.writeProjectGrid(projectId, rows)

    /**
     * Returns the full row list for [projectId] by reading all grid subcollection chunks.
     */
    suspend fun readProjectGrid(projectId: String): List<ProjectRgpRow> =
        source.readProjectGrid(projectId)

    /**
     * Returns all projects that still carry inline [rows] data in their main document.
     * Uses [com.google.firebase.firestore.Source.SERVER] to bypass the local SQLite cache.
     * Each element is `(projectId, rows, colorMapping)`.
     */
    suspend fun getProjectsWithInlineRowsFromServer(): List<Triple<String, List<ProjectRgpRow>, Map<String, String>>> =
        source.getProjectsWithInlineRowsFromServer()

    /**
     * Returns all project documents that still carry a legacy flat [beads] list but have
     * no grid ([rows] empty). Used by the one-time flat-project-to-grid migration.
     *
     * Each element is (projectId, List<(beadCode, targetGrams)>).
     */
    suspend fun getFlatProjectsForMigration(): List<Pair<String, List<Pair<String, Double>>>> =
        source.getFlatProjectsForMigration()

    /**
     * Writes [rows] and [colorMapping] to the given project document and atomically deletes
     * the legacy [beads] field. Used only by the one-time flat-project-to-grid migration.
     */
    suspend fun migrateProjectToGrid(
        projectId: String,
        rows: List<ProjectRgpRow>,
        colorMapping: Map<String, String>,
    ) = source.migrateProjectToGrid(projectId, rows, colorMapping)

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
