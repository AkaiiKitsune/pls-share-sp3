package io.github.akaiikitsune.plsshare

import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.Paint

/**
 * How the instax film and printer render the data we send, as a small parametric model, and its
 * inverse for pre-compensating prints.
 *
 * Starting values come from published observations (no manufacturer data exists):
 * - prints are darker than the screen (fujix-forum, SP-3 printing straight from a camera; Fujifilm's
 *   own note that the print "may differ from the screen");
 * - little latitude: about +1 / -2 stops with detail, shadows block up, highlights clip
 *   (photrio measurements on instax film; Emulsive; Digital Trends);
 * - more contrast and saturation than the screen (photographyblog, SP-3 review);
 * - saturated blues come out much darker, warm colours very slightly green (Emulsive, SP-2);
 * - the grey balance is disputed: Dom Varney's ICC profiling of the SP-2 needed a 7-step yellow
 *   shift for neutral greys (bluish prints), Emulsive found whites neutral. The default is a
 *   slight cool cast, between the two.
 * The calibration screen lets the user fit these to their own printer and film batch.
 */
class FilmModel(
    /** Exposure offset in stops; negative = darker print. */
    var exposure: Float = -0.35f,
    /** Midtone slope around mid-grey; > 1 compresses the latitude (clips both ends). */
    var contrast: Float = 1.18f,
    /** Below this input level everything prints black (0..0.2). */
    var blackClip: Float = 0.05f,
    /** Above this input level everything prints white (0.8..1). */
    var whiteClip: Float = 0.96f,
    var saturation: Float = 1.08f,
    /** Grey balance in the blue-yellow direction: negative = bluish print, positive = yellowish. */
    var warmth: Float = -0.05f,
    /** Grey balance in the green-magenta direction: negative = greenish. */
    var tint: Float = -0.02f,
    /** How much darker saturated blues print (0 = not at all, 1 = strongly). */
    var blueDarkening: Float = 0.5f,
) {
    companion object {
        private const val PREFIX = "film."

        fun load(prefs: SharedPreferences): FilmModel {
            val m = FilmModel()
            fun f(key: String, default: Float) = prefs.getFloat(PREFIX + key, default)
            m.exposure = f("exposure", m.exposure); m.contrast = f("contrast", m.contrast)
            m.blackClip = f("blackClip", m.blackClip); m.whiteClip = f("whiteClip", m.whiteClip)
            m.saturation = f("saturation", m.saturation); m.warmth = f("warmth", m.warmth)
            m.tint = f("tint", m.tint); m.blueDarkening = f("blueDarkening", m.blueDarkening)
            return m
        }

    }

    fun save(prefs: SharedPreferences) {
        prefs.edit()
            .putFloat(PREFIX + "exposure", exposure).putFloat(PREFIX + "contrast", contrast)
            .putFloat(PREFIX + "blackClip", blackClip).putFloat(PREFIX + "whiteClip", whiteClip)
            .putFloat(PREFIX + "saturation", saturation).putFloat(PREFIX + "warmth", warmth)
            .putFloat(PREFIX + "tint", tint).putFloat(PREFIX + "blueDarkening", blueDarkening)
            .apply()
    }

    fun copy() = FilmModel(exposure, contrast, blackClip, whiteClip, saturation, warmth, tint, blueDarkening)

    /** The colour part of the model: saturation, grey balance and blue darkening. */
    fun colorMatrix(): ColorMatrix {
        val m = ColorMatrix()
        m.setSaturation(saturation)
        m.postConcat(ColorMatrix(floatArrayOf(
            1f + 0.18f * warmth + 0.05f * tint, 0f, 0f, 0f, 0f,
            0f, 1f - 0.12f * tint, 0f, 0f, 0f,
            0f, 0f, 1f - 0.18f * warmth + 0.05f * tint, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        )))
        return m
    }

    /** The inverse of [colorMatrix], for compensation. */
    fun inverseColorMatrix(): ColorMatrix = invert(colorMatrix())

    /** The tone part: exposure, latitude compression and clipping, as a lookup table on sRGB levels. */
    fun toneCurve(): IntArray {
        val lut = IntArray(256)
        val gain = Math.pow(2.0, exposure.toDouble()).toFloat()
        for (i in 0..255) {
            var v = i / 255f
            // Exposure in linear light, back to display gamma.
            v = Math.pow(Math.pow(v.toDouble(), 2.2) * gain, 1 / 2.2).toFloat()
            // Latitude: the usable range shrinks to [blackClip, whiteClip], steeper in the middle.
            v = (v - blackClip) / (whiteClip - blackClip)
            v = 0.5f + (v - 0.5f) * contrast
            lut[i] = Math.round(v.coerceIn(0f, 1f) * 255f)
        }
        return lut
    }

    /**
     * The inverse tone curve: for each wanted print level, the input level that produces it. Levels
     * the film cannot reach (beyond the clips) map to the nearest reachable input.
     */
    fun inverseToneCurve(): IntArray {
        val forward = toneCurve()
        val inverse = IntArray(256)
        var j = 0
        for (target in 0..255) {
            while (j < 255 && forward[j] < target) j++
            inverse[target] = j
        }
        return inverse
    }

    /** Apply the model to pixels (display simulation): colours, blue darkening, then tones. */
    fun simulate(pixels: IntArray) {
        applyMatrix(pixels, colorMatrix())
        darkenBlues(pixels, inverse = false)
        Processing.applyCurve(pixels, toneCurve())
    }

    /** Apply the inverse model to pixels (print compensation), in reverse order. */
    fun compensate(pixels: IntArray) {
        Processing.applyCurve(pixels, inverseToneCurve())
        darkenBlues(pixels, inverse = true)
        applyMatrix(pixels, inverseColorMatrix())
    }

    /**
     * Saturated blues print darker: each pixel is scaled (hue preserved) by 1 − a·blueness, where
     * blueness = max(0, B − (R+G)/2) / 255 and a = 0.6 · [blueDarkening]. Neutral pixels are untouched.
     * The inverse solves f² − f + a·b_out = 0 for the scale that was applied; blues darker than
     * the film can produce are mapped to the strongest compensation available.
     */
    private fun darkenBlues(pixels: IntArray, inverse: Boolean) {
        val a = blueDarkening * 0.6f
        if (a <= 0f) return
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val blueness = (b - (r + g) / 2f) / 255f
            if (blueness <= 0f) continue
            val factor = if (!inverse) {
                1f - a * blueness
            } else {
                val disc = 1f - 4f * a * blueness
                if (disc <= 0f) 0.5f else (1f + Math.sqrt(disc.toDouble()).toFloat()) / 2f
            }
            val scale = if (inverse) 1f / factor else factor
            val nr = (r * scale).toInt().coerceIn(0, 255)
            val ng = (g * scale).toInt().coerceIn(0, 255)
            val nb = (b * scale).toInt().coerceIn(0, 255)
            pixels[i] = (p and 0xFF000000.toInt()) or (nr shl 16) or (ng shl 8) or nb
        }
    }

    private fun applyMatrix(pixels: IntArray, matrix: ColorMatrix) {
        val m = matrix.array
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val nr = (m[0] * r + m[1] * g + m[2] * b + m[4]).toInt().coerceIn(0, 255)
            val ng = (m[5] * r + m[6] * g + m[7] * b + m[9]).toInt().coerceIn(0, 255)
            val nb = (m[10] * r + m[11] * g + m[12] * b + m[14]).toInt().coerceIn(0, 255)
            pixels[i] = (p and 0xFF000000.toInt()) or (nr shl 16) or (ng shl 8) or nb
        }
    }

    /** Invert the 3x3 colour part of a matrix (the offsets are zero in this model). */
    private fun invert(matrix: ColorMatrix): ColorMatrix {
        val m = matrix.array
        val a = doubleArrayOf(m[0].toDouble(), m[1].toDouble(), m[2].toDouble(), m[5].toDouble(), m[6].toDouble(), m[7].toDouble(), m[10].toDouble(), m[11].toDouble(), m[12].toDouble())
        val det = a[0] * (a[4] * a[8] - a[5] * a[7]) - a[1] * (a[3] * a[8] - a[5] * a[6]) + a[2] * (a[3] * a[7] - a[4] * a[6])
        if (Math.abs(det) < 1e-9) return ColorMatrix()
        val inv = doubleArrayOf(
            (a[4] * a[8] - a[5] * a[7]) / det, (a[2] * a[7] - a[1] * a[8]) / det, (a[1] * a[5] - a[2] * a[4]) / det,
            (a[5] * a[6] - a[3] * a[8]) / det, (a[0] * a[8] - a[2] * a[6]) / det, (a[2] * a[3] - a[0] * a[5]) / det,
            (a[3] * a[7] - a[4] * a[6]) / det, (a[1] * a[6] - a[0] * a[7]) / det, (a[0] * a[4] - a[1] * a[3]) / det,
        )
        return ColorMatrix(floatArrayOf(
            inv[0].toFloat(), inv[1].toFloat(), inv[2].toFloat(), 0f, 0f,
            inv[3].toFloat(), inv[4].toFloat(), inv[5].toFloat(), 0f, 0f,
            inv[6].toFloat(), inv[7].toFloat(), inv[8].toFloat(), 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        ))
    }

    /** A copy of [source] seen through the model. */
    fun simulated(source: Bitmap): Bitmap {
        val w = source.width
        val h = source.height
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)
        simulate(pixels)
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { setPixels(pixels, 0, w, 0, 0, w, h) }
    }
}

