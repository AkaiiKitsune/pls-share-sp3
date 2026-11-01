package instax

/**
 * The printer's raw image format: uncompressed 8-bit RGB at exactly the print size.
 * Rows go from the bottom of the print to the top, and each row holds all of its red values,
 * then all green, then all blue.
 *
 * Pixels are packed ARGB ints (`0xAARRGGBB`), the layout both Android's `Bitmap.getPixels`
 * and `BufferedImage.getRGB` use.
 */
object RawImage {
    fun byteSize(width: Int, height: Int): Int = width * height * 3

    fun encode(pixels: IntArray, width: Int, height: Int): ByteArray {
        require(pixels.size == width * height) { "expected ${width * height} pixels for ${width}x$height, got ${pixels.size}" }
        val out = ByteArray(byteSize(width, height))
        var o = 0
        for (y in height - 1 downTo 0) {
            val row = y * width
            for (x in 0 until width) out[o++] = (pixels[row + x] shr 16).toByte()
            for (x in 0 until width) out[o++] = (pixels[row + x] shr 8).toByte()
            for (x in 0 until width) out[o++] = pixels[row + x].toByte()
        }
        return out
    }

    fun decode(data: ByteArray, width: Int, height: Int): IntArray {
        require(data.size == byteSize(width, height)) { "expected ${byteSize(width, height)} bytes for ${width}x$height, got ${data.size}" }
        val pixels = IntArray(width * height)
        var i = 0
        for (y in height - 1 downTo 0) {
            val row = y * width
            val r = i
            val g = i + width
            val b = i + 2 * width
            for (x in 0 until width) {
                pixels[row + x] = (0xFF shl 24) or ((data[r + x].toInt() and 0xFF) shl 16) or
                    ((data[g + x].toInt() and 0xFF) shl 8) or (data[b + x].toInt() and 0xFF)
            }
            i += 3 * width
        }
        return pixels
    }
}
