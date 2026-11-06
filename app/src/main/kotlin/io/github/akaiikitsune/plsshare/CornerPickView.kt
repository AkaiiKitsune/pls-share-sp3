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

    /** Marked points in photo pixels, in the order tapped (image corners, or the anchor patches' centres). */
    val corners = ArrayList<FloatArray>()
    var onChanged: (() -> Unit)? = null
    /** Called with the index of a marker the finger just released (after a tap or a drag). */
    var onReleased: ((Int) -> Unit)? = null
    /** Labels of the four markers, in tapping order. */
    var labels = listOf("1", "2", "3", "4")
    /** An outline to draw besides the markers (the image area derived from the anchors), as (x0, y0, …) in photo pixels. */
    var outline: FloatArray? = null
        set(value) { field = value; invalidate() }
    /** Whether the markers themselves are joined into a quad (true when they are the image's corners). */
    var joinMarkers = true

    private val photoPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * context.resources.displayMetrics.density; color = Ui.accent(context) }
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.5f * context.resources.displayMetrics.density; color = Ui.accent(context)
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(10f * context.resources.displayMetrics.density, 6f * context.resources.displayMetrics.density), 0f)
    }
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.accent(context) }
    private val markerRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f; color = Color.WHITE }
    private val density = context.resources.displayMetrics.density
    private val radius = 9f * density
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 13f * density; textAlign = Paint.Align.CENTER; setShadowLayer(4f, 0f, 0f, Color.BLACK) }
    private val dest = RectF()
    private var dragging = -1
    private val loupeRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f * context.resources.displayMetrics.density; color = Color.WHITE }
    private val loupeShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x80000000.toInt() }
    private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * context.resources.displayMetrics.density; color = Ui.accent(context) }
    private val loupePath = Path()
    private val loupeZoom = 3f

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

    private var downX = 0f
    private var downY = 0f

    /**
     * A finger on a marker drags it (and the page must not scroll); elsewhere, the page scrolls
     * as usual, and a plain tap (no movement) adds the next marker there.
     */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (bitmap == null) return false
        fit()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x; downY = event.y
                dragging = corners.indices.minByOrNull { i -> val v = toView(corners[i]); (v[0] - event.x) * (v[0] - event.x) + (v[1] - event.y) * (v[1] - event.y) }
                    ?.takeIf { i -> val v = toView(corners[i]); Math.hypot((v[0] - event.x).toDouble(), (v[1] - event.y).toDouble()) < 28 * density } ?: -1
                if (dragging >= 0) parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> if (dragging >= 0) { corners[dragging] = toPhoto(event.x, event.y); onChanged?.invoke() }
            MotionEvent.ACTION_UP -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                var released = dragging
                if (released < 0 && corners.size < 4 && Math.hypot((event.x - downX).toDouble(), (event.y - downY).toDouble()) < 12 * density) {
                    corners += toPhoto(event.x, event.y)
                    released = corners.size - 1
                    onChanged?.invoke()
                }
                dragging = -1; loupeSlot = -1
                if (released >= 0) onReleased?.invoke(released)
            }
            MotionEvent.ACTION_CANCEL -> { parent?.requestDisallowInterceptTouchEvent(false); dragging = -1; loupeSlot = -1 }
        }
        invalidate()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        val b = bitmap ?: return
        fit()
        canvas.drawBitmap(b, null, dest, photoPaint)
        if (joinMarkers && corners.size >= 2) {
            val path = Path()
            corners.forEachIndexed { i, c -> val v = toView(c); if (i == 0) path.moveTo(v[0], v[1]) else path.lineTo(v[0], v[1]) }
            if (corners.size == 4) path.close()
            canvas.drawPath(path, linePaint)
        }
        outline?.let { o ->
            val path = Path()
            for (k in 0 until 4) { val v = toView(floatArrayOf(o[2 * k], o[2 * k + 1])); if (k == 0) path.moveTo(v[0], v[1]) else path.lineTo(v[0], v[1]) }
            path.close()
            canvas.drawPath(path, outlinePaint)
        }
        corners.forEachIndexed { i, c ->
            val v = toView(c)
            canvas.drawCircle(v[0], v[1], radius, markerPaint)
            canvas.drawCircle(v[0], v[1], radius, markerRing)
            canvas.drawText(labels[i], v[0], v[1] - radius - 6f * density, labelPaint)
        }
        if (dragging >= 0) drawLoupe(canvas, toView(corners[dragging]))
    }

    /** Which corner of the view holds the loupe during a drag (0 TL, 1 TR, 2 BL, 3 BR), −1 between drags. */
    private var loupeSlot = -1

    /**
     * A magnified view of what is under the finger, drawn in a corner of the view. It stays where
     * it is for the whole drag, and only moves to the corner farthest from the finger when the
     * finger comes close to it, so it does not jump around while the user adjusts a marker.
     */
    private fun drawLoupe(canvas: Canvas, at: FloatArray) {
        val r = 56f * density
        val pad = 8f * density
        val xs = floatArrayOf(r + pad, width - r - pad)
        val ys = floatArrayOf(r + pad, height - r - pad)
        fun dist(slot: Int): Float = Math.hypot((xs[slot % 2] - at[0]).toDouble(), (ys[slot / 2] - at[1]).toDouble()).toFloat()
        if (loupeSlot < 0 || dist(loupeSlot) < r + 40f * density) loupeSlot = (0..3).maxByOrNull { dist(it) }!!
        val cx = xs[loupeSlot % 2]
        val cy = ys[loupeSlot / 2]
        loupePath.reset()
        loupePath.addCircle(cx, cy, r, Path.Direction.CW)
        canvas.drawCircle(cx + 2f * density, cy + 3f * density, r + 2f * density, loupeShadow)
        canvas.save()
        canvas.clipPath(loupePath)
        canvas.translate(cx, cy)
        canvas.scale(loupeZoom, loupeZoom)
        canvas.translate(-at[0], -at[1])
        canvas.drawBitmap(bitmap!!, null, dest, photoPaint)
        outline?.let { o ->
            val path = Path()
            for (k in 0 until 4) { val v = toView(floatArrayOf(o[2 * k], o[2 * k + 1])); if (k == 0) path.moveTo(v[0], v[1]) else path.lineTo(v[0], v[1]) }
            path.close()
            canvas.drawPath(path, Paint(outlinePaint).apply { strokeWidth = outlinePaint.strokeWidth / loupeZoom })
        }
        // The quad's edges, so the user sees where the line will run.
        if (joinMarkers && corners.size >= 2) {
            val path = Path()
            corners.forEachIndexed { i, c -> val v = toView(c); if (i == 0) path.moveTo(v[0], v[1]) else path.lineTo(v[0], v[1]) }
            if (corners.size == 4) path.close()
            canvas.drawPath(path, Paint(linePaint).apply { strokeWidth = linePaint.strokeWidth / loupeZoom })
        }
        canvas.restore()
        canvas.drawLine(cx - r * 0.5f, cy, cx - 4f * density, cy, crossPaint)
        canvas.drawLine(cx + 4f * density, cy, cx + r * 0.5f, cy, crossPaint)
        canvas.drawLine(cx, cy - r * 0.5f, cx, cy - 4f * density, crossPaint)
        canvas.drawLine(cx, cy + 4f * density, cx, cy + r * 0.5f, crossPaint)
        canvas.drawCircle(cx, cy, r, loupeRing)
    }
}
