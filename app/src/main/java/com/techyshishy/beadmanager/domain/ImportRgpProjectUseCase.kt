package com.techyshishy.beadmanager.domain

import android.content.ContentResolver
import android.net.Uri
import android.util.Log
import com.techyshishy.beadmanager.data.firestore.ProjectBeadEntry
import com.techyshishy.beadmanager.data.firestore.ProjectEntry
import com.techyshishy.beadmanager.data.model.BEADS_PER_GRAM
import com.techyshishy.beadmanager.data.repository.CatalogRepository
import com.techyshishy.beadmanager.data.repository.ProjectRepository
import com.techyshishy.beadmanager.data.rgp.RgpParseException
import com.techyshishy.beadmanager.data.rgp.parseRgp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToLong
import java.io.IOException
import javax.inject.Inject

sealed class ImportResult {
    data class Success(val projectId: String, val name: String) : ImportResult()
    sealed class Failure : ImportResult() {
        data object NotGzip : Failure()
        data object InvalidJson : Failure()
        /** The file's colorMapping contains only hex colors — no Delica codes to import. */
        data object NoDelicaCodes : Failure()
        /** One or more DB codes in colorMapping were not found in the local catalog. */
        data class UnrecognizedCodes(val codes: List<String>) : Failure()
    }
}

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
            unrecognized.forEach { code ->
                Log.e("RgpImport", "Unrecognized bead code in RGP file: $code")
            }
            return ImportResult.Failure.UnrecognizedCodes(unrecognized.sorted())
        }

        // 4. Count beads per palette letter across all rows.
        val beadCountByLetter = mutableMapOf<String, Int>()
        for (row in rgpProject.rows) {
            for (step in row.steps) {
                beadCountByLetter[step.description] =
                    (beadCountByLetter[step.description] ?: 0) + step.count
            }
        }

        // 5. Build the project bead list.
        //    Multiple palette letters may map to the same DB code (e.g. the same color used
        //    in two different letter slots). Sum their counts so each code appears exactly once —
        //    the rest of the app assumes codes are unique within a project's bead list.
        val dbCodeByLetter = rgpProject.colorMapping
            .entries
            .filter { it.value.startsWith("DB") }
            .associate { (letter, code) -> letter to code }

        // Accumulate integer bead counts per code, then divide once and round to 2 decimal places.
        // Dividing per-step and summing doubles produces 15+ decimal places of IEEE 754 noise.
        val countsByCode = mutableMapOf<String, Int>()
        for ((letter, code) in dbCodeByLetter) {
            val count = beadCountByLetter[letter] ?: continue
            countsByCode[code] = (countsByCode[code] ?: 0) + count
        }

        // Guard: a colorMapping with DB codes but none referenced in any step is a degenerate file.
        if (countsByCode.isEmpty()) return ImportResult.Failure.NoDelicaCodes

        val beads = countsByCode.map { (code, count) ->
            val scaled = count.toDouble() / BEADS_PER_GRAM * 100.0
            val grams = scaled.roundToLong() / 100.0
            ProjectBeadEntry(beadCode = code, targetGrams = grams)
        }

        // 6. Create the project; return success with the assigned ID.
        if (rgpProject.name.isBlank()) return ImportResult.Failure.InvalidJson
        val projectId = projectRepository.createProject(
            ProjectEntry(name = rgpProject.name, beads = beads),
        )
        return ImportResult.Success(projectId = projectId, name = rgpProject.name)
    }
}
