package io.github.akaiikitsune.plsshare

/** Per-pixel steps of the look: tone curve (as a lookup table) and unsharp-mask sharpening. */
object Processing {
    /**
     * Build the 256-entry tone curve for a state. Order: brightness, contrast around mid-grey,
     * shadows / highlights (bell-shaped weights peaking at 1/3 and 2/3), blacks / whites (weights
     * concentrated at the ends).
     */
    fun toneCurve(state: EditState): IntArray {
        val lut = IntArray(256)
        val brightness = state.brightness / 100f * 0.5f
        val contrast = 1f + state.contrast / 100f
        val shadows = state.shadows / 100f * 0.45f
        val highlights = state.highlights / 100f * 0.45f
        val blacks = state.blacks / 100f * 0.35f
        val whites = state.whites / 100f * 0.35f
        for (i in 0..255) {
            var v = i / 255f
            v += brightness
            v = 0.5f + (v - 0.5f) * contrast
            v = v.coerceIn(0f, 1f)
            val u = 1f - v
            v += shadows * (v * u * u * 6.75f)
            v += highlights * (v * v * u * 6.75f)
            v += blacks * (u * u * u)
            v += whites * (v * v * v)
            lut[i] = Math.round(v.coerceIn(0f, 1f) * 255f)
        }
        return lut
    }

    fun apply(pixels: IntArray, width: Int, height: Int, state: EditState, sharpen: Boolean) {
        if (state.brightness != 0f || state.contrast != 0f || state.shadows != 0f || state.highlights != 0f ||
            state.blacks != 0f || state.whites != 0f
        ) {
            applyCurve(pixels, toneCurve(state))
        }
        if (sharpen && state.sharpness > 0f) unsharpMask(pixels, width, height, state.sharpness / 100f * 1.6f)
    }

    fun applyCurves(pixels: IntArray, lutR: IntArray, lutG: IntArray, lutB: IntArray) {
        for (i in pixels.indices) {
            val p = pixels[i]
            pixels[i] = (p and 0xFF000000.toInt()) or
                (lutR[(p shr 16) and 0xFF] shl 16) or (lutG[(p shr 8) and 0xFF] shl 8) or lutB[p and 0xFF]
        }
    }

    fun applyCurve(pixels: IntArray, lut: IntArray) {
        for (i in pixels.indices) {
            val p = pixels[i]
            pixels[i] = (p and 0xFF000000.toInt()) or
                (lut[(p shr 16) and 0xFF] shl 16) or (lut[(p shr 8) and 0xFF] shl 8) or lut[p and 0xFF]
        }
    }

    /** out = p + amount · (p − blur3x3(p)), per channel. */
    fun unsharpMask(pixels: IntArray, width: Int, height: Int, amount: Float) {
        if (width < 3 || height < 3) return
        val src = pixels.copyOf()
        val k = Math.round(amount * 256f)
        for (y in 1 until height - 1) {
            val row = y * width
            for (x in 1 until width - 1) {
                val i = row + x
                var sr = 0; var sg = 0; var sb = 0
                for (dy in -1..1) {
                    val r0 = i + dy * width
                    for (dx in -1..1) {
                        val q = src[r0 + dx]
                        sr += (q shr 16) and 0xFF; sg += (q shr 8) and 0xFF; sb += q and 0xFF
                    }
                }
                val p = src[i]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                val nr = (r + ((r * 9 - sr) * k) / (9 * 256)).coerceIn(0, 255)
                val ng = (g + ((g * 9 - sg) * k) / (9 * 256)).coerceIn(0, 255)
                val nb = (b + ((b * 9 - sb) * k) / (9 * 256)).coerceIn(0, 255)
                pixels[i] = (p and 0xFF000000.toInt()) or (nr shl 16) or (ng shl 8) or nb
            }
        }
    }

    /** Histogram of the red, green, blue and luminance channels, sampling every [stride]-th pixel. */
    fun histogram(pixels: IntArray, stride: Int = 3): Array<IntArray> {
        val r = IntArray(256); val g = IntArray(256); val b = IntArray(256); val l = IntArray(256)
        var i = 0
        while (i < pixels.size) {
            val p = pixels[i]
            val pr = (p shr 16) and 0xFF; val pg = (p shr 8) and 0xFF; val pb = p and 0xFF
            r[pr]++; g[pg]++; b[pb]++
            l[(pr * 77 + pg * 150 + pb * 29) shr 8]++
            i += stride
        }
        return arrayOf(r, g, b, l)
    }

    /**
     * Auto levels: stretch the luminance so that the darkest 0.5 % of pixels become black and the
     * brightest 0.5 % white, expressed as the brightness and contrast sliders (-100..100).
     * Returns null when the photo already uses the full range.
     */
    fun autoLevels(pixels: IntArray): Pair<Float, Float>? {
        val luma = histogram(pixels, stride = 1)[3]
        val total = luma.sum()
        if (total == 0) return null
        val cut = total / 200
        var acc = 0
        var low = 0
        while (low < 255 && acc + luma[low] < cut) { acc += luma[low]; low++ }
        acc = 0
        var high = 255
        while (high > 0 && acc + luma[high] < cut) { acc += luma[high]; high-- }
        if (high - low < 16) return null
        val lo = low / 255f
        val hi = high / 255f
        val c = (1f / (hi - lo)).coerceIn(1f, 2f)
        // v' = 0.5 + (v + b - 0.5)·c must equal (v - lo)·c, so b = 0.5 - 0.5/c - lo.
        val b = 0.5f - 0.5f / c - lo
        val brightness = (b * 200f).coerceIn(-100f, 100f)
        val contrast = ((c - 1f) * 100f).coerceIn(-100f, 100f)
        return brightness to contrast
    }

    const val CLIP_HIGH = 0xFFFF2020.toInt()
    const val CLIP_LOW = 0xFF2070FF.toInt()

    /**
     * A hatched overlay (diagonal stripes, like a camera's zebra) marking blown highlights (any
     * channel ≥ 253) in red and crushed shadows (all ≤ 2) in blue. [stripe] is the stripe width in pixels.
     */
    fun clipping(pixels: IntArray, width: Int, out: IntArray = IntArray(pixels.size), stripe: Int = 6): IntArray {
        val period = stripe * 2
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
            val hatched = ((i % width) + (i / width)) % period < stripe
            out[i] = when {
                !hatched -> 0
                r >= 253 || g >= 253 || b >= 253 -> CLIP_HIGH
                r <= 2 && g <= 2 && b <= 2 -> CLIP_LOW
                else -> 0
            }
        }
        return out
    }
}
