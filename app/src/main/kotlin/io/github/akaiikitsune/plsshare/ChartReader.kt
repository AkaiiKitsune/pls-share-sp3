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
    private const val PASSES = 8
    /** Top of the gradient (content coordinates): it is drawn over the bottom of the last row of patches. */
    const val GRADIENT_TOP = 0.86f

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

        // Paper white: the instax frame beyond the file's edge and its framing ring, on each side;
        // sides that fall off the print, or on the ring's lines, are dropped (the brightest wins).
        // In content coordinates the file's edge is at −0.14 / 1.14 and the frame runs about 0.1
        // further out on the sides and top, more at the bottom.
        val f0 = -(TestChart.CONTENT_INSET / TestChart.CONTENT).toDouble() // file 0 in content coordinates
        val f1 = 1 - f0
        val outside = listOf(
            average(pixels, width, height, h, 0.3, f0 - 0.085, 0.7, f0 - 0.03, 5),
            average(pixels, width, height, h, 0.3, f1 + 0.03, 0.7, f1 + 0.085, 5),
            average(pixels, width, height, h, f0 - 0.085, 0.3, f0 - 0.03, 0.7, 5),
            average(pixels, width, height, h, f1 + 0.03, 0.3, f1 + 0.085, 0.7, 5),
            // Older prints without the ring: the frame right outside the content.
            average(pixels, width, height, h, 0.3, -0.055, 0.7, -0.015, 5),
            average(pixels, width, height, h, 0.3, 1.015, 0.7, 1.055, 5),
            average(pixels, width, height, h, -0.055, 0.3, -0.015, 0.7, 5),
            average(pixels, width, height, h, 1.015, 0.3, 1.055, 0.7, 5),
        )
        // Paper is bright and, even under a warm light, less coloured than a wooden or woven table.
        val brightest = outside.maxOf { it.sum() }
        fun chroma(v: FloatArray) = (v.max() - v.min()) / v.sum().coerceAtLeast(1f)
        val bright = outside.filter { it.sum() >= brightest * 0.8f }
        val leastChroma = bright.minOf { chroma(it) }
        val valid = outside.map { if (it.sum() >= brightest * 0.8f && chroma(it) <= leastChroma * 1.4f + 0.02f) it else null }
        val paper = FloatArray(3) { c -> valid.filterNotNull().map { it[c] }.sorted().let { v -> v[v.size / 2] } }
        // Uneven light (a window on one side, a flash fall-off) shows as a gradient across the frame:
        // model the paper white as varying linearly between opposite sides, per channel, so every
        // patch is divided by the white of its own neighbourhood.
        val top = valid[0] ?: valid[4]; val bottom = valid[1] ?: valid[5]; val left = valid[2] ?: valid[6]; val right = valid[3] ?: valid[7]
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
            // The gradient covers the bottom of the last row: only what shows is read.
            val b = Math.min(t + cell * 0.75f - 2 * gap, GRADIENT_TOP)
            val rect = inner(l, t, l + cell - 2 * gap, b, 0.5f)
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

    /** The pixel-wise mean of the rectified charts of several readings: one picture of all the photos at once. */
    fun averageWarped(readings: List<Reading>): Bitmap {
        require(readings.isNotEmpty())
        if (readings.size == 1) return readings[0].warped
        val n = SIZE * SIZE
        val sr = IntArray(n); val sg = IntArray(n); val sb = IntArray(n)
        val px = IntArray(n)
        for (r in readings) {
            r.warped.getPixels(px, 0, SIZE, 0, 0, SIZE, SIZE)
            for (i in 0 until n) { val c = px[i]; sr[i] += Color.red(c); sg[i] += Color.green(c); sb[i] += Color.blue(c) }
        }
        val k = readings.size
        for (i in 0 until n) px[i] = Color.rgb(sr[i] / k, sg[i] / k, sb[i] / k)
        return Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888).apply { setPixels(px, 0, SIZE, 0, 0, SIZE, SIZE) }
    }

    /** The mean of several readings of the same chart (different photos, lights or angles). */
    fun average(readings: List<Reading>): Reading {
        require(readings.isNotEmpty())
        if (readings.size == 1) return readings[0]
        fun mean(lists: List<FloatArray>): FloatArray = FloatArray(3) { c -> lists.map { it[c] }.average().toFloat() }
        val greys = readings[0].greys.indices.map { i -> mean(readings.map { it.greys[i] }) }
        val patches = readings[0].patches.indices.map { i -> mean(readings.map { it.patches[i] }) }
        return Reading(greys, patches, mean(readings.map { it.printedWhite }), mean(readings.map { it.paperWhite }), readings[0].warped)
    }

    /**
     * Find the chart's content square in a photo, without any tap: on the colour chart from its
     * grid of patches ([detectFromGrid]). The monochrome chart has no grid; its corners are tapped.
     */
    fun detectCorners(photo: Bitmap, type: FilmType): FloatArray? =
        (if (type.monochrome) null else runCatching { detectFromGrid(photo) }.getOrNull())
            ?: runCatching { detectFromRing(photo) }.getOrNull()

    // ------------------------------------------------------------ Detection from the framing ring

    /**
     * Align the chart on its framing ring, which both charts carry: on every third row and column
     * of the photo, the ring crosses as a series of at least five dark lines at even spacing; the
     * innermost line of each series, over the rows and columns, draws the ring's inner rectangle
     * (the file's 8 % line), one straight edge per side; the four edges are fitted with the
     * outliers dropped, their spacing must be 84 line pitches, and the content square follows
     * from the chart's geometry. The numbers interrupt a few rows and columns, which are simply
     * skipped; the frame may hide up to three lines on a side.
     */
    fun detectFromRing(photo: Bitmap): FloatArray? {
        val w = photo.width; val h = photo.height
        val full = IntArray(w * h)
        photo.getPixels(full, 0, w, 0, 0, w, h)
        val luma = FloatArray(w * h) { val c = full[it]; (299 * Color.red(c) + 587 * Color.green(c) + 114 * Color.blue(c)) / 1000f }
        // Dips of a profile: local minima (the centre of a flat bottom) at least 10 % below the
        // brightest sample within six pixels on both sides.
        fun dips(p: FloatArray): List<Int> {
            val out = ArrayList<Int>()
            var i = 2
            while (i < p.size - 2) {
                val v = p[i]
                if (v <= p[i - 1] && v <= p[i + 1] && v <= p[i - 2] && v <= p[i + 2]) {
                    var left = v; var right = v
                    for (j in Math.max(0, i - 6) until i) if (p[j] > left) left = p[j]
                    for (j in i + 1..Math.min(p.size - 1, i + 6)) if (p[j] > right) right = p[j]
                    val paper = Math.min(left, right)
                    if (v < paper * 0.9f && paper > 40f) {
                        var a = i; var b = i
                        while (a > 0 && p[a - 1] <= v + (paper - v) * 0.15f) a--
                        while (b < p.size - 1 && p[b + 1] <= v + (paper - v) * 0.15f) b++
                        out += (a + b) / 2
                        i = b + 1
                        continue
                    }
                }
                i++
            }
            return out
        }
        // Series of at least five dips at even spacing (3 to 24 px): (first, last, pitch).
        fun series(d: List<Int>): List<Triple<Int, Int, Float>> {
            val out = ArrayList<Triple<Int, Int, Float>>()
            var i = 0
            while (i < d.size - 4) {
                val g0 = (d[i + 1] - d[i]).toFloat()
                if (g0 < 3f || g0 > 24f) { i++; continue }
                var k = i + 1
                var sum = g0; var n = 1
                while (k < d.size - 1) {
                    val g = (d[k + 1] - d[k]).toFloat()
                    val mean = sum / n
                    if (g < mean * 0.65f || g > mean * 1.5f) break
                    sum += g; n++; k++
                }
                if (k - i >= 4) { out += Triple(d[i], d[k], sum / n); i = k + 1 } else i++
            }
            return out
        }
        // Per row: the pair of series (one each side) with matching pitch and 84 pitches between their inner ends.
        class Crossing(val at: Int, val innerA: Int, val innerB: Int, val pitch: Float)
        fun crossings(count: Int, length: Int, profile: (Int, FloatArray) -> Unit): List<Crossing> {
            val out = ArrayList<Crossing>()
            val p = FloatArray(length)
            var at = 0
            while (at < count) {
                profile(at, p)
                val ser = series(dips(p))
                var best: Crossing? = null; var bestError = Float.MAX_VALUE
                // Just inside each series lies the white margin between the ring and the content:
                // one to two and a half pitches of paper, brighter than anything in the series.
                fun paperInside(inner: Int, pitch: Float, direction: Int, ceiling: Float): Boolean {
                    var k = (pitch * 1.2f).toInt()
                    while (k <= (pitch * 2.5f).toInt()) {
                        val x = inner + direction * k
                        if (x < 0 || x >= length || p[x] < ceiling * 0.8f) return false
                        k++
                    }
                    return true
                }
                for (a in ser) for (b in ser) {
                    if (b.first <= a.second) continue
                    val pitch = (a.third + b.third) / 2
                    if (Math.abs(a.third - b.third) > pitch * 0.15f) continue
                    val span = (b.first - a.second).toFloat()
                    val error = Math.abs(span / pitch - 84f)
                    if (error >= 10f || error >= bestError) continue
                    var brightest = 0f
                    for (x in a.first..b.second) if (p[x] > brightest) brightest = p[x]
                    if (!paperInside(a.second, pitch, 1, brightest) || !paperInside(b.first, pitch, -1, brightest)) continue
                    bestError = error; best = Crossing(at, a.second, b.first, pitch)
                }
                best?.let { out += it }
                at += 3
            }
            return out
        }
        val rows = crossings(h, w) { y, p -> for (x in 0 until w) p[x] = luma[y * w + x] }
        val cols = crossings(w, h) { x, p -> for (y in 0 until h) p[y] = luma[y * w + x] }
        if (rows.size < 12 || cols.size < 12) return null
        // A straight edge through the inner ends: the best two-point line by inliers within 2.5 px, refitted on them.
        fun edge(points: List<FloatArray>): DoubleArray? { // (along, across) -> across = a + b * along
            var best: DoubleArray? = null; var bestCount = 0
            val n = points.size
            val tries = minOf(400, n * (n - 1) / 2)
            var t = 0
            while (t < tries) {
                val i = (t * 7919) % n; val j = (t * 104729 + 1) % n
                t++
                if (i == j) continue
                val pi = points[i]; val pj = points[j]
                if (Math.abs(pj[0] - pi[0]) < 1f) continue
                val b = (pj[1] - pi[1]) / (pj[0] - pi[0]); val a = pi[1] - b * pi[0]
                val count = points.count { Math.abs(it[1] - (a + b * it[0])) <= 2.5f }
                if (count > bestCount) { bestCount = count; best = doubleArrayOf(a.toDouble(), b.toDouble()) }
            }
            val line = best ?: return null
            val inliers = points.filter { Math.abs(it[1] - (line[0] + line[1] * it[0])) <= 2.5f }
            if (inliers.size < 8 || inliers.size < points.size / 2) return null
            val mx = inliers.map { it[0].toDouble() }.average(); val my = inliers.map { it[1].toDouble() }.average()
            val b = inliers.sumOf { (it[0] - mx) * (it[1] - my) } / inliers.sumOf { (it[0] - mx) * (it[0] - mx) }
            return doubleArrayOf(my - b * mx, b)
        }
        val left = edge(rows.map { floatArrayOf(it.at.toFloat(), it.innerA.toFloat()) })      // x = a + b y
        val right = edge(rows.map { floatArrayOf(it.at.toFloat(), it.innerB.toFloat()) })
        val top = edge(cols.map { floatArrayOf(it.at.toFloat(), it.innerA.toFloat()) })       // y = a + b x
        val bottom = edge(cols.map { floatArrayOf(it.at.toFloat(), it.innerB.toFloat()) })
        if (left == null || right == null || top == null || bottom == null) return null
        fun meet(vertical: DoubleArray, horizontal: DoubleArray): DoubleArray {
            // x = v0 + v1 y, y = h0 + h1 x  =>  x = v0 + v1 (h0 + h1 x)
            val x = (vertical[0] + vertical[1] * horizontal[0]) / (1 - vertical[1] * horizontal[1])
            return doubleArrayOf(x, horizontal[0] + horizontal[1] * x)
        }
        val tl = meet(left, top); val tr = meet(right, top); val br = meet(right, bottom); val bl = meet(left, bottom)
        // The inner rectangle is the file's 8 % line: 84 % of the file wide, 1.077 content squares.
        val pitch = (rows.map { it.pitch } + cols.map { it.pitch }).sorted().let { it[it.size / 2] }
        val width = (Math.hypot(tr[0] - tl[0], tr[1] - tl[1]) + Math.hypot(br[0] - bl[0], br[1] - bl[1])) / 2
        val height = (Math.hypot(bl[0] - tl[0], bl[1] - tl[1]) + Math.hypot(br[0] - tr[0], br[1] - tr[1])) / 2
        if (width / pitch !in 72.0..96.0 || height / pitch !in 72.0..96.0) return null
        val inner = TestChart.fileToContent(0.08f).toDouble()
        val outer = TestChart.fileToContent(0.92f).toDouble()
        val hm = homographyFrom(doubleArrayOf(inner, inner, outer, inner, outer, outer, inner, outer),
            floatArrayOf(tl[0].toFloat(), tl[1].toFloat(), tr[0].toFloat(), tr[1].toFloat(), br[0].toFloat(), br[1].toFloat(), bl[0].toFloat(), bl[1].toFloat()))
        val corners = FloatArray(8)
        for ((idx, c) in listOf(0.0 to 0.0, 1.0 to 0.0, 1.0 to 1.0, 0.0 to 1.0).withIndex()) {
            val p = map(hm, c.first, c.second)
            corners[2 * idx] = p[0].toFloat(); corners[2 * idx + 1] = p[1].toFloat()
        }
        for (v in corners) if (v.isNaN()) return null
        return if (validateGreys(photo, corners)) corners else null
    }

    // ------------------------------------------------------------ Detection from the colour grid

    /** A uniform patch of colour in the (downscaled) photo. */
    private class Blob(val cx: Float, val cy: Float, val area: Int, val r: Float, val g: Float, val b: Float) {
        val hsv = FloatArray(3).also { FilmModel.rgbToHsv(r, g, b, it) }
        val hue get() = hsv[0]
        val sat get() = hsv[1]
        val value get() = hsv[2]
    }

    /**
     * Uniform blobs: pixels whose neighbourhood is flat, grown into regions of one colour. Textured
     * backgrounds break up, the chart's patches and steps come out whole; the white of the chart
     * and frame forms one huge region that the size limit drops.
     */
    private fun findBlobs(photo: Bitmap): Pair<List<Blob>, Int> {
        val scale = maxOf(1, maxOf(photo.width, photo.height) / 600)
        val w = photo.width / scale
        val h = photo.height / scale
        val full = IntArray(photo.width * photo.height)
        photo.getPixels(full, 0, photo.width, 0, 0, photo.width, photo.height)
        // Box-averaged downscale: also tames sensor noise and JPEG grain.
        val r = IntArray(w * h); val g = IntArray(w * h); val b = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var sr = 0; var sg = 0; var sb = 0
            for (dy in 0 until scale) for (dx in 0 until scale) {
                val c = full[(y * scale + dy) * photo.width + x * scale + dx]
                sr += Color.red(c); sg += Color.green(c); sb += Color.blue(c)
            }
            val n = scale * scale
            r[y * w + x] = sr / n; g[y * w + x] = sg / n; b[y * w + x] = sb / n
        }
        fun diff(i: Int, j: Int) = maxOf(Math.abs(r[i] - r[j]), Math.abs(g[i] - g[j]), Math.abs(b[i] - b[j]))
        val flat = BooleanArray(w * h)
        val reach = 2
        for (y in reach until h - reach) for (x in reach until w - reach) {
            val i = y * w + x
            flat[i] = diff(i, i - reach) < 14 && diff(i, i + reach) < 14 && diff(i, i - reach * w) < 14 && diff(i, i + reach * w) < 14
        }
        val label = IntArray(w * h)
        val queue = IntArray(w * h)
        val blobs = ArrayList<Blob>()
        val maxArea = w * h / 12
        var next = 0
        for (start in 0 until w * h) {
            if (!flat[start] || label[start] != 0) continue
            next++
            label[start] = next
            var head = 0; var tail = 0
            queue[tail++] = start
            var sr = 0L; var sg = 0L; var sb = 0L; var sx = 0L; var sy = 0L; var n = 0
            var minX = w; var maxX = 0; var minY = h; var maxY = 0
            while (head < tail) {
                val i = queue[head++]
                n++; sr += r[i]; sg += g[i]; sb += b[i]
                val x = i % w; val y = i / w
                sx += x; sy += y
                if (x < minX) minX = x; if (x > maxX) maxX = x; if (y < minY) minY = y; if (y > maxY) maxY = y
                for (j in intArrayOf(if (x > 0) i - 1 else -1, if (x < w - 1) i + 1 else -1, if (y > 0) i - w else -1, if (y < h - 1) i + w else -1)) {
                    if (j < 0 || !flat[j] || label[j] != 0) continue
                    // Grow only into the same colour: the region's running mean against the pixel.
                    if (Math.abs(r[j] - sr / n) > 24 || Math.abs(g[j] - sg / n) > 24 || Math.abs(b[j] - sb / n) > 24) continue
                    label[j] = next
                    queue[tail++] = j
                }
            }
            if (n < 25 || n > maxArea) continue
            val bw = maxX - minX + 1; val bh = maxY - minY + 1
            val fill = n.toFloat() / (bw * bh)
            val aspect = bw.toFloat() / bh
            if (fill < 0.7f || aspect < 0.45f || aspect > 2.6f) continue
            blobs += Blob(sx.toFloat() / n, sy.toFloat() / n, n, sr.toFloat() / n, sg.toFloat() / n, sb.toFloat() / n)
        }
        return blobs to scale
    }

    /** The centres of the 16 patches as fractions of the chart (column, row). */
    private fun patchCentre(col: Int, row: Int): DoubleArray {
        val margin = 0.04; val cell = 0.92 / 4; val gridTop = margin + 0.14 + 0.04; val gap = 0.012
        // The centre of what shows: the gradient covers the bottom of the last row.
        val top = gridTop + row * cell * 0.75 + gap
        val bottom = Math.min(top + cell * 0.75 - 2 * gap, GRADIENT_TOP.toDouble())
        return doubleArrayOf(margin + (col + 0.5) * cell, (top + bottom) / 2)
    }

    /**
     * Align the chart on its colour grid: the red, green, magenta and yellow patches (the top-left
     * 2×2 block, four hues nothing else on the chart has) anchor a first homography, every other
     * patch found near its predicted place refines it by least squares, and the image corners
     * follow from the chart's geometry. Backgrounds, lighting and perspective do not matter as long
     * as the patches read as uniform rectangles.
     */
    fun detectFromGrid(photo: Bitmap): FloatArray? {
        val (blobs, scale) = findBlobs(photo)
        if (blobs.size < 4) return null
        fun hueIn(b: Blob, from: Float, to: Float) = if (from <= to) b.hue in from..to else (b.hue >= from || b.hue <= to)
        val reds = blobs.filter { hueIn(it, 335f, 18f) && it.sat >= 0.4f && it.value >= 0.25f }
        val greens = blobs.filter { hueIn(it, 95f, 175f) && it.sat >= 0.3f }
        val magentas = blobs.filter { hueIn(it, 285f, 340f) && it.sat >= 0.35f }
        val yellows = blobs.filter { hueIn(it, 35f, 75f) && it.sat >= 0.45f && it.value >= 0.5f }
        val side = minOf(photo.width, photo.height) / scale
        // Anchors: the red patch plus at least two of green (one column right), magenta (one row
        // down) and yellow (both). Each partner gives the grid's column pitch, from which the row
        // pitch follows (a quarter turn, three quarters of the length); the hypothesis that the
        // other partners confirm best wins. Glare may hide one patch, so three anchors are enough.
        fun dist(a: Blob, x: Float, y: Float) = Math.hypot((a.cx - x).toDouble(), (a.cy - y).toDouble()).toFloat()
        var best: Map<Int, Blob>? = null   // key: col + 2 * row within the 2×2 block
        var bestScore = Float.MAX_VALUE
        for (red in reds) {
            val hypotheses = ArrayList<FloatArray>() // column pitch (dx, dy)
            for (green in greens) hypotheses += floatArrayOf(green.cx - red.cx, green.cy - red.cy)
            for (magenta in magentas) { val dx = magenta.cx - red.cx; val dy = magenta.cy - red.cy; hypotheses += floatArrayOf(dy / 0.75f, -dx / 0.75f) }
            for (yellow in yellows) {
                // v = d + R(d)·0.75 with R(dx, dy) = (−dy, dx): v = (dx − 0.75 dy, dy + 0.75 dx).
                val vx = yellow.cx - red.cx; val vy = yellow.cy - red.cy
                val det = 1f + 0.75f * 0.75f
                hypotheses += floatArrayOf((vx + 0.75f * vy) / det, (vy - 0.75f * vx) / det)
            }
            for (d in hypotheses) {
                val len = Math.hypot(d[0].toDouble(), d[1].toDouble()).toFloat()
                if (len < side * 0.04f || len > side * 0.6f) continue
                val downX = -d[1] * 0.75f; val downY = d[0] * 0.75f
                val found = HashMap<Int, Blob>()
                found[0] = red
                var score = 0f
                fun take(key: Int, list: List<Blob>, px: Float, py: Float) {
                    val cand = list.filter { it.area > red.area * 0.4f && it.area < red.area * 2.5f }.minByOrNull { dist(it, px, py) } ?: return
                    val dd = dist(cand, px, py)
                    if (dd < len * 0.3f) { found[key] = cand; score += dd / len }
                }
                take(1, greens, red.cx + d[0], red.cy + d[1])
                take(2, magentas, red.cx + downX, red.cy + downY)
                take(3, yellows, red.cx + d[0] + downX, red.cy + d[1] + downY)
                if (found.size < 3) continue
                // More anchors first, then the tighter fit.
                val total = score / (found.size - 1) - found.size
                if (total < bestScore) { bestScore = total; best = found }
            }
        }
        val anchors = best ?: return null
        val norm = maxOf(photo.width, photo.height).toFloat() / scale
        val red = anchors[0]!!
        // First alignment from the anchors (an affine map when only three are known).
        val srcA = anchors.keys.sorted().map { patchCentre(it % 2, it / 2) }
        val dstA = anchors.keys.sorted().map { doubleArrayOf((anchors[it]!!.cx / norm).toDouble(), (anchors[it]!!.cy / norm).toDouble()) }
        var hm = if (anchors.size == 4) {
            homographyFrom(srcA.flatMap { it.toList() }.toDoubleArray(), dstA.flatMap { it.toList() }.map { it.toFloat() }.toFloatArray())
        } else {
            affineHomography(srcA, dstA)
        }
        // Refinement: every patch found near its predicted centre joins the fit.
        val pitch = run {
            val a = map(hm, patchCentre(0, 0)[0], patchCentre(0, 0)[1]); val b = map(hm, patchCentre(1, 0)[0], patchCentre(1, 0)[1])
            (Math.hypot(a[0] - b[0], a[1] - b[1]) * norm).toFloat()
        }
        val matched = ArrayList<Pair<DoubleArray, Blob>>()
        for (row in 0 until 4) for (col in 0 until 4) {
            val c = patchCentre(col, row)
            val p = map(hm, c[0], c[1])
            val px = (p[0] * norm).toFloat(); val py = (p[1] * norm).toFloat()
            val blob = blobs.filter { it.area > red.area * 0.4f && it.area < red.area * 2.5f }.minByOrNull { dist(it, px, py) } ?: continue
            if (dist(blob, px, py) > pitch * 0.25f) continue
            matched += c to blob
        }
        if (matched.size >= 6) {
            hm = leastSquaresHomography(matched.map { it.first }, matched.map { doubleArrayOf((it.second.cx / norm).toDouble(), (it.second.cy / norm).toDouble()) })
        } else if (anchors.size < 4) {
            return null
        }
        val corners = FloatArray(8)
        for ((idx, c) in listOf(0.0 to 0.0, 1.0 to 0.0, 1.0 to 1.0, 0.0 to 1.0).withIndex()) {
            val p = map(hm, c.first, c.second)
            corners[2 * idx] = (p[0] * norm * scale).toFloat(); corners[2 * idx + 1] = (p[1] * norm * scale).toFloat()
        }
        for (v in corners) if (v.isNaN()) return null
        return if (validateGreys(photo, corners)) corners else null
    }

    /** The grey steps, read with these corners, must run from dark to light in order. */
    private fun validateGreys(photo: Bitmap, corners: FloatArray): Boolean {
        val h = homography(corners)
        val full = IntArray(photo.width * photo.height)
        photo.getPixels(full, 0, photo.width, 0, 0, photo.width, photo.height)
        val stepW = 0.92 / 11
        val steps = (0 until 11).map { i ->
            val p = map(h, 0.04 + (i + 0.5) * stepW, 0.11)
            val c = sample(full, photo.width, photo.height, p[0], p[1])
            (299 * Color.red(c) + 587 * Color.green(c) + 114 * Color.blue(c)) / 1000f
        }
        if (steps[10] < steps[0] * 2.5f) return false
        for (i in 1 until 11) if (steps[i] < steps[i - 1] - 12f) return false
        return true
    }

    /** The affine map through three correspondences, as a homography (h6 = h7 = 0). */
    fun affineHomography(src: List<DoubleArray>, dst: List<DoubleArray>): DoubleArray {
        // Solve [x y 1] · (a b c) = u and (d e f) = v for the three points.
        val m = Array(3) { i -> doubleArrayOf(src[i][0], src[i][1], 1.0, dst[i][0], dst[i][1]) }
        for (col in 0 until 3) {
            var pivot = col
            for (r in col + 1 until 3) if (Math.abs(m[r][col]) > Math.abs(m[pivot][col])) pivot = r
            val tmp = m[col]; m[col] = m[pivot]; m[pivot] = tmp
            val p = m[col][col]
            if (Math.abs(p) < 1e-12) continue
            for (c in col until 5) m[col][c] /= p
            for (r in 0 until 3) if (r != col) {
                val f = m[r][col]
                if (f != 0.0) for (c in col until 5) m[r][c] -= f * m[col][c]
            }
        }
        return doubleArrayOf(m[0][3], m[1][3], m[2][3], m[0][4], m[1][4], m[2][4], 0.0, 0.0, 1.0)
    }

    /** Homography fitting n ≥ 4 correspondences by least squares (normal equations of the DLT with h8 = 1). */
    fun leastSquaresHomography(src: List<DoubleArray>, dst: List<DoubleArray>): DoubleArray {
        val ata = Array(8) { DoubleArray(8) }
        val atb = DoubleArray(8)
        for (i in src.indices) {
            val x = src[i][0]; val y = src[i][1]; val u = dst[i][0]; val v = dst[i][1]
            val rows = listOf(doubleArrayOf(x, y, 1.0, 0.0, 0.0, 0.0, -u * x, -u * y) to u, doubleArrayOf(0.0, 0.0, 0.0, x, y, 1.0, -v * x, -v * y) to v)
            for ((row, rhs) in rows) {
                for (a in 0 until 8) { for (b in 0 until 8) ata[a][b] += row[a] * row[b]; atb[a] += row[a] * rhs }
            }
        }
        // Solve the 8×8 system.
        val m = Array(8) { i -> DoubleArray(9) { j -> if (j < 8) ata[i][j] else atb[i] } }
        for (col in 0 until 8) {
            var pivot = col
            for (r in col + 1 until 8) if (Math.abs(m[r][col]) > Math.abs(m[pivot][col])) pivot = r
            val tmp = m[col]; m[col] = m[pivot]; m[pivot] = tmp
            val p = m[col][col]
            if (Math.abs(p) < 1e-12) continue
            for (c in col until 9) m[col][c] /= p
            for (r in 0 until 8) if (r != col) {
                val f = m[r][col]
                if (f != 0.0) for (c in col until 9) m[r][c] -= f * m[col][c]
            }
        }
        return DoubleArray(9) { if (it < 8) m[it][8] else 1.0 }
    }

    // ------------------------------------------------------------ Printer geometry

    /**
     * Where the grey steps' top (file 4 %) and the gradient's bottom (file 96 %) actually are on
     * the print, in chart coordinates aligned on the patch grid, then the steps' left edge (4 %)
     * and the patches' right edge (94.8 %) when they can be found too: (top, bottom[, left, right]).
     * Null when the rows cannot be measured.
     */
    fun measureGeometry(photo: Bitmap, corners: FloatArray): FloatArray? {
        val h = homography(corners)
        val full = IntArray(photo.width * photo.height)
        photo.getPixels(full, 0, photo.width, 0, 0, photo.width, photo.height)
        fun luma(x: Double, y: Double): Float {
            val p = map(h, x, y)
            val c = sample(full, photo.width, photo.height, p[0], p[1])
            return (299 * Color.red(c) + 587 * Color.green(c) + 114 * Color.blue(c)) / 1000f
        }
        // The chart's own white, between the steps and the patches.
        val white = (0 until 5).map { luma(0.3 + it * 0.1, 0.2) }.sorted()[2]
        if (white < 60f) return null
        val threshold = white * 0.5f
        // From inside the dark element outwards, until the first bright pixel (so a dark table
        // beyond the print cannot be mistaken for content): three columns, the median of them.
        fun scan(xs: List<Double>, from: Double, step: Double, limit: Double): Float? {
            val found = xs.mapNotNull { x ->
                if (luma(x, from) >= threshold) return@mapNotNull null // not dark where the element should be
                var y = from
                var hit: Double? = null
                while (if (step < 0) y >= limit else y <= limit) { if (luma(x, y) >= threshold) { hit = y; break }; y += step }
                hit
            }
            return if (found.size < 2) null else found.sorted()[found.size / 2].toFloat()
        }
        val stepTop = scan(listOf(0.08, 0.11, 0.14), 0.11, -0.0025, -0.12) ?: return null
        val gradientBottom = scan(listOf(0.08, 0.12, 0.16), 0.91, 0.0025, 1.12) ?: return null
        if (stepTop < -0.1f || stepTop > 0.12f || gradientBottom < 0.88f || gradientBottom > 1.1f) return null
        // Columns: the black step's left edge (file 4 %) and the right column of patches' right
        // edge (file 94.8 %), through its dark patches (blue, purple, dark brown).
        fun scanX(ys: List<Double>, from: Double, step: Double, limit: Double, t: Float): Float? {
            val found = ys.mapNotNull { y ->
                if (luma(from, y) >= t) return@mapNotNull null
                var x = from
                var hit: Double? = null
                while (if (step < 0) x >= limit else x <= limit) { if (luma(x, y) >= t) { hit = x; break }; x += step }
                hit
            }
            return if (found.size < 2) null else found.sorted()[found.size / 2].toFloat()
        }
        val stepLeft = scanX(listOf(0.08, 0.11, 0.14), 0.10, -0.0025, -0.12, threshold)
        // The right column's patches are lighter than the black step (purple, brown): a softer threshold, and a start well inside.
        val patchRight = scanX(listOf(0.306, 0.479, 0.651), 0.87, 0.0025, 1.08, white * 0.62f)
        if (stepLeft == null || patchRight == null || stepLeft < -0.1f || stepLeft > 0.12f || patchRight < 0.86f || patchRight > 1.05f) {
            return floatArrayOf(stepTop, gradientBottom)
        }
        return floatArrayOf(stepTop, gradientBottom, stepLeft, patchRight)
    }

    /** Luma of a photo pixel at a file position, through the content homography (no copy of the photo). */
    private fun lumaAtFile(photo: Bitmap, h: DoubleArray, fx: Double, fy: Double): Float {
        val p = map(h, TestChart.fileToContent(fx.toFloat()).toDouble(), TestChart.fileToContent(fy.toFloat()).toDouble())
        val c = photo.getPixel(p[0].toInt().coerceIn(0, photo.width - 1), p[1].toInt().coerceIn(0, photo.height - 1))
        return (299 * Color.red(c) + 587 * Color.green(c) + 114 * Color.blue(c)) / 1000f
    }

    /** Positions along a side clear of the numbers, which sit at 22 %, 32 %, … 92 % along each side. */
    private fun clearOfNumbers(along: Double): Boolean = (1..8).none { Math.abs(along - (0.12 + 0.1 * it)) < 0.025 }

    /**
     * Reads the framing ring: how much of the file the frame hides on each side (top, bottom,
     * left, right), in percent of the file, at a third of the way along the side.
     *
     * Lines sit every 1 % from the file's edge (1 % to 8 %, plus a bold line on the edge itself):
     * counting them outwards from the content, on a stretch of the side clear of the numbers,
     * gives the first whole rectangle, and the hidden width is taken half a percent less (the
     * frame's edge falls somewhere inside that last percent). With the [card] known (its corners
     * in file coordinates), the reading is refined: when the print is a little turned, the next
     * line shows at one end of the side and not at the other, and where it stops is a point of the
     * frame's edge, whose direction is the card's; the hidden width then follows exactly. Null
     * when a side cannot be read.
     */
    fun readRing(photo: Bitmap, corners: FloatArray, card: FloatArray? = null): FloatArray? {
        val h = homography(corners)
        fun lumaFile(fx: Double, fy: Double) = lumaAtFile(photo, h, fx, fy)
        // The first positions clear of the numbers (the top and bottom sides' "8" reaches into the
        // first 11 % of the left and right sides' lines, so nothing before 0.12).
        val clear = listOf(0.14, 0.17, 0.27, 0.37, 0.47)
        // The ring's own white, between the lines and the content, on each side.
        fun white(side: Int): Float {
            val v = clear.map { along ->
                when (side) { 0 -> lumaFile(along, 0.095); 1 -> lumaFile(along, 0.905); 2 -> lumaFile(0.095, along); else -> lumaFile(0.905, along) }
            }.sorted()
            return v[v.size / 2]
        }
        // The lines along one perpendicular, from the content outwards past the file's edge: their
        // positions t (file units in from the file's edge; negative beyond it), innermost first.
        // The thin lines are a pixel or two wide in the photo and the paper between close lines is
        // greyed by their halo, so a line is a dip of the profile (averaged over a short stretch
        // along the line, which is straight in chart coordinates) at least 10 % below the paper on
        // both sides of it, within three quarters of a percent; no fixed threshold would do.
        fun lines(side: Int, along: Double, white: Float): List<Double> {
            val step = 0.0004
            val start = 0.10; val end = -0.03
            val samples = ((start - end) / step).toInt()
            val profile = FloatArray(samples) { i ->
                val t = start - i * step
                var sum = 0f
                for (k in -2..2) {
                    val a = along + k * 0.004
                    sum += when (side) { 0 -> lumaFile(a, t); 1 -> lumaFile(a, 1 - t); 2 -> lumaFile(t, a); else -> lumaFile(1 - t, a) }
                }
                sum / 5
            }
            val reach = (0.0075 / step).toInt()
            // Dips: local minima (over ±3 samples) with enough prominence, merged when within 0.4 %.
            val dips = ArrayList<Double>()
            var i = 3
            while (i < samples - 3) {
                val v = profile[i]
                if (v < white * 0.5f) {
                    // Off the print (a dark table, a shadow) when the next 1.2 % is dark throughout: a line never is.
                    var darkRun = 0
                    for (j in i until Math.min(samples, i + 30)) if (profile[j] < white * 0.5f) darkRun++
                    if (darkRun >= 28) break
                }
                var isMin = true
                for (j in i - 3..i + 3) if (profile[j] < v) { isMin = false; break }
                if (isMin) {
                    var left = v; var right = v
                    for (j in Math.max(0, i - reach) until i) if (profile[j] > left) left = profile[j]
                    for (j in i + 1..Math.min(samples - 1, i + reach)) if (profile[j] > right) right = profile[j]
                    val paper = Math.min(left, right)
                    if (v < paper * 0.9f) {
                        // The centre of the flat bottom of this dip.
                        var a = i; var b = i
                        while (a > 0 && profile[a - 1] <= v + (paper - v) * 0.15f) a--
                        while (b < samples - 1 && profile[b + 1] <= v + (paper - v) * 0.15f) b++
                        val centre = start - (a + b) / 2.0 * step
                        if (dips.isEmpty() || dips.last() - centre > 0.004) dips += centre
                        i = b
                    }
                }
                i++
            }
            // Each line is about 1 % further out than the previous one (half that for the edge line);
            // the first sits 3 % beyond the content, more where the printer stretches.
            var lastLine = 0.11
            var minGap = 0.015
            var maxGap = 0.075
            val found = ArrayList<Double>()
            for (centre in dips) {
                val gap = lastLine - centre
                if (gap > minGap && gap < maxGap) { found += centre; lastLine = centre; minGap = 0.0045; maxGap = 0.017 }
            }
            return found
        }
        // How the frame's edge runs along each side, as dt/dalong, from the card's edges (parallel to it).
        fun edgeSlope(side: Int): Double {
            val c = card ?: return 0.0
            return when (side) {
                0 -> (c[3] - c[1]) / (c[2] - c[0]).toDouble()
                1 -> -(c[5] - c[7]) / (c[4] - c[6]).toDouble()
                2 -> (c[6] - c[0]) / (c[7] - c[1]).toDouble()
                else -> -(c[4] - c[2]) / (c[5] - c[3]).toDouble()
            }
        }
        val out = FloatArray(4)
        for (side in 0 until 4) {
            val w = white(side)
            if (w < 60f) return null
            val counts = clear.map { lines(side, it, w).size }.sorted()
            val n = counts[counts.size / 2]
            // n lines seen: the first whole rectangle is 9 - n (9 lines when the edge line shows).
            out[side] = if (n >= 9) 0f else Math.max(0f, (8 - n) + 0.5f)
            if (card == null || n >= 9) continue
            // Refinement: along the whole side, does the next line show at one end only?
            val alongs = (11..89).map { it / 100.0 }.filter { clearOfNumbers(it) }
            val seen = alongs.map { lines(side, it, w) }
            val ns = seen.map { it.size }
            val lo = ns.min(); val hi = ns.max()
            if (hi != lo + 1) continue
            // The best split into a run with hi lines and a run with lo lines, either way round.
            var bestSplit = -1; var bestErrors = Int.MAX_VALUE; var hiFirst = true
            for (split in 3..alongs.size - 3) { // at least three positions on each side of the crossing
                val e1 = (0 until split).count { ns[it] != hi } + (split until alongs.size).count { ns[it] != lo }
                val e2 = (0 until split).count { ns[it] != lo } + (split until alongs.size).count { ns[it] != hi }
                if (e1 < bestErrors) { bestErrors = e1; bestSplit = split; hiFirst = true }
                if (e2 < bestErrors) { bestErrors = e2; bestSplit = split; hiFirst = false }
            }
            if (bestSplit < 0 || bestErrors > alongs.size / 8) continue
            val crossing = (alongs[bestSplit - 1] + alongs[bestSplit]) / 2
            // The line that stops there: its position, the median where it shows.
            val positions = seen.filter { it.size == hi }.map { it.last() }.sorted()
            val lineT = positions[positions.size / 2]
            val slope = edgeSlope(side)
            // The edge hides the line where it runs further in than the line does: on the side of the
            // crossing where the slope takes it inwards. Anything else is noise, not a crossing.
            val hiddenAfter = slope > 0
            if (hiddenAfter != hiFirst || Math.abs(slope) < 1e-4) continue
            val hidden = (lineT + slope * (0.3 - crossing)) * 100
            // Within the percent the coarse count allowed, give or take the line's own thickness.
            if (Math.abs(hidden - ((8 - lo) + 0.5)) <= 1.2) out[side] = Math.max(0f, hidden.toFloat())
        }
        return out
    }

    /**
     * Where the card's four edges are, in file coordinates: its corners (top-left, top-right,
     * bottom-right, bottom-left), flat. The card is a true rectangle, so where its edges fall in
     * the chart's own frame says how the printer rotated, scaled and skewed the file on the paper.
     * Each edge is found on nine lines across its side as the first place beyond the file's edge
     * where the paper ends (a run of 1.5 % that differs from the paper just inside it), a line is
     * fitted through them with the outliers dropped, and the corners are the intersections. Null
     * when an edge cannot be found or is implausible (the background looked like paper).
     */
    fun measureCard(photo: Bitmap, corners: FloatArray): FloatArray? {
        val h = homography(corners)
        fun rgb(fx: Double, fy: Double): Int {
            val p = map(h, TestChart.fileToContent(fx.toFloat()).toDouble(), TestChart.fileToContent(fy.toFloat()).toDouble())
            return photo.getPixel(p[0].toInt().coerceIn(0, photo.width - 1), p[1].toInt().coerceIn(0, photo.height - 1))
        }
        fun luma(c: Int) = (299 * Color.red(c) + 587 * Color.green(c) + 114 * Color.blue(c)) / 1000f
        val white = (0 until 9).map { luma(rgb(0.1 + 0.1 * it, 0.095)) }.sorted()[4]
        if (white < 60f) return null
        fun distance(a: Int, b: Int) = maxOf(Math.abs(Color.red(a) - Color.red(b)), Math.abs(Color.green(a) - Color.green(b)), Math.abs(Color.blue(a) - Color.blue(b)))
        // The edge on one perpendicular: outward from 3 % beyond the file's edge.
        fun edge(side: Int, along: Double, limit: Double): Double? {
            fun at(t: Double) = when (side) { 0 -> rgb(along, t); 1 -> rgb(along, 1 - t); 2 -> rgb(t, along); else -> rgb(1 - t, along) }
            var t = -0.03
            while (t > limit) {
                // The paper just inside: the brightest of the previous 2 % (a ring line may sit there).
                var paper = at(t + 0.004)
                for (k in 2..6) { val c = at(t + 0.004 * k); if (luma(c) > luma(paper)) paper = c }
                var ends = true
                var u = t
                while (u > t - 0.015) {
                    if (distance(at(u), paper) < white * 0.12f) { ends = false; break }
                    u -= 0.001
                }
                if (ends) return t
                t -= 0.0005
            }
            return null
        }
        val alongs = (1..9).map { it / 10.0 }
        // Each side's edge as a line t = a + b·along, with the outliers (more than 0.4 % off) dropped.
        fun fitSide(side: Int): DoubleArray? {
            val limit = if (side == 1) -0.45 else -0.2
            var pts = alongs.mapNotNull { al -> edge(side, al, limit)?.let { al to it } }
            var line = doubleArrayOf(0.0, 0.0)
            for (pass in 0 until 2) {
                if (pts.size < 6) return null
                val mx = pts.map { it.first }.average(); val my = pts.map { it.second }.average()
                val b = pts.sumOf { (it.first - mx) * (it.second - my) } / pts.sumOf { (it.first - mx) * (it.first - mx) }
                line = doubleArrayOf(my - b * mx, b)
                pts = pts.filter { Math.abs(it.second - (line[0] + line[1] * it.first)) <= 0.004 }
            }
            if (pts.size < 6) return null
            val middle = line[0] + line[1] * 0.5
            val plausible = if (side == 1) middle in -0.45..-0.15 else middle in -0.2..-0.02
            return if (plausible) line else null
        }
        val lines = Array(4) { fitSide(it) }
        if (lines.any { it == null }) return null
        // Lines in file coordinates: top y = a0 + b0 x, bottom y = 1 - (a1 + b1 x), left x = a2 + b2 y, right x = 1 - (a3 + b3 y).
        fun corner(horizontal: DoubleArray, flipH: Boolean, vertical: DoubleArray, flipV: Boolean): DoubleArray {
            // Iterate: x from the vertical line at the current y, y from the horizontal line at that x.
            var x = 0.5; var y = 0.5
            for (i in 0 until 8) {
                val vx = vertical[0] + vertical[1] * y
                x = if (flipV) 1 - vx else vx
                val hy = horizontal[0] + horizontal[1] * x
                y = if (flipH) 1 - hy else hy
            }
            return doubleArrayOf(x, y)
        }
        val top = lines[0]!!; val bottom = lines[1]!!; val left = lines[2]!!; val right = lines[3]!!
        val tl = corner(top, false, left, false); val tr = corner(top, false, right, true)
        val br = corner(bottom, true, right, true); val bl = corner(bottom, true, left, false)
        return floatArrayOf(tl[0].toFloat(), tl[1].toFloat(), tr[0].toFloat(), tr[1].toFloat(), br[0].toFloat(), br[1].toFloat(), bl[0].toFloat(), bl[1].toFloat())
    }

    /** Inverse of a 3×3 homography. */
    fun invert(h: DoubleArray): DoubleArray {
        val a = h[0]; val b = h[1]; val c = h[2]; val d = h[3]; val e = h[4]; val f = h[5]; val g = h[6]; val hh = h[7]; val i = h[8]
        val det = a * (e * i - f * hh) - b * (d * i - f * g) + c * (d * hh - e * g)
        val inv = doubleArrayOf(
            (e * i - f * hh) / det, (c * hh - b * i) / det, (b * f - c * e) / det,
            (f * g - d * i) / det, (a * i - c * g) / det, (c * d - a * f) / det,
            (d * hh - e * g) / det, (b * g - a * hh) / det, (a * e - b * d) / det,
        )
        return inv
    }

    // ------------------------------------------------------------ Anchors: the four corner patches

    /** Grid positions (column, row) of the four anchor patches: red, cyan, grey, sky blue. */
    val anchorCells = listOf(0 to 0, 3 to 0, 3 to 3, 0 to 3)

    /** The chart coordinates of the four anchor patches' centres, flat (x0, y0, …). */
    fun anchorChartPoints(): DoubleArray = anchorCells.flatMap { (c, r) -> patchCentre(c, r).toList() }.toDoubleArray()

    /**
     * The image area's corners from the four anchor patch centres in the photo (red top-left, cyan
     * top-right, grey bottom-right, sky blue bottom-left): the patches are easy to aim at and sit
     * well inside the print, unlike the image's edges, which the printer partly hides and stretches.
     */
    fun cornersFromAnchors(anchors: FloatArray): FloatArray {
        val h = homographyFrom(anchorChartPoints(), anchors)
        val out = FloatArray(8)
        for ((k, c) in listOf(0.0 to 0.0, 1.0 to 0.0, 1.0 to 1.0, 0.0 to 1.0).withIndex()) {
            val p = map(h, c.first, c.second)
            out[2 * k] = p[0].toFloat(); out[2 * k + 1] = p[1].toFloat()
        }
        return out
    }

    /** The anchor patch centres in the photo for given image corners (the inverse of [cornersFromAnchors]). */
    fun anchorsFromCorners(corners: FloatArray): FloatArray {
        val h = homography(corners)
        val pts = anchorChartPoints()
        val out = FloatArray(8)
        for (k in 0 until 4) {
            val p = map(h, pts[2 * k], pts[2 * k + 1])
            out[2 * k] = p[0].toFloat(); out[2 * k + 1] = p[1].toFloat()
        }
        return out
    }

    /** The uniform blobs of a photo, in photo pixels (centre x, y, and the side of a square of the same area). */
    fun patchCandidates(photo: Bitmap): List<FloatArray> {
        val (blobs, scale) = findBlobs(photo)
        return blobs.map { floatArrayOf(it.cx * scale, it.cy * scale, Math.sqrt(it.area.toDouble()).toFloat() * scale) }
    }

    /**
     * Magnet for a released anchor: the centre of the uniform patch under or next to the finger,
     * when there is one of a plausible size; otherwise null and the marker stays where it is.
     */
    fun snapToPatch(candidates: List<FloatArray>, x: Float, y: Float, photoSide: Int): FloatArray? {
        val best = candidates.filter { it[2] > photoSide * 0.015f && it[2] < photoSide * 0.25f }
            .minByOrNull { Math.hypot((it[0] - x).toDouble(), (it[1] - y).toDouble()) } ?: return null
        val d = Math.hypot((best[0] - x).toDouble(), (best[1] - y).toDouble())
        return if (d < best[2] * 0.9) floatArrayOf(best[0], best[1]) else null
    }

    /** Solve the homography that maps four source points (x0,y0,…) to four destination points. */
    fun homographyFrom(src: DoubleArray, dst: FloatArray): DoubleArray {
        val a = Array(8) { DoubleArray(9) }
        for (i in 0 until 4) {
            val x = src[2 * i]; val y = src[2 * i + 1]
            val u = dst[2 * i].toDouble(); val v = dst[2 * i + 1].toDouble()
            a[2 * i] = doubleArrayOf(x, y, 1.0, 0.0, 0.0, 0.0, -u * x, -u * y, u)
            a[2 * i + 1] = doubleArrayOf(0.0, 0.0, 0.0, x, y, 1.0, -v * x, -v * y, v)
        }
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

    /** A fitted model with its errors, in percent of paper white (RMS over the steps or patches, per channel). */
    class Fit(val model: FilmModel, val greyRms: Double, val colourRmsBefore: Double, val colourRmsAfter: Double) {
        operator fun component1() = model
        operator fun component2() = report
        val report: String get() = "RMS %.1f %%".format(greyRms)
    }

    /** RMS distance, in percent, between the patches as the model simulates them and as measured. */
    private fun colourRms(m: FilmModel, reading: Reading): Double {
        if (reading.patches.size != TestChart.patches.size) return 0.0
        var err = 0.0
        for ((i, src) in TestChart.patches.withIndex()) {
            val px = intArrayOf(src)
            m.simulate(px)
            val meas = reading.patches[i]
            val dr = Color.red(px[0]) / 255.0 - meas[0]
            val dg = Color.green(px[0]) / 255.0 - meas[1]
            val db = Color.blue(px[0]) / 255.0 - meas[2]
            err += (dr * dr + dg * dg + db * db) / 3
        }
        return Math.sqrt(err / TestChart.patches.size) * 100
    }

    /**
     * Fit the six-sector hue tables: for each patch, the hue rotation, the saturation ratio and the
     * brightness ratio between the measured colour and the model's prediction, accumulated into the
     * two sectors around the patch's source hue, weighted by its chroma. Several passes, since the
     * correction is applied at the input and the tone curves bend it.
     */
    private fun fitHues(m: FilmModel, reading: Reading) {
        m.resetHues()
        val hsvP = FloatArray(3); val hsvM = FloatArray(3); val hsvS = FloatArray(3)
        repeat(PASSES) {
            val shiftSum = FloatArray(FilmModel.HUES); val gainSum = FloatArray(FilmModel.HUES); val valueSum = FloatArray(FilmModel.HUES); val weight = FloatArray(FilmModel.HUES)
            for ((i, src) in TestChart.patches.withIndex()) {
                val px = intArrayOf(src)
                m.simulate(px)
                FilmModel.rgbToHsv(Color.red(px[0]), Color.green(px[0]), Color.blue(px[0]), hsvP)
                val meas = reading.patches[i]
                FilmModel.rgbToHsv(Math.round(meas[0].coerceIn(0f, 1f) * 255), Math.round(meas[1].coerceIn(0f, 1f) * 255), Math.round(meas[2].coerceIn(0f, 1f) * 255), hsvM)
                FilmModel.rgbToHsv(Color.red(src), Color.green(src), Color.blue(src), hsvS)
                // Neutral or near-neutral patches say nothing reliable about hue.
                if (hsvS[1] < 0.25f || hsvP[1] < 0.08f || hsvM[1] < 0.05f) continue
                var dh = hsvM[0] - hsvP[0]
                if (dh > 180f) dh -= 360f
                if (dh < -180f) dh += 360f
                val logGain = Math.log((hsvM[1] / hsvP[1]).toDouble()).toFloat()
                // Brightness: the ratio is attributed to the hue in proportion to the source saturation.
                val ratio = (hsvM[2] / hsvP[2].coerceAtLeast(0.05f)).coerceIn(0.2f, 2f)
                val logValue = Math.log((1f - (1f - ratio) / hsvS[1]).coerceIn(0.25f, 2f).toDouble()).toFloat()
                val w = minOf(hsvP[1], hsvM[1]) * minOf(hsvP[2], hsvM[2])
                val pos = hsvS[0] / 60f
                val i0 = pos.toInt() % FilmModel.HUES
                val i1 = (i0 + 1) % FilmModel.HUES
                val t = pos - pos.toInt()
                for ((k, wk) in listOf(i0 to w * (1f - t), i1 to w * t)) {
                    shiftSum[k] += dh * wk; gainSum[k] += logGain * wk; valueSum[k] += logValue * wk; weight[k] += wk
                }
            }
            for (k in 0 until FilmModel.HUES) {
                if (weight[k] < 0.05f) continue
                m.hueShift[k] = (m.hueShift[k] + shiftSum[k] / weight[k]).coerceIn(-40f, 40f)
                m.hueGain[k] = (m.hueGain[k] * Math.exp((gainSum[k] / weight[k]).toDouble()).toFloat()).coerceIn(0.4f, 1.8f)
                m.hueValue[k] = (m.hueValue[k] * Math.exp((valueSum[k] / weight[k]).toDouble()).toFloat()).coerceIn(0.3f, 1.5f)
            }
        }
        // The gains express deviations from the global saturation: fold their common part into it.
        val mean = Math.exp(m.hueGain.sumOf { Math.log(it.toDouble()) } / FilmModel.HUES).toFloat()
        if (mean > 0.5f && mean < 2f) {
            m.saturation = (m.saturation * mean).coerceIn(0.4f, 1.5f)
            for (k in 0 until FilmModel.HUES) m.hueGain[k] = (m.hueGain[k] / mean).coerceIn(0.4f, 1.8f)
        }
    }

    /**
     * Fit the film model to a reading: tones from the grey steps, then the global colour terms,
     * then the per-hue table from the colour patches.
     */
    fun fit(reading: Reading, type: FilmType): Fit {
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
        val greyRms = Math.sqrt(err / n) * 100

        val colourBefore = colourRms(m, reading)
        var colourAfter = colourBefore
        if (!type.monochrome && reading.patches.size == 16) {
            // The hue tables cover what the blue-darkening term did, so it is dropped while they are fitted.
            val blue = m.blueDarkening
            m.blueDarkening = 0f
            fitHues(m, reading)
            colourAfter = colourRms(m, reading)
            // Keep the tables only if they actually help.
            if (colourAfter >= colourBefore) { m.resetHues(); m.blueDarkening = blue; colourAfter = colourBefore }
        }
        return Fit(m, greyRms, colourBefore, colourAfter)
    }
}
