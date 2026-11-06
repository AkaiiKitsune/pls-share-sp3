package io.github.akaiikitsune.plsshare

import android.graphics.Bitmap
import android.graphics.Color

/**
 * Reads a photographed test chart and fits a [FilmModel] to it.
 *
 * The caller gives the four corners of the printed image area (the square inside the instax
 * frame), in photo pixels, clockwise from the top-left. The photo is warped to a square with a
 * homography, the patches are averaged at the positions [TestChart] drew them, and every value is
 * divided by the paper white sampled in the frame just outside the image, which cancels the
 * lighting's brightness and colour (daylight or flash).
 */
object ChartReader {
    const val SIZE = 400

    class Reading(
        /** Normalised RGB (0..1 of paper white) of the 11 grey steps, 0 % to 100 %. */
        val greys: List<FloatArray>,
        /** Normalised RGB of the 16 colour patches (colour chart only). */
        val patches: List<FloatArray>,
        /** Normalised RGB of the printed white margin inside the image. */
        val printedWhite: FloatArray,
        /** The paper white as photographed (raw RGB 0..255), for information. */
        val paperWhite: FloatArray,
        /** The warped chart, for display. */
        val warped: Bitmap,
    )

    /** Solve the homography that maps the unit square to the four corners (TL, TR, BR, BL). */
    fun homography(corners: FloatArray): DoubleArray {
        val src = doubleArrayOf(0.0, 0.0, 1.0, 0.0, 1.0, 1.0, 0.0, 1.0)
        val dst = DoubleArray(8) { corners[it].toDouble() }
        // 8 equations in h0..h7 (h8 = 1).
        val a = Array(8) { DoubleArray(9) }
        for (i in 0 until 4) {
            val x = src[2 * i]; val y = src[2 * i + 1]
            val u = dst[2 * i]; val v = dst[2 * i + 1]
            a[2 * i] = doubleArrayOf(x, y, 1.0, 0.0, 0.0, 0.0, -u * x, -u * y, u)
            a[2 * i + 1] = doubleArrayOf(0.0, 0.0, 0.0, x, y, 1.0, -v * x, -v * y, v)
        }
        // Gaussian elimination with partial pivoting.
        for (col in 0 until 8) {
            var pivot = col
            for (r in col + 1 until 8) if (Math.abs(a[r][col]) > Math.abs(a[pivot][col])) pivot = r
            val tmp = a[col]; a[col] = a[pivot]; a[pivot] = tmp
            val p = a[col][col]
            if (Math.abs(p) < 1e-12) continue
            for (c in col until 9) a[col][c] /= p
            for (r in 0 until 8) if (r != col) {
                val f = a[r][col]
                if (f != 0.0) for (c in col until 9) a[r][c] -= f * a[col][c]
            }
        }
        return DoubleArray(9) { if (it < 8) a[it][8] else 1.0 }
    }

    /** Map a point of the unit square to photo pixels. */
    fun map(h: DoubleArray, x: Double, y: Double): DoubleArray {
        val w = h[6] * x + h[7] * y + h[8]
        return doubleArrayOf((h[0] * x + h[1] * y + h[2]) / w, (h[3] * x + h[4] * y + h[5]) / w)
    }

    private fun sample(pixels: IntArray, width: Int, height: Int, px: Double, py: Double): Int {
        val x = px.toInt().coerceIn(0, width - 1)
        val y = py.toInt().coerceIn(0, height - 1)
        return pixels[y * width + x]
    }

    /** Average raw RGB over a rectangle of the unit square, mapped through the homography. */
    private fun average(pixels: IntArray, width: Int, height: Int, h: DoubleArray, l: Double, t: Double, r: Double, b: Double, grid: Int = 9): FloatArray {
        var sr = 0.0; var sg = 0.0; var sb = 0.0; var n = 0
        for (i in 0 until grid) for (j in 0 until grid) {
            val x = l + (r - l) * (i + 0.5) / grid
            val y = t + (b - t) * (j + 0.5) / grid
            val p = map(h, x, y)
            val c = sample(pixels, width, height, p[0], p[1])
            sr += Color.red(c); sg += Color.green(c); sb += Color.blue(c); n++
        }
        return floatArrayOf((sr / n).toFloat(), (sg / n).toFloat(), (sb / n).toFloat())
    }

    /** Central part of a patch rectangle (fractions of the chart), shrunk by [keep] on each axis. */
    private fun inner(l: Float, t: Float, r: Float, b: Float, keep: Float = 0.5f): DoubleArray {
        val cx = (l + r) / 2; val cy = (t + b) / 2
        val hw = (r - l) / 2 * keep; val hh = (b - t) / 2 * keep
        return doubleArrayOf((cx - hw).toDouble(), (cy - hh).toDouble(), (cx + hw).toDouble(), (cy + hh).toDouble())
    }

