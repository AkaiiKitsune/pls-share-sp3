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
import java.text.DateFormat
import java.util.Date

/** A text stamped on the print: a caption, or the date / time of the photo. Positions are fractions of the print size. */
class Overlay(
    var kind: Kind = Kind.TEXT,
    var text: String = "",
    var x: Float = 0.5f,
    var y: Float = 0.92f,
    /** Text height as a fraction of the print size. */
    var size: Float = 0.045f,
    var dark: Boolean = false,
    var style: Style = Style.SHADOW,
    var font: Font = Font.BOLD,
    /** The last anchor used by the placement grid, re-applied when the margin changes. */
    var anchor: Anchor? = null,
    /** Margin between the text and the edge for anchored placement, as a fraction of the print size. */
    var margin: Float = 0.06f,
) {
    enum class Kind { TEXT, DATE, TIME, DATETIME }
    enum class Style { SHADOW, OUTLINE, PILL }

    /** System font families, so no font file ships with the app. Missing families fall back to sans-serif. */
    enum class Font(val family: String, val typefaceStyle: Int) {
        BOLD("sans-serif", android.graphics.Typeface.BOLD),
        LIGHT("sans-serif-light", android.graphics.Typeface.NORMAL),
        CONDENSED("sans-serif-condensed", android.graphics.Typeface.BOLD),
        SERIF("serif", android.graphics.Typeface.NORMAL),
        MONO("monospace", android.graphics.Typeface.NORMAL),
        CURSIVE("cursive", android.graphics.Typeface.NORMAL),
        CASUAL("casual", android.graphics.Typeface.NORMAL);

        val typeface: android.graphics.Typeface by lazy { android.graphics.Typeface.create(family, typefaceStyle) }
    }

    /** Anchor positions for the quick placement grid: -1 = left/top edge, 0 = centre, 1 = right/bottom edge. */
    enum class Anchor(val h: Int, val v: Int) {
        TOP_LEFT(-1, -1), TOP(0, -1), TOP_RIGHT(1, -1),
        LEFT(-1, 0), CENTER(0, 0), RIGHT(1, 0),
        BOTTOM_LEFT(-1, 1), BOTTOM(0, 1), BOTTOM_RIGHT(1, 1),
    }

    /** Place the text against an anchor, keeping [margin] of the print between the text and the edge. */
    fun place(anchor: Anchor, widthFraction: Float) {
        this.anchor = anchor
        val halfW = widthFraction / 2
        val halfH = size * 0.7f
        x = when (anchor.h) { -1 -> margin + halfW; 1 -> 1f - margin - halfW; else -> 0.5f }.coerceIn(0.02f, 0.98f)
        y = when (anchor.v) { -1 -> margin + halfH; 1 -> 1f - margin - halfH; else -> 0.5f }.coerceIn(0.02f, 0.98f)
    }

    fun copy() = Overlay(kind, text, x, y, size, dark, style, font, anchor, margin)

    /** The string to draw, resolving date kinds against [photoDate] (or now). */
    fun resolve(photoDate: Long?): String {
        val date = Date(photoDate ?: System.currentTimeMillis())
        return when (kind) {
            Kind.TEXT -> text
            Kind.DATE -> DateFormat.getDateInstance(DateFormat.MEDIUM).format(date)
            Kind.TIME -> DateFormat.getTimeInstance(DateFormat.SHORT).format(date)
            Kind.DATETIME -> DateFormat.getDateInstance(DateFormat.MEDIUM).format(date) + "  " + DateFormat.getTimeInstance(DateFormat.SHORT).format(date)
        }
    }
}

/** How the photo sits in the square print, plus colour adjustments. All values are print-relative. */
class EditState {
    /** Zoom relative to "fill the square". 1 = fill, smaller shows white borders. */
    var scale = 1f

    /** Image centre offset as a fraction of the print size. 0 = centred. */
    var offsetX = 0f
    var offsetY = 0f

    /** Degrees, clockwise, 0 ≤ rotation < 360. The "rotate" button adds 90, a two-finger twist is continuous. */
    var rotation = 0f

