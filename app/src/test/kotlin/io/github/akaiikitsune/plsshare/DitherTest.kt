package io.github.akaiikitsune.plsshare

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
class DitherTest {
    @Test
    fun `dithered compensation keeps the mean and breaks the steps`() {
        val m = FilmModel.defaults(FilmType.COLOR)
        val w = 256; val h = 64
        // A smooth horizontal grey ramp, 0..255 over 256 columns.
        val src = IntArray(w * h) { Color.rgb(it % w, it % w, it % w) }
        val plain = src.clone(); m.compensate(plain)
        val dithered = src.clone(); m.compensateDithered(dithered, w)
        // Judged as the film prints them: inputs the film cannot tell apart (below its black, above
        // its white) may differ in the data without any difference on paper.
        val plainPrint = plain.clone(); m.simulate(plainPrint)
        val ditheredPrint = dithered.clone(); m.simulate(ditheredPrint)
        // Within the film's range, the dithered print averages to the wanted level; the plain
        // 8-bit compensation can only land on the levels its stepped data allows.
        var worstDithered = 0.0; var worstPlain = 0.0
        for (x in 60..220) {
            var sum = 0.0
            for (y in 0 until h) sum += Color.red(ditheredPrint[y * w + x])
            worstDithered = maxOf(worstDithered, Math.abs(sum / h - x))
            worstPlain = maxOf(worstPlain, Math.abs(Color.red(plainPrint[x]) - x).toDouble())
        }
        println("on paper, worst deviation from the wanted level: dithered %.2f, plain %.2f".format(worstDithered, worstPlain))
        assertTrue("dithered print averages to the wanted level", worstDithered <= 2.5)
        assertTrue("dithering beats the stepped compensation", worstDithered < worstPlain)
        // The dithered data has more distinct levels in the midtones than the stepped plain one.
        val plainDistinct = (60 until 200).map { Color.red(plain[it]) }.toSet().size
        val ditheredDistinct = (60 until 200).flatMap { x -> (0 until h).map { y -> Color.red(dithered[y * w + x]) } }.toSet().size
        println("distinct midtone levels: plain $plainDistinct, dithered $ditheredDistinct")
        assertTrue(ditheredDistinct >= plainDistinct)
        // Each pixel sits on one of the two data levels around the wanted one; in the shadows, where
        // the film's curve is steep, one data level is up to eight paper levels, so that is the bound.
        for (y in 0 until h) for (x in 60..220) {
            val d = Math.abs(Color.red(ditheredPrint[y * w + x]) - x)
            assertTrue("pixel ($x,$y) off by $d on paper", d <= 10)
        }
        // A black input compensates to a level on the film's black plateau (its end, so that the
        // darks stay continuous with the shadows just above), and prints as the film's black.
        assertEquals(plainPrint[0] and 0xFFFFFF, intArrayOf(Color.rgb(0, 0, 0)).also { m.simulate(it) }[0] and 0xFFFFFF)
        assertTrue("black stays on the plateau: ${Color.red(plain[0])}", Color.red(plain[0]) <= Math.round(m.blackClip * 255) + 1)
    }

    @Test
    fun `settings survive a JSON round trip`() {
        val m = FilmModel.defaults(FilmType.COLOR).apply { gamma = 0.77f; hueShift[2] = 9f; hueGain[4] = 1.3f; hueValue[1] = 0.6f; printSharpness = 50f }
        val back = FilmModel.fromJson(m.toJson(), FilmType.COLOR)
        assertEquals(m.gamma, back.gamma, 1e-5f)
        assertEquals(9f, back.hueShift[2], 1e-5f)
        assertEquals(1.3f, back.hueGain[4], 1e-5f)
        assertEquals(0.6f, back.hueValue[1], 1e-5f)
        assertEquals(50f, back.printSharpness, 1e-5f)
    }
}
