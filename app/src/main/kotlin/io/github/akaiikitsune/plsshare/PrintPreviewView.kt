package io.github.akaiikitsune.plsshare

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/**
 * The print as it will look in hand: the 800 px image inside an instax SQUARE frame (72 × 86 mm,
 * image 62 × 62 mm, thick edge at the bottom), with a soft shadow.
 */
class PrintPreviewView(context: Context) : View(context) {
    var bitmap: Bitmap? = null
        set(value) { field = value; simulated = null; invalidate() }

    var filmPreview = false
        set(value) { field = value; invalidate() }

    var film: FilmModel = FilmModel()
        set(value) { field = value; simulated = null; invalidate() }
    var compensated = false
        set(value) { field = value; simulated = null; invalidate() }
    private var simulated: Bitmap? = null

    var onClick: (() -> Unit)? = null

    private val paper = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x30000000 }
    private val photo = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dest = RectF()
    private val card = RectF()

    companion object {
        private const val WIDTH_MM = 72f
        private const val HEIGHT_MM = 86f
        private const val IMAGE_MM = 62f
        private const val TOP_MM = 6f
    }

    init {
        setOnClickListener { onClick?.invoke() }
    }

    private fun simulate(source: Bitmap): Bitmap {
        val w = source.width
        val h = source.height
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)
        if (compensated) film.compensate(pixels)
        film.simulate(pixels)
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { setPixels(pixels, 0, w, 0, 0, w, h) }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, Math.round(w * HEIGHT_MM / WIDTH_MM))
    }

    override fun onDraw(canvas: Canvas) {
        val margin = width * 0.12f
        val cardW = width - 2 * margin
        val cardH = cardW * HEIGHT_MM / WIDTH_MM
        val left = margin
        val top = (height - cardH) / 2
        val corner = cardW * 0.02f
        card.set(left + cardW * 0.01f, top + cardH * 0.02f, left + cardW + cardW * 0.01f, top + cardH + cardH * 0.02f)
        canvas.drawRoundRect(card, corner, corner, shadow)
        card.set(left, top, left + cardW, top + cardH)
        canvas.drawRoundRect(card, corner, corner, paper)
        val b = bitmap ?: return
        val scale = cardW / WIDTH_MM
        val side = IMAGE_MM * scale
        val il = left + (WIDTH_MM - IMAGE_MM) / 2 * scale
        val it = top + TOP_MM * scale
        dest.set(il, it, il + side, it + side)
        val shown = if (filmPreview) simulated ?: simulate(b).also { simulated = it } else b
        canvas.drawBitmap(shown, null, dest, photo)
    }
}
