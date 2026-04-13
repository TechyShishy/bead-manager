package com.techyshishy.beadmanager.data.firestore

/**
 * A single row in a project's RGP bead grid, as stored in Firestore.
 *
 * Structurally mirrors [com.techyshishy.beadmanager.data.rgp.RgpRow] but uses Firestore
 * reflection deserialization (no-arg constructor via default values) rather than
 * kotlinx.serialization, which the rgp layer uses. The two classes are intentionally
 * separate because the serialization mechanisms are incompatible — do not merge them.
 *
 * Default values are required for Firestore no-argument deserialization.
 */
data class ProjectRgpRow(
    val id: Int = 0,
    val steps: List<ProjectRgpStep> = emptyList(),
)
