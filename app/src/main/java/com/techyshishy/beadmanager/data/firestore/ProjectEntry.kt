package com.techyshishy.beadmanager.data.firestore

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.Exclude
import com.google.firebase.firestore.ServerTimestamp

/**
 * A user-defined project that groups a bead grid and one or more orders.
 *
 * Firestore path: users/{uid}/projects/{projectId}
 * Debug path:     users_debug/{uid}/projects/{projectId}
 *
 * Bead requirements are derived from the RGP grid rather than stored as a flat list. The
 * [colorMapping] palette maps letter keys (e.g. "A") to Miyuki Delica catalog codes; bead gram
 * quantities are computed on-demand via `computeBeadRequirements` from the rows loaded out of the
 * `grid/` subcollection. The inline `rows` field was retired to avoid Firestore mutation overlays
 * that exceed SQLite's CursorWindow limit for large patterns.
 *
 * Grid storage: rows are stored in the subcollection `projects/{id}/grid/{chunkIndex}` as
 * [ProjectGridChunk] documents, chunked at 200 rows each. [rowCount] is a denormalised total
 * written atomically with the grid so callers can determine whether a grid exists without
 * loading the subcollection.
 *
 * [rowCount]             — total number of rows stored in the grid subcollection. 0 for
 *                          projects with no imported grid.
 * [colorMapping]         — maps palette letter keys (e.g. "A", "AB") to either a Miyuki Delica
 *                          catalog code (e.g. "DB0001") or a hex color string (e.g. "#ff0000ff").
 *                          Hex entries are not actionable for inventory.
 * [originalColorMapping] — maps the same palette letter keys to the catalog code that was
 *                          present at the time of the first swap. Written once per key — never
 *                          overwritten by subsequent swaps. Empty map for projects where no
 *                          swap has ever occurred; a missing key means that palette slot was
 *                          never changed. Existing Firestore documents without this field
 *                          deserialize cleanly with the empty-map default.
 * [position]      — rowguide progress cursor. Keys are "row" and "step"; values are
 *                   0-based indices into the row list.
 * [markedSteps]   — rowguide per-step completion marks. Outer key is the row [id] as a
 *                   String; inner key is the step [id] as a String; value is the mark mode.
 * [markedRows]    — rowguide per-row completion marks. Key is the row [id] as a String.
 *
 * Default values are required for Firestore no-argument deserialization.
 */
data class ProjectEntry(
    @DocumentId val projectId: String = "",
    val name: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    val notes: String? = null,
    val rowCount: Int = 0,
    val colorMapping: Map<String, String> = emptyMap(),
    val originalColorMapping: Map<String, String> = emptyMap(),
    val position: Map<String, Int> = emptyMap(),
    val markedSteps: Map<String, Map<String, Int>> = emptyMap(),
    val markedRows: Map<String, Int> = emptyMap(),
    val imageUrl: String? = null,
    val tags: List<String> = emptyList(),
    @ServerTimestamp val lastUpdated: Timestamp? = null,
) {
    /**
     * True when all palette keys recorded in [originalColorMapping] are still at their original
     * values in the current [colorMapping]. Vacuously true when [originalColorMapping] is empty
     * (no swaps have ever been made). If a key recorded in [originalColorMapping] is no longer
     * present in [colorMapping], that slot is treated as changed and this returns false.
     *
     * This is a computed property — it has no backing field. [@get:Exclude] prevents Firebase's
     * CustomClassMapper from including it during Firestore serialization.
     */
    @get:Exclude
    val isAllOriginalColors: Boolean
        get() = originalColorMapping.all { (key, original) ->
            colorMapping[key] == original
        }
}