    /** -100..100 */
    var brightness = 0f

    /** -100..100 */
    var contrast = 0f

    /** 0..2, 1 = unchanged. */
    var saturation = 1f

    /** -100..100: lift or darken the dark tones (not the blacks themselves). */
    var shadows = 0f

    /** -100..100: recover or push the bright tones (not the whites themselves). */
    var highlights = 0f

    /** -100..100: black point. */
    var blacks = 0f

    /** -100..100: white point. */
    var whites = 0f

    /** -100..100: cold to warm. */
    var temperature = 0f

    /** -100..100: green to magenta. */
    var tint = 0f

    /** 0..100: unsharp mask amount. */
    var sharpness = 0f

    /** 0..100: darkened corners. */
    var vignette = 0f

    /** A [Filters] preset id, applied before the adjustments above. */
    var filter = "normal"

    /** Border around the photo as a fraction of the print size (0 = none). */
    var frameWidth = 0f

    /** Extra band under the photo, Polaroid style, as a fraction of the print size. */
    var frameBand = 0f

    /** Corner radius of the photo area as a fraction of the print size. */
    var cornerRadius = 0f

    /** Colour of the border and band. */
    var frameColor = Color.WHITE

    /** Texts stamped on the print, drawn last. */
    val overlays = mutableListOf<Overlay>()

    /** When the photo was taken (EXIF), used by date overlays. Null means "now". */
    var photoDate: Long? = null

    fun reset() {
        scale = 1f; offsetX = 0f; offsetY = 0f; rotation = 0f
        brightness = 0f; contrast = 0f; saturation = 1f; filter = "normal"
        shadows = 0f; highlights = 0f; blacks = 0f; whites = 0f
        temperature = 0f; tint = 0f; sharpness = 0f; vignette = 0f
        frameWidth = 0f; frameBand = 0f; cornerRadius = 0f; frameColor = Color.WHITE
        overlays.clear()
    }

    fun copyFrom(other: EditState) {
        overlays.clear()
        for (o in other.overlays) overlays += o.copy()
        photoDate = other.photoDate
        scale = other.scale; offsetX = other.offsetX; offsetY = other.offsetY; rotation = other.rotation
        brightness = other.brightness; contrast = other.contrast; saturation = other.saturation; filter = other.filter
        shadows = other.shadows; highlights = other.highlights; blacks = other.blacks; whites = other.whites
        temperature = other.temperature; tint = other.tint; sharpness = other.sharpness; vignette = other.vignette
        frameWidth = other.frameWidth; frameBand = other.frameBand; cornerRadius = other.cornerRadius; frameColor = other.frameColor
    }

    /** Copy only the look (filter, colours, tones, details, frame), not the framing or the texts. */
    fun copyLookFrom(other: EditState) {
        brightness = other.brightness; contrast = other.contrast; saturation = other.saturation; filter = other.filter
        shadows = other.shadows; highlights = other.highlights; blacks = other.blacks; whites = other.whites
        temperature = other.temperature; tint = other.tint; sharpness = other.sharpness; vignette = other.vignette
        frameWidth = other.frameWidth; frameBand = other.frameBand; cornerRadius = other.cornerRadius; frameColor = other.frameColor
    }

    /** The photo area inside the frame and band, as fractions of the print: left, top, right, bottom. */
    fun innerRect(): FloatArray = floatArrayOf(frameWidth, frameWidth, 1f - frameWidth, 1f - frameWidth - frameBand)

    /** Whether any per-pixel step (tone curve, sharpening) is needed beyond the colour matrix. */
    val needsPixelPass: Boolean
        get() = brightness != 0f || contrast != 0f || shadows != 0f || highlights != 0f || blacks != 0f || whites != 0f || sharpness != 0f

    companion object {
        const val MIN_SCALE = 0.25f
        const val MAX_SCALE = 6f
    }
}

