package io.github.akaiikitsune.plsshare

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import instax.ImageSource

enum class Fit { CROP, CONTAIN }

/** Loading photos from the gallery and shaping them for the print. */
object Photos {
    /** The longest side a loaded photo is kept at. The print is 800 px, so this leaves room for cropping. */
    private const val MAX_SIDE = 2048

    /**
     * Decode a photo with its EXIF rotation applied, downsampled to about [MAX_SIDE], as a
     * software bitmap so its pixels can be read.
     */
    fun load(resolver: ContentResolver, uri: Uri): Bitmap {
        val source = ImageDecoder.createSource(resolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = false
            val longest = maxOf(info.size.width, info.size.height)
            if (longest > MAX_SIDE) decoder.setTargetSampleSize(longest / MAX_SIDE)
        }
    }

    /**
     * Scale [bitmap] to exactly [width] x [height]. With [Fit.CROP] the photo fills the print and
     * the overflow is cut evenly from both sides. With [Fit.CONTAIN] the whole photo is kept and
     * centred on white.
     */
    fun prepare(bitmap: Bitmap, width: Int, height: Int, fit: Fit): Bitmap {
        val target = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(target)
        canvas.drawColor(Color.WHITE)
        val scaleX = width.toFloat() / bitmap.width
        val scaleY = height.toFloat() / bitmap.height
        val scale = if (fit == Fit.CROP) maxOf(scaleX, scaleY) else minOf(scaleX, scaleY)
        val w = Math.round(bitmap.width * scale)
        val h = Math.round(bitmap.height * scale)
        val left = (width - w) / 2
        val top = (height - h) / 2
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        canvas.drawBitmap(bitmap, null, Rect(left, top, left + w, top + h), paint)
        return target
    }

    /** An [ImageSource] the printer client can ask for pixels at its own print size. */
    fun source(bitmap: Bitmap, fit: Fit) = ImageSource { width, height ->
        val prepared = prepare(bitmap, width, height, fit)
        val pixels = IntArray(width * height)
        prepared.getPixels(pixels, 0, width, 0, 0, width, height)
        prepared.recycle()
        pixels
    }
}
