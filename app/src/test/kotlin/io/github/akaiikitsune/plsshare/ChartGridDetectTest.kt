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

/** The colour grid aligns the chart on real photos: five backgrounds, three lights, one tilt, plus the two older photos. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [31])
class ChartGridDetectTest {
    private fun load(name: String) = javaClass.getResourceAsStream("/$name.jpg")!!.use { BitmapFactory.decodeStream(it)!! }

    private fun luma(c: FloatArray) = 0.299f * c[0] + 0.587f * c[1] + 0.114f * c[2]

    @Test
    fun `every real photo is aligned and reads the same print`() {
        val reference = ChartReader.read(load("chart-daylight"), floatArrayOf(639f, 1214f, 2332f, 1214f, 2332f, 2824f, 639f, 2824f), FilmType.COLOR)
        val names = listOf("chart-bamboo", "chart-fabric", "chart-stickers", "chart-wood-tilted", "chart-wood", "chart-daylight", "chart-flash")
        val failures = ArrayList<String>()
        for (name in names) {
            val photo = load(name)
            val corners = ChartReader.detectCorners(photo, FilmType.COLOR)
            println("$name: ${corners?.joinToString { "%.0f".format(it) } ?: "not found"}")
            if (corners == null) { failures += "$name: not found"; continue }
            val reading = ChartReader.read(photo, corners, FilmType.COLOR)
            val fit = ChartReader.fit(reading, FilmType.COLOR)
            val greys = reading.greys.map { luma(it) }
            val worstGrey = greys.indices.maxOf { Math.abs(greys[it] - luma(reference.greys[it])) }
            val worstPatch = reading.patches.indices.maxOf { i -> (0 until 3).maxOf { c -> Math.abs(reading.patches[i][c] - reference.patches[i][c]) } }
            println("   printed white %.2f, greys vs reference worst %.3f, patches worst %.3f, fit greys %.1f %% colours %.1f %%".format(
                luma(reading.printedWhite), worstGrey, worstPatch, fit.greyRms, fit.colourRmsAfter))
            if (luma(reading.printedWhite) !in 0.85f..1.15f) failures += "$name: printed white off"
            // The flash lifts the blacks and midtones on its own (seen with hand-picked corners too).
            if (worstGrey > 0.15f) failures += "$name: greys differ from the reference by $worstGrey"
            // Coloured light (tungsten on the wood photos) moves the camera's saturated channels: looser than the greys.
            if (worstPatch > 0.4f) failures += "$name: patches differ from the reference by $worstPatch"
            if (fit.greyRms > 6.5) failures += "$name: grey fit ${fit.greyRms}" // warm tungsten light on the wood photos
        }
        assertTrue(failures.joinToString("; "), failures.isEmpty())
    }

    @Test
    fun `the daylight photo lands near the hand-picked corners`() {
        val photo = load("chart-daylight")
        val found = ChartReader.detectCorners(photo, FilmType.COLOR)
        assertNotNull(found)
        val hand = floatArrayOf(639f, 1214f, 2332f, 1214f, 2332f, 2824f, 639f, 2824f)
        for (i in 0 until 8) assertEquals("corner value $i", hand[i], found!![i], 80f)
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [31])
class AnchorMagnetTest {
    private fun load(name: String) = javaClass.getResourceAsStream("/$name.jpg")!!.use { BitmapFactory.decodeStream(it)!! }

    @Test
    fun `a marker released near a corner patch snaps to its centre, and the corners round-trip`() {
        for (name in listOf("chart-wood", "chart-bamboo", "chart-fabric", "chart-stickers")) {
            val photo = load(name)
            val corners = ChartReader.detectCorners(photo, FilmType.COLOR)!!
            val anchors = ChartReader.anchorsFromCorners(corners)
            val back = ChartReader.cornersFromAnchors(anchors)
            for (i in 0 until 8) assertEquals(corners[i], back[i], 0.5f)
            val candidates = ChartReader.patchCandidates(photo)
            val side = maxOf(photo.width, photo.height)
            for (k in 0 until 4) for ((ox, oy) in listOf(12 to 7, -10 to 9, 8 to -11)) {
                val snapped = ChartReader.snapToPatch(candidates, anchors[2 * k] + ox, anchors[2 * k + 1] + oy, side)
                val before = Math.hypot(ox.toDouble(), oy.toDouble())
                val after = snapped?.let { Math.hypot((it[0] - anchors[2 * k]).toDouble(), (it[1] - anchors[2 * k + 1]).toDouble()) }
                println("$name anchor $k offset ($ox,$oy): %.1f px -> %s".format(before, after?.let { "%.1f px".format(it) } ?: "no patch"))
                // The blob centre and the least-squares prediction differ by a few pixels; the snap must land on the blob.
                assertTrue("$name anchor $k: snapped $after", after != null && after < 12.0)
            }
        }
    }
}
