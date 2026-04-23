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
 * [position], [markedSteps], and [markedRows] are optional rowguide-specific fields.
 * Files exported by pxlpxl omit them. They default to null so files without them
 * deserialize cleanly; callers map null to empty collections as needed.
 *
 * [image] holds a base64-encoded PNG of the project cover when present. Bead Manager
 * writes it on export when a cover image exists; consuming tools (rowguide, pxlpxl) that
 * support the field display it automatically. Files without the field deserialize cleanly
 * because it defaults to null.
 *
 * Other fields (`firstLastAppearanceMap`) are silently dropped by `ignoreUnknownKeys = true`
 * in the parser and are not captured here.
 */
@Serializable
data class RgpProject(
    val id: Int,
    val name: String,
    val rows: List<RgpRow>,
    val colorMapping: Map<String, String>,
    val position: Map<String, Int>? = null,
    val markedSteps: Map<String, Map<String, Int>>? = null,
    val markedRows: Map<String, Int>? = null,
    val image: String? = null,
)
