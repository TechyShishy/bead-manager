package com.techyshishy.beadmanager.data.rgp

import com.techyshishy.beadmanager.data.firestore.ProjectEntry
import com.techyshishy.beadmanager.data.firestore.ProjectRgpRow
import kotlinx.serialization.json.Json
import java.io.OutputStream
import java.util.zip.GZIPOutputStream

private val writerJson = Json { explicitNulls = false }

/**
 * Returns the djb2 hash of [s] as an [Int].
 *
 * djb2 is the algorithm used by pxlpxl to derive a deterministic integer `id` from the
 * project's string identifier. Using the same algorithm here ensures that exporting and
 * re-importing a project always produces the same `id` field, which rowguide and pxlpxl
 * rely on for project identity. Kotlin [Int] arithmetic wraps on overflow, producing
 * the same truncated 32-bit result as the original algorithm.
 */
internal fun djb2(s: String): Int {
    var h = 5381
    for (c in s) {
        h = (h shl 5) + h + c.code // h * 33 + c
    }
    return h
}

/**
 * Serializes [project] to a gzip-compressed `.rgp` JSON file and writes it to [stream].
 *
 * The output is a standards-conformant RGP file that pxlpxl and rowguide can load:
 * - `id` is a deterministic djb2 hash of [ProjectEntry.projectId]
 * - `rows` and `colorMapping` are always written
 * - `position`, `markedSteps`, and `markedRows` are omitted when empty (rowguide treats
 *   absent and empty identically; pxlpxl files never include them)
 *
 * [rows] is passed separately from [project] because the grid is stored in a Firestore
 * subcollection rather than inline in [ProjectEntry]. An empty [rows] list is valid when
 * [project]'s [ProjectEntry.colorMapping] is non-empty — the resulting file has an empty
 * `rows` array and can be round-tripped through [parseRgp]. [ExportRgpProjectUseCase] only
 * blocks export when *both* rows and colorMapping are empty.
 *
 * [stream] is wrapped in a [GZIPOutputStream] internally. The gzip trailer is written
 * via [GZIPOutputStream.finish] before this function returns, but [stream] itself is
 * **not closed** — the caller retains ownership and must close it (e.g., via `use`).
 */
fun writeRgp(stream: OutputStream, project: ProjectEntry, rows: List<ProjectRgpRow>) {
    val rgpProject = RgpProject(
        id = djb2(project.projectId),
        name = project.name,
        rows = rows.map { row ->
            RgpRow(
                id = row.id,
                steps = row.steps.map { step ->
                    RgpStep(id = step.id, count = step.count, description = step.description)
                },
            )
        },
        colorMapping = project.colorMapping,
        position = project.position.takeIf { it.isNotEmpty() },
        markedSteps = project.markedSteps.takeIf { it.isNotEmpty() },
        markedRows = project.markedRows.takeIf { it.isNotEmpty() },
    )
    val json = writerJson.encodeToString(RgpProject.serializer(), rgpProject)
    val gzip = GZIPOutputStream(stream)
    gzip.write(json.toByteArray(Charsets.UTF_8))
    gzip.finish()
}
