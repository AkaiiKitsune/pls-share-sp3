package io.github.akaiikitsune.plsshare

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [31])
class GeometryTest {
    private fun load(name: String) = javaClass.getResourceAsStream("/$name.jpg")!!.use { BitmapFactory.decodeStream(it)!! }

    @Test
    fun `the geometry is measured consistently on every real photo`() {
        val results = ArrayList<FloatArray>()
        for (name in listOf("chart-bamboo", "chart-fabric", "chart-stickers", "chart-wood-tilted", "chart-wood", "chart-daylight", "chart-flash")) {
            val photo = load(name)
            val corners = ChartReader.detectCorners(photo, FilmType.COLOR)!!
            val g = ChartReader.measureGeometry(photo, corners)
            println("$name: " + (g?.joinToString { "%.3f".format(it) } ?: "null"))
            assertNotNull("$name measured", g)
            assertEquals("$name measured all four edges", 4, g!!.size)
            results += g
        }
        // The same print every time: the measurements must agree within a couple of percent.
        for (c in 0 until 4) {
            val values = results.map { it[c] }
            assertTrue("spread of edge $c: ${values.max() - values.min()}", values.max() - values.min() < 0.05f)
        }
        // Columns match the chart within a couple of percent (content coordinates).
        assertEquals(0.04f, results.map { it[2] }.average().toFloat(), 0.025f)
        assertEquals(0.948f, results.map { it[3] }.average().toFloat(), 0.025f)
        // Through set(), the geometry's stretch is the content's.
        val g = PrinterGeometry().apply { set(results[0]) }
        assertEquals((results[0][1] - results[0][0]) / 0.92f, g.stretch, 1e-3f)
        // And no stretch to speak of: the lines land where the file puts them (the top of the old
        // chart was cut by the frame on two photos, so the median).
        assertEquals(0.96f, results.map { it[1] }.average().toFloat(), 0.01f)
        assertEquals(0.04f, results.map { it[0] }.sorted()[results.size / 2], 0.01f)
    }

    /** The file square rotated by [degrees] on a 72 × 86 card, as card corners in file coordinates (a similarity). */
    private fun cardFor(degrees: Double, sideMm: Float, cx: Float, cy: Float): FloatArray {
        val t = Math.toRadians(-degrees)
        val cos = Math.cos(t); val sin = Math.sin(t)
        val out = FloatArray(8)
        for ((i, c) in listOf(0f to 0f, 72f to 0f, 72f to 86f, 0f to 86f).withIndex()) {
            val dx = c.first - cx; val dy = c.second - cy
            out[2 * i] = (0.5 + (cos * dx - sin * dy) / sideMm).toFloat()
            out[2 * i + 1] = (0.5 + (sin * dx + cos * dy) / sideMm).toFloat()
        }
        return out
    }

