package io.github.akaiikitsune.plsshare

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import kotlin.math.sqrt

/** RGB histogram with a luminance outline, on a square-root scale so small counts stay visible. */
class HistogramView(context: Context) : View(context) {
    private var channels: Array<IntArray>? = null
    private val fills = listOf(0x70FF4A4A.toInt(), 0x7040D070.toInt(), 0x704A8CFF.toInt())
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val lumaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = 0xC0FFFFFF.toInt()
    }
    private val path = Path()

    /** Smooth each channel with a 5-bin box filter: tone curves leave gaps between levels that would otherwise draw as a comb. */
    fun update(channels: Array<IntArray>?) {
        this.channels = channels?.map { bins ->
            IntArray(256) { i ->
                var sum = 0
                var n = 0
                for (j in (i - 2).coerceAtLeast(0)..(i + 2).coerceAtMost(255)) { sum += bins[j]; n++ }
                sum / n
            }
        }?.toTypedArray()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val data = channels ?: return
        val w = width.toFloat()
        val h = height.toFloat()
        val max = data.maxOf { ch -> ch.maxOrNull() ?: 1 }.coerceAtLeast(1)
        val scale = h / sqrt(max.toFloat())
        fun trace(bins: IntArray) {
            path.reset()
            path.moveTo(0f, h)
            for (i in 0..255) path.lineTo(i / 255f * w, h - sqrt(bins[i].toFloat()) * scale)
            path.lineTo(w, h)
            path.close()
        }
        for (c in 0..2) {
            trace(data[c])
            fillPaint.color = fills[c]
            canvas.drawPath(path, fillPaint)
        }
        trace(data[3])
        canvas.drawPath(path, lumaPaint)
    }
}
