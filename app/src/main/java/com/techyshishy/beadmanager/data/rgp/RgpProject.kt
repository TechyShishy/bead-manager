package com.techyshishy.beadmanager.data.rgp

import kotlinx.serialization.Serializable

@Serializable
data class RgpStep(
    val id: Int,
    val count: Int,
    val description: String,
)

@Serializable
data class RgpRow(
    val id: Int,
    val steps: List<RgpStep>,
)

/**
 * Top-level structure of a `.rgp` file after gzip decompression and JSON decoding.
 *
 * An `.rgp` file is a gzip-compressed JSON object. [colorMapping] is required — a
 * file without it is not a valid modern RGP project and will fail validation in
 * [RgpParser]. Values in [colorMapping] are either:
 *   - Miyuki Delica catalog codes starting with "DB" (e.g. "DB0001")
 *   - Hex colors with alpha starting with "#" (e.g. "#ff0000ff") — used by pixel-art
 *     projects in pxlpxl and not actionable for bead inventory import
 *
 * Fields beyond `id`, `name`, `rows`, and `colorMapping` (position,
 * firstLastAppearanceMap, image, markedSteps, markedRows) are ignored.
 */
@Serializable
data class RgpProject(
    val id: Int,
    val name: String,
    val rows: List<RgpRow>,
    val colorMapping: Map<String, String>,
)
