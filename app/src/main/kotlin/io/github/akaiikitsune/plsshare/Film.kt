package io.github.akaiikitsune.plsshare

import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.Paint

/**
 * How the instax film and printer render the data we send, as a small parametric model, with its
 * inverse for pre-compensating prints.
 *
 * The defaults were fitted on a real SP-3 print of [TestChart] (instax SQUARE colour film),
 * photographed next to the phone and measured relative to the paper white:
 * - inputs below about 20 % all print as the same black, inputs above about 88 % as the same white;
 * - the darkest black is far from pure black and the brightest white stays below the paper white
 *   (instax has a modest density range), so the print's own range is compressed at both ends;
 * - in between, midtones print lighter than the input (50 % grey came out at about 67 % of the
 *   paper white), a concave curve, gamma ≈ 0.6;
 * - blacks are warm (red/brown), mid greys nearly neutral with a touch of magenta;
 * - colours are clearly less saturated than on screen; blues come out lighter, not darker.
 * A camera photo of a print lifts blacks and desaturates a little (flare), so the black level and
 * the saturation are tempered. The calibration screen lets the user fit the rest by eye.
 */
/** The two instax SQUARE films. Each has its own model, calibration and test chart. */
enum class FilmType(val key: String) {
    COLOR("color"), MONO("mono");

    val monochrome: Boolean get() = this == MONO

    companion object {
        fun fromKey(key: String?) = values().firstOrNull { it.key == key } ?: COLOR
    }
}

