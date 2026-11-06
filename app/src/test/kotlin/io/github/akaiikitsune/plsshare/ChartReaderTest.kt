package io.github.akaiikitsune.plsshare

import android.graphics.BitmapFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [31])
class ChartReaderTest {
    private fun load(name: String) = javaClass.getResourceAsStream("/$name")!!.use { BitmapFactory.decodeStream(it)!! }

    private fun describe(label: String, reading: ChartReader.Reading, model: FilmModel, report: String) {
        println("== $label: paper white ${reading.paperWhite.map { it.toInt() }}, printed white ${reading.printedWhite.map { "%.2f".format(it) }}")
        println("   greys: " + reading.greys.map { "%.2f".format(0.299f * it[0] + 0.587f * it[1] + 0.114f * it[2]) })
        println("   black %.2f white %.2f clips %.2f/%.2f gamma %.2f warmBlacks %.2f sat %.2f warmth %.2f tint %.2f blue %.2f  (%s)".format(
            model.blackLevel, model.whiteLevel, model.blackClip, model.whiteClip, model.gamma, model.shadowWarmth, model.saturation, model.warmth, model.tint, model.blueDarkening, report))
    }

    @Test
    fun `homography maps the unit square corners exactly`() {
        val corners = floatArrayOf(100f, 120f, 900f, 110f, 950f, 1000f, 80f, 980f)
        val h = ChartReader.homography(corners)
        val pts = listOf(0.0 to 0.0, 1.0 to 0.0, 1.0 to 1.0, 0.0 to 1.0)
        for ((i, p) in pts.withIndex()) {
            val m = ChartReader.map(h, p.first, p.second)
            assertEquals(corners[2 * i].toDouble(), m[0], 0.01)
            assertEquals(corners[2 * i + 1].toDouble(), m[1], 0.01)
        }
    }

    @Test
    fun `synthetic chart through a known model is recovered`() {
        // Render the chart, simulate a film, read it back with identity corners.
        val truth = FilmModel.defaults(FilmType.COLOR)
        val chart = TestChart.render(600, FilmType.COLOR)
        val simulated = truth.simulated(chart)
        // Put it on a "paper" canvas with a white border so the paper white can be sampled.
        val paper = android.graphics.Bitmap.createBitmap(800, 800, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(paper)
        c.drawColor(android.graphics.Color.WHITE)
        c.drawBitmap(simulated, 100f, 100f, null)
        val reading = ChartReader.read(paper, floatArrayOf(100f, 100f, 700f, 100f, 700f, 700f, 100f, 700f), FilmType.COLOR)
        val (model, report) = ChartReader.fit(reading, FilmType.COLOR)
        describe("synthetic", reading, model, report)
        assertEquals(truth.blackLevel, model.blackLevel, 0.03f)
        assertEquals(truth.whiteLevel, model.whiteLevel, 0.03f)
        assertEquals(truth.blackClip, model.blackClip, 0.06f)
        assertEquals(truth.whiteClip, model.whiteClip, 0.06f)
        assertEquals(truth.gamma, model.gamma, 0.12f)
        assertEquals(truth.saturation, model.saturation, 0.15f)
        assertTrue(report.startsWith("RMS"))
    }

    @Test
    fun `daylight and flash photos of the same print agree after normalisation`() {
        val day = ChartReader.read(load("chart-daylight.jpg"), floatArrayOf(639f, 1214f, 2332f, 1214f, 2332f, 2824f, 639f, 2824f), FilmType.COLOR)
        val flash = ChartReader.read(load("chart-flash.jpg"), floatArrayOf(769f, 1191f, 2270f, 1191f, 2270f, 2637f, 769f, 2637f), FilmType.COLOR)
        val (md, rd) = ChartReader.fit(day, FilmType.COLOR)
        val (mf, rf) = ChartReader.fit(flash, FilmType.COLOR)
        describe("daylight", day, md, rd)
        describe("flash", flash, mf, rf)
        // The film's character must come out whatever the light.
        // Flash flare lifts the midtones more than daylight does; both must still read as a lift, not a darkening.
        assertTrue("midtones lift (gamma < 1) in both", md.gamma < 1f && mf.gamma < 1f)
        assertTrue("blacks block up early in both", md.blackClip >= 0.1f && mf.blackClip >= 0.1f)
        assertTrue("desaturated in both", md.saturation < 0.95f && mf.saturation < 0.95f)
        assertTrue("warm blacks in both", md.shadowWarmth > 0.2f && mf.shadowWarmth > 0.2f)
        // The two photos differ in light, angle and flare; the fits must stay in the same ballpark.
        assertEquals("black level agrees", md.blackLevel, mf.blackLevel, 0.10f)
        assertEquals("white level agrees", md.whiteLevel, mf.whiteLevel, 0.08f)
        assertEquals("gamma agrees", md.gamma, mf.gamma, 0.35f)
        assertEquals("saturation agrees", md.saturation, mf.saturation, 0.25f)
    }
}
