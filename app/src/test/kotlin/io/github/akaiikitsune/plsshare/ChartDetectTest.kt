package io.github.akaiikitsune.plsshare

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [31])
class ChartDetectTest {
    /** A print of the chart (white frame around the image) photographed on a dark table, with perspective. */
    /** The content square alone, as older prints had it (the ring around it changes nothing for the grid). */
    private fun photo(type: FilmType, corners: FloatArray): Bitmap {
        val chart = FilmModel.defaults(type).simulated(TestChart.renderContent(500, type))
        val print = Bitmap.createBitmap(640, 760, Bitmap.Config.ARGB_8888)
        Canvas(print).apply {
            drawColor(Color.rgb(236, 236, 232))
            drawBitmap(chart, 70f, 70f, null)
        }
        val out = Bitmap.createBitmap(1000, 1200, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.rgb(40, 38, 36))
        // Map the print's image area corners onto the wanted photo corners.
        val m = Matrix()
        m.setPolyToPoly(floatArrayOf(70f, 70f, 570f, 70f, 570f, 570f, 70f, 570f), 0, corners, 0, 4)
        canvas.drawBitmap(print, m, Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    private fun check(found: FloatArray?, wanted: FloatArray, tolerance: Float, label: String) {
        assertNotNull("$label: corners found", found)
        for (i in 0 until 8) assertEquals("$label corner value $i", wanted[i], found!![i], tolerance)
    }

    @Test
    fun `the colour grid is found under perspective, with and without the ring`() {
        val wanted = floatArrayOf(210f, 250f, 790f, 230f, 820f, 900f, 180f, 880f)
        val found = ChartReader.detectCorners(photo(FilmType.COLOR, wanted), FilmType.COLOR)
        check(found, wanted, 25f, "content only")
        // The full chart: the content square sits at CONTENT_INSET inside the file.
        val full = FilmModel.defaults(FilmType.COLOR).simulated(TestChart.render(640, FilmType.COLOR))
        val print = Bitmap.createBitmap(800, 920, Bitmap.Config.ARGB_8888)
        Canvas(print).apply { drawColor(Color.rgb(236, 236, 232)); drawBitmap(full, 80f, 80f, null) }
        val out = Bitmap.createBitmap(1000, 1200, Bitmap.Config.ARGB_8888)
        Canvas(out).apply {
            drawColor(Color.rgb(60, 50, 40))
            val m = Matrix(); m.setPolyToPoly(floatArrayOf(80f, 80f, 720f, 80f, 720f, 720f, 80f, 720f), 0, wanted, 0, 4)
            drawBitmap(print, m, Paint(Paint.FILTER_BITMAP_FLAG))
        }
        val foundFull = ChartReader.detectCorners(out, FilmType.COLOR)
        assertNotNull(foundFull)
        // Expected content corners: the file corners moved inwards by CONTENT_INSET.
        val h = ChartReader.homography(wanted)
        val i = TestChart.CONTENT_INSET.toDouble()
        val expected = FloatArray(8)
        for ((k, c) in listOf(i to i, 1 - i to i, 1 - i to 1 - i, i to 1 - i).withIndex()) { val p = ChartReader.map(h, c.first, c.second); expected[2 * k] = p[0].toFloat(); expected[2 * k + 1] = p[1].toFloat() }
        check(foundFull, expected, 25f, "full chart")
        // And the mono chart has no grid: nothing is proposed, the user taps.
        assertNull(ChartReader.detectCorners(photo(FilmType.MONO, wanted), FilmType.MONO))
    }

    @Test
    fun `averaging two readings lands between them`() {
        val photo = javaClass.getResourceAsStream("/chart-daylight.jpg")!!.use { BitmapFactory.decodeStream(it)!! }
        val flash = javaClass.getResourceAsStream("/chart-flash.jpg")!!.use { BitmapFactory.decodeStream(it)!! }
        val a = ChartReader.read(photo, floatArrayOf(639f, 1214f, 2332f, 1214f, 2332f, 2824f, 639f, 2824f), FilmType.COLOR)
        val b = ChartReader.read(flash, floatArrayOf(769f, 1191f, 2270f, 1191f, 2270f, 2637f, 769f, 2637f), FilmType.COLOR)
        val avg = ChartReader.average(listOf(a, b))
        for (i in a.greys.indices) for (c in 0 until 3) {
            val lo = minOf(a.greys[i][c], b.greys[i][c]); val hi = maxOf(a.greys[i][c], b.greys[i][c])
            assertTrue(avg.greys[i][c] in lo - 1e-4f..hi + 1e-4f)
        }
        assertEquals(16, avg.patches.size)
    }
}
