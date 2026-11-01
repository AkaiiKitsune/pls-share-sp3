package instax

import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

enum class Fit { CROP, CONTAIN }

/** Image helpers for the JVM, using java.awt. Android has its own in the app module. */
object DesktopImages {
    fun load(file: File): BufferedImage =
        ImageIO.read(file) ?: throw IllegalArgumentException("cannot read ${file.path} as an image")

    /**
     * An [ImageSource] that scales [image] to whatever size the printer asks for.
     * With [Fit.CROP] the image fills the print and the overflow is cropped evenly. With
     * [Fit.CONTAIN] the whole image is scaled to fit and centred on white.
     */
    fun source(image: BufferedImage, fit: Fit = Fit.CROP) = ImageSource { width, height ->
        val scaled = prepare(image, width, height, fit)
        scaled.getRGB(0, 0, width, height, null, 0, width)
    }

    fun prepare(image: BufferedImage, width: Int, height: Int, fit: Fit): BufferedImage {
        val target = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = target.createGraphics()
        try {
            g.color = Color.WHITE
            g.fillRect(0, 0, width, height)
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            val scaleX = width.toDouble() / image.width
            val scaleY = height.toDouble() / image.height
            val scale = if (fit == Fit.CROP) maxOf(scaleX, scaleY) else minOf(scaleX, scaleY)
            val w = Math.round(image.width * scale).toInt()
            val h = Math.round(image.height * scale).toInt()
            g.drawImage(image, (width - w) / 2, (height - h) / 2, w, h, null)
        } finally {
            g.dispose()
        }
        return target
    }

    fun fromRaw(data: ByteArray, width: Int, height: Int): BufferedImage {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        image.setRGB(0, 0, width, height, RawImage.decode(data, width, height), 0, width)
        return image
    }
}
