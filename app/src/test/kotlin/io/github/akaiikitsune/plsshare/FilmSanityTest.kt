package io.github.akaiikitsune.plsshare

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/**
 * The film model must never produce artefacts, whatever was measured: these checks run on the
 * default model, on a model measured by hand on a real print (the values of a calibration
 * screen), and on the models fitted from every real chart photo. Each model is applied the way
 * the app applies it, exact per pixel and through the preview's 3D table, to a real photo and
 * to synthetic ramps, and judged on:
 *  - the round trip: compensation then simulation gives the input back where the film can reach it;
 *  - smoothness: neighbouring input colours give neighbouring outputs (no tears in the table);
 *  - neutrals: greys stay grey, dark and near-neutral colours do not take a hue;
 *  - the preview against the exact path: the table does not invent anything.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [31])
class FilmSanityTest {
    private fun load(name: String) = javaClass.getResourceAsStream("/$name.jpg")!!.use { BitmapFactory.decodeStream(it)!! }

    /** The model read on a calibration screen after measuring five photos (nightly 80). */
    private fun screenModel() = FilmModel(
        FilmType.COLOR, exposure = 0f, gamma = 0.70f, blackClip = 0.25f, whiteClip = 0.85f, blackLevel = 0.11f, whiteLevel = 1.00f,
        shadowWarmth = 0.42f, saturation = 0.84f, warmth = -0.18f, tint = 0.13f, blueDarkening = 0f,
        hueShift = floatArrayOf(-9f, -2f, 4f, 3f, -3f, 11f), hueGain = floatArrayOf(1.03f, 1.31f, 0.60f, 1.78f, 0.63f, 1.09f), hueValue = floatArrayOf(1.04f, 1.47f, 0.51f, 0.89f, 0.68f, 1.04f),
        crosstalk = floatArrayOf(1.06f, -0.02f, -0.04f, 0.22f, 0.64f, 0.14f, 0.04f, -0.02f, 0.98f),
    )

    private fun models(): List<Pair<String, FilmModel>> {
        val out = ArrayList<Pair<String, FilmModel>>()
        out += "defaults" to FilmModel.defaults(FilmType.COLOR)
        out += "screen" to screenModel()
        val names = listOf("ring-steel", "ring-fabric", "ring-stickers", "ring-wood", "ring-bamboo", "chart-daylight", "chart-flash")
        val readings = ArrayList<ChartReader.Reading>()
        for (name in names) {
            val photo = load(name)
            val corners = ChartReader.detectCorners(photo, FilmType.COLOR) ?: continue
            val reading = ChartReader.read(photo, corners, FilmType.COLOR)
            readings += reading
            out += name to ChartReader.fit(reading, FilmType.COLOR).model
        }
        out += "median of ring photos" to ChartReader.fit(ChartReader.average(readings.take(5)), FilmType.COLOR).model
        return out
    }

    private fun hsv(c: Int): FloatArray = FloatArray(3).also { FilmModel.rgbToHsv(Color.red(c), Color.green(c), Color.blue(c), it) }

    private fun hueDistance(a: Float, b: Float): Float { var d = Math.abs(a - b) % 360f; if (d > 180f) d = 360f - d; return d }

    class Report(val name: String) {
        val problems = ArrayList<String>()
        fun check(ok: Boolean, text: String) { if (!ok) problems += text }
    }

    private fun judge(name: String, m: FilmModel, sample: IntArray, width: Int, dir: File): Report {
        val r = Report(name)
        // 1. The preview path on the sample photo, and the exact path: they must agree, and
        //    neither may swing the hue of a pixel that was not saturated.
        val fast = sample.clone().also { m.simulateFast(it, compensated = true) }
        val exact = sample.clone().also { m.compensate(it); m.simulate(it) }
        var disagree = 0; var swung = 0; var darkHued = 0
        for (i in sample.indices) {
            val a = fast[i]; val b = exact[i]
            if (Math.abs(Color.red(a) - Color.red(b)) > 24 || Math.abs(Color.green(a) - Color.green(b)) > 24 || Math.abs(Color.blue(a) - Color.blue(b)) > 24) disagree++
            val src = hsv(sample[i]); val out = hsv(exact[i])
            // Saturation means little on near-black outputs (a few levels of warm black read as saturated).
            if (src[1] < 0.12f && out[1] > 0.30f && out[2] > 0.15f) swung++
            if (src[2] < 0.20f && src[1] < 0.30f && out[1] > 0.45f && out[2] > 0.2f) darkHued++
        }
        val n = sample.size
        r.check(disagree < n / 200, "preview differs from the exact path on ${100.0 * disagree / n} % of pixels")
        r.check(swung < n / 500, "near-neutral pixels take a colour: ${100.0 * swung / n} %")
        r.check(darkHued < n / 500, "dark pixels take a strong colour: ${100.0 * darkHued / n} %")
        Bitmap.createBitmap(fast, width, sample.size / width, Bitmap.Config.ARGB_8888).let { b -> FileOutputStream(File(dir, "film-${name.replace(' ', '-')}.png")).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        // 2. Neutrals: a grey ramp through the round trip stays grey and monotonic.
        val ramp = IntArray(256) { Color.rgb(it, it, it) }
        val rampOut = ramp.clone().also { m.compensate(it); m.simulate(it) }
        var maxChroma = 0; var drops = 0
        for (i in 0 until 256) {
            val c = rampOut[i]
            maxChroma = maxOf(maxChroma, maxOf(Color.red(c), Color.green(c), Color.blue(c)) - minOf(Color.red(c), Color.green(c), Color.blue(c)))
            if (i > 0 && luma(c) < luma(rampOut[i - 1]) - 4f) drops++
        }
        r.check(maxChroma <= 40, "a grey ramp takes colour through the round trip: chroma up to $maxChroma")
        r.check(drops == 0, "a grey ramp is not monotonic through the round trip: $drops drops")
        // 3. Smoothness of the preview table: adjacent entries (8 levels apart) may not jump by more than a third of the range.
        val lut = m.lut(compensated = true)
        val size = FilmModel.Lut.LUT_N
        var worst = 0; var worstAt = ""
        for (rr in 0 until size) for (g in 0 until size) for (b in 0 until size) {
            val here = lut[(rr * size + g) * size + b]
            for ((axis, d) in listOf(if (rr + 1 < size) lut[((rr + 1) * size + g) * size + b] else here, if (g + 1 < size) lut[(rr * size + g + 1) * size + b] else here, if (b + 1 < size) lut[(rr * size + g) * size + b + 1] else here).withIndex()) {
                val jump = maxOf(Math.abs(Color.red(here) - Color.red(d)), Math.abs(Color.green(here) - Color.green(d)), Math.abs(Color.blue(here) - Color.blue(d)))
                if (jump > worst) { worst = jump; worstAt = "node (${rr * 8},${g * 8},${b * 8}) axis $axis: (${Color.red(here)},${Color.green(here)},${Color.blue(here)}) -> (${Color.red(d)},${Color.green(d)},${Color.blue(d)})" }
            }
        }
        r.check(worst <= 90, "the preview table tears: a step of 8 input levels moves an output channel by $worst at $worstAt")
        // 4. The round trip on colours the film can reach (the compensation did not hit the data's
        //    limits, where the film's gamut ends): close to the input.
        //    Any sane film reaches the colours of modest saturation and middling brightness.
        var err = 0.0; var count = 0; var worstTrip = 0; var worstTripAt = ""
        for (rr in 0 until 256 step 17) for (g in 0 until 256 step 17) for (b in 0 until 256 step 17) {
            val src = hsv(Color.rgb(rr, g, b))
            if (src[1] > 0.35f || src[2] < 0.35f || src[2] > 0.85f) continue
            val px = intArrayOf(Color.rgb(rr, g, b))
            m.compensate(px); m.simulate(px)
            val d = listOf(Color.red(px[0]) - rr, Color.green(px[0]) - g, Color.blue(px[0]) - b)
            err += d.sumOf { it * it.toDouble() } / 3; count++
            val worstHere = d.maxOf { Math.abs(it) }
            if (worstHere > worstTrip) { worstTrip = worstHere; worstTripAt = "($rr,$g,$b) -> (${Color.red(px[0])},${Color.green(px[0])},${Color.blue(px[0])})" }
        }
        val rms = if (count == 0) 0.0 else Math.sqrt(err / count)
        r.check(rms < 6.0, "round trip off by $rms levels RMS on $count modest colours, worst $worstTripAt")
        // 5. Unreachable colours fade towards grey, not towards another hue: on a ramp from grey
        //    to pure green, the output's hue stays green and its saturation never decreases along the ramp.
        var lastSat = 0f; var hueOff = 0; var rampText = ""
        for (i in 0..20) {
            val t = i / 20f
            val px = intArrayOf(Color.rgb(Math.round(128 - 128 * t), Math.round(128 + 127 * t), Math.round(128 - 128 * t)))
            m.compensate(px); m.simulate(px)
            val out = hsv(px[0])
            rampText += " (%d,%d,%d)".format(Color.red(px[0]), Color.green(px[0]), Color.blue(px[0]))
            if (out[1] > 0.1f && hueDistance(out[0], 120f) > 35f) hueOff++
            if (out[1] < lastSat - 0.12f) hueOff++
            lastSat = maxOf(lastSat, out[1])
        }
        r.check(hueOff == 0, "the ramp from grey to green turns or collapses ($hueOff steps):$rampText")
        return r
    }

    private fun luma(c: Int) = 0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)

    /** A dark scene: ramps of dark tints (warm, olive, blue, neutral) from near black to the midtones, the case of a shadowy background. */
    private fun darkRamps(): Pair<IntArray, Int> {
        val w = 512; val h = 256
        val px = IntArray(w * h)
        val tints = listOf(floatArrayOf(1f, 0.85f, 0.7f), floatArrayOf(0.8f, 1f, 0.6f), floatArrayOf(0.6f, 0.7f, 1f), floatArrayOf(1f, 1f, 1f))
        for (y in 0 until h) for (x in 0 until w) {
            val t = tints[(y * 4 / h).coerceIn(0, 3)]
            val level = 8f + 100f * x / w
            px[y * w + x] = Color.rgb(Math.round(level * t[0]).coerceIn(0, 255), Math.round(level * t[1]).coerceIn(0, 255), Math.round(level * t[2]).coerceIn(0, 255))
        }
        return px to w
    }

    @Test
    fun `no model produces artefacts`() {
        val dir = File(System.getProperty("screenshots.dir") ?: "build/screenshots").apply { mkdirs() }
        val photo = Photos.scaledForEditing(load("sample"), 600)
        val sample = IntArray(photo.width * photo.height)
        photo.getPixels(sample, 0, photo.width, 0, 0, photo.width, photo.height)
        val (ramps, rampsWidth) = darkRamps()
        val failures = ArrayList<String>()
        for ((name, m) in models()) {
            val report = judge(name, m, sample, photo.width, dir)
            // The dark ramps: no tint may come out as a vivid colour, nor brighter than the input.
            val fast = ramps.clone().also { m.simulateFast(it, compensated = true) }
            // (The film's own black is the floor: a model whose blacks are lifted lifts every dark input to it.)
            val floor = m.toneCurves().maxOf { it[0] } / 255f
            var vivid = 0; var brighter = 0
            for (i in ramps.indices) {
                val out = hsv(fast[i]); val src = hsv(ramps[i])
                if (out[1] > 0.5f && out[2] > 0.2f) vivid++
                if (out[2] > maxOf(src[2], floor) + 0.25f) brighter++
            }
            report.check(vivid == 0, "dark tints come out vivid on $vivid pixels of the ramps")
            report.check(brighter == 0, "dark tints come out much brighter on $brighter pixels of the ramps")
            println("$name: " + (if (report.problems.isEmpty()) "ok" else report.problems.joinToString("; ")))
            for (p in report.problems) failures += "$name: $p"
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }
}
