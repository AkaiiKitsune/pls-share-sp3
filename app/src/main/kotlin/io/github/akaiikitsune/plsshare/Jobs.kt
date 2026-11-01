package io.github.akaiikitsune.plsshare

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream

/** Rendered prints on disk: the queue of what is waiting, and the history of what was printed. */
object Jobs {
    fun queueDir(context: Context): File = File(context.filesDir, "queue").apply { mkdirs() }
    fun historyDir(context: Context): File = File(context.filesDir, "history").apply { mkdirs() }

    /** Save a rendered print as PNG, named after the time so listings sort chronologically. */
    fun save(dir: File, bitmap: Bitmap): File {
        var file = File(dir, "${System.currentTimeMillis()}.png")
        while (file.exists()) file = File(dir, "${System.currentTimeMillis() + 1}.png")
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return file
    }

    fun list(dir: File): List<File> = (dir.listFiles { f -> f.name.endsWith(".png") } ?: emptyArray()).sortedBy { it.name }

    fun load(file: File): Bitmap? = BitmapFactory.decodeFile(file.path)

    /** A small copy for lists. */
    fun thumbnail(file: File, side: Int): Bitmap? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, options)
        var sample = 1
        while (options.outWidth / (sample * 2) >= side) sample *= 2
        val decode = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeFile(file.path, decode)
    }

    /** The time a job file was created, from its name. */
    fun time(file: File): Long = file.nameWithoutExtension.toLongOrNull() ?: file.lastModified()

    /** Copy a print into the history (after it was printed). */
    fun archive(context: Context, file: File): File {
        val target = File(historyDir(context), file.name)
        if (!target.exists()) file.copyTo(target)
        return target
    }

    /** Save a print into the phone's gallery (Pictures/pls share SP-3) and return its URI. */
    fun exportToGallery(context: Context, bitmap: Bitmap, name: String = "pls-share-sp3-${System.currentTimeMillis()}.png"): Uri? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/pls share SP-3")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        resolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } ?: return null
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri
    }
}