    @Test
    fun `with the card and the cuts, prints are placed whole and straight in the window`() {
        // The file printed 64 mm wide, turned 1.5° clockwise, centred 36 mm from the left and 37 mm from the top.
        val g = PrinterGeometry().apply { card = cardFor(1.5, 64f, 36f, 37f) }
        assertEquals(1.5f, g.rotationDegrees, 0.02f)
        assertEquals(0f, g.keystone, 0.002f)
        assertEquals(64f, g.printedSizeMm[0], 0.1f); assertEquals(64f, g.printedSizeMm[1], 0.1f)
        assertTrue(g.measured)
        assertTrue(!g.placed) // no cuts: the window is unknown
        g.cutTop = 0.03f; g.cutBottom = 0.005f; g.cutLeft = 0.015f; g.cutRight = 0.005f
        assertTrue(g.placed)
        val target = g.targetMm!!
        assertEquals(64f * (1f - (0.02f + 0.035f) / 2), target[2], 0.3f) // the window's mean side (2 % cut across, 3.5 % down)
        assertEquals(0f, g.hiddenTop, 0f); assertEquals(0f, g.hiddenLeft, 0f) // nothing hidden once placed
        // Four quadrants: after the pre-warp, the file pixel that lands at a quadrant's centre in the window holds that quadrant.
        val image = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        val colours = intArrayOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW)
        image.setPixels(IntArray(200 * 200) { colours[(it % 200) / 100 + 2 * (it / 200 / 100)] }, 0, 200, 0, 0, 200, 200)
        val warped = g.prewarp(image)
        val t = Math.toRadians(-1.5)
        for ((i, q) in listOf(0.25f to 0.25f, 0.75f to 0.25f, 0.25f to 0.75f, 0.75f to 0.75f).withIndex()) {
            // Window millimetres of the quadrant's centre, then back to the file position printed there.
            val mx = target[0] + (q.first - 0.5f) * target[2]; val my = target[1] + (q.second - 0.5f) * target[2]
            val dx = mx - 36f; val dy = my - 37f
            val fx = 0.5 + (Math.cos(t) * dx - Math.sin(t) * dy) / 64f; val fy = 0.5 + (Math.sin(t) * dx + Math.cos(t) * dy) / 64f
            assertEquals("quadrant $i", colours[i], warped.getPixel(Math.round(fx * 199).toInt(), Math.round(fy * 199).toInt()))
        }
        // Off: nothing placed, the cuts show as bands again.
        g.compensate = false
        assertEquals(0.03f, g.hiddenTop, 1e-4f)
        assertTrue(g.prewarp(image) === image)
        // Saved and loaded back.
        val text = PrinterGeometry.cardText(g.card!!)
        val back = PrinterGeometry.parseCard(text)!!
        for (i in 0 until 8) assertEquals(g.card!![i], back[i], 1e-4f)
    }

    @Test
    fun `a rotation entered by hand turns the print, with or without the card`() {
        // Without the card: a 2° trim alone pre-warps; the top-right corner's pixel comes from further anticlockwise in the image.
        val image = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        image.setPixels(IntArray(200 * 200) { Color.rgb(it % 200, it / 200, 0) }, 0, 200, 0, 0, 200, 200) // x in red, y in green
        val g = PrinterGeometry().apply { rotation = 2f }
        assertTrue(g.measured && g.rotationByHand)
        assertEquals(2f, g.rotation, 0f)
        val warped = g.prewarp(image)
        val p = warped.getPixel(150, 50) // file (0.75, 0.25)
        val t = Math.toRadians(2.0)
        val ex = (Math.cos(t) * 0.25 - Math.sin(t) * -0.25 + 0.5) * 199; val ey = (Math.sin(t) * 0.25 + Math.cos(t) * -0.25 + 0.5) * 199
        assertEquals(ex, Color.red(p).toDouble(), 1.5); assertEquals(ey, Color.green(p).toDouble(), 1.5)
        // With the card: the trim adds to the measured rotation, so a trim that cancels it prints the file as if it were straight.
        val placed = PrinterGeometry().apply { card = cardFor(1.5, 64f, 36f, 37f); cutTop = 0.02f; cutBottom = 0.02f; cutLeft = 0.02f; cutRight = 0.02f }
        assertEquals(1.5f, placed.rotation, 0.02f) // the slider starts on the measured value
        placed.rotation = 0f // set by hand to nothing: the file is taken to land straight
        val straight = placed.prewarp(image)
        val centre = straight.getPixel(100, 100)
        assertEquals(100f, Color.red(centre).toFloat(), 2f); assertEquals(100f, Color.green(centre).toFloat(), 2f)
        // Rows stay rows: two pixels on the same file row come from the same image row (no residual rotation).
        assertEquals(Color.green(straight.getPixel(40, 100)).toFloat(), Color.green(straight.getPixel(160, 100)).toFloat(), 1f)
    }

    @Test
    fun `the card's edges are measured on a synthetic print, turned and all`() {
        // The chart printed 1000 px wide on a 1125 × 1344 px card (72 × 86 mm at 15.6 px/mm), turned 1° clockwise.
        val chart = TestChart.render(1000, FilmType.COLOR)
        val photo = Bitmap.createBitmap(1400, 1700, Bitmap.Config.ARGB_8888)
        Canvas(photo).apply {
            drawColor(Color.rgb(90, 70, 50))
            drawRect(137f, 150f, 137f + 1125f, 150f + 1344f, android.graphics.Paint().apply { color = Color.WHITE })
            save(); rotate(1f, 137f + 1125f / 2, 150f + 1344f / 2); drawBitmap(chart, 200f, 220f, null); restore()
        }
        val corners = ChartReader.detectCorners(photo, FilmType.COLOR)!!
        val card = ChartReader.measureCard(photo, corners)!!
        val g = PrinterGeometry().apply { this.card = card }
        assertEquals(1f, g.rotationDegrees, 0.15f)
        assertEquals(0f, g.keystone, 0.006f)
        assertEquals(1000f / 1125f * 72f, g.printedSizeMm[0], 0.5f)
        assertEquals(1000f / 1125f * 72f, g.printedSizeMm[1], 0.5f)
    }

    @Test
    fun `an unmeasured geometry changes nothing, a measured one pre-warps rows`() {
        val image = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        val px = IntArray(64 * 64) { Color.rgb(it / 64 * 4, 0, 0) } // row index encoded in red
        image.setPixels(px, 0, 64, 0, 0, 64, 64)
        val none = PrinterGeometry()
        assertTrue(!none.measured)
        assertTrue(none.prewarp(image) === image)
        assertEquals(0f, none.hiddenTop, 0f); assertEquals(0f, none.hiddenBottom, 0f)

        val g = PrinterGeometry().apply { set(floatArrayOf(0.01f, 0.98f)) }
        assertTrue(g.measured)
        assertTrue("stretch %.3f".format(g.stretch), g.stretch > 1.04f && g.stretch < 1.07f)
        val warped = g.prewarp(image)
        // File row f holds the image row at toPrint(f): near the top it reaches rows above (clamped), lower rows are pulled down.
        val mid = Color.red(warped.getPixel(0, 32)) / 4f
        assertEquals(g.toPrint(32 / 63f) * 63f, mid, 1.5f)
        // No cuts entered: nothing hidden, whatever the stretch.
        assertEquals(0f, g.hiddenBottom, 0f)
        // Cuts read on the framing chart are the hidden bands; with the pre-warp they move through the map.
        g.cutTop = 0.04f; g.cutBottom = 0.03f; g.cutLeft = 0.05f; g.cutRight = 0.01f
        assertTrue(g.cutsKnown)
        assertEquals(g.toPrint(0.04f), g.hiddenTop, 1e-4f)
        assertEquals(0.05f, g.hiddenLeft, 1e-4f) // no horizontal stretch
        g.compensate = false
        assertEquals(0.04f, g.hiddenTop, 1e-4f); assertEquals(0.03f, g.hiddenBottom, 1e-4f); assertEquals(0.01f, g.hiddenRight, 1e-4f)
        // Columns: a 2 % horizontal stretch is modelled and pre-warped too.
        val gx = PrinterGeometry().apply { set(floatArrayOf(0.04f, 0.96f, 0.04f, 0.966f)) }
        assertTrue("stretchX %.3f".format(gx.stretchX), gx.stretchX > 1.015f && gx.stretchX < 1.025f)
        val warpedX = gx.prewarp(image)
        assertEquals(64, warpedX.width)
        // The framing chart renders with its rectangles.
        // The combined chart: the ring's 1 % line near the edge, white between the ring and the content, the content inside.
        val chart = TestChart.render(800, FilmType.COLOR)
        assertTrue((2..12).any { Color.red(chart.getPixel(400, it)) < 80 })
        assertEquals(Color.WHITE, chart.getPixel(400, 78))
        assertTrue(Color.red(chart.getPixel(Math.round(800 * TestChart.contentToFile(0.08f)), Math.round(800 * TestChart.contentToFile(0.11f)))) < 60) // the black step
        java.io.File(System.getProperty("screenshots.dir") ?: "build/screenshots").apply { mkdirs() }.let { dir ->
            java.io.FileOutputStream(java.io.File(dir, "chart-colour.png")).use { chart.compress(Bitmap.CompressFormat.PNG, 100, it) }
            java.io.FileOutputStream(java.io.File(dir, "chart-mono.png")).use { TestChart.render(800, FilmType.MONO).compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
