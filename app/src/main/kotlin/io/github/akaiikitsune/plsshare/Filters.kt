package io.github.akaiikitsune.plsshare

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint

/** A named colour look, applied before the user's own brightness / contrast / saturation. */
class Preset(val id: String, val matrix: ColorMatrix)

object Filters {
    /** Build a colour matrix from a few knobs: contrast around mid-grey, brightness offset, saturation, per-channel gain and offset. */
    fun look(
        contrast: Float = 1f,
        brightness: Float = 0f,
        saturation: Float = 1f,
        gainR: Float = 1f, gainG: Float = 1f, gainB: Float = 1f,
        offsetR: Float = 0f, offsetG: Float = 0f, offsetB: Float = 0f,
    ): ColorMatrix {
        val m = ColorMatrix()
        m.setSaturation(saturation)
        val t = (1f - contrast) * 128f + brightness
        m.postConcat(ColorMatrix(floatArrayOf(
            contrast, 0f, 0f, 0f, t,
            0f, contrast, 0f, 0f, t,
            0f, 0f, contrast, 0f, t,
            0f, 0f, 0f, 1f, 0f,
        )))
        m.postConcat(ColorMatrix(floatArrayOf(
            gainR, 0f, 0f, 0f, offsetR,
            0f, gainG, 0f, 0f, offsetG,
            0f, 0f, gainB, 0f, offsetB,
            0f, 0f, 0f, 1f, 0f,
        )))
        return m
    }

    private val sepia = ColorMatrix(floatArrayOf(
        0.393f, 0.769f, 0.189f, 0f, 0f,
        0.349f, 0.686f, 0.168f, 0f, 0f,
        0.272f, 0.534f, 0.131f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    ))

    val presets: List<Preset> = listOf(
        Preset("normal", ColorMatrix()),
        Preset("vivid", look(contrast = 1.12f, saturation = 1.4f)),
        Preset("warm", look(brightness = 4f, saturation = 1.1f, gainR = 1.06f, gainB = 0.92f, offsetR = 6f)),
        Preset("cool", look(saturation = 1.05f, gainR = 0.94f, gainB = 1.08f, offsetB = 6f)),
        Preset("fade", look(contrast = 0.82f, brightness = 18f, saturation = 0.85f)),
        Preset("retro", look(contrast = 0.9f, brightness = 8f, saturation = 0.72f, gainR = 1.08f, gainG = 1.0f, gainB = 0.84f, offsetB = 10f)),
        Preset("sepia", ColorMatrix(sepia).apply { postConcat(look(contrast = 0.95f, brightness = -4f)) }),
        Preset("mono", look(saturation = 0f)),
        Preset("noir", look(contrast = 1.35f, brightness = -8f, saturation = 0f)),
        Preset("pop", look(contrast = 1.25f, brightness = 2f, saturation = 1.2f)),
    )

    fun byId(id: String): Preset = presets.firstOrNull { it.id == id } ?: presets[0]

    /** A copy of [source] with the preset applied, for the editor's thumbnails. */
    fun thumbnail(source: Bitmap, preset: Preset): Bitmap {
        val out = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(preset.matrix) }
        Canvas(out).drawBitmap(source, 0f, 0f, paint)
        return out
    }
}
