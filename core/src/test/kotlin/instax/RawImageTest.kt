package instax

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RawImageTest {
    @Test
    fun `encode matches the reference layout`() {
        // Same vector as the Python reference: a 2x2 image, pixels (1,2,3) (4,5,6) / (7,8,9) (10,11,12).
        val pixels = intArrayOf(rgb(1, 2, 3), rgb(4, 5, 6), rgb(7, 8, 9), rgb(10, 11, 12))
        val expected = byteArrayOf(7, 10, 8, 11, 9, 12, 1, 4, 2, 5, 3, 6)
        assertArrayEquals(expected, RawImage.encode(pixels, 2, 2))
    }

    @Test
    fun `decode reverses encode`() {
        val width = 13
        val height = 7
        val pixels = IntArray(width * height) { i -> rgb(i * 7 and 0xFF, i * 13 and 0xFF, i * 31 and 0xFF) }
        val data = RawImage.encode(pixels, width, height)
        assertEquals(width * height * 3, data.size)
        assertArrayEquals(pixels, RawImage.decode(data, width, height))
    }

    @Test
    fun `sp3 image is 1920000 bytes`() {
        assertEquals(1_920_000, RawImage.byteSize(800, 800))
        assertEquals(1_440_000, RawImage.byteSize(600, 800))
    }

    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
}
