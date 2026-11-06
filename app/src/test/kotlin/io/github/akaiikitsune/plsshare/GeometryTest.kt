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
        assertEquals(1f - PrinterGeometry.WINDOW_BOTTOM, g.hiddenBottom, 1e-4f)
        g.compensate = false
        assertTrue(g.hiddenTop > 0f && g.hiddenBottom > 0.04f)
        // Columns: a 2 % horizontal stretch pulls the right edge out of the window.
        val gx = PrinterGeometry(stepLeft = 0.04f, patchRight = 0.966f, compensate = false)
        assertTrue("stretchX %.3f".format(gx.stretchX), gx.stretchX > 1.015f && gx.stretchX < 1.025f)
        assertTrue(gx.hiddenRight > 0.01f && gx.hiddenLeft < 0.005f)
        val warpedX = PrinterGeometry(stepLeft = 0.04f, patchRight = 0.966f).prewarp(image)
        assertEquals(64, warpedX.width)
    }
}
