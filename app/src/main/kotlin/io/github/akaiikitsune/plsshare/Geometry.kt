package io.github.akaiikitsune.plsshare

import android.content.SharedPreferences
import android.graphics.Bitmap

/**
 * Where the printer puts the file on the print, measured on photos of the test chart.
 *
 * Two measurements, both in the frame of the chart's patch grid (which the reader aligns on):
 *
 *  - the file's own reference lines (the grey steps' top and left at 4 %, the gradient's bottom
 *    at 96 %, the patches' right edge at 94.8 %), which give a linear map of file rows and columns
 *    to print rows and columns ([toPrint], [toPrintX]); on the SP-3 it is close to the identity;
 *  - the card's four edges ([card]), a true 72 × 86 mm rectangle: where they fall in the chart's
 *    frame says how the printer rotated, scaled and skewed the file on the paper, as a homography
 *    to card millimetres ([toCard]).
 *
 * What the frame hides on each side is read on the framing ring (or entered) as four cuts, in
 * fractions of the file. With the card known, the cuts locate the frame's window on the card, and
 * printing then places the image whole and square inside that window ([prewarp]), undoing the
 * rotation and the stretch: nothing is hidden any more. Without the card, prints are pre-warped
 * through the row and column maps only, and the previews show the cuts as dark bands.
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
    /** The card's corners (top-left, top-right, bottom-right, bottom-left) in file coordinates, or null. */
    var card: FloatArray? = null,
    /** The rotation set by hand, in degrees clockwise, replacing the measured one; NaN while the measurement stands. */
    var rotationSet: Float = Float.NaN,
) {
    companion object {
        /** File positions of the chart's reference lines (content positions through [TestChart.contentToFile]). */
        val STEP_TOP_FILE = TestChart.contentToFile(0.04f)
        val GRADIENT_BOTTOM_FILE = TestChart.contentToFile(0.96f)
        val STEP_LEFT_FILE = TestChart.contentToFile(0.04f)
        val PATCH_RIGHT_FILE = TestChart.contentToFile(0.948f)
        /** instax SQUARE film: the card, and its image window. */
        const val CARD_WIDTH_MM = 72f
        const val CARD_HEIGHT_MM = 86f
        const val WINDOW_MM = 62f
        private const val PREFIX = "geometry."

        fun load(prefs: SharedPreferences) = PrinterGeometry(
            prefs.getFloat(PREFIX + "stepTop", STEP_TOP_FILE),
            prefs.getFloat(PREFIX + "gradientBottom", GRADIENT_BOTTOM_FILE),
            prefs.getFloat(PREFIX + "stepLeft", STEP_LEFT_FILE),
            prefs.getFloat(PREFIX + "patchRight", PATCH_RIGHT_FILE),
            prefs.getBoolean(PREFIX + "compensate", true),
            prefs.getFloat(PREFIX + "cutTop", 0f), prefs.getFloat(PREFIX + "cutBottom", 0f),
            prefs.getFloat(PREFIX + "cutLeft", 0f), prefs.getFloat(PREFIX + "cutRight", 0f),
            prefs.getString(PREFIX + "card", null)?.let { parseCard(it) },
            prefs.getFloat(PREFIX + "rotationSet", Float.NaN),
        )

        fun parseCard(text: String): FloatArray? = runCatching {
            val v = text.split(",").map { it.trim().toFloat() }
            if (v.size == 8) v.toFloatArray() else null
        }.getOrNull()

        fun cardText(card: FloatArray): String = card.joinToString(",") { "%.5f".format(java.util.Locale.ROOT, it) }
    }

    fun save(prefs: SharedPreferences) {
        prefs.edit().putFloat(PREFIX + "stepTop", stepTop).putFloat(PREFIX + "gradientBottom", gradientBottom)
            .putFloat(PREFIX + "stepLeft", stepLeft).putFloat(PREFIX + "patchRight", patchRight)
            .putBoolean(PREFIX + "compensate", compensate)
            .putFloat(PREFIX + "cutTop", cutTop).putFloat(PREFIX + "cutBottom", cutBottom).putFloat(PREFIX + "cutLeft", cutLeft).putFloat(PREFIX + "cutRight", cutRight)
            .putString(PREFIX + "card", card?.let { cardText(it) })
            .putFloat(PREFIX + "rotationSet", rotationSet)
            .apply()
    }

    fun reset() {
        stepTop = STEP_TOP_FILE; gradientBottom = GRADIENT_BOTTOM_FILE; stepLeft = STEP_LEFT_FILE; patchRight = PATCH_RIGHT_FILE
        cutTop = 0f; cutBottom = 0f; cutLeft = 0f; cutRight = 0f
        card = null
        rotationSet = Float.NaN
    }

    /** Whether the frame's cuts were entered. */
    val cutsKnown: Boolean get() = cutTop > 0f || cutBottom > 0f || cutLeft > 0f || cutRight > 0f

    /** Set from a measurement in content coordinates (top, bottom[, left, right] of the four reference lines). */
    fun set(measured: FloatArray) {
        stepTop = TestChart.contentToFile(measured[0]); gradientBottom = TestChart.contentToFile(measured[1])
        if (measured.size >= 4) { stepLeft = TestChart.contentToFile(measured[2]); patchRight = TestChart.contentToFile(measured[3]) }
    }

    /** Set the cuts from a ring reading (top, bottom, left, right), in percent of the file. */
    fun setCuts(ringPercent: FloatArray) {
        cutTop = ringPercent[0] / 100f; cutBottom = ringPercent[1] / 100f; cutLeft = ringPercent[2] / 100f; cutRight = ringPercent[3] / 100f
    }

    /** Whether the lines were measured; without a measurement the row and column maps are the identity. */
    val linesMeasured: Boolean get() = Math.abs(stepTop - STEP_TOP_FILE) > 1e-4f || Math.abs(gradientBottom - GRADIENT_BOTTOM_FILE) > 1e-4f ||
        Math.abs(stepLeft - STEP_LEFT_FILE) > 1e-4f || Math.abs(patchRight - PATCH_RIGHT_FILE) > 1e-4f

    /** Whether anything was measured or entered that changes prints. */
    val measured: Boolean get() = linesMeasured || card != null || rotationByHand

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

    // ------------------------------------------------------------ The card

    /** Homography from the chart's frame (file coordinates) to card millimetres, or null without the card. */
    private val cardMap: DoubleArray? get() = card?.let {
        ChartReader.homographyFrom(DoubleArray(8) { i -> it[i].toDouble() },
            floatArrayOf(0f, 0f, CARD_WIDTH_MM, 0f, CARD_WIDTH_MM, CARD_HEIGHT_MM, 0f, CARD_HEIGHT_MM))
    }

    /** Where a file position lands on the card, in millimetres from its top-left corner. */
    fun toCard(fx: Float, fy: Float): FloatArray {
        val m = cardMap ?: return floatArrayOf(fx, fy)
        val p = ChartReader.map(m, toPrintX(fx).toDouble(), toPrint(fy).toDouble())
        return floatArrayOf(p[0].toFloat(), p[1].toFloat())
    }

    /** The printed file's corners on the card, in millimetres (top-left, top-right, bottom-right, bottom-left). */
    private fun printedQuad(): Array<FloatArray> = arrayOf(toCard(0f, 0f), toCard(1f, 0f), toCard(1f, 1f), toCard(0f, 1f))

    /** The printed file's width and height on the card, in millimetres (means of its two sides each). */
    val printedSizeMm: FloatArray get() {
        val q = printedQuad()
        fun d(a: FloatArray, b: FloatArray) = Math.hypot((a[0] - b[0]).toDouble(), (a[1] - b[1]).toDouble()).toFloat()
        return floatArrayOf((d(q[0], q[1]) + d(q[3], q[2])) / 2, (d(q[0], q[3]) + d(q[1], q[2])) / 2)
    }

    /** How the file is turned on the card, in degrees, clockwise positive (the mean of its top and bottom edges). */
    val rotationDegrees: Float get() {
        val q = printedQuad()
        val top = Math.atan2((q[1][1] - q[0][1]).toDouble(), (q[1][0] - q[0][0]).toDouble())
        val bottom = Math.atan2((q[2][1] - q[3][1]).toDouble(), (q[2][0] - q[3][0]).toDouble())
        return Math.toDegrees((top + bottom) / 2).toFloat()
    }

    /** Whether the rotation was set by hand rather than measured. */
    val rotationByHand: Boolean get() = !rotationSet.isNaN()

    /** The rotation the printer is taken to apply, in degrees clockwise: the one set by hand, else the measured one (0 without the card). */
    var rotation: Float
        get() = if (rotationByHand) rotationSet else if (card != null) rotationDegrees else 0f
        set(value) { rotationSet = value }

    /** What the hand-set rotation adds to the measured one, in degrees. */
    private val rotationTrim: Float get() = rotation - (if (card != null) rotationDegrees else 0f)

    /** The printed file's bottom width relative to its top width, minus one (negative: narrower at the bottom). */
    val keystone: Float get() {
        val q = printedQuad()
        fun d(a: FloatArray, b: FloatArray) = Math.hypot((a[0] - b[0]).toDouble(), (a[1] - b[1]).toDouble()).toFloat()
        return d(q[3], q[2]) / d(q[0], q[1]) - 1f
    }

    /**
     * The frame's window on the card, from the cuts: (left, top, right, bottom) in millimetres,
     * taking the ring's readings where they were made, a third of the way along each side. Null
     * without the card or the cuts.
     */
    val windowMm: FloatArray? get() {
        if (card == null || !cutsKnown) return null
        val left = toCard(cutLeft, 0.3f)[0]; val right = toCard(1f - cutRight, 0.3f)[0]
        val top = toCard(0.3f, cutTop)[1]; val bottom = toCard(0.3f, 1f - cutBottom)[1]
        return floatArrayOf(left, top, right, bottom)
    }

    /** The square the image is printed into: the window's centre and mean side, (cx, cy, side) in millimetres. */
    val targetMm: FloatArray? get() {
        val w = windowMm ?: return null
        val side = ((w[2] - w[0]) + (w[3] - w[1])) / 2
        return floatArrayOf((w[0] + w[2]) / 2, (w[1] + w[3]) / 2, side)
    }

    /** Whether the card and the window are measured and plausible, so that prints can be placed in the window. */
    val placed: Boolean get() {
        val t = targetMm ?: return false
        val size = printedSizeMm
        return t[2] > WINDOW_MM * 0.9f && t[2] < WINDOW_MM * 1.1f && size[0] > 55f && size[0] < 72f && size[1] > 55f && size[1] < 80f && Math.abs(rotationDegrees) < 5f
    }

    /** Whether the next print is placed whole in the window. */
    val placing: Boolean get() = compensate && placed

    /**
     * Fraction of the image, as sent, hidden under the frame on each side. With the image placed
     * in the window, nothing. Otherwise the cuts read on the framing chart, which are fractions
     * of the file; with the pre-warp on, file row f shows image row toPrint(f), so the cuts move
     * through the map.
     */
    val hiddenTop: Float get() = if (!cutsKnown || placing) 0f else (if (compensate && linesMeasured) toPrint(cutTop) else cutTop).coerceIn(0f, 1f)
    val hiddenBottom: Float get() = if (!cutsKnown || placing) 0f else (if (compensate && linesMeasured) 1f - toPrint(1f - cutBottom) else cutBottom).coerceIn(0f, 1f)
    val hiddenLeft: Float get() = if (!cutsKnown || placing) 0f else (if (compensate && linesMeasured) toPrintX(cutLeft) else cutLeft).coerceIn(0f, 1f)
    val hiddenRight: Float get() = if (!cutsKnown || placing) 0f else (if (compensate && linesMeasured) 1f - toPrintX(1f - cutRight) else cutRight).coerceIn(0f, 1f)

    /**
     * Pre-warp a print. With the card and the window known, file pixel (c, f) lands at
     * [toCard] (c, f) on the paper and receives the image pixel that belongs there when the image
     * fills the window square: the print comes out whole, square and straight. Otherwise file row
     * f, column c receives the image at ([toPrintX] (c), [toPrint] (f)), so that the printer's
     * stretch lands every pixel at its own place. Samples outside the image repeat its edge.
     */
    fun prewarp(image: Bitmap): Bitmap {
        if (!measured || !compensate) return image
        val w = image.width; val h = image.height
        val src = IntArray(w * h)
        image.getPixels(src, 0, w, 0, 0, w, h)
        val out = IntArray(w * h)
        fun lerp(a: Int, b: Int, t: Int): Int {
            val r = (((a shr 16) and 0xFF) * (256 - t) + ((b shr 16) and 0xFF) * t) shr 8
            val g = (((a shr 8) and 0xFF) * (256 - t) + ((b shr 8) and 0xFF) * t) shr 8
            val bl = ((a and 0xFF) * (256 - t) + (b and 0xFF) * t) shr 8
            return (a and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or bl
        }
        fun bilinear(px: Float, py: Float): Int {
            val x = px.coerceIn(0f, w - 1f); val y = py.coerceIn(0f, h - 1f)
            val x0 = x.toInt().coerceIn(0, w - 1); val x1 = (x0 + 1).coerceAtMost(w - 1); val tx = ((x - x0) * 256).toInt().coerceIn(0, 256)
            val y0 = y.toInt().coerceIn(0, h - 1); val y1 = (y0 + 1).coerceAtMost(h - 1); val ty = ((y - y0) * 256).toInt().coerceIn(0, 256)
            return lerp(lerp(src[y0 * w + x0], src[y0 * w + x1], tx), lerp(src[y1 * w + x0], src[y1 * w + x1], tx), ty)
        }
        // The hand-entered rotation: the file is taken to land that much further clockwise.
        val trim = Math.toRadians(rotationTrim.toDouble())
        val cosT = Math.cos(trim); val sinT = Math.sin(trim)
        val target = if (placed) targetMm else null
        val m = cardMap
        if (target != null && m != null) {
            val cx = target[0]; val cy = target[1]; val side = target[2]
            for (f in 0 until h) {
                val fy = toPrint(f / (h - 1f)).toDouble()
                val o = f * w
                for (c in 0 until w) {
                    val p = ChartReader.map(m, toPrintX(c / (w - 1f)).toDouble(), fy)
                    val dx = p[0] - cx; val dy = p[1] - cy
                    val u = ((cosT * dx - sinT * dy) / side + 0.5).toFloat(); val v = ((sinT * dx + cosT * dy) / side + 0.5).toFloat()
                    out[o + c] = bilinear(u * (w - 1), v * (h - 1))
                }
            }
        } else {
            for (f in 0 until h) {
                val fy = f / (h - 1.0) - 0.5
                val o = f * w
                for (c in 0 until w) {
                    val fx = c / (w - 1.0) - 0.5
                    val rx = (cosT * fx - sinT * fy + 0.5).toFloat(); val ry = (sinT * fx + cosT * fy + 0.5).toFloat()
                    out[o + c] = bilinear(toPrintX(rx) * (w - 1), toPrint(ry) * (h - 1))
                }
            }
        }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { setPixels(out, 0, w, 0, 0, w, h) }
    }
}
