package io.github.akaiikitsune.plsshare

import android.content.SharedPreferences
import android.graphics.Bitmap

/**
 * Where the printer puts the file's rows on the print, measured on photos of the test chart.
 *
 * Relative to the grid of patches (which the chart reader aligns on), the grey steps' top, at 4 %
 * of the file, comes out higher, and the gradient's bottom, at 96 %, comes out lower: the SP-3
 * stretches the image vertically by a few percent, and its frame then hides the top and bottom of
 * it. The two measured positions give a linear map from file rows to print rows; its inverse
 * pre-warps prints so that what is framed on screen is what lands on paper. The same is done for
 * columns with the grey steps' left edge and the patches' right edge. What the frame hides on each
 * side is read by the user on the framing chart (nested, numbered rectangles) and entered as four
 * cuts; the previews then show those bands.
 */
class PrinterGeometry(
    /** Print position of the file's row 4 % (the grey steps' top). */
    var stepTop: Float = STEP_TOP_FILE,
    /** Print position of the file's row 96 % (the gradient's bottom). */
    var gradientBottom: Float = GRADIENT_BOTTOM_FILE,
    /** Print position of the file's column 4 % (the grey steps' left edge). */
    var stepLeft: Float = STEP_LEFT_FILE,
    /** Print position of the file's column 94.8 % (the patches' right edge). */
    var patchRight: Float = PATCH_RIGHT_FILE,
    /** Whether prints are pre-warped through the inverse map. */
    var compensate: Boolean = true,
    /** What the frame hides on each side, as fractions of the file, read on the framing chart. */
    var cutTop: Float = 0f,
    var cutBottom: Float = 0f,
    var cutLeft: Float = 0f,
    var cutRight: Float = 0f,
) {
    companion object {
        /** File positions of the chart's reference lines (content positions through [TestChart.contentToFile]). */
        val STEP_TOP_FILE = TestChart.contentToFile(0.04f)
        val GRADIENT_BOTTOM_FILE = TestChart.contentToFile(0.96f)
        val STEP_LEFT_FILE = TestChart.contentToFile(0.04f)
        val PATCH_RIGHT_FILE = TestChart.contentToFile(0.948f)
        private const val PREFIX = "geometry."

        fun load(prefs: SharedPreferences) = PrinterGeometry(
            prefs.getFloat(PREFIX + "stepTop", STEP_TOP_FILE),
            prefs.getFloat(PREFIX + "gradientBottom", GRADIENT_BOTTOM_FILE),
            prefs.getFloat(PREFIX + "stepLeft", STEP_LEFT_FILE),
            prefs.getFloat(PREFIX + "patchRight", PATCH_RIGHT_FILE),
            prefs.getBoolean(PREFIX + "compensate", true),
            prefs.getFloat(PREFIX + "cutTop", 0f), prefs.getFloat(PREFIX + "cutBottom", 0f),
            prefs.getFloat(PREFIX + "cutLeft", 0f), prefs.getFloat(PREFIX + "cutRight", 0f),
        )
    }

    fun save(prefs: SharedPreferences) {
        prefs.edit().putFloat(PREFIX + "stepTop", stepTop).putFloat(PREFIX + "gradientBottom", gradientBottom)
            .putFloat(PREFIX + "stepLeft", stepLeft).putFloat(PREFIX + "patchRight", patchRight)
            .putBoolean(PREFIX + "compensate", compensate)
            .putFloat(PREFIX + "cutTop", cutTop).putFloat(PREFIX + "cutBottom", cutBottom).putFloat(PREFIX + "cutLeft", cutLeft).putFloat(PREFIX + "cutRight", cutRight)
            .apply()
    }

    fun reset() {
        stepTop = STEP_TOP_FILE; gradientBottom = GRADIENT_BOTTOM_FILE; stepLeft = STEP_LEFT_FILE; patchRight = PATCH_RIGHT_FILE
        cutTop = 0f; cutBottom = 0f; cutLeft = 0f; cutRight = 0f
    }

    /** Whether the frame's cuts were entered. */
    val cutsKnown: Boolean get() = cutTop > 0f || cutBottom > 0f || cutLeft > 0f || cutRight > 0f

    /** Set from a measurement in content coordinates (top, bottom[, left, right] of the four reference lines). */
    fun set(measured: FloatArray) {
        stepTop = TestChart.contentToFile(measured[0]); gradientBottom = TestChart.contentToFile(measured[1])
        if (measured.size >= 4) { stepLeft = TestChart.contentToFile(measured[2]); patchRight = TestChart.contentToFile(measured[3]) }
    }

    /** Whether anything was measured; without a measurement the map is the identity and nothing is shown or warped. */
    val measured: Boolean get() = Math.abs(stepTop - STEP_TOP_FILE) > 1e-4f || Math.abs(gradientBottom - GRADIENT_BOTTOM_FILE) > 1e-4f ||
        Math.abs(stepLeft - STEP_LEFT_FILE) > 1e-4f || Math.abs(patchRight - PATCH_RIGHT_FILE) > 1e-4f

    /** Vertical scale of the print relative to the file (1.03 = 3 % taller). */
    val stretch: Float get() = (gradientBottom - stepTop) / (GRADIENT_BOTTOM_FILE - STEP_TOP_FILE)
    private val offset: Float get() = stepTop - stretch * STEP_TOP_FILE
    /** Horizontal scale of the print relative to the file. */
    val stretchX: Float get() = (patchRight - stepLeft) / (PATCH_RIGHT_FILE - STEP_LEFT_FILE)
    private val offsetX: Float get() = stepLeft - stretchX * STEP_LEFT_FILE

    /** The print row (0..1) where a file row (0..1) lands. */
    fun toPrint(file: Float): Float = offset + stretch * file

    /** The file row that lands on a print row. */
    fun toFile(print: Float): Float = (print - offset) / stretch

    fun toPrintX(file: Float): Float = offsetX + stretchX * file
    fun toFileX(print: Float): Float = (print - offsetX) / stretchX

    /**
     * Fraction of the image, as sent, hidden under the frame on each side: the cuts read on the
     * framing chart, which are fractions of the file; with the pre-warp on, file row f shows image
     * row toPrint(f), so the cuts move through the map.
     */
    val hiddenTop: Float get() = if (!cutsKnown) 0f else (if (compensate && measured) toPrint(cutTop) else cutTop).coerceIn(0f, 1f)
    val hiddenBottom: Float get() = if (!cutsKnown) 0f else (if (compensate && measured) 1f - toPrint(1f - cutBottom) else cutBottom).coerceIn(0f, 1f)
    val hiddenLeft: Float get() = if (!cutsKnown) 0f else (if (compensate && measured) toPrintX(cutLeft) else cutLeft).coerceIn(0f, 1f)
    val hiddenRight: Float get() = if (!cutsKnown) 0f else (if (compensate && measured) 1f - toPrintX(1f - cutRight) else cutRight).coerceIn(0f, 1f)

    /**
     * Pre-warp a print: file row f, column c receives the image at ([toPrintX] (c), [toPrint] (f)),
     * so the printer's stretch lands every pixel at its own place. Samples outside the image
     * repeat its edge; they end up under the frame anyway.
     */
    fun prewarp(image: Bitmap): Bitmap {
        if (!measured || !compensate) return image
        val w = image.width; val h = image.height
        val src = IntArray(w * h)
        image.getPixels(src, 0, w, 0, 0, w, h)
        val out = IntArray(w * h)
        // Column sampling positions, shared by every row.
        val x0 = IntArray(w); val x1 = IntArray(w); val tx = IntArray(w)
        for (c in 0 until w) {
            val p = (toPrintX(c / (w - 1f)) * (w - 1)).coerceIn(0f, w - 1f)
            x0[c] = p.toInt().coerceIn(0, w - 1); x1[c] = (x0[c] + 1).coerceAtMost(w - 1); tx[c] = ((p - x0[c]) * 256).toInt().coerceIn(0, 256)
        }
        fun lerp(a: Int, b: Int, t: Int): Int {
            val r = (((a shr 16) and 0xFF) * (256 - t) + ((b shr 16) and 0xFF) * t) shr 8
            val g = (((a shr 8) and 0xFF) * (256 - t) + ((b shr 8) and 0xFF) * t) shr 8
            val bl = ((a and 0xFF) * (256 - t) + (b and 0xFF) * t) shr 8
            return (a and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or bl
        }
        for (f in 0 until h) {
            val p = (toPrint(f / (h - 1f)) * (h - 1)).coerceIn(0f, h - 1f)
            val y0 = p.toInt().coerceIn(0, h - 1)
            val y1 = (y0 + 1).coerceAtMost(h - 1)
            val ty = ((p - y0) * 256).toInt().coerceIn(0, 256)
            val r0 = y0 * w; val r1 = y1 * w; val o = f * w
            for (c in 0 until w) {
                val top = lerp(src[r0 + x0[c]], src[r0 + x1[c]], tx[c])
                val bottom = lerp(src[r1 + x0[c]], src[r1 + x1[c]], tx[c])
                out[o + c] = lerp(top, bottom, ty)
            }
        }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { setPixels(out, 0, w, 0, 0, w, h) }
    }
}
