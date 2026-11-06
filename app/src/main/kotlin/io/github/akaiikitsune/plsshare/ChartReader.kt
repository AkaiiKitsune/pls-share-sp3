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
     * Find the image area of the printed chart in a photo, without any tap: on the colour chart
     * from its grid of patches ([detectFromGrid]), otherwise from the corner marks ([detectFromMarks]).
     */
    fun detectCorners(photo: Bitmap, type: FilmType): FloatArray? =
        (if (type.monochrome) null else runCatching { detectFromGrid(photo) }.getOrNull()) ?: detectFromMarks(photo)

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
        val margin = 0.04; val cell = 0.92 / 4; val gridTop = margin + 0.14 + 0.04
        return doubleArrayOf(margin + (col + 0.5) * cell, gridTop + (row + 0.5) * cell * 0.75)
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

    // ------------------------------------------------------------ Detection from the corner marks

    /**
     * Find the image area from the four corner marks.
     *
     * The print is the bright region; everything dark that it encloses (the grey steps, the
     * patches, the gradient, the corner marks) forms "holes" in it. The extreme hole pixels in the
     * four diagonal directions are the outer corners of the corner marks, printed a little inside
     * the image area (the frame hides its very edge); their known positions align the whole chart.
     * Returns null when no convincing chart is found there (a chart printed before the marks
     * existed, a dark object on the table, glare): the user then taps the corners.
     */
    fun detectFromMarks(photo: Bitmap): FloatArray? {
        val scale = maxOf(1, maxOf(photo.width, photo.height) / 500)
        val w = photo.width / scale
        val h = photo.height / scale
        if (w < 40 || h < 40) return null
        val full = IntArray(photo.width * photo.height)
        photo.getPixels(full, 0, photo.width, 0, 0, photo.width, photo.height)
        val luma = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val c = full[(y * scale + scale / 2) * photo.width + x * scale + scale / 2]
            luma[y * w + x] = (299 * Color.red(c) + 587 * Color.green(c) + 114 * Color.blue(c)) / 1000
        }
        // Paper white: the 95th percentile of brightness.
        val hist = IntArray(256)
        for (v in luma) hist[v]++
        var acc = 0; var paper = 255
        for (v in 0..255) { acc += hist[v]; if (acc >= luma.size * 0.95) { paper = v; break } }
        val bright = BooleanArray(w * h) { luma[it] > paper * 0.5f }
        // Flood from the borders over the non-bright pixels: that is the outside (table, shadows).
        val outside = BooleanArray(w * h)
        val queue = IntArray(w * h)
        var head = 0; var tail = 0
        fun push(i: Int) { if (!bright[i] && !outside[i]) { outside[i] = true; queue[tail++] = i } }
        for (x in 0 until w) { push(x); push((h - 1) * w + x) }
        for (y in 0 until h) { push(y * w); push(y * w + w - 1) }
        while (head < tail) {
            val i = queue[head++]
            val x = i % w; val y = i / w
            if (x > 0) push(i - 1); if (x < w - 1) push(i + 1); if (y > 0) push(i - w); if (y < h - 1) push(i + w)
        }
        // Holes: dark pixels enclosed by the print, in components big enough not to be dust.
        val label = IntArray(w * h)
        var next = 0
        val sizes = ArrayList<Int>()
        for (start in 0 until w * h) {
            if (bright[start] || outside[start] || label[start] != 0) continue
            next++
            label[start] = next
            head = 0; tail = 0; queue[tail++] = start
            var count = 0
            while (head < tail) {
                val i = queue[head++]; count++
                val x = i % w; val y = i / w
                for (j in intArrayOf(if (x > 0) i - 1 else -1, if (x < w - 1) i + 1 else -1, if (y > 0) i - w else -1, if (y < h - 1) i + w else -1)) {
                    if (j >= 0 && !bright[j] && !outside[j] && label[j] == 0) { label[j] = next; queue[tail++] = j }
                }
            }
            sizes += count
        }
        // Dust is a few pixels at this scale; the corner marks are small too, so the bar is low.
        val minSize = maxOf(4, w * h / 40000)
        var tl = -1; var tr = -1; var br = -1; var bl = -1
        var sTl = Int.MAX_VALUE; var sTr = Int.MIN_VALUE; var sBr = Int.MIN_VALUE; var sBl = Int.MAX_VALUE
        for (i in 0 until w * h) {
            val l = label[i]
            if (l == 0 || sizes[l - 1] < minSize) continue
            val x = i % w; val y = i / w
            if (x + y < sTl) { sTl = x + y; tl = i }
            if (x - y > sTr) { sTr = x - y; tr = i }
            if (x + y > sBr) { sBr = x + y; br = i }
            if (x - y < sBl) { sBl = x - y; bl = i }
        }
        if (tl < 0) return null
        fun pt(i: Int, dx: Float, dy: Float) = floatArrayOf(((i % w) + dx) * scale, ((i / w) + dy) * scale)
        // Outer corners of the extreme pixels.
        val quad = floatArrayOf(
            pt(tl, 0f, 0f)[0], pt(tl, 0f, 0f)[1], pt(tr, 1f, 0f)[0], pt(tr, 1f, 0f)[1],
            pt(br, 1f, 1f)[0], pt(br, 1f, 1f)[1], pt(bl, 0f, 1f)[0], pt(bl, 0f, 1f)[1],
        )
        val side = minOf(w, h) * scale
        for (k in 0 until 4) {
            val n = (k + 1) % 4
            val len = Math.hypot((quad[2 * n] - quad[2 * k]).toDouble(), (quad[2 * n + 1] - quad[2 * k + 1]).toDouble())
            if (len < side * 0.15) return null
        }
        // The quad joins the outer corners of the four marks, which sit at known positions inside
        // the image area: the homography from those positions gives the whole chart's alignment.
        val k = TestChart.FIDUCIAL_INSET.toDouble()
        val known = doubleArrayOf(k, k, 1 - k, k, 1 - k, 1 - k, k, 1 - k)
        val hk = homographyFrom(known, quad)
        fun lumaAt(x: Double, y: Double): Float {
            val p = map(hk, x, y)
            val c = sample(full, photo.width, photo.height, p[0], p[1])
            return (299 * Color.red(c) + 587 * Color.green(c) + 114 * Color.blue(c)) / 1000f / paper
        }
        // Validation: the four marks read dark, and the grey steps run from dark to light in order,
        // which a dark object on the table or a chart printed without marks will not satisfy.
        val fc = (TestChart.FIDUCIAL_INSET + TestChart.FIDUCIAL / 2).toDouble()
        val marks = listOf(fc to fc, 1 - fc to fc, 1 - fc to 1 - fc, fc to 1 - fc).count { (x, y) -> lumaAt(x, y) < 0.5f }
        if (marks != 4) return null
        val stepW = 0.92 / 11
        val steps = (0 until 11).map { i -> lumaAt(0.04 + (i + 0.5) * stepW, 0.11) }
        if (steps[0] > 0.45f || steps[10] < 0.8f) return null
        for (i in 1 until 11) if (steps[i] < steps[i - 1] - 0.04f) return null
        val corners = FloatArray(8)
        for ((idx, c) in listOf(0.0 to 0.0, 1.0 to 0.0, 1.0 to 1.0, 0.0 to 1.0).withIndex()) {
            val p = map(hk, c.first, c.second)
            corners[2 * idx] = p[0].toFloat(); corners[2 * idx + 1] = p[1].toFloat()
        }
        return corners
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
