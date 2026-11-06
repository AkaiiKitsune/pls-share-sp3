package io.github.akaiikitsune.plsshare

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The framing ring aligns the chart on its own, for the monochrome chart (no colour grid) as well as the colour one. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [31])
class RingDetectTest {
    private fun load(name: String) = javaClass.getResourceAsStream("/$name.jpg")!!.use { BitmapFactory.decodeStream(it)!! }

    @Test
    fun `the ring finds the same corners as the colour grid on the real photos`() {
        for (name in listOf("ring-steel", "ring-fabric", "ring-stickers", "ring-wood", "ring-bamboo")) {
            val photo = load(name)
            val grid = ChartReader.detectFromGrid(photo)!!
            val ring = ChartReader.detectFromRing(photo)
            println("$name: grid " + grid.joinToString { "%.0f".format(it) } + " ring " + (ring?.joinToString { "%.0f".format(it) } ?: "not found"))
            assertNotNull("$name: ring not found", ring)
            val side = Math.hypot((grid[2] - grid[0]).toDouble(), (grid[3] - grid[1]).toDouble())
            for (i in 0 until 8) assertEquals("$name corner value $i", grid[i], ring!![i], (side * 0.012).toFloat())
        }
    }

    @Test
    fun `a monochrome print, turned on its card, is aligned by its ring`() {
        for (degrees in listOf(0f, 2f, -3f)) {
            val chart = TestChart.render(1000, FilmType.MONO)
            val photo = Bitmap.createBitmap(1400, 1700, Bitmap.Config.ARGB_8888)
            Canvas(photo).apply {
                drawColor(Color.rgb(90, 70, 50))
                drawRect(137f, 150f, 137f + 1125f, 150f + 1344f, Paint().apply { color = Color.WHITE })
                save(); rotate(degrees, 137f + 1125f / 2, 150f + 1344f / 2); drawBitmap(chart, 200f, 220f, null); restore()
            }
            val found = ChartReader.detectCorners(photo, FilmType.MONO)
            assertNotNull("turned $degrees°: not found", found)
            println("turned $degrees: found " + found!!.joinToString { "%.1f".format(it) })
            // The content square's corners, turned like the chart.
            val x0 = 200 + 1000 * TestChart.CONTENT_INSET; val y0 = 220 + 1000 * TestChart.CONTENT_INSET; val side = 1000 * TestChart.CONTENT
            val cx = 137f + 1125f / 2; val cy = 150f + 1344f / 2
            val t = Math.toRadians(degrees.toDouble())
            for ((i, c) in listOf(x0 to y0, x0 + side to y0, x0 + side to y0 + side, x0 to y0 + side).withIndex()) {
                val dx = c.first - cx; val dy = c.second - cy
                val ex = cx + Math.cos(t) * dx - Math.sin(t) * dy; val ey = cy + Math.sin(t) * dx + Math.cos(t) * dy
                assertEquals("turned $degrees°, corner $i x", ex, found!![2 * i].toDouble(), 3.0)
                assertEquals("turned $degrees°, corner $i y", ey, found[2 * i + 1].toDouble(), 3.0)
            }
            // The card and the ring read on it too.
            val card = ChartReader.measureCard(photo, found!!)
            assertNotNull("turned $degrees°: card", card)
            assertEquals(degrees, PrinterGeometry().apply { this.card = card }.rotationDegrees, 0.2f)
            val ring = ChartReader.readRing(photo, found, card)!!
            assertTrue("ring ${ring.joinToString()}", ring.all { it < 0.6f })
        }
    }
}
