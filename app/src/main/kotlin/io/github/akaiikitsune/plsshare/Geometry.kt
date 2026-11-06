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
 * pre-warps prints so that what is framed on screen is what lands on paper, and the frame's window,
 * measured once on a print's edges, tells which part of the file stays visible. Columns were
 * measured to match the file within a percent, so only rows are modelled.
 */
class PrinterGeometry(
    /** Print position of the file's row 4 % (the grey steps' top). */
    var stepTop: Float = STEP_TOP_FILE,
    /** Print position of the file's row 96 % (the gradient's bottom). */
    var gradientBottom: Float = GRADIENT_BOTTOM_FILE,
    /** Whether prints are pre-warped through the inverse map. */
    var compensate: Boolean = true,
) {
    companion object {
        const val STEP_TOP_FILE = 0.04f
        const val GRADIENT_BOTTOM_FILE = 0.96f
        /** The frame's window on the print, in print coordinates, measured on the frame's edges of a print. */
        const val WINDOW_TOP = 0f
        const val WINDOW_BOTTOM = 0.96f
        private const val PREFIX = "geometry."

        fun load(prefs: SharedPreferences) = PrinterGeometry(
            prefs.getFloat(PREFIX + "stepTop", STEP_TOP_FILE),
            prefs.getFloat(PREFIX + "gradientBottom", GRADIENT_BOTTOM_FILE),
            prefs.getBoolean(PREFIX + "compensate", true),
        )
    }

    fun save(prefs: SharedPreferences) {
        prefs.edit().putFloat(PREFIX + "stepTop", stepTop).putFloat(PREFIX + "gradientBottom", gradientBottom).putBoolean(PREFIX + "compensate", compensate).apply()
    }

    fun reset() { stepTop = STEP_TOP_FILE; gradientBottom = GRADIENT_BOTTOM_FILE }

    /** Whether anything was measured; without a measurement the map is the identity and nothing is shown or warped. */
    val measured: Boolean get() = Math.abs(stepTop - STEP_TOP_FILE) > 1e-4f || Math.abs(gradientBottom - GRADIENT_BOTTOM_FILE) > 1e-4f

    /** Vertical scale of the print relative to the file (1.03 = 3 % taller). */
    val stretch: Float get() = (gradientBottom - stepTop) / (GRADIENT_BOTTOM_FILE - STEP_TOP_FILE)
    private val offset: Float get() = stepTop - stretch * STEP_TOP_FILE

    /** The print row (0..1) where a file row (0..1) lands. */
    fun toPrint(file: Float): Float = offset + stretch * file

    /** The file row that lands on a print row. */
    fun toFile(print: Float): Float = (print - offset) / stretch

    /** Fraction of the image, as sent, hidden under the frame at the top and at the bottom. */
    val hiddenTop: Float get() = if (!measured) 0f else if (compensate) WINDOW_TOP else toFile(WINDOW_TOP).coerceIn(0f, 1f)
    val hiddenBottom: Float get() = if (!measured) 0f else if (compensate) 1f - WINDOW_BOTTOM else (1f - toFile(WINDOW_BOTTOM)).coerceIn(0f, 1f)

    /**
     * Pre-warp a print: file row f receives the image's row at [toPrint] (f), so the printer's
     * stretch lands every image row at its own place. Rows mapped outside the image repeat its
     * edge; they end up under the frame anyway.
     */
    fun prewarp(image: Bitmap): Bitmap {
        if (!measured || !compensate) return image
        val w = image.width; val h = image.height
        val src = IntArray(w * h)
        image.getPixels(src, 0, w, 0, 0, w, h)
        val out = IntArray(w * h)
        for (f in 0 until h) {
            val p = (toPrint(f / (h - 1f)) * (h - 1)).coerceIn(0f, h - 1f)
            val y0 = p.toInt().coerceIn(0, h - 1)
            val y1 = (y0 + 1).coerceAtMost(h - 1)
            val t = ((p - y0) * 256).toInt().coerceIn(0, 256)
            val r0 = y0 * w; val r1 = y1 * w; val o = f * w
            for (x in 0 until w) {
                val a = src[r0 + x]; val b = src[r1 + x]
                val r = (((a shr 16) and 0xFF) * (256 - t) + ((b shr 16) and 0xFF) * t) shr 8
                val g = (((a shr 8) and 0xFF) * (256 - t) + ((b shr 8) and 0xFF) * t) shr 8
                val bl = ((a and 0xFF) * (256 - t) + (b and 0xFF) * t) shr 8
                out[o + x] = (a and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or bl
            }
        }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { setPixels(out, 0, w, 0, 0, w, h) }
    }
}