    fun read(photo: Bitmap, corners: FloatArray, type: FilmType): Reading {
        val width = photo.width
        val height = photo.height
        val pixels = IntArray(width * height)
        photo.getPixels(pixels, 0, width, 0, 0, width, height)
        val h = homography(corners)

        // Paper white: the instax frame just outside the image (its side borders are only about 8 %
        // of the image wide), on each side; sides that fall off the print are dropped.
        val outside = listOf(
            average(pixels, width, height, h, 0.3, -0.055, 0.7, -0.015, 5),
            average(pixels, width, height, h, 0.3, 1.015, 0.7, 1.055, 5),
            average(pixels, width, height, h, -0.055, 0.3, -0.015, 0.7, 5),
            average(pixels, width, height, h, 1.015, 0.3, 1.055, 0.7, 5),
        )
        val brightest = outside.maxOf { it.sum() }
        val valid = outside.map { if (it.sum() >= brightest * 0.8f) it else null }
        val paper = FloatArray(3) { c -> valid.filterNotNull().map { it[c] }.sorted().let { v -> v[v.size / 2] } }
        // Uneven light (a window on one side, a flash fall-off) shows as a gradient across the frame:
        // model the paper white as varying linearly between opposite sides, per channel, so every
        // patch is divided by the white of its own neighbourhood.
        val top = valid[0]; val bottom = valid[1]; val left = valid[2]; val right = valid[3]
        fun lerp(a: FloatArray?, b: FloatArray?, t: Double, c: Int): Float = when {
            a != null && b != null -> (a[c] + (b[c] - a[c]) * t.toFloat())
            a != null -> a[c]
            b != null -> b[c]
            else -> paper[c]
        }
        fun paperAt(x: Double, y: Double, c: Int): Float {
            val h = lerp(left, right, x, c); val v = lerp(top, bottom, y, c)
            val hm = lerp(left, right, 0.5, c); val vm = lerp(top, bottom, 0.5, c)
            return paper[c] * (h / hm.coerceAtLeast(1f)) * (v / vm.coerceAtLeast(1f))
        }
        fun norm(v: FloatArray, rect: DoubleArray): FloatArray {
            val cx = (rect[0] + rect[2]) / 2; val cy = (rect[1] + rect[3]) / 2
            return FloatArray(3) { c -> (v[c] / paperAt(cx, cy, c).coerceAtLeast(1f)).coerceIn(0f, 1.2f) }
        }

        // Geometry of TestChart.render, as fractions of the chart size.
        val margin = 0.04f
        val innerW = 1f - 2 * margin
        val stepW = innerW / 11
        val stepH = 0.14f
        val greys = (0 until 11).map { i ->
            val l = margin + i * stepW
            val rect = inner(l, margin, l + stepW, margin + stepH, 0.5f)
            norm(average(pixels, width, height, h, rect[0], rect[1], rect[2], rect[3]), rect)
        }
        val patches = if (type.monochrome) emptyList() else (0 until 16).map { i ->
            val gridTop = margin + stepH + 0.04f
            val cell = innerW / 4
            val gap = 0.012f
            val col = i % 4; val row = i / 4
            val l = margin + col * cell + gap
            val t = gridTop + row * cell * 0.75f + gap
            val rect = inner(l, t, l + cell - 2 * gap, t + cell * 0.75f - 2 * gap, 0.5f)
            norm(average(pixels, width, height, h, rect[0], rect[1], rect[2], rect[3]), rect)
        }
        // Printed white: the chart's own margin (right of the grey steps, which stop before the right edge).
        val whiteRect = doubleArrayOf(0.6, (margin + stepH + 0.005f).toDouble(), 0.95, (margin + stepH + 0.03f).toDouble())
        val printedWhite = norm(average(pixels, width, height, h, whiteRect[0], whiteRect[1], whiteRect[2], whiteRect[3], 5), whiteRect)

        val warped = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val out = IntArray(SIZE * SIZE)
        for (y in 0 until SIZE) for (x in 0 until SIZE) {
            val p = map(h, (x + 0.5) / SIZE, (y + 0.5) / SIZE)
            out[y * SIZE + x] = sample(pixels, width, height, p[0], p[1])
        }
        warped.setPixels(out, 0, SIZE, 0, 0, SIZE, SIZE)
        return Reading(greys, patches, printedWhite, paper, warped)
    }

    private fun luma(c: FloatArray) = 0.299f * c[0] + 0.587f * c[1] + 0.114f * c[2]