/** A test chart to print once and compare with the screen: grey steps, primaries, skin tones. */
object TestChart {
    val greys = (0..10).map { Math.round(it * 25.5f) }
    val patches = listOf(
        Color.rgb(200, 40, 40), Color.rgb(40, 170, 60), Color.rgb(40, 70, 200), Color.rgb(0, 190, 210),
        Color.rgb(210, 40, 170), Color.rgb(240, 210, 40), Color.rgb(255, 140, 60), Color.rgb(120, 70, 190),
        Color.rgb(242, 196, 170), Color.rgb(205, 150, 115), Color.rgb(150, 100, 70), Color.rgb(90, 60, 45),
        Color.rgb(110, 160, 230), Color.rgb(170, 200, 120), Color.rgb(230, 170, 190), Color.rgb(120, 120, 120),
    )

    fun render(size: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        canvas.drawColor(Color.WHITE)
        val margin = size * 0.04f
        val inner = size - 2 * margin
        // Row 1-2: grey steps (0 % .. 100 %), each step labelled by position only.
        val stepW = inner / greys.size
        val stepH = size * 0.14f
        greys.forEachIndexed { i, v ->
            paint.color = Color.rgb(v, v, v)
            canvas.drawRect(margin + i * stepW, margin, margin + (i + 1) * stepW, margin + stepH, paint)
        }
        // Row 3-6: colour patches in a 4x4 grid.
        val gridTop = margin + stepH + size * 0.04f
        val cell = inner / 4
        val gap = size * 0.012f
        patches.forEachIndexed { i, c ->
            val col = i % 4
            val row = i / 4
            paint.color = c
            val left = margin + col * cell + gap
            val top = gridTop + row * cell * 0.75f + gap
            canvas.drawRect(left, top, left + cell - 2 * gap, top + cell * 0.75f - 2 * gap, paint)
        }
        // Bottom: a continuous gradient black -> white to see where it blocks and clips.
        val gradTop = size - margin - size * 0.10f
        val shader = android.graphics.LinearGradient(margin, 0f, size - margin, 0f, Color.BLACK, Color.WHITE, android.graphics.Shader.TileMode.CLAMP)
        val gp = Paint().apply { this.shader = shader }
        canvas.drawRect(margin, gradTop, size - margin, size - margin, gp)
        return bitmap
    }
}
