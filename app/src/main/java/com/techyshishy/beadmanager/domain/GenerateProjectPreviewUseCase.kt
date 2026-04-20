package com.techyshishy.beadmanager.domain

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import com.techyshishy.beadmanager.data.db.BeadEntity
import com.techyshishy.beadmanager.data.firestore.ProjectRgpRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import javax.inject.Inject

/**
 * Renders a project's RGP peyote grid into a PNG and returns the compressed bytes.
 *
 * The coordinate system follows flat peyote stitch geometry: two consecutive buffer rows
 * together form one visual row, with odd buffer rows flush at the top and even buffer rows
 * offset downward by half a bead height. Odd buffer rows are also worked right-to-left
 * (RTL), so the RLE-expanded bead sequence must be reversed before mapping bead index to
 * screen column.
 *
 * Bead pixel constants are empirically derived from Miyuki Delica 11/0 physical measurements
 * (tube length ~1.29 mm, outer diameter ~1.6 mm, vertical pitch ~1.73 mm with thread tension).
 * The 3:4 width:height aspect ratio matches the physical aspect ratio to within 0.3%.
 * These are defined here so they are easy to promote to user preferences in a future pass.
 */
class GenerateProjectPreviewUseCase @Inject constructor() {

    companion object {
        private const val BEAD_WIDTH_PX = 3
        private const val BEAD_HEIGHT_PX = 4
    }

    /**
     * Renders [rows] to a PNG bitmap using [colorMapping] to resolve palette keys and
     * [beadLookup] to resolve DB codes to ARGB color ints.
     *
     * Resolution chain per bead: palette letter → [colorMapping] value (a DB code) →
     * [beadLookup] entry → [BeadEntity.hex] → ARGB int via [android.graphics.Color.parseColor].
     * Any missing link in the chain causes the bead cell to be skipped (rendered transparent).
     *
     * Bitmap rendering runs on [Dispatchers.Default] to keep the main thread free. PNG
     * compression and the resulting [ByteArrayOutputStream] are also performed there.
     *
     * @return PNG-compressed bytes of the rendered bitmap.
     * @throws IllegalArgumentException if [rows] is empty.
     */
    suspend fun render(
        rows: List<ProjectRgpRow>,
        colorMapping: Map<String, String>,
        beadLookup: Map<String, BeadEntity>,
    ): ByteArray {
        require(rows.isNotEmpty()) { "Cannot render a preview for a project with no grid rows." }

        return withContext(Dispatchers.Default) {
            val bufHeight = rows.size
            val bufWidth = rows.maxOf { row -> row.steps.sumOf { it.count } }

            val canvasWidth = bufWidth * 2 * BEAD_WIDTH_PX
            // Even buffer rows are always offset downward by half a bead height, and one is
            // always present regardless of parity, so the canvas always needs the extra half-bead
            // of room at the bottom. The even-row beads span from (beadRow*H + H/2) to
            // ((beadRow+1)*H + H/2), and the maximum beadRow is (bufHeight-1)/2 (integer div).
            val canvasHeight = ((bufHeight + 1) / 2) * BEAD_HEIGHT_PX + BEAD_HEIGHT_PX / 2

            val bitmap = Bitmap.createBitmap(canvasWidth, canvasHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val paint = Paint().apply { style = Paint.Style.FILL }

            // Use list index (not row.id) for all geometry. Row IDs come from the source
            // .rgp file verbatim and are not guaranteed to be 0-indexed or contiguous.
            rows.forEachIndexed { by, row ->
                val isOddRow = by % 2 == 1
                val beadRow = by / 2

                // Expand RLE steps into a flat sequence of palette letter keys.
                val expanded = buildList {
                    for (step in row.steps) {
                        repeat(step.count) { add(step.description) }
                    }
                }

                // Odd rows are worked RTL — reverse the expanded sequence so bead index 0
                // maps to the rightmost column rather than the leftmost.
                val beads = if (isOddRow) expanded.reversed() else expanded

                for (bx in beads.indices) {
                    val letter = beads[bx]
                    val dbCode = colorMapping[letter] ?: continue
                    val hex = beadLookup[dbCode]?.hex ?: continue
                    val argb = runCatching { android.graphics.Color.parseColor(hex) }.getOrNull()
                        ?: continue

                    val col = if (isOddRow) bx * 2 else bx * 2 + 1
                    val sx = col * BEAD_WIDTH_PX
                    val sy = beadRow * BEAD_HEIGHT_PX + if (col % 2 == 1) BEAD_HEIGHT_PX / 2 else 0

                    paint.color = argb
                    canvas.drawRect(
                        sx.toFloat(),
                        sy.toFloat(),
                        (sx + BEAD_WIDTH_PX).toFloat(),
                        (sy + BEAD_HEIGHT_PX).toFloat(),
                        paint,
                    )
                }
            }

            try {
                ByteArrayOutputStream().use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    out.toByteArray()
                }
            } finally {
                bitmap.recycle()
            }
        }
    }
}