class FilmModel(
    val type: FilmType = FilmType.COLOR,
    /** Exposure offset in stops applied to the input; 0 = as measured. */
    var exposure: Float = 0f,
    /** Midtone curve inside the usable range; < 1 lifts the midtones (as measured), > 1 darkens them. */
    var gamma: Float = 0.62f,
    /** Inputs below this level all print as the darkest black (0..0.3). */
    var blackClip: Float = 0.20f,
    /** Inputs above this level all print as the brightest white (0.7..1). */
    var whiteClip: Float = 0.88f,
    /** The darkest black the film produces, as a fraction of the paper white (0..0.4). */
    var blackLevel: Float = 0.17f,
    /** The brightest white the film produces, as a fraction of the paper white (0.7..1). */
    var whiteLevel: Float = 0.92f,
    /** How red/brown the blacks are (0 = neutral blacks, 1 = strongly warm). */
    var shadowWarmth: Float = 0.6f,
    var saturation: Float = 0.72f,
    /** Grey balance in the blue-yellow direction: negative = bluish print, positive = yellowish. */
    var warmth: Float = 0f,
    /** Grey balance in the green-magenta direction: negative = greenish, positive = magenta. */
    var tint: Float = 0.05f,
    /** Extra darkening of saturated blues (0 = none; the measured print did not show any). */
    var blueDarkening: Float = 0f,
    /**
     * Unsharp-mask amount (0..100) applied to the data sent to the printer, never to the preview:
     * instax film softens the image noticeably, so the print is sharpened in advance.
     */
    var printSharpness: Float = 35f,
    /**
     * Per-hue correction in six sectors (red, yellow, green, cyan, blue, magenta, interpolated by
     * hue angle): how many degrees the film rotates each hue, how it scales its saturation on top
     * of the global [saturation], and how it darkens (or lightens) it, in proportion to the
     * colour's saturation so that neutrals are untouched. Only measured from a photo of the chart,
     * never set by hand.
     */
    var hueShift: FloatArray = FloatArray(HUES),
    var hueGain: FloatArray = FloatArray(HUES) { 1f },
    var hueValue: FloatArray = FloatArray(HUES) { 1f },
) {
    companion object {
        private const val PREFIX = "film3."
        const val HUES = 6
        val HUE_NAMES = listOf("R", "Y", "G", "C", "B", "M")

        /** RGB (0..255) to hue (0..360), saturation and value (0..1), into [out]. */
        fun rgbToHsv(r: Int, g: Int, b: Int, out: FloatArray) {
            val max = maxOf(r, g, b)
            val min = minOf(r, g, b)
            val d = (max - min).toFloat()
            out[2] = max / 255f
            out[1] = if (max == 0) 0f else d / max
            out[0] = when {
                d == 0f -> 0f
                max == r -> 60f * (((g - b) / d) % 6f)
                max == g -> 60f * ((b - r) / d + 2f)
                else -> 60f * ((r - g) / d + 4f)
            }.let { if (it < 0f) it + 360f else it }
        }

        /** Hue, saturation, value back to a packed RGB (alpha opaque). */
        fun hsvToRgb(h: Float, s: Float, v: Float): Int {
            val c = v * s
            val hh = ((h % 360f + 360f) % 360f) / 60f
            val x = c * (1f - Math.abs(hh % 2f - 1f))
            val (r1, g1, b1) = when (hh.toInt()) {
                0 -> Triple(c, x, 0f); 1 -> Triple(x, c, 0f); 2 -> Triple(0f, c, x)
                3 -> Triple(0f, x, c); 4 -> Triple(x, 0f, c); else -> Triple(c, 0f, x)
            }
            val m = v - c
            return Color.rgb(Math.round((r1 + m) * 255).coerceIn(0, 255), Math.round((g1 + m) * 255).coerceIn(0, 255), Math.round((b1 + m) * 255).coerceIn(0, 255))
        }

        /** The value of a six-sector table at a hue, interpolated linearly between sector centres. */
        fun sectorValue(table: FloatArray, hue: Float): Float {
            val pos = ((hue % 360f + 360f) % 360f) / 60f
            val i = pos.toInt() % HUES
            val t = pos - pos.toInt()
            return table[i] * (1f - t) + table[(i + 1) % HUES] * t
        }

        /**
         * Starting point for each film. Colour: measured on a real print (see the class comment).
         * Monochrome: not measured yet; published reviews describe neutral greys, deeper blacks and
         * a somewhat softer gradation than colour film, so it starts from the colour tone curve
         * with neutral, deeper blacks.
         */
        fun defaults(type: FilmType): FilmModel = when (type) {
            FilmType.COLOR -> FilmModel(type)
            FilmType.MONO -> FilmModel(
                type, gamma = 0.68f, blackClip = 0.16f, whiteClip = 0.90f, blackLevel = 0.10f, whiteLevel = 0.93f,
                shadowWarmth = 0f, saturation = 0f, warmth = 0f, tint = 0f, blueDarkening = 0f,
            )
        }

        fun load(prefs: SharedPreferences, type: FilmType): FilmModel {
            val m = defaults(type)
            val prefix = PREFIX + type.key + "."
            fun f(key: String, default: Float) = prefs.getFloat(prefix + key, default)
            m.exposure = f("exposure", m.exposure); m.gamma = f("gamma", m.gamma)
            m.blackClip = f("blackClip", m.blackClip); m.whiteClip = f("whiteClip", m.whiteClip)
            m.blackLevel = f("blackLevel", m.blackLevel); m.whiteLevel = f("whiteLevel", m.whiteLevel)
            m.shadowWarmth = f("shadowWarmth", m.shadowWarmth); m.saturation = f("saturation", m.saturation)
            m.warmth = f("warmth", m.warmth); m.tint = f("tint", m.tint); m.blueDarkening = f("blueDarkening", m.blueDarkening)
            m.printSharpness = f("printSharpness", m.printSharpness)
            for (i in 0 until HUES) { m.hueShift[i] = f("hueShift$i", 0f); m.hueGain[i] = f("hueGain$i", 1f); m.hueValue[i] = f("hueValue$i", 1f) }
            return m
        }
    }

    fun save(prefs: SharedPreferences) {
        val prefix = PREFIX + type.key + "."
        val e = prefs.edit()
            .putFloat(prefix + "exposure", exposure).putFloat(prefix + "gamma", gamma)
            .putFloat(prefix + "blackClip", blackClip).putFloat(prefix + "whiteClip", whiteClip)
            .putFloat(prefix + "blackLevel", blackLevel).putFloat(prefix + "whiteLevel", whiteLevel)
            .putFloat(prefix + "shadowWarmth", shadowWarmth).putFloat(prefix + "saturation", saturation)
            .putFloat(prefix + "warmth", warmth).putFloat(prefix + "tint", tint).putFloat(prefix + "blueDarkening", blueDarkening)
            .putFloat(prefix + "printSharpness", printSharpness)
        for (i in 0 until HUES) e.putFloat(prefix + "hueShift$i", hueShift[i]).putFloat(prefix + "hueGain$i", hueGain[i]).putFloat(prefix + "hueValue$i", hueValue[i])
        e.apply()
    }

    fun copy() = FilmModel(type, exposure, gamma, blackClip, whiteClip, blackLevel, whiteLevel, shadowWarmth, saturation, warmth, tint, blueDarkening, printSharpness, hueShift.clone(), hueGain.clone(), hueValue.clone())

    /** Whether any per-hue correction is set. */
    val hueCorrected: Boolean get() = hueShift.any { it != 0f } || hueGain.any { it != 1f } || hueValue.any { it != 1f }

    fun resetHues() { hueShift.fill(0f); hueGain.fill(1f); hueValue.fill(1f) }

    /** The brightness factor of a colour of saturation [s] at hue [h]: 1 for neutrals, the sector's value gain when fully saturated. */
    private fun valueFactor(h: Float, s: Float): Float = 1f - (1f - sectorValue(hueValue, h)) * s

    /**
     * The input hue that the shift table maps onto [hOut]. The shifted hue is piecewise linear in
     * the input hue, so each sector is solved exactly; when several sectors answer (a steep table),
     * the one nearest to [hOut] wins.
     */
    fun inverseHue(hOut: Float): Float {
        var best = hOut
        var bestDist = Float.MAX_VALUE
        for (i in 0 until HUES) {
            val a = hueShift[i]
            val b = hueShift[(i + 1) % HUES]
            val slope = (b - a) / 60f
            if (1f + slope <= 0.05f) continue
            for (wrap in -1..1) {
                val target = hOut + 360f * wrap
                val h = (target - a + slope * 60f * i) / (1f + slope)
                if (h < 60f * i - 0.01f || h >= 60f * (i + 1) + 0.01f) continue
                var d = Math.abs(h - hOut) % 360f
                if (d > 180f) d = 360f - d
                if (d < bestDist) { bestDist = d; best = h }
            }
        }
        return best
    }

    /**
     * Per-hue correction on the input colours (before the global colour matrix): rotate each hue by
     * the sector's shift, scale its saturation by the sector's gain, and scale its brightness by the
     * sector's value gain in proportion to the saturation. The inverse solves the hue exactly and
     * divides the saturation and brightness back; what the film cannot reach is clamped.
     */
    fun hueCorrect(pixels: IntArray, inverse: Boolean) {
        if (type.monochrome || !hueCorrected) return
        val hsv = FloatArray(3)
        for (i in pixels.indices) {
            val p = pixels[i]
            rgbToHsv((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF, hsv)
            if (hsv[1] < 0.02f) continue
            val h: Float
            val s: Float
            val v: Float
            if (!inverse) {
                h = hsv[0] + sectorValue(hueShift, hsv[0])
                s = hsv[1] * sectorValue(hueGain, hsv[0])
                v = hsv[2] * valueFactor(hsv[0], hsv[1])
            } else {
                h = inverseHue(hsv[0])
                s = hsv[1] / sectorValue(hueGain, h).coerceAtLeast(0.2f)
                v = hsv[2] / valueFactor(h, s.coerceIn(0f, 1f)).coerceAtLeast(0.2f)
            }
            pixels[i] = (p and 0xFF000000.toInt()) or (hsvToRgb(h, s.coerceIn(0f, 1f), v.coerceIn(0f, 1f)) and 0xFFFFFF)
        }
    }

    /** The colour part of the model: saturation (none at all on monochrome film) and grey balance. */
    fun colorMatrix(): ColorMatrix {
        val m = ColorMatrix()
        m.setSaturation(if (type.monochrome) 0f else saturation)
        m.postConcat(ColorMatrix(floatArrayOf(
            1f + 0.18f * warmth + 0.05f * tint, 0f, 0f, 0f, 0f,
            0f, 1f - 0.12f * tint, 0f, 0f, 0f,
            0f, 0f, 1f - 0.18f * warmth + 0.05f * tint, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        )))
        return m
    }

    /** The inverse of the colour part. Monochrome film discards colour, so only the grey balance is inverted. */
    fun inverseColorMatrix(): ColorMatrix = if (type.monochrome) invert(greyBalanceMatrix()) else invert(colorMatrix())

    private fun greyBalanceMatrix() = ColorMatrix(floatArrayOf(
        1f + 0.18f * warmth + 0.05f * tint, 0f, 0f, 0f, 0f,
        0f, 1f - 0.12f * tint, 0f, 0f, 0f,
        0f, 0f, 1f - 0.18f * warmth + 0.05f * tint, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    ))

    /** The black level of each channel: warm blacks have more red and less blue. */
    private fun blackLevels(): FloatArray = floatArrayOf(
        (blackLevel * (1f + 0.35f * shadowWarmth)).coerceIn(0f, 0.6f),
        (blackLevel * (1f - 0.05f * shadowWarmth)).coerceIn(0f, 0.6f),
        (blackLevel * (1f - 0.25f * shadowWarmth)).coerceIn(0f, 0.6f),
    )

    /**
     * The tone curve of one channel: exposure, input clipping to the usable range, midtone gamma,
     * then the output compressed between the channel's black level and the white level.
     */
    fun toneCurve(channelBlack: Float): IntArray {
        val lut = IntArray(256)
        val gain = Math.pow(2.0, exposure.toDouble())
        val range = (whiteClip - blackClip).coerceAtLeast(0.05f)
        for (i in 0..255) {
            var v = (i / 255.0)
            v = Math.pow(Math.pow(v, 2.2) * gain, 1 / 2.2)
            var u = ((v - blackClip) / range).coerceIn(0.0, 1.0)
            u = Math.pow(u, gamma.toDouble().coerceAtLeast(0.1))
            val out = channelBlack + u * (whiteLevel - channelBlack)
            lut[i] = Math.round(out.coerceIn(0.0, 1.0) * 255).toInt()
        }
        return lut
    }

    fun toneCurves(): Array<IntArray> {
        val blacks = blackLevels()
        return arrayOf(toneCurve(blacks[0]), toneCurve(blacks[1]), toneCurve(blacks[2]))
    }

    /**
     * The inverse of a tone curve: for each wanted print level, the input level that produces it.
     * Levels the film cannot reach map to the nearest reachable input (black or white).
     */
    fun inverseToneCurve(forward: IntArray): IntArray {
        val inverse = IntArray(256)
        var j = 0
        for (target in 0..255) {
            while (j < 255 && forward[j] < target) j++
            inverse[target] = j
        }
        return inverse
    }

    fun inverseToneCurves(): Array<IntArray> = toneCurves().map { inverseToneCurve(it) }.toTypedArray()

    /** Apply the model to pixels (display simulation): per-hue correction, colours, blue darkening, then tones. */
    fun simulate(pixels: IntArray) {
        hueCorrect(pixels, inverse = false)
        applyMatrix(pixels, colorMatrix())
        darkenBlues(pixels, inverse = false)
        val c = toneCurves()
        Processing.applyCurves(pixels, c[0], c[1], c[2])
    }

    /** Apply the inverse model to pixels (print compensation), in reverse order. */
    fun compensate(pixels: IntArray) {
        val c = inverseToneCurves()
        Processing.applyCurves(pixels, c[0], c[1], c[2])
        darkenBlues(pixels, inverse = true)
        applyMatrix(pixels, inverseColorMatrix())
        hueCorrect(pixels, inverse = true)
    }

    /**
     * Optional darkening of saturated blues, hue preserved: each pixel is scaled by 1 − a·blueness,
     * blueness = max(0, B − (R+G)/2) / 255, a = 0.6 · [blueDarkening]. The inverse solves
     * f² − f + a·b_out = 0; blues darker than the film can produce get the strongest compensation.
     */
    private fun darkenBlues(pixels: IntArray, inverse: Boolean) {
        val a = if (type.monochrome) 0f else blueDarkening * 0.6f
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
            pixels[i] = (p and 0xFF000000.toInt()) or
                ((r * scale).toInt().coerceIn(0, 255) shl 16) or ((g * scale).toInt().coerceIn(0, 255) shl 8) or (b * scale).toInt().coerceIn(0, 255)
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

/** Test charts to print once and compare with the screen. */
object TestChart {
    fun render(size: Int, type: FilmType): Bitmap = if (type.monochrome) renderMono(size) else render(size)

    /** Monochrome chart: coarse and fine grey scales, a few luminance patches, a continuous gradient. */
    fun renderMono(size: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        canvas.drawColor(Color.WHITE)
        val margin = size * 0.04f
        val inner = size - 2 * margin
        // 11 coarse steps.
        val stepW = inner / greys.size
        val stepH = size * 0.14f
        greys.forEachIndexed { i, v ->
            paint.color = Color.rgb(v, v, v)
            canvas.drawRect(margin + i * stepW, margin, margin + (i + 1) * stepW, margin + stepH, paint)
        }
        // 21 fine steps, to see where the shadows and highlights merge.
        val fineTop = margin + stepH + size * 0.03f
        val fineW = inner / 21
        for (i in 0..20) {
            val v = Math.round(i * 255f / 20f)
            paint.color = Color.rgb(v, v, v)
            canvas.drawRect(margin + i * fineW, fineTop, margin + (i + 1) * fineW, fineTop + stepH * 0.6f, paint)
        }
        // Shadow and highlight detail blocks: thin dark lines on near-black, light lines on near-white.
        val detailTop = fineTop + stepH * 0.6f + size * 0.04f
        val blockH = size * 0.16f
        val blockW = (inner - size * 0.02f) / 2
        paint.color = Color.rgb(20, 20, 20)
        canvas.drawRect(margin, detailTop, margin + blockW, detailTop + blockH, paint)
        paint.color = Color.rgb(235, 235, 235)
        canvas.drawRect(margin + blockW + size * 0.02f, detailTop, size - margin, detailTop + blockH, paint)
        paint.strokeWidth = size * 0.006f
        for (i in 1..6) {
            val y = detailTop + blockH * i / 7
            paint.color = Color.rgb(20 + i * 12, 20 + i * 12, 20 + i * 12)
            canvas.drawLine(margin + size * 0.02f, y, margin + blockW - size * 0.02f, y, paint)
            paint.color = Color.rgb(235 - i * 12, 235 - i * 12, 235 - i * 12)
            canvas.drawLine(margin + blockW + size * 0.04f, y, size - margin - size * 0.02f, y, paint)
        }
        // Gradient.
        val gradTop = size - margin - size * 0.10f
        val shader = android.graphics.LinearGradient(margin, 0f, size - margin, 0f, Color.BLACK, Color.WHITE, android.graphics.Shader.TileMode.CLAMP)
        canvas.drawRect(margin, gradTop, size - margin, size - margin, Paint().apply { this.shader = shader })
        return bitmap
    }

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
        val stepW = inner / greys.size
        val stepH = size * 0.14f
        greys.forEachIndexed { i, v ->
            paint.color = Color.rgb(v, v, v)
            canvas.drawRect(margin + i * stepW, margin, margin + (i + 1) * stepW, margin + stepH, paint)
        }
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
        val gradTop = size - margin - size * 0.10f
        val shader = android.graphics.LinearGradient(margin, 0f, size - margin, 0f, Color.BLACK, Color.WHITE, android.graphics.Shader.TileMode.CLAMP)
        val gp = Paint().apply { this.shader = shader }
        canvas.drawRect(margin, gradTop, size - margin, size - margin, gp)
        return bitmap
    }
}
