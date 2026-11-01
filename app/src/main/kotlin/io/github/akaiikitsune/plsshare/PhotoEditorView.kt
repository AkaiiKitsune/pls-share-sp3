package io.github.akaiikitsune.plsshare

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.DashPathEffect
import android.graphics.RectF
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View

/** A square view showing the print as it will come out, with pinch-to-zoom and drag-to-pan. */
class PhotoEditorView(context: Context) : View(context) {
    var bitmap: Bitmap? = null
        set(value) { field = value; frameDirty = true; invalidate() }
    val state = EditState()
    var onChanged: (() -> Unit)? = null

    /** Called with the processed pixels of the square after each full render, for the histogram. */
    var onFrame: ((IntArray, Int) -> Unit)? = null

    /** Paint blown highlights and crushed shadows over the photo. */
    var showClipping = false
        set(value) {
            field = value
            frameDirty = true
            removeCallbacks(blink)
            if (value) { blinkOn = true; postDelayed(blink, BLINK_MS) }
            invalidate()
        }

    /** Simulate the film's rendering on screen (display only). */
    var filmPreview = false
        set(value) { field = value; frameDirty = true; invalidate() }
    private val filmPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = android.graphics.ColorMatrixColorFilter(PrintRenderer.filmLook) }

    /** Show the photo without any look (same framing), while a "compare" button is held. */
    var showOriginal = false
        set(value) { if (field != value) { field = value; invalidate() } }
    private val neutral = EditState()
    private var originalFrame: Bitmap? = null

    /** Texts tab: touches move the selected overlay instead of the photo. */
    var overlayMode = false
        set(value) { field = value; invalidate() }
    var selectedOverlay: Overlay? = null
        set(value) { field = value; invalidate() }
    var onOverlaySelected: ((Overlay?) -> Unit)? = null
    private var overlayBounds: List<FloatArray> = emptyList()
    private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        pathEffect = DashPathEffect(floatArrayOf(14f, 10f), 0f)
    }
    private var dragging: Overlay? = null

    // The clipping overlay blinks gently: its alpha alternates every BLINK_MS while it is shown.
    private val clipPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var blinkOn = true
    private val blink = object : Runnable {
        override fun run() {
            if (!showClipping) return
            blinkOn = !blinkOn
            invalidate()
            postDelayed(this, BLINK_MS)
        }
    }

    /** Whether the histogram wants the pixels of the square. */
    var wantHistogram = false
        set(value) { field = value; histogramDirty = true; invalidate() }

    // When per-pixel steps are needed (tone curve, sharpening, clipping overlay) the square is
    // rendered off screen. While a finger moves or a slider is dragged, that render is done at
    // PREVIEW_SIZE without sharpening and scaled up, and the full-size render happens on release.
    // Without per-pixel steps the photo is drawn straight to the (hardware) canvas.
    private var frame: Bitmap? = null
    private var framePixels: IntArray? = null
    private var overlay: Bitmap? = null
    private var overlayPixels: IntArray? = null
    private var frameDirty = true
    private var frameInteractive = false
    private var histogramDirty = true
    private var histogramFrame: Bitmap? = null
    private var histogramPixels: IntArray? = null
    private val scaledPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dest = RectF()

    /** A slider is being dragged: render fast until [endInteraction]. */
    private var interactive = false

    fun beginInteraction() {
        interactive = true
    }

    fun endInteraction() {
        interactive = false
        frameDirty = true
        invalidate()
    }

    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = 0x40808080
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = 0x50FFFFFF
    }
    private val snapPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = Ui.accent(context)
    }

    /** Whether a finger is down: the grid and the snap guides are only shown while editing. */
    private var touching = false

    /** The print-space line (0, 0.5 or 1) the photo is currently snapped to on each axis, if any. */
    private var snappedX: Float? = null
    private var snappedY: Float? = null
    private var snappedScale = false
    private var snappedRotation = false

    // Two-finger twist
    private var twistStartAngle = 0f
    private var twistStartRotation = 0f
    private var twisting = false
    private var twistArmed = false
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 36f
        setShadowLayer(4f, 0f, 0f, Color.BLACK)
    }

    companion object {
        /** How close (fraction of the print size) an edge must be to a guide to stick to it. */
        private const val SNAP = 0.02f

        private const val BLINK_MS = 500L

        /** Side of the off-screen render while interacting, and of the histogram sample. */
        private const val PREVIEW_SIZE = 480
        private const val HISTOGRAM_SIZE = 192

        /** Degrees of twist ignored before a rotation starts, so pinching does not rotate by accident. */
        private const val TWIST_DEAD_ZONE = 4f

        /** Degrees within which the angle sticks to a quarter turn. */
        private const val ROTATION_SNAP = 2.5f
    }

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val b = bitmap ?: return false
            val before = state.scale
            state.scale *= detector.scaleFactor
            PrintRenderer.clamp(b, state)
            // Zoom around the fingers: keep the point under the focus in place.
            val size = side().toFloat()
            val fx = (detector.focusX - left()) / size - 0.5f
            val fy = (detector.focusY - top()) / size - 0.5f
            val ratio = state.scale / before
            state.offsetX = fx + (state.offsetX - fx) * ratio
            state.offsetY = fy + (state.offsetY - fy) * ratio
            PrintRenderer.clamp(b, state)
            snap(b, withScale = true)
            changed()
            return true
        }
    })

    private val panDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            val b = bitmap ?: return false
            val size = side().toFloat()
            state.offsetX -= distanceX / size
            state.offsetY -= distanceY / size
            PrintRenderer.clamp(b, state)
            snap(b, withScale = false)
            changed()
            return true
        }
    })

    /**
     * Magnetic guides: the photo's edges stick to the print's edges, its centre to the print's
     * centre, and while zooming its width or height sticks to exactly the print size.
     */
    private fun snap(b: Bitmap, withScale: Boolean) {
        snappedScale = false
        val quarterTurn = state.rotation % 90f == 0f
        if (withScale && quarterTurn) {
            val r = PrintRenderer.imageRect(b, state)
            val inner = state.innerRect()
            val areaW = inner[2] - inner[0]
            val areaH = inner[3] - inner[1]
            val w = r[2] - r[0]
            val h = r[3] - r[1]
            val target = when {
                kotlin.math.abs(w - areaW) < SNAP -> w / areaW
                kotlin.math.abs(h - areaH) < SNAP -> h / areaH
                else -> 0f
            }
            if (target > 0f) {
                state.scale /= target
                snappedScale = true
            }
        }
        val r = PrintRenderer.imageRect(b, state)
        val inner = state.innerRect()
        snappedX = snapAxis(r[0], r[2], inner[0], inner[2], quarterTurn) { state.offsetX += it }
        snappedY = snapAxis(r[1], r[3], inner[1], inner[3], quarterTurn) { state.offsetY += it }
        PrintRenderer.clamp(b, state)
    }

    /** Stick the angle to the nearest quarter turn when close to it. */
    private fun snapRotation() {
        val nearest = Math.round(state.rotation / 90f) * 90f
        snappedRotation = kotlin.math.abs(state.rotation - nearest) < ROTATION_SNAP
        if (snappedRotation) state.rotation = nearest
    }

    private fun angleBetween(event: MotionEvent): Float {
        val dx = event.getX(1) - event.getX(0)
        val dy = event.getY(1) - event.getY(0)
        return Math.toDegrees(kotlin.math.atan2(dy.toDouble(), dx.toDouble())).toFloat()
    }

    private fun onTwist(event: MotionEvent) {
        val b = bitmap ?: return
        var delta = angleBetween(event) - twistStartAngle
        while (delta > 180f) delta -= 360f
        while (delta < -180f) delta += 360f
        if (!twistArmed) {
            if (kotlin.math.abs(delta) < TWIST_DEAD_ZONE) return
            twistArmed = true
            twistStartAngle = angleBetween(event)
            twistStartRotation = state.rotation
            return
        }
        state.rotation = twistStartRotation + delta
        snapRotation()
        PrintRenderer.clamp(b, state)
        changed()
    }

    /** Move an axis so that its left/top edge, right/bottom edge or centre meets the nearest guide within [SNAP]. */
    private fun snapAxis(low: Float, high: Float, areaLow: Float, areaHigh: Float, edges: Boolean, move: (Float) -> Unit): Float? {
        val centre = (low + high) / 2
        val areaCentre = (areaLow + areaHigh) / 2
        val candidates = if (edges) listOf(areaLow - low to areaLow, areaHigh - high to areaHigh, areaCentre - centre to areaCentre)
        else listOf(areaCentre - centre to areaCentre)
        val best = candidates.minByOrNull { kotlin.math.abs(it.first) } ?: return null
        if (kotlin.math.abs(best.first) >= SNAP) return null
        move(best.first)
        return best.second
    }

    private fun side() = minOf(width, height)
    private fun left() = (width - side()) / 2f
    private fun top() = (height - side()) / 2f

    private fun changed() {
        frameDirty = true
        histogramDirty = true
        invalidate()
        onChanged?.invoke()
    }

    /** Re-render the square into [frame] at [size] pixels, with the per-pixel steps. */
    private fun renderFrame(b: Bitmap, size: Int, sharpen: Boolean) {
        var f = frame
        if (f == null || f.width != size) {
            f = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            frame = f
            framePixels = IntArray(size * size)
            overlay = null
        }
        val pixels = framePixels!!
        PrintRenderer.draw(Canvas(f), b, state, 0f, 0f, size.toFloat())
        f.getPixels(pixels, 0, size, 0, 0, size, size)
        if (state.needsPixelPass) {
            Processing.apply(pixels, size, size, state, sharpen)
            f.setPixels(pixels, 0, size, 0, 0, size, size)
        }
        if (showClipping) {
            val o = overlay ?: Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { overlay = it }
            val op = overlayPixels?.takeIf { it.size == pixels.size } ?: IntArray(pixels.size).also { overlayPixels = it }
            Processing.clipping(pixels, size, op, stripe = maxOf(3, size / 80))
            o.setPixels(op, 0, size, 0, 0, size, size)
        }
        frameDirty = false
        frameInteractive = !sharpen
    }

    /** A small render of the square, with the tone curve, for the histogram. */
    private fun renderHistogram(b: Bitmap) {
        val size = HISTOGRAM_SIZE
        val f = histogramFrame ?: Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { histogramFrame = it }
        val pixels = histogramPixels ?: IntArray(size * size).also { histogramPixels = it }
        PrintRenderer.draw(Canvas(f), b, state, 0f, 0f, size.toFloat())
        f.getPixels(pixels, 0, size, 0, 0, size, size)
        if (state.needsPixelPass) Processing.apply(pixels, size, size, state, sharpen = false)
        histogramDirty = false
        onFrame?.invoke(pixels, size)
    }

    /** Overlays changed: redraw without re-running the per-pixel steps. */
    fun overlaysChanged() {
        invalidate()
        onChanged?.invoke()
    }

    /** Force a full re-render (for example to feed the histogram after it was switched on). */
    fun invalidateFrame() {
        frameDirty = true
        invalidate()
    }

    /** Apply a change made from outside (buttons, sliders). */
    fun update(block: EditState.() -> Unit) {
        state.block()
        bitmap?.let { PrintRenderer.clamp(it, state) }
        changed()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        // Square when the height is free (inside a scrolling page); otherwise fill the given box
        // and draw the square centred in it.
        if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) setMeasuredDimension(w, w)
        else setMeasuredDimension(w, h)
    }

    /** Where the square sits inside the view: left, top, side. */
    fun squareBounds(): FloatArray = floatArrayOf(left(), top(), side().toFloat())

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (overlayMode) return onOverlayTouch(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Keep the scrolling page from stealing vertical drags while a finger is on the photo.
                parent?.requestDisallowInterceptTouchEvent(true)
                touching = true
                invalidate()
            }
            MotionEvent.ACTION_POINTER_DOWN -> if (event.pointerCount == 2) {
                twisting = true
                twistArmed = false
                twistStartAngle = angleBetween(event)
                twistStartRotation = state.rotation
            }
            MotionEvent.ACTION_MOVE -> if (twisting && event.pointerCount >= 2) onTwist(event)
            MotionEvent.ACTION_POINTER_UP -> if (event.pointerCount <= 2) { twisting = false; snappedRotation = false }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                touching = false
                twisting = false
                snappedX = null; snappedY = null; snappedScale = false; snappedRotation = false
                if (frameInteractive) frameDirty = true
                invalidate()
            }
        }
        scaleDetector.onTouchEvent(event)
        if (!scaleDetector.isInProgress) panDetector.onTouchEvent(event)
        return true
    }

    private var lastX = 0f
    private var lastY = 0f

    /** In the texts tab: tap selects an overlay, drag moves it, pinch resizes it. */
    private fun onOverlayTouch(event: MotionEvent): Boolean {
        val size = side().toFloat()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val fx = (event.x - left()) / size
                val fy = (event.y - top()) / size
                val overlays = state.overlays
                var hit: Overlay? = null
                for (i in overlayBounds.indices.reversed()) {
                    val r = overlayBounds[i]
                    if (i < overlays.size && fx >= r[0] && fx <= r[2] && fy >= r[1] && fy <= r[3]) { hit = overlays[i]; break }
                }
                if (hit != null) {
                    selectedOverlay = hit
                    onOverlaySelected?.invoke(hit)
                }
                dragging = hit
                lastX = event.x; lastY = event.y
                touching = true
            }
            MotionEvent.ACTION_MOVE -> {
                val o = dragging
                if (o != null && event.pointerCount == 1) {
                    o.x = (o.x + (event.x - lastX) / size).coerceIn(0.02f, 0.98f)
                    o.y = (o.y + (event.y - lastY) / size).coerceIn(0.02f, 0.98f)
                    o.anchor = null
                    lastX = event.x; lastY = event.y
                    overlaysChanged()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                dragging = null
                touching = false
                invalidate()
            }
        }
        overlayScale.onTouchEvent(event)
        return true
    }

    private val overlayScale = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val o = dragging ?: return false
            o.size = (o.size * detector.scaleFactor).coerceIn(0.015f, 0.25f)
            overlaysChanged()
            return true
        }
    })

    override fun onDraw(canvas: Canvas) {
        val b = bitmap
        val size = side().toFloat()
        if (b == null) {
            canvas.drawColor(Color.TRANSPARENT)
            return
        }
        val l = left()
        val t = top()
        val busy = touching || interactive
        if (showOriginal) {
            neutral.reset()
            neutral.scale = state.scale; neutral.offsetX = state.offsetX; neutral.offsetY = state.offsetY; neutral.rotation = state.rotation
            if (filmPreview) {
                // The film simulation is a display property, not an edit: keep it on the original too.
                val previewSize = minOf(size.toInt(), PREVIEW_SIZE)
                val o = originalFrame?.takeIf { it.width == previewSize }
                    ?: Bitmap.createBitmap(previewSize, previewSize, Bitmap.Config.ARGB_8888).also { originalFrame = it }
                PrintRenderer.draw(Canvas(o), b, neutral, 0f, 0f, previewSize.toFloat())
                dest.set(l, t, l + size, t + size)
                canvas.drawBitmap(o, null, dest, filmPaint)
            } else {
                PrintRenderer.draw(canvas, b, neutral, l, t, size)
            }
            textPaint.color = Color.WHITE
            canvas.drawText("Original", l + 16f, t + 44f, textPaint)
            canvas.drawRect(l, t, l + size, t + size, framePaint)
            return
        }
        if (state.needsPixelPass || showClipping || filmPreview) {
            val renderSize = if (busy) minOf(size.toInt(), PREVIEW_SIZE) else size.toInt()
            val f = frame
            if (frameDirty || f == null || f.width != renderSize || (!busy && frameInteractive)) {
                renderFrame(b, renderSize, sharpen = !busy)
            }
            dest.set(l, t, l + size, t + size)
            canvas.drawBitmap(frame!!, null, dest, if (filmPreview) filmPaint else scaledPaint)
            if (showClipping) overlay?.let {
                clipPaint.alpha = if (blinkOn) 230 else 90
                canvas.drawBitmap(it, null, dest, clipPaint)
            }
        } else {
            PrintRenderer.draw(canvas, b, state, l, t, size)
        }
        overlayBounds = if (state.overlays.isNotEmpty()) PrintRenderer.drawOverlays(canvas, state, l, t, size) else emptyList()
        if (overlayMode) {
            val sel = selectedOverlay
            val index = state.overlays.indexOf(sel)
            if (sel != null && index in overlayBounds.indices) {
                val r = overlayBounds[index]
                selectionPaint.color = Ui.accent(context)
                canvas.drawRoundRect(l + r[0] * size, t + r[1] * size, l + r[2] * size, t + r[3] * size, 12f, 12f, selectionPaint)
            }
        }
        if (wantHistogram && histogramDirty) renderHistogram(b)
        if (touching) {
            // Rule-of-thirds grid while editing.
            for (i in 1..2) {
                val p = i / 3f * size
                canvas.drawLine(l + p, t, l + p, t + size, gridPaint)
                canvas.drawLine(l, t + p, l + size, t + p, gridPaint)
            }
            snappedX?.let { x -> canvas.drawLine(l + x * size, t, l + x * size, t + size, snapPaint) }
            snappedY?.let { y -> canvas.drawLine(l, t + y * size, l + size, t + y * size, snapPaint) }
            if (snappedScale) {
                val r = PrintRenderer.imageRect(b, state)
                canvas.drawRect(l + r[0] * size, t + r[1] * size, l + r[2] * size, t + r[3] * size, snapPaint)
            }
            if (twisting && twistArmed) {
                textPaint.color = if (snappedRotation) Ui.accent(context) else Color.WHITE
                canvas.drawText(String.format(java.util.Locale.US, "%.1f°", state.rotation), l + 16f, t + size - 16f, textPaint)
            }
        }
        canvas.drawRect(l, t, l + size, t + size, framePaint)
    }
}
