package io.github.akaiikitsune.plsshare

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File

/**
 * Serves files of the app's private `capture` cache directory to the camera app, so a photo of the
 * test chart can be taken straight into the app without a library file provider and without
 * leaving a copy in the user's gallery. Not exported: the camera only gets the one URI it is
 * granted through the capture intent's clip data.
 */
class CaptureProvider : ContentProvider() {
    companion object {
        private const val AUTHORITY = "io.github.akaiikitsune.plsshare.capture"

        fun dir(context: Context): File = File(context.cacheDir, "capture").apply { mkdirs() }

        /** A fresh file and its content URI for one capture. */
        fun newCapture(context: Context): Pair<File, Uri> {
            val file = File(dir(context), "chart-${System.currentTimeMillis()}.jpg")
            return file to Uri.Builder().scheme("content").authority(AUTHORITY).appendPath(file.name).build()
        }

        fun fileFor(context: Context, uri: Uri): File? {
            if (uri.authority != AUTHORITY) return null
            val name = uri.lastPathSegment ?: return null
            if (name.contains('/') || name.contains("..")) return null
            return File(dir(context), name)
        }
    }

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val file = fileFor(context!!, uri) ?: throw java.io.FileNotFoundException(uri.toString())
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
    }

    override fun getType(uri: Uri): String = "image/jpeg"

    override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor {
        val file = fileFor(context!!, uri)
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val cursor = MatrixCursor(cols)
        if (file != null) cursor.addRow(cols.map { when (it) { OpenableColumns.DISPLAY_NAME -> file.name; OpenableColumns.SIZE -> file.length(); else -> null } })
        return cursor
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0
}
