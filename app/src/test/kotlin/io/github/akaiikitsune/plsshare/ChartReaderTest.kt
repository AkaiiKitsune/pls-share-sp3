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

    private fun describe(label: String, reading: ChartReader.Reading, fit: ChartReader.Fit) {
        val model = fit.model
        println("== $label: paper white ${reading.paperWhite.map { it.toInt() }}, printed white ${reading.printedWhite.map { "%.2f".format(it) }}")
        println("   greys: " + reading.greys.map { "%.2f".format(0.299f * it[0] + 0.587f * it[1] + 0.114f * it[2]) })
        println("   black %.2f white %.2f clips %.2f/%.2f gamma %.2f warmBlacks %.2f sat %.2f warmth %.2f tint %.2f blue %.2f  (greys RMS %.1f %%)".format(
            model.blackLevel, model.whiteLevel, model.blackClip, model.whiteClip, model.gamma, model.shadowWarmth, model.saturation, model.warmth, model.tint, model.blueDarkening, fit.greyRms))
        println("   colours RMS %.1f %% -> %.1f %%; hues: %s".format(fit.colourRmsBefore, fit.colourRmsAfter,
            (0 until FilmModel.HUES).joinToString(" ") { "%s%+.0f°×%.2f×%.2f".format(FilmModel.HUE_NAMES[it], model.hueShift[it], model.hueGain[it], model.hueValue[it]) }))
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
        val fit = ChartReader.fit(reading, FilmType.COLOR)
        val model = fit.model
        describe("synthetic", reading, fit)
        assertEquals(truth.blackLevel, model.blackLevel, 0.03f)
        assertEquals(truth.whiteLevel, model.whiteLevel, 0.03f)
        assertEquals(truth.blackClip, model.blackClip, 0.06f)
        assertEquals(truth.whiteClip, model.whiteClip, 0.06f)
        assertEquals(truth.gamma, model.gamma, 0.12f)
        assertEquals(truth.saturation, model.saturation, 0.15f)
        assertTrue(fit.report.startsWith("RMS"))
        // A model with no hue twist must not grow a large one (small tables absorb the tone-fit residue).
        for (k in 0 until FilmModel.HUES) {
            assertEquals(0f, model.hueShift[k], 8f)
            assertEquals(1f, model.hueGain[k], 0.2f)
            assertEquals(1f, model.hueValue[k], 0.12f)
        }
    }

    @Test
    fun `a hue twist in the film is recovered from the patches`() {
        val truth = FilmModel.defaults(FilmType.COLOR).apply {
            hueShift[0] = 12f; hueGain[0] = 0.8f   // reds rotate towards yellow and desaturate
            hueShift[3] = -25f; hueGain[3] = 1.1f  // cyans drift towards blue (as seen on real prints)
            hueValue[2] = 0.5f                      // greens print much darker (as seen on real prints)
        }
        val chart = TestChart.render(600, FilmType.COLOR)
        val simulated = truth.simulated(chart)
        val paper = android.graphics.Bitmap.createBitmap(800, 800, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(paper)
        c.drawColor(android.graphics.Color.WHITE)
        c.drawBitmap(simulated, 100f, 100f, null)
        val reading = ChartReader.read(paper, floatArrayOf(100f, 100f, 700f, 100f, 700f, 700f, 100f, 700f), FilmType.COLOR)
        val fit = ChartReader.fit(reading, FilmType.COLOR)
        describe("twisted", reading, fit)
        assertTrue("hue table lowers the colour error", fit.colourRmsAfter < fit.colourRmsBefore * 0.7)
        assertEquals(truth.hueShift[0], fit.model.hueShift[0], 6f)
        assertEquals(truth.hueShift[3], fit.model.hueShift[3], 8f)
        assertEquals(truth.hueGain[0], fit.model.hueGain[0], 0.15f)
        assertEquals(0f, fit.model.hueShift[2], 6f)
        assertEquals(truth.hueValue[2], fit.model.hueValue[2], 0.12f)
        assertEquals(1f, fit.model.hueValue[0], 0.12f)
    }

    @Test
    fun `compensation undoes the hue correction`() {
        val m = FilmModel.defaults(FilmType.COLOR).apply {
            hueShift[0] = 12f; hueGain[0] = 0.8f; hueShift[3] = -25f; hueGain[3] = 1.1f; hueShift[4] = 8f; hueGain[4] = 1.3f
            hueValue[2] = 0.5f; hueValue[4] = 0.8f
        }
        for (src in TestChart.patches) {
            val px = intArrayOf(src)
            m.hueCorrect(px, inverse = true)
            m.hueCorrect(px, inverse = false)
            val dr = Math.abs(android.graphics.Color.red(px[0]) - android.graphics.Color.red(src))
            val dg = Math.abs(android.graphics.Color.green(px[0]) - android.graphics.Color.green(src))
            val db = Math.abs(android.graphics.Color.blue(px[0]) - android.graphics.Color.blue(src))
            assertTrue("patch ${Integer.toHexString(src)} round-trips ($dr $dg $db)", dr <= 3 && dg <= 3 && db <= 3)
        }
    }

    @Test
    fun `daylight and flash photos of the same print agree after normalisation`() {
        val day = ChartReader.read(load("chart-daylight.jpg"), floatArrayOf(639f, 1214f, 2332f, 1214f, 2332f, 2824f, 639f, 2824f), FilmType.COLOR)
        val flash = ChartReader.read(load("chart-flash.jpg"), floatArrayOf(769f, 1191f, 2270f, 1191f, 2270f, 2637f, 769f, 2637f), FilmType.COLOR)
        val fd = ChartReader.fit(day, FilmType.COLOR)
        val ff = ChartReader.fit(flash, FilmType.COLOR)
        val md = fd.model
        val mf = ff.model
        describe("daylight", day, fd)
        describe("flash", flash, ff)
        assertTrue("hue table never worsens the colours", fd.colourRmsAfter <= fd.colourRmsBefore && ff.colourRmsAfter <= ff.colourRmsBefore)
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
