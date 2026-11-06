package io.github.akaiikitsune.plsshare

import android.graphics.BitmapFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The combined chart (framing ring + centred colours), printed once and photographed on five
 * backgrounds at the size the app loads chart photos: the grid aligns it, the geometry measures
 * the stretch, and the ring says how much the frame hides on each side.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [31])
class RingReadTest {
    private fun load(name: String) = javaClass.getResourceAsStream("/$name.jpg")!!.use { BitmapFactory.decodeStream(it)!! }

    private val names = listOf("ring-steel", "ring-fabric", "ring-stickers", "ring-wood", "ring-bamboo")

    @Test
    fun `the ring is read the same on every photo of the same print`() {
        val rings = ArrayList<FloatArray>()
        val geometries = ArrayList<FloatArray>()
        for (name in names) {
            val photo = load(name)
            val corners = ChartReader.detectCorners(photo, FilmType.COLOR)
            assertNotNull("$name aligned", corners)
            val g = ChartReader.measureGeometry(photo, corners!!)
            val ring = ChartReader.readRing(photo, corners)
            println("$name: geometry " + (g?.joinToString { "%.3f".format(it) } ?: "null") + " ring " + (ring?.joinToString { "%.1f".format(it) } ?: "null"))
            assertNotNull("$name geometry", g)
            assertNotNull("$name ring", ring)
            geometries += g!!
            rings += ring!!
        }
        for (side in 0 until 4) {
            val values = rings.map { it[side] }
            assertTrue("side $side read differently: $values", values.max() - values.min() <= 1f)
        }
        // Seen on the print: the top loses the first rectangles, the bottom and sides keep most of theirs.
        assertTrue("top hidden ${rings.map { it[0] }}", rings.map { it[0] }.average() >= 1.0)
    }

    @Test
    fun `a rendered chart shows every ring line`() {
        // The chart on its white frame, as on a print that hides nothing.
        val chart = TestChart.render(1200, FilmType.COLOR)
        val print = android.graphics.Bitmap.createBitmap(1400, 1400, android.graphics.Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(print).apply { drawColor(android.graphics.Color.WHITE); drawBitmap(chart, 100f, 100f, null) }
        val inset = 100 + 1200 * TestChart.CONTENT_INSET
        val size = 1200 * TestChart.CONTENT
        val corners = floatArrayOf(inset, inset, inset + size, inset, inset + size, inset + size, inset, inset + size)
        val ring = ChartReader.readRing(print, corners)
        assertNotNull(ring)
        for (side in 0 until 4) assertEquals("side $side", 0f, ring!![side], 0.01f)
        // Hide the top 3.2 % and the left 1.4 %: the ring says 3.5 and 1.5 (the first whole rectangles are 4 and 2).
        val hidden = android.graphics.Bitmap.createBitmap(print)
        android.graphics.Canvas(hidden).apply {
            val white = android.graphics.Paint().apply { color = android.graphics.Color.WHITE }
            drawRect(0f, 0f, 1400f, 100f + 1200 * 0.032f, white)
            drawRect(0f, 0f, 100f + 1200 * 0.014f, 1400f, white)
        }
        val cut = ChartReader.readRing(hidden, corners)!!
        assertEquals(3.5f, cut[0], 0.01f)
        assertEquals(0f, cut[1], 0.01f)
        assertEquals(1.5f, cut[2], 0.01f)
        assertEquals(0f, cut[3], 0.01f)
    }
}
