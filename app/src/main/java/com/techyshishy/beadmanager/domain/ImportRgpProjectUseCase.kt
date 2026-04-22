package com.techyshishy.beadmanager.domain

import android.content.ContentResolver
import android.net.Uri
import com.techyshishy.beadmanager.data.firestore.ProjectEntry
import com.techyshishy.beadmanager.data.firestore.ProjectRgpRow
import com.techyshishy.beadmanager.data.firestore.ProjectRgpStep
import com.techyshishy.beadmanager.data.repository.CatalogRepository
import com.techyshishy.beadmanager.data.repository.ProjectRepository
import com.techyshishy.beadmanager.data.rgp.RgpParseException
import com.techyshishy.beadmanager.data.rgp.parseRgp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject

class ImportRgpProjectUseCase @Inject constructor(
    private val contentResolver: ContentResolver,
    private val catalogRepository: CatalogRepository,
    private val projectRepository: ProjectRepository,
) {
    suspend fun import(uri: Uri): ImportResult {
        // 1. Parse — fail fast before touching any Firestore state.
        //    openInputStream and gzip decompression/JSON decode are IO-bound; run on IO dispatcher.
        //    openInputStream returning null is treated as an unreadable file (InvalidJson).
        val rgpProject = try {
            withContext(Dispatchers.IO) {
                val stream = contentResolver.openInputStream(uri)
                    ?: throw IOException("Content resolver returned null stream for $uri")
                stream.use { parseRgp(it) }
            }
        } catch (e: RgpParseException.NotGzip) {
            return ImportResult.Failure.NotGzip
        } catch (e: RgpParseException.InvalidJson) {
            return ImportResult.Failure.InvalidJson
        } catch (e: IOException) {
            return ImportResult.Failure.InvalidJson
        }

        // 2. Extract DB-prefixed codes from colorMapping. Hex-only files have no codes.
        val dbCodes = rgpProject.colorMapping.values
            .filter { it.startsWith("DB") }
            .toSet()
        if (dbCodes.isEmpty()) return ImportResult.Failure.NoDelicaCodes

        // 3. Validate all codes against the catalog in one query.
        val catalogMap = catalogRepository.allBeadsAsMap()
        val unrecognized = dbCodes.filter { it !in catalogMap }
        if (unrecognized.isNotEmpty()) {
            return ImportResult.Failure.UnrecognizedCodes(unrecognized.sorted())
        }

        // 4. Create the project; return success with the assigned ID.
        if (rgpProject.name.isBlank()) return ImportResult.Failure.InvalidJson
        val projectRows = rgpProject.rows.map { row ->
            ProjectRgpRow(
                id = row.id,
                steps = row.steps.map { step ->
                    ProjectRgpStep(id = step.id, count = step.count, description = step.description)
                },
            )
        }
        val projectId = projectRepository.createProject(
            ProjectEntry(
                name = rgpProject.name,
                colorMapping = rgpProject.colorMapping,
                position = rgpProject.position ?: emptyMap(),
                markedSteps = rgpProject.markedSteps ?: emptyMap(),
                markedRows = rgpProject.markedRows ?: emptyMap(),
            ),
        )
        // Write the grid to the `grid/` subcollection. Rows are chunked so each document
        // stays well below Firestore's 1 MB limit and the local SQLite CursorWindow limit.
        // If this write fails, clean up the orphaned project document so the user can retry.
        try {
            projectRepository.writeProjectGrid(projectId, projectRows)
        } catch (e: Exception) {
            runCatching { projectRepository.deleteProject(projectId) }
            return ImportResult.Failure.WriteError
        }
        return ImportResult.Success(projectId = projectId, name = rgpProject.name)
    }
}
