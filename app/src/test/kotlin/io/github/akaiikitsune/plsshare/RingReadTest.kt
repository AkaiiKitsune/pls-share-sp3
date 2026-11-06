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
            val card = ChartReader.measureCard(photo, corners)
            val fine = card?.let { ChartReader.readRing(photo, corners, it) }
            println("$name: geometry " + (g?.joinToString { "%.3f".format(it) } ?: "null") + " ring " + (ring?.joinToString { "%.1f".format(it) } ?: "null") +
                " fine " + (fine?.joinToString { "%.2f".format(it) } ?: "no card"))
            if (fine != null) for (side in 0 until 4) assertEquals("$name side $side fine vs coarse", ring!![side], fine[side], 1.2f)
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
    fun `the card's edges give the same rotation on every photo where they show`() {
        val cards = ArrayList<FloatArray>()
        for (name in names) {
            val photo = load(name)
            val corners = ChartReader.detectCorners(photo, FilmType.COLOR)!!
            val card = ChartReader.measureCard(photo, corners)
            val g = PrinterGeometry().apply { this.card = card }
            println("$name: card " + (card?.let { "rotation %.2f° keystone %+.1f %% printed %.1f × %.1f mm".format(g.rotationDegrees, g.keystone * 100, g.printedSizeMm[0], g.printedSizeMm[1]) } ?: "not found"))
            if (card != null) cards += card
        }
        // Backgrounds close to paper (brushed steel) hide the edges; the others find them.
        assertTrue("cards found: ${cards.size}", cards.size >= 3)
        val median = FloatArray(8) { c -> cards.map { it[c] }.sorted().let { v -> v[v.size / 2] } }
        val g = PrinterGeometry().apply { card = median; setCuts(floatArrayOf(3f, 0f, 1.5f, 0.5f)) }
        // This print is turned about half a degree clockwise, printed about 64 mm wide, and the window measures about 62 mm.
        assertEquals(0.5f, g.rotationDegrees, 0.3f)
        assertTrue("keystone ${g.keystone}", Math.abs(g.keystone) < 0.03f)
        assertEquals(64f, g.printedSizeMm[0], 2f)
        assertEquals(64f, g.printedSizeMm[1], 3f)
        assertTrue("placed, window ${g.targetMm?.get(2)}", g.placed)
        assertEquals(PrinterGeometry.WINDOW_MM, g.targetMm!![2], 3f)
        for (card in cards) {
            val one = PrinterGeometry().apply { this.card = card }
            assertEquals("rotation on one photo", g.rotationDegrees, one.rotationDegrees, 0.35f)
        }
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