    /**
     * Fit the film model to a reading. Returns the model and a short report of the fit
     * (RMS error on the grey steps, in percent of paper white).
     */
    fun fit(reading: Reading, type: FilmType): Pair<FilmModel, String> {
        val m = FilmModel.defaults(type)
        val g = reading.greys.map { luma(it) }
        val n = g.size
        val black = g.take(3).minOrNull() ?: 0f
        val white = g.takeLast(3).maxOrNull() ?: 1f
        m.blackLevel = black.coerceIn(0f, 0.4f)
        m.whiteLevel = white.coerceIn(0.7f, 1f)
        // Clips: the first / last input step whose value leaves the plateau.
        val tol = 0.03f
        var lastBlack = 0
        for (i in 0 until n) if (g[i] - black <= tol) lastBlack = i else break
        var firstWhite = n - 1
        for (i in n - 1 downTo 0) if (white - g[i] <= tol) firstWhite = i else break
        // The plateau ends somewhere between the last flat step and the next: take the midpoint.
        m.blackClip = ((lastBlack + 0.5f) / (n - 1)).coerceIn(0f, 0.3f)
        m.whiteClip = ((firstWhite - 0.5f) / (n - 1)).coerceIn(0.7f, 1f)
        m.exposure = 0f
        // Gamma on the interior steps: t = u^gamma.
        val gammas = ArrayList<Double>()
        for (i in 0 until n) {
            val v = i.toFloat() / (n - 1)
            val u = (v - m.blackClip) / (m.whiteClip - m.blackClip)
            val t = (g[i] - black) / (white - black)
            if (u > 0.08f && u < 0.92f && t > 0.03f && t < 0.97f) gammas += Math.log(t.toDouble()) / Math.log(u.toDouble())
        }
        m.gamma = (if (gammas.isEmpty()) 0.62 else gammas.sorted()[gammas.size / 2]).toFloat().coerceIn(0.3f, 2f)

        // Blacks' warmth from the darkest step: R/B ratio against the model's black levels.
        val b0 = reading.greys[0]
        val ratio = b0[0] / b0[2].coerceAtLeast(0.01f)
        m.shadowWarmth = ((ratio - 1f) / (0.35f + 0.25f * ratio)).coerceIn(0f, 1f)
        // Grey balance from the middle steps (4..7): channel ratios against their luma.
        var wr = 0f; var wg = 0f; var wb = 0f; var k = 0
        for (i in 4..7) { val c = reading.greys[i]; val l = luma(c).coerceAtLeast(0.01f); wr += c[0] / l; wg += c[1] / l; wb += c[2] / l; k++ }
        wr /= k; wg /= k; wb /= k
        m.warmth = ((wr - wb) / 0.36f).coerceIn(-0.6f, 0.6f)
        m.tint = ((1f - wg) / 0.12f).coerceIn(-0.6f, 0.6f)

        if (!type.monochrome && reading.patches.size == 16) {
            // Saturation: measured chroma over the chroma predicted by the tone model with saturation 1.
            val probe = m.copy().apply { saturation = 1f; blueDarkening = 0f }
            val ratios = ArrayList<Float>()
            for ((i, src) in TestChart.patches.withIndex()) {
                val px = intArrayOf(src)
                probe.simulate(px)
                val p = px[0]
                val predicted = floatArrayOf(Color.red(p) / 255f, Color.green(p) / 255f, Color.blue(p) / 255f)
                val measured = reading.patches[i]
                val cp = predicted.max() - predicted.min()
                val cm = measured.max() - measured.min()
                if (cp > 0.15f) ratios += cm / cp
            }
            m.saturation = (if (ratios.isEmpty()) 0.72f else ratios.sorted()[ratios.size / 2]).coerceIn(0.4f, 1.5f)
            // Blue darkening: the blue patch's luma against the prediction with the fitted saturation.
            val probe2 = m.copy().apply { blueDarkening = 0f }
            val blue = intArrayOf(TestChart.patches[2])
            probe2.simulate(blue)
            val lp = (0.299f * Color.red(blue[0]) + 0.587f * Color.green(blue[0]) + 0.114f * Color.blue(blue[0])) / 255f
            val lm = luma(reading.patches[2])
            val blueness = (Color.blue(TestChart.patches[2]) - (Color.red(TestChart.patches[2]) + Color.green(TestChart.patches[2])) / 2f) / 255f
            m.blueDarkening = if (lm < lp && blueness > 0f) ((1f - lm / lp) / (0.6f * blueness)).coerceIn(0f, 1f) else 0f
        } else {
            m.saturation = if (type.monochrome) 0f else m.saturation
        }

        // Fit quality: RMS between measured grey steps and the model's prediction.
        var err = 0.0
        for (i in 0 until n) {
            val v = Math.round(i * 255f / (n - 1))
            val px = intArrayOf(Color.rgb(v, v, v))
            m.simulate(px)
            val pl = (0.299 * Color.red(px[0]) + 0.587 * Color.green(px[0]) + 0.114 * Color.blue(px[0])) / 255.0
            err += (pl - g[i]) * (pl - g[i])
        }
        val rms = Math.sqrt(err / n) * 100
        return m to "RMS %.1f %%".format(rms)
    }
}
