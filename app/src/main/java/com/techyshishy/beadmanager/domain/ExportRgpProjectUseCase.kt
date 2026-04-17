package com.techyshishy.beadmanager.domain

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import com.techyshishy.beadmanager.data.repository.ProjectRepository
import com.techyshishy.beadmanager.data.rgp.writeRgp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject

sealed class ExportResult {
    data class Success(val suggestedFilename: String) : ExportResult()
    sealed class Failure : ExportResult() {
        /** No project with the given ID was found in Firestore. */
        data object NotFound : Failure()
        /** The project exists but has no grid rows and no beads — nothing to export. */
        data object NoGrid : Failure()
        /** An I/O error occurred while writing the output stream. */
        data object IoError : Failure()
    }
}

/**
 * Exports a project from Firestore as a gzip-compressed `.rgp` file written to [uri] via
 * [ContentResolver].
 *
 * The export mirrors the import path in [ImportRgpProjectUseCase]: load from Firestore,
 * validate, then perform I/O-bound work on [Dispatchers.IO].
 *
 * Returns [ExportResult.Failure.NotFound] if the project does not exist,
 * [ExportResult.Failure.NoGrid] if the project has neither grid rows nor beads (completely
 * empty), or [ExportResult.Failure.IoError] if writing the output stream fails.
 */
class ExportRgpProjectUseCase @Inject constructor(
    private val contentResolver: ContentResolver,
    private val projectRepository: ProjectRepository,
) {
    suspend fun export(projectId: String, uri: Uri): ExportResult {
        val project = projectRepository.projectStream(projectId).first()
            ?: return ExportResult.Failure.NotFound

        val rows = projectRepository.readProjectGrid(projectId)
        if (rows.isEmpty() && project.colorMapping.isEmpty()) return ExportResult.Failure.NoGrid

        return try {
            withContext(Dispatchers.IO) {
                val stream = contentResolver.openOutputStream(uri)
                    ?: throw IOException("Content resolver returned null stream for $uri")
                stream.use { writeRgp(it, project, rows) }
                val displayName = contentResolver
                    .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0) else null
                    } ?: "${project.name}.rgp"
                ExportResult.Success(suggestedFilename = displayName)
            }
        } catch (e: IOException) {
            ExportResult.Failure.IoError
        }
    }
}
