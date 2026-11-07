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
    /**
     * Colour crosstalk of the film, a 3×3 matrix (row-major: the output red is the first row times
     * the input RGB) applied after the global saturation and grey balance, before the tone curves.
     * Each row sums to one, so greys pass through untouched. Only measured from the chart's
     * patches, never set by hand; the identity until then.
     */
    var crosstalk: FloatArray = IDENTITY.clone(),
) {
    companion object {
        private const val PREFIX = "film3."
        const val HUES = 6
        val HUE_NAMES = listOf("R", "Y", "G", "C", "B", "M")
        val IDENTITY = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        /** The most two neighbouring hue sectors' shifts may differ, in degrees (see [constrainHueShifts]). */
        const val MAX_HUE_STEP = 36f

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

        /** Float RGB (0..255) to hue (0..360), saturation and value (0..1), into [out]. */
        fun rgbToHsv(r: Float, g: Float, b: Float, out: FloatArray) {
            val max = maxOf(r, g, b)
            val min = minOf(r, g, b)
            val d = max - min
            out[2] = max / 255f
            out[1] = if (max <= 0f) 0f else d / max
            out[0] = when {
                d <= 0f -> 0f
                max == r -> 60f * (((g - b) / d) % 6f)
                max == g -> 60f * ((b - r) / d + 2f)
                else -> 60f * ((r - g) / d + 4f)
            }.let { if (it < 0f) it + 360f else it }
        }

        /** Hue, saturation, value to float RGB (0..255), into [out]. */
        fun hsvToRgb(h: Float, s: Float, v: Float, out: FloatArray) {
            val c = v * s
            val hh = ((h % 360f + 360f) % 360f) / 60f
            val x = c * (1f - Math.abs(hh % 2f - 1f))
            val (r1, g1, b1) = when (hh.toInt()) {
                0 -> Triple(c, x, 0f); 1 -> Triple(x, c, 0f); 2 -> Triple(0f, c, x)
                3 -> Triple(0f, x, c); 4 -> Triple(x, 0f, c); else -> Triple(c, 0f, x)
            }
            val m = v - c
            out[0] = ((r1 + m) * 255f).coerceIn(0f, 255f); out[1] = ((g1 + m) * 255f).coerceIn(0f, 255f); out[2] = ((b1 + m) * 255f).coerceIn(0f, 255f)
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

        fun fromJson(o: org.json.JSONObject, type: FilmType): FilmModel {
            val m = defaults(type)
            fun f(key: String, default: Float) = o.optDouble(key, default.toDouble()).toFloat()
            m.exposure = f("exposure", m.exposure); m.gamma = f("gamma", m.gamma)
            m.blackClip = f("blackClip", m.blackClip); m.whiteClip = f("whiteClip", m.whiteClip)
            m.blackLevel = f("blackLevel", m.blackLevel); m.whiteLevel = f("whiteLevel", m.whiteLevel)
            m.shadowWarmth = f("shadowWarmth", m.shadowWarmth); m.saturation = f("saturation", m.saturation)
            m.warmth = f("warmth", m.warmth); m.tint = f("tint", m.tint); m.blueDarkening = f("blueDarkening", m.blueDarkening)
            m.printSharpness = f("printSharpness", m.printSharpness)
            for ((key, table) in listOf("hueShift" to m.hueShift, "hueGain" to m.hueGain, "hueValue" to m.hueValue)) {
                val a = o.optJSONArray(key) ?: continue
                for (i in 0 until minOf(HUES, a.length())) table[i] = a.optDouble(i, table[i].toDouble()).toFloat()
            }
            o.optJSONArray("crosstalk")?.let { a -> if (a.length() == 9) for (i in 0 until 9) m.crosstalk[i] = a.optDouble(i, m.crosstalk[i].toDouble()).toFloat() }
            m.constrainHueShifts()
            return m
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
            for (i in 0 until 9) m.crosstalk[i] = f("crosstalk$i", IDENTITY[i])
            m.constrainHueShifts()
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
        for (i in 0 until 9) e.putFloat(prefix + "crosstalk$i", crosstalk[i])
        e.apply()
    }

    fun toJson(): org.json.JSONObject = org.json.JSONObject()
        .put("exposure", exposure.toDouble()).put("gamma", gamma.toDouble()).put("blackClip", blackClip.toDouble()).put("whiteClip", whiteClip.toDouble())
        .put("blackLevel", blackLevel.toDouble()).put("whiteLevel", whiteLevel.toDouble()).put("shadowWarmth", shadowWarmth.toDouble())
        .put("saturation", saturation.toDouble()).put("warmth", warmth.toDouble()).put("tint", tint.toDouble()).put("blueDarkening", blueDarkening.toDouble())
        .put("printSharpness", printSharpness.toDouble())
        .put("hueShift", org.json.JSONArray(hueShift.map { it.toDouble() })).put("hueGain", org.json.JSONArray(hueGain.map { it.toDouble() })).put("hueValue", org.json.JSONArray(hueValue.map { it.toDouble() }))
        .put("crosstalk", org.json.JSONArray(crosstalk.map { it.toDouble() }))

    fun copy() = FilmModel(type, exposure, gamma, blackClip, whiteClip, blackLevel, whiteLevel, shadowWarmth, saturation, warmth, tint, blueDarkening, printSharpness, hueShift.clone(), hueGain.clone(), hueValue.clone(), crosstalk.clone())

    /** Whether any per-hue correction is set. */
    val hueCorrected: Boolean get() = hueShift.any { it != 0f } || hueGain.any { it != 1f } || hueValue.any { it != 1f }

    /** Whether a crosstalk matrix was measured. */
    val crosstalkSet: Boolean get() = (0 until 9).any { Math.abs(crosstalk[it] - IDENTITY[it]) > 1e-4f }

    /** Whether anything measured on the colour patches is set (hue tables or crosstalk). */
    val colourMeasured: Boolean get() = hueCorrected || crosstalkSet

    /** Clear everything measured on the colour patches: the hue tables and the crosstalk matrix. */
    fun resetHues() { hueShift.fill(0f); hueGain.fill(1f); hueValue.fill(1f); IDENTITY.copyInto(crosstalk) }

    fun resetCrosstalk() { IDENTITY.copyInto(crosstalk) }

    fun resetHueTables() { hueShift.fill(0f); hueGain.fill(1f); hueValue.fill(1f) }

    /**
     * Keeps the hue mapping invertible: the shifted hue is piecewise linear with slope
     * 1 + (next − this) / 60 across each sector, so two neighbouring sectors may not differ by
     * more than 36° (slope 0.4 at least), else the mapping folds back and a band of output hues
     * becomes unreachable, a hole in the film's gamut that the compensation then falls into.
     * Differences beyond that are split between the two sectors, until none remains.
     */
    fun constrainHueShifts() {
        repeat(20) {
            var changed = false
            for (i in 0 until HUES) {
                val j = (i + 1) % HUES
                val d = hueShift[j] - hueShift[i]
                if (Math.abs(d) > MAX_HUE_STEP) {
                    val excess = (Math.abs(d) - MAX_HUE_STEP) / 2 * Math.signum(d)
                    hueShift[i] += excess; hueShift[j] -= excess
                    changed = true
                }
            }
            if (!changed) return
        }
    }

    /**
     * The brightness factor of a colour of saturation [s] and value [v] at hue [h]: 1 for
     * neutrals, the sector's value gain when fully saturated, and nothing in the shadows (below
     * 12 % of value, with a ramp to 35 %), where the film's black plateau swallows the difference
     * anyway and a brightness correction would only swing the darks.
     */
    private fun valueFactor(h: Float, s: Float, v: Float): Float = 1f - (1f - sectorValue(hueValue, h)) * s * hueWeight(s) * darkWeight(v)

    private fun darkWeight(v: Float): Float {
        val t = ((v - 0.12f) / 0.23f).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /**
     * How much of the hue and saturation correction applies at saturation [s]: nothing on neutrals
     * and near-neutrals (a blown highlight with a trace of colour must not turn lavender), all of
     * it from 20 % saturation on, with a smooth ramp in between.
     */
    private fun hueWeight(s: Float): Float {
        val t = ((s - 0.03f) / 0.17f).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun gainAt(h: Float, w: Float): Float = 1f + (sectorValue(hueGain, h) - 1f) * w

    /**
     * The input hue that the shift table, scaled by [w], maps onto [hOut]. The shifted hue is
     * piecewise linear in the input hue, so each sector is solved exactly; when several sectors
     * answer (a steep table), the one nearest to [hOut] wins.
     */
    fun inverseHue(hOut: Float, w: Float = 1f): Float {
        var best = hOut
        var bestDist = Float.MAX_VALUE
        for (i in 0 until HUES) {
            val a = hueShift[i] * w
            val b = hueShift[(i + 1) % HUES] * w
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
     * sector's value gain in proportion to the saturation, the whole fading in with saturation.
     * The inverse solves the hue exactly for the weight of the input saturation (found by a few
     * fixed-point steps) and divides the saturation and brightness back; what the film cannot
     * reach is clamped.
     */
    fun hueCorrect(pixels: IntArray, inverse: Boolean) {
        if (type.monochrome || !hueCorrected) return
        val hsv = FloatArray(3)
        for (i in pixels.indices) {
            val p = pixels[i]
            rgbToHsv((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF, hsv)
            if (hsv[1] < 0.03f) continue
            val h: Float
            val s: Float
            val v: Float
            if (!inverse) {
                val w = hueWeight(hsv[1])
                h = hsv[0] + sectorValue(hueShift, hsv[0]) * w
                s = hsv[1] * gainAt(hsv[0], w)
                v = hsv[2] * valueFactor(hsv[0], hsv[1], hsv[2])
            } else {
                var sin = hsv[1]
                var hin = hsv[0]
                repeat(5) {
                    val w = hueWeight(sin)
                    hin = inverseHue(hsv[0], w)
                    sin = (hsv[1] / gainAt(hin, w).coerceAtLeast(0.2f)).coerceIn(0f, 1f)
                }
                h = hin
                s = sin
                v = hsv[2] / valueFactor(h, s, hsv[2]).coerceAtLeast(0.2f)
            }
            pixels[i] = (p and 0xFF000000.toInt()) or (hsvToRgb(h, s.coerceIn(0f, 1f), v.coerceIn(0f, 1f)) and 0xFFFFFF)
        }
    }

    /** The global colour part of the model: saturation (none at all on monochrome film) and grey balance. */
    fun baseColorMatrix(): ColorMatrix {
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

    /** The colour part of the model: the global part, then the film's crosstalk (colour film only). */
    fun colorMatrix(): ColorMatrix {
        val m = baseColorMatrix()
        if (!type.monochrome && crosstalkSet) {
            val c = crosstalk
            m.postConcat(ColorMatrix(floatArrayOf(
                c[0], c[1], c[2], 0f, 0f,
                c[3], c[4], c[5], 0f, 0f,
                c[6], c[7], c[8], 0f, 0f,
                0f, 0f, 0f, 1f, 0f,
            )))
        }
        return m
    }

    /** The fractional input level that prints as [target] (0..255) on channel [channel]: the inverse tone curve, for fitting. */
    fun inverseTone(channel: Int, target: Float): Float = inverseToneFractional(toneCurves()[channel], target)

    /** The film's white on a channel, as a level (0..255): targets above it are out of reach. */
    fun toneTop(channel: Int): Int = toneCurves()[channel][255]

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
        // Levels brighter than the film's white all map to the input where the white plateau
        // starts, never to 255: a clipped channel must not jump away from its neighbours.
        val top = forward[255]
        var j = 0
        for (target in 0..255) {
            val wanted = minOf(target, top)
            while (j < 255 && forward[j] < wanted) j++
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

    /** Apply the inverse model to pixels (print compensation): the fractional pipeline, rounded. */
    fun compensate(pixels: IntArray) {
        val ctx = CompensationContext()
        val rgb = FloatArray(3)
        for (i in pixels.indices) {
            val p = pixels[i]
            rgb[0] = ((p shr 16) and 0xFF).toFloat(); rgb[1] = ((p shr 8) and 0xFF).toFloat(); rgb[2] = (p and 0xFF).toFloat()
            compensateFractional(rgb, ctx)
            pixels[i] = (p and 0xFF000000.toInt()) or (Math.round(rgb[0]).coerceIn(0, 255) shl 16) or (Math.round(rgb[1]).coerceIn(0, 255) shl 8) or Math.round(rgb[2]).coerceIn(0, 255)
        }
    }

    /** What [compensateFractional] needs and should not recompute per pixel. */
    inner class CompensationContext {
        val forward = toneCurves()
        /** The darkest and brightest greys the film shows, as luma, a little inside. */
        val greyLow = 0.299f * forward[0][0] + 0.587f * forward[1][0] + 0.114f * forward[2][0] + 4f
        val greyHigh = 0.299f * forward[0][255] + 0.587f * forward[1][255] + 0.114f * forward[2][255] - 4f
        val matrix = colorMatrix().array
        val inverseMatrix = inverseColorMatrix().array
        val hsv = FloatArray(3)
        val hsv2 = FloatArray(3)
        val target = FloatArray(3)
        val probe = FloatArray(3)
        val best = FloatArray(3)
        val trial = FloatArray(3)
        val residual = FloatArray(3)
        val f0 = FloatArray(3)
        val jacobian = FloatArray(9)
        val delta = FloatArray(3)
        val solver = DoubleArray(12)
        val reached = FloatArray(3)
        val direct = FloatArray(3)
        val second = FloatArray(3)
        val grey = FloatArray(3)
        val goal = FloatArray(3)
        val clampedGoal = FloatArray(3)
        /** The forward model's error on [x] against [goal], the goal clamped to what the film can show. */
        fun lastError(x: FloatArray, goal: FloatArray): Float {
            x.copyInto(probe); simulateFloat(probe, this)
            var worst = 0f
            for (k in 0 until 3) worst = maxOf(worst, Math.abs(probe[k] - goal[k].coerceIn(forward[k][0].toFloat(), forward[k][255].toFloat())))
            return worst
        }
    }

    /**
     * The inverse model on one colour, in place, with fractional levels: for each wanted print
     * level the input is interpolated between the two data levels that bracket it on the film's
     * curve, so that dithering between those two levels averages to the wanted level on paper.
     * Levels the film cannot reach map to the nearest end of its range.
     */
    fun compensateFractional(rgb: FloatArray, ctx: CompensationContext) {
        rgb.copyInto(ctx.target)
        // The analytic inverse starts from what the film can show: a channel below its black
        // would otherwise start at level 0, in the wrong basin for the Newton steps.
        for (k in 0 until 3) rgb[k] = rgb[k].coerceIn(ctx.forward[k][0].toFloat(), ctx.forward[k][255].toFloat())
        analyticInverse(rgb, ctx)
        refine(rgb, ctx)
    }

    /** The stage-by-stage inverse of the model on one colour, in place: exact away from the plateaus and the gamut's edge, a starting point elsewhere. */
    private fun analyticInverse(rgb: FloatArray, ctx: CompensationContext) {
        for (c in 0 until 3) rgb[c] = inverseToneFractional(ctx.forward[c], rgb[c])
        // Blue darkening, inverted.
        val a = if (type.monochrome) 0f else blueDarkening * 0.6f
        if (a > 0f) {
            val blueness = (rgb[2] - (rgb[0] + rgb[1]) / 2f) / 255f
            if (blueness > 0f) {
                val disc = 1f - 4f * a * blueness
                val factor = if (disc <= 0f) 0.5f else (1f + Math.sqrt(disc.toDouble()).toFloat()) / 2f
                for (c in 0 until 3) rgb[c] = (rgb[c] / factor).coerceIn(0f, 255f)
            }
        }
        // Colour matrix, inverted.
        val m = ctx.inverseMatrix
        val r = rgb[0]; val g = rgb[1]; val b = rgb[2]
        rgb[0] = (m[0] * r + m[1] * g + m[2] * b + m[4]).coerceIn(0f, 255f)
        rgb[1] = (m[5] * r + m[6] * g + m[7] * b + m[9]).coerceIn(0f, 255f)
        rgb[2] = (m[10] * r + m[11] * g + m[12] * b + m[14]).coerceIn(0f, 255f)
        // Per-hue correction, inverted.
        if (!type.monochrome && hueCorrected) {
            val hsv = ctx.hsv
            rgbToHsv(rgb[0], rgb[1], rgb[2], hsv)
            if (hsv[1] >= 0.03f) {
                var sin = hsv[1]
                var hin = hsv[0]
                repeat(5) {
                    val w = hueWeight(sin)
                    hin = inverseHue(hsv[0], w)
                    sin = (hsv[1] / gainAt(hin, w).coerceAtLeast(0.2f)).coerceIn(0f, 1f)
                }
                val v = (hsv[2] / valueFactor(hin, sin, hsv[2]).coerceAtLeast(0.2f)).coerceIn(0f, 1f)
                hsvToRgb(hin, sin, v, rgb)
            }
        }
    }

    /**
     * Makes a compensated colour the true inverse of the forward model, in place. Newton steps on
     * [simulateFloat] from the analytic inverse (or from the target itself, when the forward
     * model already maps that closer: the analytic inverse can land on a wrong branch of a steep
     * hue table). When the film cannot reach the target at all, the colour is pulled towards the
     * grey of the same luma until it can: the largest share of its colour that prints, found by
     * bisection, so that neighbouring colours get neighbouring compensations (a "nearest"
     * solution found by chance would tear the table at the film's gamut boundary).
     */
    private fun refine(rgb: FloatArray, ctx: CompensationContext) {
        val target = ctx.target
        val directErr = newton(rgb, target, ctx)
        if (directErr < 3f) return
        val reached = ctx.reached
        // The grey of the target's luma, kept within the greys the film can show (a dark blue
        // projects towards the darkest reachable grey, not towards a black it cannot print).
        val l = (0.299f * target[0] + 0.587f * target[1] + 0.114f * target[2]).coerceIn(ctx.greyLow, ctx.greyHigh)
        val grey = ctx.grey; grey[0] = l; grey[1] = l; grey[2] = l
        val goal = ctx.goal
        // The grey itself, from its own analytic inverse (the tone curves' plateaus make a start
        // on the plateau hopeless: no slope to follow).
        val direct = ctx.direct; rgb.copyInto(direct)
        for (k in 0 until 3) rgb[k] = grey[k].coerceIn(ctx.forward[k][0].toFloat(), ctx.forward[k][255].toFloat())
        analyticInverse(rgb, ctx)
        val greyErr = newton(rgb, grey, ctx)
        if (greyErr >= 3f) {
            // Even the grey is out of reach (too dark or too bright): the closer attempt wins.
            if (ctx.lastError(direct, target) <= ctx.lastError(rgb, target)) direct.copyInto(rgb)
            return
        }
        rgb.copyInto(reached) // the solution for the share found so far (0: grey)
        var lo = 0f; var hi = 1f
        repeat(7) {
            val mid = (lo + hi) / 2
            for (k in 0 until 3) goal[k] = grey[k] + mid * (target[k] - grey[k])
            // Two starts: the last share reached, then the goal's own analytic inverse.
            reached.copyInto(rgb)
            var err = newton(rgb, goal, ctx)
            if (err >= 3f) {
                val second = ctx.second
                for (k in 0 until 3) second[k] = goal[k].coerceIn(ctx.forward[k][0].toFloat(), ctx.forward[k][255].toFloat())
                analyticInverse(second, ctx)
                val err2 = newton(second, goal, ctx)
                if (err2 < err) { err = err2; second.copyInto(rgb) }
            }
            if (err >= 3f) {
                val third = ctx.second; direct.copyInto(third)
                val err3 = newton(third, goal, ctx)
                if (err3 < err) { err = err3; third.copyInto(rgb) }
            }
            if (err < 3f) { lo = mid; rgb.copyInto(reached) } else hi = mid
        }
        reached.copyInto(rgb)
    }

    /**
     * Newton steps from [c] (in/out) towards [goal] on the forward model: the 3×3 Jacobian by
     * finite differences (one probe per channel, looking further when a channel sits on a
     * plateau of its tone curve), the step solved from it (each channel alone when the system is
     * singular), halved while it does not help. Returns the error that remains (the largest
     * channel difference).
     */
    private fun newton(c: FloatArray, wanted: FloatArray, ctx: CompensationContext): Float {
        val f = ctx.probe
        // What the film can be asked for: a channel below its black or above its white is asked
        // for that limit (the difference is unavoidable and must not count as a failure).
        val goal = ctx.clampedGoal
        for (k in 0 until 3) goal[k] = wanted[k].coerceIn(ctx.forward[k][0].toFloat(), ctx.forward[k][255].toFloat())
        fun error(x: FloatArray): Float {
            x.copyInto(f); simulateFloat(f, ctx)
            return maxOf(Math.abs(f[0] - goal[0]), Math.abs(f[1] - goal[1]), Math.abs(f[2] - goal[2]))
        }
        var bestErr = error(c)
        val fromGoal = error(goal)
        if (fromGoal < bestErr) { goal.copyInto(c); bestErr = fromGoal }
        val best = ctx.best; c.copyInto(best)
        if (bestErr < 0.75f) return bestErr
        val trial = ctx.trial; val e = ctx.residual; val f0 = ctx.f0; val j = ctx.jacobian; val delta = ctx.delta
        repeat(20) {
            c.copyInto(f); simulateFloat(f, ctx)
            for (k in 0 until 3) { e[k] = goal[k] - f[k]; f0[k] = f[k] }
            for (k in 0 until 3) {
                c.copyInto(trial)
                var d = if (c[k] <= 251f) 4f else -4f
                trial[k] = c[k] + d
                trial.copyInto(f); simulateFloat(f, ctx)
                var own = (f[k] - f0[k]) / d
                if (Math.abs(own) < 0.05f) {
                    // On a plateau: look further, in the direction the residual wants.
                    val far = if (e[k] >= 0f) 24f else -24f
                    trial[k] = (c[k] + far).coerceIn(0f, 255f)
                    val dd = trial[k] - c[k]
                    if (Math.abs(dd) > 1f) { trial.copyInto(f); simulateFloat(f, ctx); d = dd; own = (f[k] - f0[k]) / d }
                }
                for (i in 0 until 3) j[i * 3 + k] = (f[i] - f0[i]) / d
                j[k * 3 + k] = own
            }
            // Solve J · delta = e; then, if that step helps nowhere along its halvings, each
            // channel on its own slope (the hue tables make the model kink at sector boundaries,
            // where the full Jacobian can point the wrong way).
            val full = solve3(j, e, delta, ctx.solver)
            var improved = false
            for (attempt in 0 until 2) {
                if (attempt == 1 || !full) for (k in 0 until 3) delta[k] = if (Math.abs(j[k * 3 + k]) < 0.05f) 0f else e[k] / j[k * 3 + k]
                var step = 1f
                for (halving in 0 until 7) {
                    for (k in 0 until 3) trial[k] = (c[k] + (step * delta[k]).coerceIn(-128f, 128f)).coerceIn(0f, 255f)
                    val err = error(trial)
                    if (err < bestErr - 0.01f) { bestErr = err; trial.copyInto(best); trial.copyInto(c); improved = true; break }
                    step *= 0.5f
                }
                if (improved || !full) break
            }
            if (!improved || bestErr < 0.75f) { best.copyInto(c); return bestErr }
        }
        best.copyInto(c)
        return bestErr
    }

    /** Solves a 3×3 system a·x = b into [x]; false when singular. */
    private fun solve3(a: FloatArray, b: FloatArray, x: FloatArray, m: DoubleArray): Boolean {
        for (r in 0 until 3) { m[r * 4] = a[r * 3].toDouble(); m[r * 4 + 1] = a[r * 3 + 1].toDouble(); m[r * 4 + 2] = a[r * 3 + 2].toDouble(); m[r * 4 + 3] = b[r].toDouble() }
        for (col in 0 until 3) {
            var pivot = col
            for (r in col + 1 until 3) if (Math.abs(m[r * 4 + col]) > Math.abs(m[pivot * 4 + col])) pivot = r
            if (pivot != col) for (k in 0 until 4) { val t = m[col * 4 + k]; m[col * 4 + k] = m[pivot * 4 + k]; m[pivot * 4 + k] = t }
            val p = m[col * 4 + col]
            if (Math.abs(p) < 1e-3) return false
            for (k in col until 4) m[col * 4 + k] /= p
            for (r in 0 until 3) if (r != col) { val factor = m[r * 4 + col]; if (factor != 0.0) for (k in col until 4) m[r * 4 + k] -= factor * m[col * 4 + k] }
        }
        for (r in 0 until 3) { if (m[r * 4 + 3].isNaN()) return false; x[r] = m[r * 4 + 3].toFloat() }
        return true
    }

    /**
     * The forward model on one colour in fractional levels, in place: the same stages as
     * [simulate] (per-hue correction, colour matrix, blue darkening, tone curves interpolated
     * between integer levels), used to check and refine the compensation.
     */
    fun simulateFloat(rgb: FloatArray, ctx: CompensationContext) {
        if (!type.monochrome && hueCorrected) {
            val hsv = ctx.hsv2
            rgbToHsv(rgb[0], rgb[1], rgb[2], hsv)
            if (hsv[1] >= 0.03f) {
                val w = hueWeight(hsv[1])
                val h = hsv[0] + sectorValue(hueShift, hsv[0]) * w
                val sat = (hsv[1] * gainAt(hsv[0], w)).coerceIn(0f, 1f)
                val v = (hsv[2] * valueFactor(hsv[0], hsv[1], hsv[2])).coerceIn(0f, 1f)
                hsvToRgb(h, sat, v, rgb)
            }
        }
        val m = ctx.matrix
        val r = rgb[0]; val g = rgb[1]; val b = rgb[2]
        rgb[0] = (m[0] * r + m[1] * g + m[2] * b + m[4]).coerceIn(0f, 255f)
        rgb[1] = (m[5] * r + m[6] * g + m[7] * b + m[9]).coerceIn(0f, 255f)
        rgb[2] = (m[10] * r + m[11] * g + m[12] * b + m[14]).coerceIn(0f, 255f)
        val a = if (type.monochrome) 0f else blueDarkening * 0.6f
        if (a > 0f) {
            val blueness = (rgb[2] - (rgb[0] + rgb[1]) / 2f) / 255f
            if (blueness > 0f) { val factor = 1f - a * blueness; for (k in 0 until 3) rgb[k] = (rgb[k] * factor).coerceIn(0f, 255f) }
        }
        for (k in 0 until 3) {
            val curve = ctx.forward[k]
            val x = rgb[k].coerceIn(0f, 255f)
            val i = x.toInt().coerceAtMost(254)
            val t = x - i
            rgb[k] = curve[i] + (curve[i + 1] - curve[i]) * t
        }
    }

    /** The fractional input level that prints as [target] on a channel with the given forward curve. */
    private fun inverseToneFractional(forward: IntArray, target: Float): Float {
        val top = forward[255]
        if (target <= forward[0]) {
            // At the film's black or below: the end of the black plateau, where the curve starts
            // to rise, so that the darks stay continuous with the shadows just above them (and
            // the Newton steps start where the curve has a slope).
            var k = 0
            while (k < 255 && forward[k + 1] <= forward[0]) k++
            return k.toFloat()
        }
        if (target >= top) {
            // Out of the film's reach, or exactly its white: the first input that reaches the top.
            var k = 0
            while (k < 255 && forward[k] < top) k++
            return k.toFloat()
        }
        var j = 0
        while (j < 254 && forward[j + 1] < target) j++
        // forward[j] < target <= forward[j+1].
        if (forward[j + 1] <= forward[j]) return j.toFloat()
        return j + (target - forward[j]) / (forward[j + 1] - forward[j])
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

    // ------------------------------------------------------------------ Fast path: 3D lookup table

    /**
     * The whole pipeline (optional compensation, then simulation) sampled on a 33³ grid of
     * input colours, so that previews cost a trilinear interpolation per pixel instead of the HSV
     * maths of the per-hue correction. Cached per model state; prints use the exact per-pixel path.
     */
    @Volatile private var lutKey: String? = null
    @Volatile private var lutPlain: IntArray? = null
    @Volatile private var lutCompensated: IntArray? = null
    /** The last tables built for any state, shown while the current state's table is being built. */
    @Volatile private var lastPlain: IntArray? = null
    @Volatile private var lastCompensated: IntArray? = null
    @Volatile private var buildingKey: String? = null

    private fun stateKey(): String = listOf(type, exposure, gamma, blackClip, whiteClip, blackLevel, whiteLevel, shadowWarmth, saturation, warmth, tint, blueDarkening,
        hueShift.joinToString(","), hueGain.joinToString(","), hueValue.joinToString(","), crosstalk.joinToString(",")).joinToString("|")

    /** The table for one state, built on every core at once (the compensation's Newton steps make it a few hundred milliseconds of work). */
    private fun buildLut(compensated: Boolean): IntArray {
        val n = Lut.LUT_N
        val grid = IntArray(n * n * n)
        for (r in 0 until n) for (g in 0 until n) for (b in 0 until n) grid[(r * n + g) * n + b] = Color.rgb(r * 255 / (n - 1), g * 255 / (n - 1), b * 255 / (n - 1))
        val model = copy()
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)
        val workers = (0 until threads).map { t ->
            Thread {
                val from = n * t / threads * n * n; val to = n * (t + 1) / threads * n * n
                if (to > from) {
                    val part = grid.copyOfRange(from, to)
                    if (compensated) model.compensate(part)
                    model.simulate(part)
                    System.arraycopy(part, 0, grid, from, to - from)
                }
            }.apply { start() }
        }
        workers.forEach { it.join() }
        return grid
    }

    /** The table for the current state, built now if it is not there yet. */
    fun lut(compensated: Boolean): IntArray {
        val key = stateKey()
        synchronized(this) {
            if (key != lutKey) { lutKey = key; lutPlain = null; lutCompensated = null }
            (if (compensated) lutCompensated else lutPlain)?.let { return it }
        }
        val built = buildLut(compensated)
        synchronized(this) {
            if (lutKey == key) { if (compensated) lutCompensated = built else lutPlain = built }
            if (compensated) lastCompensated = built else lastPlain = built
        }
        return built
    }

    /**
     * Preview version of [compensate] + [simulate]: the cached 3D table, trilinear. With
     * [onReady], the table for a new state is built on a background thread and [onReady] is
     * called on the main thread once it is there; meanwhile the last table built is used (or
     * nothing, the first time). Without it, the table is built here and now.
     */
    fun simulateFast(pixels: IntArray, compensated: Boolean, onReady: (() -> Unit)? = null) {
        if (onReady == null) { Lut.applyLut(pixels, lut(compensated)); return }
        val key = stateKey()
        val ready: IntArray? = synchronized(this) {
            if (key != lutKey) { lutKey = key; lutPlain = null; lutCompensated = null }
            if (compensated) lutCompensated else lutPlain
        }
        if (ready != null) { Lut.applyLut(pixels, ready); return }
        val buildKey = key + (if (compensated) "|c" else "|p")
        val start = synchronized(this) { if (buildingKey == buildKey) false else { buildingKey = buildKey; true } }
        if (start) {
            Thread {
                val built = buildLut(compensated)
                synchronized(this) {
                    if (lutKey == key) { if (compensated) lutCompensated = built else lutPlain = built }
                    if (compensated) lastCompensated = built else lastPlain = built
                    if (buildingKey == buildKey) buildingKey = null
                }
                android.os.Handler(android.os.Looper.getMainLooper()).post(onReady)
            }.start()
        }
        (if (compensated) lastCompensated else lastPlain)?.let { Lut.applyLut(pixels, it) }
    }

    object Lut {
        const val LUT_N = 33
        const val PRINT_N = 65
        private val idx = IntArray(256)
        private val frac = IntArray(256)
        private val idxP = IntArray(256)
        private val fracP = IntArray(256)

        init {
            fill(LUT_N, idx, frac)
            fill(PRINT_N, idxP, fracP)
        }

        private fun fill(n: Int, idx: IntArray, frac: IntArray) {
            for (v in 0..255) {
                val p = v * (n - 1) * 256 / 255 // fixed point 24.8 grid position
                var i = p shr 8
                var f = p and 0xFF
                if (i >= n - 1) { i = n - 2; f = 256 }
                idx[v] = i; frac[v] = f
            }
        }

        /** Interleaved gradient noise (Jimenez), 0..255, tiled: cheap, evenly spread, no visible pattern. */
        private const val NOISE = 128
        private val noise = IntArray(NOISE * NOISE).also {
            for (y in 0 until NOISE) for (x in 0 until NOISE) {
                val f = 52.9829189 * ((0.06711056 * x + 0.00583715 * y) % 1.0)
                it[y * NOISE + x] = ((f % 1.0) * 256).toInt().coerceIn(0, 255)
            }
        }

        /** One trilinear sample, in 8.8 fixed point per channel, into [out] (r, g, b). */
        private fun sample(lut: IntArray, n: Int, idx: IntArray, frac: IntArray, p: Int, out: LongArray) {
            val nn = n * n
            val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
            val ri = idx[r]; val gi = idx[g]; val bi = idx[b]
            val rf = frac[r]; val gf = frac[g]; val bf = frac[b]
            val base = ri * nn + gi * n + bi
            val c000 = lut[base]; val c001 = lut[base + 1]
            val c010 = lut[base + n]; val c011 = lut[base + n + 1]
            val c100 = lut[base + nn]; val c101 = lut[base + nn + 1]
            val c110 = lut[base + nn + n]; val c111 = lut[base + nn + n + 1]
            val w000 = (256 - rf) * (256 - gf) * (256 - bf); val w001 = (256 - rf) * (256 - gf) * bf
            val w010 = (256 - rf) * gf * (256 - bf); val w011 = (256 - rf) * gf * bf
            val w100 = rf * (256 - gf) * (256 - bf); val w101 = rf * (256 - gf) * bf
            val w110 = rf * gf * (256 - bf); val w111 = rf * gf * bf
            for (ch in 0 until 3) {
                val sh = 16 - 8 * ch
                out[ch] = (((c000 shr sh) and 0xFF).toLong() * w000 + ((c001 shr sh) and 0xFF).toLong() * w001 + ((c010 shr sh) and 0xFF).toLong() * w010 + ((c011 shr sh) and 0xFF).toLong() * w011 +
                    ((c100 shr sh) and 0xFF).toLong() * w100 + ((c101 shr sh) and 0xFF).toLong() * w101 + ((c110 shr sh) and 0xFF).toLong() * w110 + ((c111 shr sh) and 0xFF).toLong() * w111) shr 16
            }
        }

        /** Rounded trilinear application, for previews. */
        fun applyLut(pixels: IntArray, lut: IntArray) {
            val out = LongArray(3)
            for (i in pixels.indices) {
                val p = pixels[i]
                sample(lut, LUT_N, idx, frac, p, out)
                val nr = ((out[0] + 128) shr 8).toInt(); val ng = ((out[1] + 128) shr 8).toInt(); val nb = ((out[2] + 128) shr 8).toInt()
                pixels[i] = (p and 0xFF000000.toInt()) or (nr.coerceIn(0, 255) shl 16) or (ng.coerceIn(0, 255) shl 8) or nb.coerceIn(0, 255)
            }
        }

        /**
         * Dithered trilinear application on the print grid, whose nodes hold 8.8 fractional levels
         * per channel: the fraction of the interpolated level is resolved against the noise tile.
         */
        fun applyLutDithered(pixels: IntArray, width: Int, lut: Array<IntArray>, n: Int) {
            val nn = n * n
            for (i in pixels.indices) {
                val p = pixels[i]
                val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
                val ri = idxP[r]; val gi = idxP[g]; val bi = idxP[b]
                val rf = fracP[r]; val gf = fracP[g]; val bf = fracP[b]
                val base = ri * nn + gi * n + bi
                val w000 = (256 - rf).toLong() * (256 - gf) * (256 - bf); val w001 = (256 - rf).toLong() * (256 - gf) * bf
                val w010 = (256 - rf).toLong() * gf * (256 - bf); val w011 = (256 - rf).toLong() * gf * bf
                val w100 = rf.toLong() * (256 - gf) * (256 - bf); val w101 = rf.toLong() * (256 - gf) * bf
                val w110 = rf.toLong() * gf * (256 - bf); val w111 = rf.toLong() * gf * bf
                val x = i % width; val y = i / width
                val t = noise[(y % NOISE) * NOISE + (x % NOISE)]
                var packed = p and 0xFF000000.toInt()
                for (c in 0 until 3) {
                    val l = lut[c]
                    // 8.8 values weighted by 24-bit weights: 32.8 fixed point, back to 8.8, then dithered to 8.
                    val v = ((l[base] * w000 + l[base + 1] * w001 + l[base + n] * w010 + l[base + n + 1] * w011 +
                        l[base + nn] * w100 + l[base + nn + 1] * w101 + l[base + nn + n] * w110 + l[base + nn + n + 1] * w111) shr 24).toInt()
                    val level = ((v + t) shr 8).coerceIn(0, 255)
                    packed = packed or (level shl (16 - 8 * c))
                }
                pixels[i] = packed
            }
        }

    }

    private var printLutKey: String? = null
    private var printLut: Array<IntArray>? = null

    /**
     * Compensation for a print, with dithering: the inverse model stretches the tones, so plain
     * 8-bit output would skip levels and band on smooth gradients. The compensation is sampled on
     * a finer grid, interpolated with eight fractional bits, and the fraction is resolved with a
     * noise pattern, so neighbouring pixels alternate between the two nearest levels and the film
     * averages them out.
     */
    fun compensateDithered(pixels: IntArray, width: Int) {
        val key = stateKey()
        var lut = printLut
        if (lut == null || key != printLutKey) {
            val n = Lut.PRINT_N
            val table = Array(3) { IntArray(n * n * n) }
            // Every core at once: each worker takes a slice of red levels, with its own context.
            val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)
            val model = copy()
            (0 until threads).map { t ->
                Thread {
                    val ctx = model.CompensationContext()
                    val rgb = FloatArray(3)
                    for (r in n * t / threads until n * (t + 1) / threads) for (g in 0 until n) for (b in 0 until n) {
                        rgb[0] = r * 255f / (n - 1); rgb[1] = g * 255f / (n - 1); rgb[2] = b * 255f / (n - 1)
                        model.compensateFractional(rgb, ctx)
                        val k = (r * n + g) * n + b
                        for (c in 0 until 3) table[c][k] = Math.round(rgb[c] * 256f).coerceIn(0, 255 * 256)
                    }
                }.apply { start() }
            }.forEach { it.join() }
            lut = table
            printLut = lut
            printLutKey = key
        }
        Lut.applyLutDithered(pixels, width, lut, Lut.PRINT_N)
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
    /**
     * The chart is one print doing two jobs: a framing ring around the edge (nested rectangles
     * every 1 % from the file's edge, numbered, so the print shows how much the frame hides on each
     * side), and the colour or monochrome content centred inside it, scaled to [CONTENT] of the
     * file. The reader works in content coordinates (0..1 over the content square); [contentToFile]
     * converts a content position to a file position.
     */
    const val CONTENT = 0.78f
    const val CONTENT_INSET = (1f - CONTENT) / 2f

    fun contentToFile(c: Float): Float = CONTENT_INSET + c * CONTENT
    fun fileToContent(f: Float): Float = (f - CONTENT_INSET) / CONTENT

    fun render(size: Int, type: FilmType): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        drawFramingRing(canvas, size)
        val inner = Math.round(size * CONTENT)
        val content = if (type.monochrome) renderMono(inner) else renderColour(inner)
        canvas.drawBitmap(content, size * CONTENT_INSET, size * CONTENT_INSET, Paint(Paint.FILTER_BITMAP_FLAG))
        return bitmap
    }

    /** The content alone (grey steps, patches, gradient), as the reader expects it in content coordinates. */
    fun renderContent(size: Int, type: FilmType): Bitmap = if (type.monochrome) renderMono(size) else renderColour(size)

    /**
     * Nested rectangles 1 % to 8 % in from the file's edges, each numbered on all four sides, and a
     * bold line on the edge itself. On the print, the first rectangle that shows whole on a side is
     * how much the frame hides there.
     */
    private fun drawFramingRing(canvas: Canvas, size: Int) {
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.BLACK }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textAlign = Paint.Align.CENTER; typeface = android.graphics.Typeface.DEFAULT_BOLD }
        val white = Paint().apply { color = Color.WHITE }
        line.strokeWidth = size * 0.006f
        canvas.drawRect(size * 0.003f, size * 0.003f, size - size * 0.003f, size - size * 0.003f, line)
        for (pct in 1..8) {
            val inset = size * pct / 100f
            line.strokeWidth = size * (if (pct % 2 == 0) 0.004f else 0.002f)
            canvas.drawRect(inset, inset, size - inset, size - inset, line)
        }
        // Each rectangle's number just inside it, at its own position along the side, on a white patch.
        text.textSize = size * 0.022f
        for (pct in 1..8) {
            val inset = size * pct / 100f
            val along = size * (0.12f + 0.1f * pct)
            val label = "$pct"
            val tw = text.measureText(label)
            val th = text.textSize
            val pad = size * 0.003f
            fun stamp(cx: Float, cy: Float) {
                canvas.drawRect(cx - tw / 2 - pad, cy - th * 0.78f - pad, cx + tw / 2 + pad, cy + th * 0.22f + pad, white)
                canvas.drawText(label, cx, cy, text)
            }
            val depth = inset + size * 0.004f
            stamp(along, depth + th * 0.78f)
            stamp(along, size - depth - th * 0.22f)
            stamp(depth + tw / 2 + pad, along + th * 0.28f)
            stamp(size - depth - tw / 2 - pad, along + th * 0.28f)
        }
    }

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

    /** Colour content: 11 grey steps, 16 patches, a gradient. */
    fun renderColour(size: Int): Bitmap {
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
