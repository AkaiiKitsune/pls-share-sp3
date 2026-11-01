package instax

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage

class DesktopImagesTest {
    private fun image(w: Int, h: Int, colour: Int) = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB).also { img ->
        for (y in 0 until h) for (x in 0 until w) img.setRGB(x, y, colour)
    }

    @Test
    fun `crop fills the whole print`() {
        val pixels = DesktopImages.source(image(400, 100, 0xFF0000), Fit.CROP).render(800, 800)
        assertEquals(800 * 800, pixels.size)
        assertEquals(0xFFFF0000.toInt(), pixels[0])
        assertEquals(0xFFFF0000.toInt(), pixels[pixels.size - 1])
    }

    @Test
    fun `contain centres on white`() {
        val pixels = DesktopImages.source(image(400, 100, 0x0000FF), Fit.CONTAIN).render(800, 800)
        assertEquals(0xFFFFFFFF.toInt(), pixels[0]) // top-left stays white
        assertEquals(0xFF0000FF.toInt(), pixels[400 * 800 + 400]) // centre is the image
    }

    @Test
    fun `raw round trip through a buffered image`() {
        val src = DesktopImages.prepare(image(10, 10, 0x123456), 10, 10, Fit.CROP)
        val raw = RawImage.encode(src.getRGB(0, 0, 10, 10, null, 0, 10), 10, 10)
        assertEquals(0xFF123456.toInt(), DesktopImages.fromRaw(raw, 10, 10).getRGB(3, 3))
    }
}
