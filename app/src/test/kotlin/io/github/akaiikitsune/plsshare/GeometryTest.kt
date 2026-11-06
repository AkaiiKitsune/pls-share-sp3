package io.github.akaiikitsune.plsshare

import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
        // Columns match the file within a couple of percent.
        assertEquals(PrinterGeometry.STEP_LEFT_FILE, results.map { it[2] }.average().toFloat(), 0.025f)
        assertEquals(PrinterGeometry.PATCH_RIGHT_FILE, results.map { it[3] }.average().toFloat(), 0.025f)
        // And show the stretch: the gradient's bottom lands lower than in the file.
        assertTrue(results.map { it[1] }.average() > 0.965)
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

        val g = PrinterGeometry(stepTop = 0.01f, gradientBottom = 0.98f)
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
        val gx = PrinterGeometry(stepLeft = 0.04f, patchRight = 0.966f)
        assertTrue("stretchX %.3f".format(gx.stretchX), gx.stretchX > 1.015f && gx.stretchX < 1.025f)
        val warpedX = gx.prewarp(image)
        assertEquals(64, warpedX.width)
        // The framing chart renders with its rectangles.
        val framing = TestChart.renderFraming(800)
        // A dark line within the first 1.5 % (the 1 % rectangle), white further in between the rectangles' labels.
        assertTrue((2..12).any { Color.red(framing.getPixel(400, it)) < 80 })
        assertEquals(Color.WHITE, framing.getPixel(400, 200))
        java.io.File(System.getProperty("screenshots.dir") ?: "build/screenshots").apply { mkdirs() }.let { dir ->
            java.io.FileOutputStream(java.io.File(dir, "chart-framing.png")).use { framing.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
