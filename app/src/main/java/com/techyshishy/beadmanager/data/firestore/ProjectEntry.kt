package com.techyshishy.beadmanager.data.firestore

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.ServerTimestamp

/**
 * A user-defined project that groups a bead grid and one or more orders.
 *
 * Firestore path: users/{uid}/projects/{projectId}
 * Debug path:     users_debug/{uid}/projects/{projectId}
 *
 * Bead requirements are derived from the RGP grid rather than stored as a flat list. The
 * [colorMapping] palette maps letter keys (e.g. "A") to Miyuki Delica catalog codes; the
 * [rows] grid encodes how many of each palette entry the project uses. Bead gram quantities
 * are computed on-demand via `computeBeadRequirements`. Beads registered via "Add to Project"
 * without a corresponding grid step appear in [colorMapping] only and carry an implicit 0 g
 * target.
 *
 * Legacy documents that stored beads as a `beads` array field are migrated to this grid
 * format on first launch. After migration the `beads` field is deleted from Firestore.
 *
 * [rows]          — full row/step bead grid. Each [ProjectRgpRow] holds a list of
 *                   [ProjectRgpStep]s whose [ProjectRgpStep.description] is a palette key
 *                   into [colorMapping]. Empty for projects with no imported grid.
 * [colorMapping]  — maps palette letter keys (e.g. "A", "AB") to either a Miyuki Delica
 *                   catalog code (e.g. "DB0001") or a hex color string (e.g. "#ff0000ff").
 *                   Hex entries are not actionable for inventory. DB-code entries that have
 *                   no corresponding step in [rows] are colorMapping-only entries at 0 g.
 * [position]      — rowguide progress cursor. Keys are "row" and "step"; values are
 *                   0-based indices into [rows] and its steps list.
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
    // TODO: move ProjectBeadEntry out of data/firestore/ — it is no longer a Firestore-stored type
    //       and is retained here only as a UI DTO (see ProjectDetailViewModel.beads).
    val rows: List<ProjectRgpRow> = emptyList(),
    val colorMapping: Map<String, String> = emptyMap(),
    val position: Map<String, Int> = emptyMap(),
    val markedSteps: Map<String, Map<String, Int>> = emptyMap(),
    val markedRows: Map<String, Int> = emptyMap(),
)
