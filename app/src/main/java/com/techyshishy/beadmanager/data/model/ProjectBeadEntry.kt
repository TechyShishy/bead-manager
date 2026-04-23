package com.techyshishy.beadmanager.data.model

/**
 * A single bead in a project's bead list — a UI data transfer object.
 *
 * This type is produced by [com.techyshishy.beadmanager.ui.projects.ProjectDetailViewModel.beads]
 * from the project's RGP grid and colorMapping, and consumed by the project detail screen and
 * order creation logic. It is not stored in Firestore.
 *
 * [beadCode]         — catalog bead code (e.g. "DB0001"). Unique within a project bead list.
 * [targetGrams]      — gram requirement for this color derived from the project grid.
 *                      0.0 for beads registered only in colorMapping with no grid steps.
 * [originalBeadCode] — catalog code recorded at the time of the first swap for this bead's
 *                      palette key, or null when this slot has never been swapped. Only
 *                      populated when it differs from [beadCode].
 *
 * Vendor information is intentionally absent. Vendor selection happens at order time;
 * this type expresses project intent, not sourcing.
 */
data class ProjectBeadEntry(
    val beadCode: String = "",
    val targetGrams: Double = 0.0,
    val originalBeadCode: String? = null,
)