/** Draws a photo into a square print according to an [EditState]. Used by the editor, the preview and the printer. */
object PrintRenderer {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    /** Width and height of the box around the rotated photo, in pixels. */
    fun rotatedSize(bitmap: Bitmap, state: EditState): Pair<Float, Float> {
        val rad = Math.toRadians(state.rotation.toDouble())
        val c = kotlin.math.abs(kotlin.math.cos(rad)).toFloat()
        val sn = kotlin.math.abs(kotlin.math.sin(rad)).toFloat()
        val w = bitmap.width * c + bitmap.height * sn
        val h = bitmap.width * sn + bitmap.height * c
        return w to h
    }

    /**
     * The scale (print size = 1) at which the rotated photo exactly covers the photo area (the
     * square minus frame and band), so that [EditState.scale] = 1 means "fill" whatever the angle.
     * A w×h photo rotated by θ covers a W×H area once w·s ≥ W|cos θ| + H|sin θ| and h·s ≥ W|sin θ| + H|cos θ|.
     */
    fun fillScale(bitmap: Bitmap, state: EditState): Float {
        val inner = state.innerRect()
        val areaW = inner[2] - inner[0]
        val areaH = inner[3] - inner[1]
        val rad = Math.toRadians(state.rotation.toDouble())
        val c = kotlin.math.abs(kotlin.math.cos(rad)).toFloat()
        val sn = kotlin.math.abs(kotlin.math.sin(rad)).toFloat()
        return maxOf((areaW * c + areaH * sn) / bitmap.width, (areaW * sn + areaH * c) / bitmap.height)
    }

    /** The [EditState.scale] that shows the whole photo inside the photo area. */
    fun containScale(bitmap: Bitmap, state: EditState): Float {
        val inner = state.innerRect()
        val (w, h) = rotatedSize(bitmap, state)
        return minOf((inner[2] - inner[0]) / w, (inner[3] - inner[1]) / h) / fillScale(bitmap, state)
    }

    /** Where the box around the photo sits, as fractions of the print size: left, top, right, bottom. */
    fun imageRect(bitmap: Bitmap, state: EditState): FloatArray {
        val inner = state.innerRect()
        val (w, h) = rotatedSize(bitmap, state)
        val s = fillScale(bitmap, state) * state.scale
        val halfW = w * s / 2
        val halfH = h * s / 2
        val cx = (inner[0] + inner[2]) / 2 + state.offsetX
        val cy = (inner[1] + inner[3]) / 2 + state.offsetY
        return floatArrayOf(cx - halfW, cy - halfH, cx + halfW, cy + halfH)
    }

    /** Keep the photo at least touching the square, so it cannot be dragged out of sight. */
    fun clamp(bitmap: Bitmap, state: EditState) {
        state.scale = state.scale.coerceIn(EditState.MIN_SCALE, EditState.MAX_SCALE)
        state.rotation = ((state.rotation % 360f) + 360f) % 360f
        val r = imageRect(bitmap, state)
        val inner = state.innerRect()
        val halfW = (r[2] - r[0]) / 2
        val halfH = (r[3] - r[1]) / 2
        val areaHalfW = (inner[2] - inner[0]) / 2
        val areaHalfH = (inner[3] - inner[1]) / 2
        state.offsetX = state.offsetX.coerceIn(-areaHalfW - halfW + 0.1f, areaHalfW + halfW - 0.1f)
        state.offsetY = state.offsetY.coerceIn(-areaHalfH - halfH + 0.1f, areaHalfH + halfH - 0.1f)
    }

    /** The colour matrix part of the look: preset, saturation, temperature and tint. */
    fun colorMatrix(state: EditState): ColorMatrix {
        val matrix = ColorMatrix(Filters.byId(state.filter).matrix)
        val saturation = ColorMatrix().apply { setSaturation(state.saturation) }
        matrix.postConcat(saturation)
        val t = state.temperature / 100f
        val g = state.tint / 100f
        matrix.postConcat(ColorMatrix(floatArrayOf(
            1f + 0.18f * t + 0.05f * g, 0f, 0f, 0f, 0f,
            0f, 1f - 0.12f * g, 0f, 0f, 0f,
            0f, 0f, 1f - 0.18f * t + 0.05f * g, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        )))
        return matrix
    }

