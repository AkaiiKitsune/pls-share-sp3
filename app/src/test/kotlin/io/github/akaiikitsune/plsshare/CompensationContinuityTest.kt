package io.github.akaiikitsune.plsshare

import android.graphics.BitmapFactory
import android.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Compensation then simulation must stay continuous up to the clipped highlights: no colour jump when a channel hits 255. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [31])
class CompensationContinuityTest {
    @Test
    fun `clipped highlights do not jump in colour`() {
        val day = ChartReader.read(javaClass.getResourceAsStream("/chart-daylight.jpg")!!.use { BitmapFactory.decodeStream(it)!! }, floatArrayOf(639f, 1214f, 2332f, 1214f, 2332f, 2824f, 639f, 2824f), FilmType.COLOR)
        val models = listOf(ChartReader.fit(day, FilmType.COLOR).model, FilmModel.defaults(FilmType.COLOR))
        val ramps = listOf<(Int) -> Int>(
            { v -> Color.rgb((v - 20).coerceIn(0, 255), (v - 8).coerceIn(0, 255), v) },   // bluish
            { v -> Color.rgb(v, v, v) },                                                   // neutral
            { v -> Color.rgb(v, (v - 10).coerceIn(0, 255), (v - 25).coerceIn(0, 255)) }, // warm
            { v -> Color.rgb((v - 30).coerceIn(0, 255), v, (v - 5).coerceIn(0, 255)) },  // greenish
        )
        for (m in models) for (ramp in ramps) {
            var prev: Int? = null
            for (v in 200..255) {
                for (fast in listOf(false, true)) {
                    val px = intArrayOf(ramp(v))
                    if (fast) m.simulateFast(px, true) else { m.compensate(px); m.simulate(px) }
                    if (!fast) {
                        val p = prev
                        if (p != null) {
                            val d = maxOf(Math.abs(Color.red(px[0]) - Color.red(p)), Math.abs(Color.green(px[0]) - Color.green(p)), Math.abs(Color.blue(px[0]) - Color.blue(p)))
                            assertTrue("step at v=$v: ${Integer.toHexString(p)} -> ${Integer.toHexString(px[0])}", d <= 6)
                        }
                        prev = px[0]
                    }
                    // Whites must come out close to neutral white, never tinted beyond the source's own cast.
                    val cast = maxOf(Color.red(px[0]), Color.green(px[0]), Color.blue(px[0])) - minOf(Color.red(px[0]), Color.green(px[0]), Color.blue(px[0]))
                    val srcCast = maxOf(Color.red(ramp(v)), Color.green(ramp(v)), Color.blue(ramp(v))) - minOf(Color.red(ramp(v)), Color.green(ramp(v)), Color.blue(ramp(v)))
                    assertTrue("cast at v=$v fast=$fast: $cast vs source $srcCast", cast <= srcCast + 14)
                }
            }
        }
    }
}
