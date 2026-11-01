package io.github.akaiikitsune.plsshare

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import instax.ImageSource

/** How the photo sits in the square print, plus colour adjustments. All values are print-relative. */
class EditState {
    /** Zoom relative to "fill the square". 1 = fill, smaller shows white borders. */
    var scale = 1f

    /** Image centre offset as a fraction of the print size. 0 = centred. */
    var offsetX = 0f
    var offsetY = 0f

    /** Degrees, multiples of 90. */
    var rotation = 0

    /** -100..100 */
    var brightness = 0f

    /** -100..100 */
    var contrast = 0f

    /** 0..2, 1 = unchanged. */
    var saturation = 1f

    /** A [Filters] preset id, applied before the adjustments above. */
    var filter = "normal"

    fun reset() {
        scale = 1f; offsetX = 0f; offsetY = 0f; rotation = 0
        brightness = 0f; contrast = 0f; saturation = 1f; filter = "normal"
    }

    fun copyFrom(other: EditState) {
        scale = other.scale; offsetX = other.offsetX; offsetY = other.offsetY; rotation = other.rotation
        brightness = other.brightness; contrast = other.contrast; saturation = other.saturation; filter = other.filter
    }

    companion object {
        const val MIN_SCALE = 0.25f
        const val MAX_SCALE = 6f
    }
}

/** Draws a photo into a square print according to an [EditState]. Used by the editor, the preview and the printer. */
object PrintRenderer {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    /** Width and height of the photo once rotated, in pixels. */
    fun rotatedSize(bitmap: Bitmap, state: EditState): Pair<Int, Int> =
        if (state.rotation % 180 == 0) bitmap.width to bitmap.height else bitmap.height to bitmap.width

    /** The [EditState.scale] that shows the whole photo inside the square. */
    fun containScale(bitmap: Bitmap, state: EditState): Float {
        val (w, h) = rotatedSize(bitmap, state)
        val fill = maxOf(1f / w, 1f / h)
        val contain = minOf(1f / w, 1f / h)
        return contain / fill
    }

    /** Where the photo sits in the square, as fractions of the print size: left, top, right, bottom. */
    fun imageRect(bitmap: Bitmap, state: EditState): FloatArray {
        val (w, h) = rotatedSize(bitmap, state)
        val fill = maxOf(1f / w, 1f / h) * state.scale
        val halfW = w * fill / 2
        val halfH = h * fill / 2
        val cx = 0.5f + state.offsetX
        val cy = 0.5f + state.offsetY
        return floatArrayOf(cx - halfW, cy - halfH, cx + halfW, cy + halfH)
    }

    /** Keep the photo at least touching the square, so it cannot be dragged out of sight. */
    fun clamp(bitmap: Bitmap, state: EditState) {
        state.scale = state.scale.coerceIn(EditState.MIN_SCALE, EditState.MAX_SCALE)
        val (w, h) = rotatedSize(bitmap, state)
        val fill = maxOf(1f / w, 1f / h) * state.scale
        val halfW = w * fill / 2
        val halfH = h * fill / 2
        state.offsetX = state.offsetX.coerceIn(-0.5f - halfW + 0.1f, 0.5f + halfW - 0.1f)
        state.offsetY = state.offsetY.coerceIn(-0.5f - halfH + 0.1f, 0.5f + halfH - 0.1f)
    }

    fun colorMatrix(state: EditState): ColorMatrix {
        val matrix = ColorMatrix(Filters.byId(state.filter).matrix)
        val saturation = ColorMatrix().apply { setSaturation(state.saturation) }
        matrix.postConcat(saturation)
        val c = 1f + state.contrast / 100f
        val b = state.brightness / 100f * 255f
        val t = (1f - c) * 128f + b
        matrix.postConcat(ColorMatrix(floatArrayOf(
            c, 0f, 0f, 0f, t,
            0f, c, 0f, 0f, t,
            0f, 0f, c, 0f, t,
            0f, 0f, 0f, 1f, 0f,
        )))
        return matrix
    }

    /** Draw the print into a [size] x [size] square whose top-left corner is at ([left], [top]). */
    fun draw(canvas: Canvas, bitmap: Bitmap, state: EditState, left: Float, top: Float, size: Float) {
        canvas.save()
        canvas.clipRect(left, top, left + size, top + size)
        canvas.drawColor(Color.WHITE)
        val (w, h) = rotatedSize(bitmap, state)
        val fill = maxOf(size / w, size / h) * state.scale
        val matrix = Matrix()
        matrix.postTranslate(-bitmap.width / 2f, -bitmap.height / 2f)
        matrix.postRotate(state.rotation.toFloat())
        matrix.postScale(fill, fill)
        matrix.postTranslate(left + size / 2 + state.offsetX * size, top + size / 2 + state.offsetY * size)
        paint.colorFilter = ColorMatrixColorFilter(colorMatrix(state))
        canvas.drawBitmap(bitmap, matrix, paint)
        canvas.restore()
    }

    fun render(bitmap: Bitmap, state: EditState, size: Int): Bitmap {
        val target = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        draw(Canvas(target), bitmap, state, 0f, 0f, size.toFloat())
        return target
    }

    /** An [ImageSource] the printer client can ask for pixels at its own print size. */
    fun source(bitmap: Bitmap, state: EditState) = ImageSource { width, height ->
        // The SP-2 prints 600x800: render a square at the larger side and crop the middle.
        val side = maxOf(width, height)
        val square = render(bitmap, state, side)
        val pixels = IntArray(width * height)
        square.getPixels(pixels, 0, width, (side - width) / 2, (side - height) / 2, width, height)
        square.recycle()
        pixels
    }
}

/** Loading photos from the gallery. */
object Photos {
    /** The longest side a loaded photo is kept at. The print is 800 px, so this leaves room for zooming. */
    private const val MAX_SIDE = 2400

    /** Decode a photo with its EXIF rotation applied, downsampled to about [MAX_SIDE], as a software bitmap. */
    fun load(resolver: ContentResolver, uri: Uri): Bitmap {
        val source = ImageDecoder.createSource(resolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = false
            val longest = maxOf(info.size.width, info.size.height)
            if (longest > MAX_SIDE) decoder.setTargetSampleSize(longest / MAX_SIDE)
        }
    }
}
