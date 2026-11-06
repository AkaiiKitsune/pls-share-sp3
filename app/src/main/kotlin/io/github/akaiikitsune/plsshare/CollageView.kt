package io.github.akaiikitsune.plsshare

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View

private fun f(l: Float, t: Float, r: Float, b: Float) = floatArrayOf(l, t, r, b)

/** A collage layout: cells as fractions of the square (left, top, right, bottom). */
enum class CollageLayout(val id: String, val cells: List<FloatArray>) {
    GRID2("grid2", listOf(f(0f, 0f, .5f, .5f), f(.5f, 0f, 1f, .5f), f(0f, .5f, .5f, 1f), f(.5f, .5f, 1f, 1f))),
    SIDE("side", listOf(f(0f, 0f, .5f, 1f), f(.5f, 0f, 1f, 1f))),
    STACK("stack", listOf(f(0f, 0f, 1f, .5f), f(0f, .5f, 1f, 1f))),
    STRIP3("strip3", listOf(f(0f, 0f, 1f, 1f / 3), f(0f, 1f / 3, 1f, 2f / 3), f(0f, 2f / 3, 1f, 1f))),
    BIG_LEFT("bigleft", listOf(f(0f, 0f, 2f / 3, 1f), f(2f / 3, 0f, 1f, .5f), f(2f / 3, .5f, 1f, 1f))),
    BIG_TOP("bigtop", listOf(f(0f, 0f, 1f, 2f / 3), f(0f, 2f / 3, .5f, 1f), f(.5f, 2f / 3, 1f, 1f)));
}

/** One photo in a cell, covering it, with a pan offset as a fraction of the cell. */
class CollageCell {
    var bitmap: Bitmap? = null
    var offsetX = 0f
    var offsetY = 0f
}

/**
 * Builds a square collage. Tap an empty cell to pick a photo (via [onPickCell]), drag inside a
 * filled cell to pan it, long-press to clear it. [render] gives the print-ready bitmap.
 */
class CollageView(context: Context) : View(context) {
    var layout: CollageLayout = CollageLayout.GRID2
        set(value) {
            field = value
            cells = List(value.cells.size) { i -> if (i < cells.size) cells[i] else CollageCell() }
            invalidate()
        }
    var cells: List<CollageCell> = List(CollageLayout.GRID2.cells.size) { CollageCell() }
        private set

    /** Gap between cells and around the edge, as a fraction of the square. */
    var gap = 0.02f
        set(value) { field = value; invalidate() }
    var background = Color.WHITE
        set(value) { field = value; invalidate() }
    var rounded = 0f
        set(value) { field = value; invalidate() }

    var onPickCell: ((Int) -> Unit)? = null
    var onChanged: (() -> Unit)? = null

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66808080; textAlign = Paint.Align.CENTER }
    private val rect = RectF()
    private var dragCell = -1
    private var lastX = 0f
    private var lastY = 0f

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val i = cellAt(e.x, e.y)
            if (i >= 0) onPickCell?.invoke(i)
            return true
        }
        override fun onLongPress(e: MotionEvent) {
            val i = cellAt(e.x, e.y)
            if (i >= 0 && cells[i].bitmap != null) { cells[i].bitmap = null; cells[i].offsetX = 0f; cells[i].offsetY = 0f; invalidate(); onChanged?.invoke() }
        }
    })

    fun filledCount() = cells.count { it.bitmap != null }

    private fun side() = minOf(width, height).toFloat()

    private fun cellAt(x: Float, y: Float): Int {
        val s = side()
        for ((i, c) in layout.cells.withIndex()) {
            if (x >= c[0] * s && x <= c[2] * s && y >= c[1] * s && y <= c[3] * s) return i
        }
        return -1
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, w)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestures.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                dragCell = cellAt(event.x, event.y).takeIf { it >= 0 && cells[it].bitmap != null } ?: -1
                lastX = event.x; lastY = event.y
            }
            MotionEvent.ACTION_MOVE -> if (dragCell >= 0) {
                val c = layout.cells[dragCell]
                val s = side()
                val cell = cells[dragCell]
                cell.offsetX += (event.x - lastX) / ((c[2] - c[0]) * s)
                cell.offsetY += (event.y - lastY) / ((c[3] - c[1]) * s)
                clamp(dragCell)
                lastX = event.x; lastY = event.y
                invalidate()
                onChanged?.invoke()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { parent?.requestDisallowInterceptTouchEvent(false); dragCell = -1 }
        }
        return true
    }

    /** Keep the photo covering its cell. */
    private fun clamp(i: Int) {
        val cell = cells[i]
        val b = cell.bitmap ?: return
        val c = layout.cells[i]
        val cw = c[2] - c[0]
        val ch = c[3] - c[1]
        val scale = maxOf(cw / b.width, ch / b.height)
        val overflowX = (b.width * scale - cw) / cw / 2
        val overflowY = (b.height * scale - ch) / ch / 2
        cell.offsetX = cell.offsetX.coerceIn(-overflowX, overflowX)
        cell.offsetY = cell.offsetY.coerceIn(-overflowY, overflowY)
    }

    fun setCell(i: Int, bitmap: Bitmap) {
        cells[i].bitmap = bitmap
        cells[i].offsetX = 0f; cells[i].offsetY = 0f
        invalidate()
        onChanged?.invoke()
    }

    override fun onDraw(canvas: Canvas) {
        draw(canvas, side())
    }

    /** Draw the collage into a [size] square at the canvas origin. */
    fun draw(canvas: Canvas, size: Float) {
        fill.color = background
        canvas.drawRect(0f, 0f, size, size, fill)
        val g = gap * size
        for ((i, c) in layout.cells.withIndex()) {
            val l = c[0] * size + g / 2 + if (c[0] == 0f) g / 2 else 0f
            val t = c[1] * size + g / 2 + if (c[1] == 0f) g / 2 else 0f
            val r = c[2] * size - g / 2 - if (c[2] == 1f) g / 2 else 0f
            val b = c[3] * size - g / 2 - if (c[3] == 1f) g / 2 else 0f
            rect.set(l, t, r, b)
            canvas.save()
            if (rounded > 0f) {
                val path = android.graphics.Path().apply { addRoundRect(rect, rounded * size, rounded * size, android.graphics.Path.Direction.CW) }
                canvas.clipPath(path)
            } else canvas.clipRect(rect)
            val bmp = cells[i].bitmap
            if (bmp == null) {
                fill.color = 0x22808080
                canvas.drawRect(rect, fill)
                hint.textSize = (r - l).coerceAtMost(b - t) * 0.25f
                canvas.drawText("+", (l + r) / 2, (t + b) / 2 + hint.textSize * 0.35f, hint)
            } else {
                val cw = r - l
                val ch = b - t
                val scale = maxOf(cw / bmp.width, ch / bmp.height)
                val dw = bmp.width * scale
                val dh = bmp.height * scale
                val dx = l + (cw - dw) / 2 + cells[i].offsetX * cw
                val dy = t + (ch - dh) / 2 + cells[i].offsetY * ch
                canvas.drawBitmap(bmp, null, RectF(dx, dy, dx + dw, dy + dh), paint)
            }
            canvas.restore()
        }
    }

    /** The print-ready collage. */
    fun render(size: Int): Bitmap {
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        draw(Canvas(out), size.toFloat())
        return out
    }
}
