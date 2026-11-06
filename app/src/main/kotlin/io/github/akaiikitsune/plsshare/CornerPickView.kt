package io.github.akaiikitsune.plsshare

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View

/**
 * Shows a photo and lets the user mark the four corners of the chart's image area: tap them in
 * order (top-left, top-right, bottom-right, bottom-left), then drag any marker to adjust.
 */
class CornerPickView(context: Context) : View(context) {
    var bitmap: Bitmap? = null
        set(value) { field = value; corners.clear(); invalidate() }

    /** Corners in photo pixels, in the order tapped. */
    val corners = ArrayList<FloatArray>()
    var onChanged: (() -> Unit)? = null

    private val photoPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * context.resources.displayMetrics.density; color = Ui.accent(context) }
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.accent(context) }
    private val markerRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f; color = Color.WHITE }
    private val density = context.resources.displayMetrics.density
    private val radius = 9f * density
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 13f * density; textAlign = Paint.Align.CENTER; setShadowLayer(4f, 0f, 0f, Color.BLACK) }
    private val dest = RectF()
    private var dragging = -1

    val complete: Boolean get() = corners.size == 4

    /** The four corners as a flat array (TL, TR, BR, BL), or null until complete. */
    fun cornersArray(): FloatArray? = if (!complete) null else FloatArray(8) { corners[it / 2][it % 2] }

    private fun fit() {
        val b = bitmap ?: return
        val scale = minOf(width.toFloat() / b.width, height.toFloat() / b.height)
        val w = b.width * scale
        val h = b.height * scale
        dest.set((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
    }

    private fun toPhoto(x: Float, y: Float): FloatArray {
        val b = bitmap!!
        return floatArrayOf(((x - dest.left) / dest.width() * b.width).coerceIn(0f, b.width - 1f), ((y - dest.top) / dest.height() * b.height).coerceIn(0f, b.height - 1f))
    }

    private fun toView(p: FloatArray): FloatArray {
        val b = bitmap!!
        return floatArrayOf(dest.left + p[0] / b.width * dest.width(), dest.top + p[1] / b.height * dest.height())
    }

    fun reset() { corners.clear(); invalidate(); onChanged?.invoke() }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val b = bitmap
        val h = if (b == null) w else (w * b.height.toFloat() / b.width).toInt().coerceAtMost((w * 1.4f).toInt())
        setMeasuredDimension(w, h)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (bitmap == null) return false
        fit()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                dragging = corners.indices.minByOrNull { i -> val v = toView(corners[i]); (v[0] - event.x) * (v[0] - event.x) + (v[1] - event.y) * (v[1] - event.y) }
                    ?.takeIf { i -> val v = toView(corners[i]); Math.hypot((v[0] - event.x).toDouble(), (v[1] - event.y).toDouble()) < 28 * density } ?: -1
                if (dragging < 0 && corners.size < 4) {
                    corners += toPhoto(event.x, event.y)
                    dragging = corners.size - 1
                    onChanged?.invoke()
                }
            }
            MotionEvent.ACTION_MOVE -> if (dragging >= 0) { corners[dragging] = toPhoto(event.x, event.y); onChanged?.invoke() }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { parent?.requestDisallowInterceptTouchEvent(false); dragging = -1 }
        }
        invalidate()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        val b = bitmap ?: return
        fit()
        canvas.drawBitmap(b, null, dest, photoPaint)
        if (corners.size >= 2) {
            val path = Path()
            corners.forEachIndexed { i, c -> val v = toView(c); if (i == 0) path.moveTo(v[0], v[1]) else path.lineTo(v[0], v[1]) }
            if (corners.size == 4) path.close()
            canvas.drawPath(path, linePaint)
        }
        val labels = listOf("1", "2", "3", "4")
        corners.forEachIndexed { i, c ->
            val v = toView(c)
            canvas.drawCircle(v[0], v[1], radius, markerPaint)
            canvas.drawCircle(v[0], v[1], radius, markerRing)
            canvas.drawText(labels[i], v[0], v[1] - radius - 6f * density, labelPaint)
        }
    }
}
