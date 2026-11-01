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
            changed()
            return true
        }
    })

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
        canvas.drawRect(left(), top(), left() + size, top() + size, framePaint)
    }
}
