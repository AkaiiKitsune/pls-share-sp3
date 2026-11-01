package io.github.akaiikitsune.plsshare

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View

/** A square view showing the print as it will come out, with pinch-to-zoom and drag-to-pan. */
class PhotoEditorView(context: Context) : View(context) {
    var bitmap: Bitmap? = null
        set(value) { field = value; invalidate() }
    val state = EditState()
    var onChanged: (() -> Unit)? = null

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
        color = Ui.ACCENT
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
            val w = r[2] - r[0]
            val h = r[3] - r[1]
            val target = when {
                kotlin.math.abs(w - 1f) < SNAP -> w
                kotlin.math.abs(h - 1f) < SNAP -> h
                else -> 0f
            }
            if (target > 0f) {
                state.scale /= target
                snappedScale = true
            }
        }
        val r = PrintRenderer.imageRect(b, state)
        snappedX = snapAxis(r[0], r[2], quarterTurn) { state.offsetX += it }
        snappedY = snapAxis(r[1], r[3], quarterTurn) { state.offsetY += it }
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
    private fun snapAxis(low: Float, high: Float, edges: Boolean, move: (Float) -> Unit): Float? {
        val centre = (low + high) / 2
        val candidates = if (edges) listOf(0f - low to 0f, 1f - high to 1f, 0.5f - centre to 0.5f) else listOf(0.5f - centre to 0.5f)
        val best = candidates.minByOrNull { kotlin.math.abs(it.first) } ?: return null
        if (kotlin.math.abs(best.first) >= SNAP) return null
        move(best.first)
        return best.second
    }

    private fun side() = minOf(width, height)
    private fun left() = (width - side()) / 2f
    private fun top() = (height - side()) / 2f

    private fun changed() {
        invalidate()
        onChanged?.invoke()
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
        val side = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) w else minOf(w, h)
        setMeasuredDimension(side, side)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { touching = true; invalidate() }
            MotionEvent.ACTION_POINTER_DOWN -> if (event.pointerCount == 2) {
                twisting = true
                twistArmed = false
                twistStartAngle = angleBetween(event)
                twistStartRotation = state.rotation
            }
            MotionEvent.ACTION_MOVE -> if (twisting && event.pointerCount >= 2) onTwist(event)
            MotionEvent.ACTION_POINTER_UP -> if (event.pointerCount <= 2) { twisting = false; snappedRotation = false }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                touching = false
                twisting = false
                snappedX = null; snappedY = null; snappedScale = false; snappedRotation = false
                invalidate()
            }
        }
        scaleDetector.onTouchEvent(event)
        if (!scaleDetector.isInProgress) panDetector.onTouchEvent(event)
        return true
    }

    override fun onDraw(canvas: Canvas) {
        val b = bitmap
        val size = side().toFloat()
        if (b == null) {
            canvas.drawColor(Color.TRANSPARENT)
            return
        }
        PrintRenderer.draw(canvas, b, state, left(), top(), size)
        val l = left()
        val t = top()
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
                textPaint.color = if (snappedRotation) Ui.ACCENT else Color.WHITE
                canvas.drawText(String.format(java.util.Locale.US, "%.1f°", state.rotation), l + 16f, t + size - 16f, textPaint)
            }
        }
        canvas.drawRect(l, t, l + size, t + size, framePaint)
    }
}
