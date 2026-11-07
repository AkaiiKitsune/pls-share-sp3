package io.github.akaiikitsune.plsshare

import android.graphics.BitmapFactory
import android.graphics.Color
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
        val chart = TestChart.renderContent(600, FilmType.COLOR)
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
        val chart = TestChart.renderContent(600, FilmType.COLOR)
        val simulated = truth.simulated(chart)
        val paper = android.graphics.Bitmap.createBitmap(800, 800, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(paper)
        c.drawColor(android.graphics.Color.WHITE)
        c.drawBitmap(simulated, 100f, 100f, null)
        val reading = ChartReader.read(paper, floatArrayOf(100f, 100f, 700f, 100f, 700f, 700f, 100f, 700f), FilmType.COLOR)
        val fit = ChartReader.fit(reading, FilmType.COLOR)
        describe("twisted", reading, fit)
        assertTrue("colour correction lowers the colour error", fit.colourRmsAfter < fit.colourRmsBefore * 0.7)
        // The matrix and the tables share the twist between them: what counts is that the fitted
        // model renders the patches like the film does.
        assertTrue("patches rendered like the film: %.1f %%".format(patchRms(truth, fit.model)), patchRms(truth, fit.model) < 4.0)
        assertTrue(fit.model.colourMeasured)
    }

    @Test
    fun `three readings combine as medians, two as means`() {
        fun reading(k: Float) = ChartReader.Reading(List(11) { floatArrayOf(k, k, k) }, List(16) { floatArrayOf(k, k, k) }, floatArrayOf(k, k, k), floatArrayOf(k, k, k),
            android.graphics.Bitmap.createBitmap(4, 4, android.graphics.Bitmap.Config.ARGB_8888), List(ChartReader.GRADIENT_SAMPLES) { floatArrayOf(k, k, k) })
        val three = ChartReader.average(listOf(reading(0.2f), reading(0.25f), reading(0.9f))) // 0.9: a glint on one photo
        assertEquals(0.25f, three.greys[3][1], 1e-5f)
        assertEquals(0.25f, three.patches[7][0], 1e-5f)
        assertEquals(0.25f, three.gradient[10][2], 1e-5f)
        val two = ChartReader.average(listOf(reading(0.2f), reading(0.4f)))
        assertEquals(0.3f, two.greys[0][0], 1e-5f)
    }

    /** RMS distance, in percent, between the 16 patches as two models render them. */
    private fun patchRms(a: FilmModel, b: FilmModel): Double {
        var err = 0.0
        for (src in TestChart.patches) {
            val pa = intArrayOf(src); val pb = intArrayOf(src)
            a.simulate(pa); b.simulate(pb)
            for (shift in listOf(16, 8, 0)) { val d = (((pa[0] shr shift) and 0xFF) - ((pb[0] shr shift) and 0xFF)) / 255.0; err += d * d / 3 }
        }
        return Math.sqrt(err / TestChart.patches.size) * 100
    }

    @Test
    fun `the film's crosstalk is recovered from the patches`() {
        // A film whose red channel picks up a fifth of the green, and whose blue leaks into green.
        val truth = FilmModel.defaults(FilmType.COLOR).apply { crosstalk = floatArrayOf(0.8f, 0.2f, 0f, 0f, 0.85f, 0.15f, 0f, 0f, 1f) }
        val chart = TestChart.renderContent(600, FilmType.COLOR)
        val simulated = truth.simulated(chart)
        val paper = android.graphics.Bitmap.createBitmap(800, 800, android.graphics.Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(paper).apply { drawColor(android.graphics.Color.WHITE); drawBitmap(simulated, 100f, 100f, null) }
        val reading = ChartReader.read(paper, floatArrayOf(100f, 100f, 700f, 100f, 700f, 700f, 100f, 700f), FilmType.COLOR)
        val fit = ChartReader.fit(reading, FilmType.COLOR)
        describe("crosstalk", reading, fit)
        println("crosstalk found: " + fit.model.crosstalk.joinToString { "%.2f".format(it) })
        assertTrue(fit.model.crosstalkSet)
        // The global saturation, fitted first, takes part of the desaturation the mixing causes, so
        // the terms come out milder than the truth; the direction must be right, and the rendering.
        for (i in 0 until 9) assertEquals("term $i", truth.crosstalk[i], fit.model.crosstalk[i], 0.15f)
        assertTrue("red takes green: ${fit.model.crosstalk[1]}", fit.model.crosstalk[1] > 0.03f)
        assertTrue("green takes blue: ${fit.model.crosstalk[5]}", fit.model.crosstalk[5] > 0.03f)
        assertTrue("patches rendered like the film: %.1f %%".format(patchRms(truth, fit.model)), patchRms(truth, fit.model) < 3.0)
        // The compensation inverts it: a colour sent through the inverse then the film comes back.
        val px = intArrayOf(android.graphics.Color.rgb(200, 90, 60))
        fit.model.compensate(px); fit.model.simulate(px)
        val back = px[0]
        val ref = intArrayOf(android.graphics.Color.rgb(200, 90, 60)).also { FilmModel.defaults(FilmType.COLOR).apply { compensate(it); simulate(it) } }[0]
        for (shift in listOf(16, 8, 0)) assertEquals("channel $shift", ((ref shr shift) and 0xFF).toFloat(), ((back shr shift) and 0xFF).toFloat(), 12f)
    }

    @Test
    fun `near-neutral highlights are left alone by the hue correction`() {
        val m = FilmModel.defaults(FilmType.COLOR).apply { hueShift[3] = 20f; hueGain[3] = 1.8f; hueShift[4] = -30f; hueGain[4] = 1.5f; hueValue[4] = 0.6f }
        // A blown highlight with a trace of blue (saturation 2 to 4 %) must stay where it is.
        for (src in listOf(android.graphics.Color.rgb(255, 255, 250), android.graphics.Color.rgb(250, 250, 255), android.graphics.Color.rgb(245, 248, 255), android.graphics.Color.rgb(255, 252, 245))) {
            val px = intArrayOf(src)
            m.hueCorrect(px, inverse = false)
            val d = maxOf(Math.abs(android.graphics.Color.red(px[0]) - android.graphics.Color.red(src)), Math.abs(android.graphics.Color.green(px[0]) - android.graphics.Color.green(src)), Math.abs(android.graphics.Color.blue(px[0]) - android.graphics.Color.blue(src)))
            assertTrue("highlight ${Integer.toHexString(src)} moved by $d", d <= 2)
        }
    }

    @Test
    fun `compensation undoes the hue correction`() {
        val m = FilmModel.defaults(FilmType.COLOR).apply {
            hueShift[0] = 12f; hueGain[0] = 0.8f; hueShift[3] = -25f; hueGain[3] = 1.1f; hueShift[4] = 8f; hueGain[4] = 1.3f
            hueValue[2] = 0.5f; hueValue[4] = 0.8f
        }
        val extra = listOf(android.graphics.Color.rgb(250, 250, 255), android.graphics.Color.rgb(240, 236, 230), android.graphics.Color.rgb(200, 210, 220), android.graphics.Color.rgb(120, 140, 130))
        for (src in TestChart.patches + extra) {
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
        // Desaturated in both: the matrix and the global saturation share it, so judge the rendering of the vivid patches.
        fun chromaRatio(m: FilmModel): Float {
            var ratio = 0f; var n = 0
            for (src in TestChart.patches.take(8)) {
                val px = intArrayOf(src); m.simulate(px)
                val cs = (maxOf(Color.red(src), Color.green(src), Color.blue(src)) - minOf(Color.red(src), Color.green(src), Color.blue(src))).toFloat()
                val co = (maxOf(Color.red(px[0]), Color.green(px[0]), Color.blue(px[0])) - minOf(Color.red(px[0]), Color.green(px[0]), Color.blue(px[0]))).toFloat()
                ratio += co / cs; n++
            }
            return ratio / n
        }
        assertTrue("desaturated in both: %.2f %.2f".format(chromaRatio(md), chromaRatio(mf)), chromaRatio(md) < 0.95f && chromaRatio(mf) < 0.95f)
        assertTrue("warm blacks in both", md.shadowWarmth > 0.2f && mf.shadowWarmth > 0.2f)
        // The two photos differ in light, angle and flare; the fits must stay in the same ballpark.
        assertEquals("black level agrees", md.blackLevel, mf.blackLevel, 0.10f)
        assertEquals("white level agrees", md.whiteLevel, mf.whiteLevel, 0.08f)
        assertEquals("gamma agrees", md.gamma, mf.gamma, 0.35f)
        assertEquals("saturation agrees", md.saturation, mf.saturation, 0.25f)
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [31])
class FilmLutTest {
    private fun model() = FilmModel.defaults(FilmType.COLOR).apply {
        hueShift[0] = 12f; hueGain[0] = 0.8f; hueShift[3] = -25f; hueGain[3] = 1.1f; hueValue[2] = 0.5f; hueValue[4] = 0.8f; blueDarkening = 0.3f
    }

    @Test
    fun `the 3D table matches the exact pipeline`() {
        val m = model()
        val rnd = java.util.Random(7)
        for (compensated in listOf(false, true)) {
            val src = IntArray(4000) { android.graphics.Color.rgb(rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256)) } +
                TestChart.patches.toIntArray() + IntArray(11) { v -> val g = v * 255 / 10; android.graphics.Color.rgb(g, g, g) }
            val exact = src.clone()
            if (compensated) m.compensate(exact)
            m.simulate(exact)
            val fast = src.clone()
            m.simulateFast(fast, compensated)
            var worst = 0; var sum = 0L
            for (i in src.indices) {
                val d = maxOf(Math.abs(android.graphics.Color.red(exact[i]) - android.graphics.Color.red(fast[i])), Math.abs(android.graphics.Color.green(exact[i]) - android.graphics.Color.green(fast[i])), Math.abs(android.graphics.Color.blue(exact[i]) - android.graphics.Color.blue(fast[i])))
                worst = maxOf(worst, d); sum += d
            }
            println("lut (compensated=$compensated): mean error %.2f, worst $worst".format(sum.toDouble() / src.size))
            // The inverse tone curves and the hue inverse have steps that the grid smooths a little.
            assertTrue("mean error small", sum.toDouble() / src.size < 2.5)
            // The gamut projection bends the compensation at the film's gamut boundary, where the grid interpolates across the bend.
            assertTrue("worst error bounded", worst <= 64)
        }
    }

    @Test
    fun `the table is rebuilt when the model changes`() {
        val m = model()
        val a = m.lut(false)
        m.gamma += 0.1f
        val b = m.lut(false)
        assertTrue(a !== b)
        assertTrue(b === m.lut(false))
    }

    @Test
    fun `the fast path is fast`() {
        val m = model()
        val px = IntArray(480 * 480) { it * 7919 }
        m.simulateFast(px.clone(), true) // warm-up, builds the table
        val t0 = System.nanoTime()
        m.simulateFast(px, true)
        val fastMs = (System.nanoTime() - t0) / 1e6
        val t1 = System.nanoTime()
        m.compensate(px); m.simulate(px)
        val exactMs = (System.nanoTime() - t1) / 1e6
        println("480²: fast %.0f ms, exact %.0f ms".format(fastMs, exactMs))
        assertTrue("fast path is at least 3× faster", fastMs * 3 < exactMs)
    }
}
