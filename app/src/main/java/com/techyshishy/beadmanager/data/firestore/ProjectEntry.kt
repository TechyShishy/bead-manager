package com.techyshishy.beadmanager.data.firestore

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.ServerTimestamp

/**
 * A user-defined project that groups a bead list and one or more orders.
 *
 * Firestore path: users/{uid}/projects/{projectId}
 * Debug path:     users_debug/{uid}/projects/{projectId}
 *
 * The bead list is embedded as an array of [ProjectBeadEntry] — projects are always read as a
 * unit, keeping the bead list update a single Firestore write. Orders are a separate collection
 * and reference their project by [projectId].
 *
 * Existing project documents without a [beads] field deserialize safely to an empty list.
 *
 * RGP grid fields ([rows], [colorMapping], [position], [markedSteps], [markedRows]) are
 * populated during import from a `.rgp` file. They are empty for projects created before the
 * import feature was built; those projects retain only the legacy [beads] list. All five
 * fields default to empty so old documents without them deserialize cleanly.
 *
 * [rows]          — full row/step bead grid from the `.rgp` source file. Each [ProjectRgpRow]
 *                   holds a list of [ProjectRgpStep]s whose [ProjectRgpStep.description] is a
 *                   palette key into [colorMapping].
 * [colorMapping]  — maps palette letter keys (e.g. "A", "AB") to either a Miyuki Delica catalog
 *                   code (e.g. "DB0001") or a hex color string (e.g. "#ff0000ff"). Hex entries
 *                   are not actionable for inventory and are skipped during bead-count derivation.
 * [position]      — rowguide progress cursor preserved for round-trip fidelity. Keys are "row"
 *                   and "step"; values are 0-based indices into [rows] and its steps list.
 * [markedSteps]   — rowguide per-step completion marks. Outer key is the row [id] as a String;
 *                   inner key is the step [id] as a String; value is the mark mode (1–6).
 * [markedRows]    — rowguide per-row completion marks. Key is the row [id] as a String; value
 *                   is the mark mode (1–6).
 *
 * Default values are required for Firestore no-argument deserialization.
 */
data class ProjectEntry(
    @DocumentId val projectId: String = "",
    val name: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    val notes: String? = null,
    val beads: List<ProjectBeadEntry> = emptyList(),
    val rows: List<ProjectRgpRow> = emptyList(),
    val colorMapping: Map<String, String> = emptyMap(),
    val position: Map<String, Int> = emptyMap(),
    val markedSteps: Map<String, Map<String, Int>> = emptyMap(),
    val markedRows: Map<String, Int> = emptyMap(),
)