    /** Vignette: a radial darkening drawn over the photo area. */
    private fun drawVignette(canvas: Canvas, state: EditState, left: Float, top: Float, width: Float, height: Float) {
        if (state.vignette <= 0f) return
        val alpha = (state.vignette / 100f * 200f).toInt().coerceIn(0, 255)
        val shader = android.graphics.RadialGradient(
            left + width / 2, top + height / 2, maxOf(width, height) * 0.78f,
            intArrayOf(0x00000000, 0x00000000, (alpha shl 24)), floatArrayOf(0f, 0.4f, 1f),
            android.graphics.Shader.TileMode.CLAMP,
        )
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.shader = shader }
        canvas.drawRect(left, top, left + width, top + height, p)
    }

    /**
     * How instax colour film renders the screen image, from published observations (display only,
     * never applied to the printed data):
     * - prints come out darker than the screen, with midtones sliding into shadow (fujix-forum, SP-3);
     * - more contrast and saturation than the screen, detail lost in high-contrast scenes
     *   (photographyblog, SP-3 review);
     * - little latitude: shadows block up and highlights clip early (Emulsive, SP-2 + Mini film;
     *   Digital Trends, Mini 13);
     * - blues come out noticeably darker, warm colours very slightly green (Emulsive).
     * Hence: darker, steeper midtones (which clips both ends), a touch more saturation, blue
     * channel pulled down, green nudged up. Tune against real prints if yours differ.
     */
    val filmLook: ColorMatrix = Filters.look(
        contrast = 1.14f, brightness = -14f, saturation = 1.08f,
        gainR = 1.0f, gainG = 1.015f, gainB = 0.91f,
    )

    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clipPath = android.graphics.Path()

    /** Draw the print into a [size] x [size] square whose top-left corner is at ([left], [top]). */
    fun draw(canvas: Canvas, bitmap: Bitmap, state: EditState, left: Float, top: Float, size: Float) {
        canvas.save()
        canvas.clipRect(left, top, left + size, top + size)
        framePaint.color = state.frameColor
        canvas.drawRect(left, top, left + size, top + size, framePaint)
        val inner = state.innerRect()
        val il = left + inner[0] * size
        val it = top + inner[1] * size
        val ir = left + inner[2] * size
        val ib = top + inner[3] * size
        val radius = state.cornerRadius * size
        if (radius > 0f) {
            clipPath.reset()
            clipPath.addRoundRect(il, it, ir, ib, radius, radius, android.graphics.Path.Direction.CW)
            canvas.clipPath(clipPath)
        } else {
            canvas.clipRect(il, it, ir, ib)
        }
        canvas.drawColor(Color.WHITE)
        val fill = fillScale(bitmap, state) * state.scale * size
        val matrix = Matrix()
        matrix.postTranslate(-bitmap.width / 2f, -bitmap.height / 2f)
        matrix.postRotate(state.rotation)
        matrix.postScale(fill, fill)
        matrix.postTranslate((il + ir) / 2 + state.offsetX * size, (it + ib) / 2 + state.offsetY * size)
        paint.colorFilter = ColorMatrixColorFilter(colorMatrix(state))
        canvas.drawBitmap(bitmap, matrix, paint)
        drawVignette(canvas, state, il, it, ir - il, ib - it)
        canvas.restore()
    }

    private val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = android.graphics.Typeface.DEFAULT_BOLD }
    private val overlayStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        style = Paint.Style.STROKE
    }
    private val overlayBack = Paint(Paint.ANTI_ALIAS_FLAG)

    /**
     * Draw the overlays on top of the square. Returns each overlay's bounds as fractions of the
     * print size (left, top, right, bottom), for hit-testing in the editor.
     */
    fun drawOverlays(canvas: Canvas, state: EditState, left: Float, top: Float, size: Float): List<FloatArray> {
        val bounds = ArrayList<FloatArray>(state.overlays.size)
        for (o in state.overlays) {
            val text = o.resolve(state.photoDate)
            val px = o.size * size
            overlayPaint.typeface = o.font.typeface
            overlayStroke.typeface = o.font.typeface
            overlayPaint.textSize = px
            overlayPaint.clearShadowLayer()
            val width = overlayPaint.measureText(text)
            val cx = left + o.x * size
            val cy = top + o.y * size
            val baseline = cy + px * 0.35f
            val x = cx - width / 2
            val fg = if (o.dark) Color.BLACK else Color.WHITE
            val bg = if (o.dark) Color.WHITE else Color.BLACK
            when (o.style) {
                Overlay.Style.SHADOW -> {
                    overlayPaint.setShadowLayer(px * 0.12f, 0f, px * 0.06f, (0xB0 shl 24) or (bg and 0xFFFFFF))
                }
                Overlay.Style.OUTLINE -> {
                    overlayStroke.textSize = px
                    overlayStroke.strokeWidth = px * 0.1f
                    overlayStroke.color = bg
                    canvas.drawText(text, x, baseline, overlayStroke)
                }
                Overlay.Style.PILL -> {
                    overlayBack.color = (0xA0 shl 24) or (bg and 0xFFFFFF)
                    val padX = px * 0.5f
                    val padY = px * 0.3f
                    canvas.drawRoundRect(x - padX, cy - px * 0.55f - padY, x + width + padX, cy + px * 0.55f + padY, px * 0.6f, px * 0.6f, overlayBack)
                }
            }
            overlayPaint.color = fg
            canvas.drawText(text, x, baseline, overlayPaint)
            overlayPaint.clearShadowLayer()
            val margin = px * 0.4f
            bounds += floatArrayOf(
                (x - margin - left) / size, (cy - px * 0.7f - top) / size,
                (x + width + margin - left) / size, (cy + px * 0.7f - top) / size,
            )
        }
        return bounds
    }

    /** The width of an overlay's text as a fraction of the print size, for placing it against an edge. */
    fun overlayWidth(o: Overlay, photoDate: Long?): Float {
        overlayPaint.typeface = o.font.typeface
        overlayPaint.textSize = o.size * 1000f
        return overlayPaint.measureText(o.resolve(photoDate)) / 1000f
    }

    /**
     * The full look at [size] pixels: geometry and colour matrix on the canvas, then the per-pixel
     * tone curve and sharpening, then the overlays. This is exactly what gets printed.
     */
    fun render(bitmap: Bitmap, state: EditState, size: Int, sharpen: Boolean = true): Bitmap {
        val target = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        draw(Canvas(target), bitmap, state, 0f, 0f, size.toFloat())
        if (state.needsPixelPass) {
            val pixels = IntArray(size * size)
            target.getPixels(pixels, 0, size, 0, 0, size, size)
            Processing.apply(pixels, size, size, state, sharpen)
            target.setPixels(pixels, 0, size, 0, 0, size, size)
        }
        if (state.overlays.isNotEmpty()) drawOverlays(Canvas(target), state, 0f, 0f, size.toFloat())
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

    /** When the photo was taken, from its EXIF data, or null. */
    fun exifDate(resolver: ContentResolver, uri: Uri): Long? = try {
        resolver.openInputStream(uri)?.use { input ->
            val exif = android.media.ExifInterface(input)
            val raw = exif.getAttribute(android.media.ExifInterface.TAG_DATETIME_ORIGINAL)
                ?: exif.getAttribute(android.media.ExifInterface.TAG_DATETIME)
            raw?.let { java.text.SimpleDateFormat("yyyy:MM:dd HH:mm:ss", java.util.Locale.US).parse(it)?.time }
        }
    } catch (e: Exception) {
        null
    }

    /** A copy no larger than [maxSide] on its longest side, or the bitmap itself if already small enough. */
    fun scaledForEditing(bitmap: Bitmap, maxSide: Int): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= maxSide) return bitmap
        val scale = maxSide.toFloat() / longest
        return Bitmap.createScaledBitmap(bitmap, Math.round(bitmap.width * scale), Math.round(bitmap.height * scale), true)
    }

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
