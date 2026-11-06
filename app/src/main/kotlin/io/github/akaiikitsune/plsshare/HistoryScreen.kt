package io.github.akaiikitsune.plsshare

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Date

/** The prints already made: thumbnails with reprint, share and delete. */
class HistoryScreen(private val activity: MainActivity) {
    private val s get() = activity.s
    private val ui get() = activity.ui
    private lateinit var historyList: LinearLayout


    fun build(): View {
        val root = ui.column(20)
        historyList = ui.column()
        root.addView(historyList, LinearLayout.LayoutParams(ui.match, ui.wrap))
        return activity.fixedHeaderScreen(activity.titleBar(s.historyTitle, ui.softButton(s.back) { activity.show(activity.homeView) }.apply { minimumWidth = 0 }), root)
    }

    fun refresh() {
        historyList.removeAllViews()
        val files = Jobs.list(Jobs.historyDir(activity)).reversed()
        if (files.isEmpty()) {
            historyList.addView(ui.text(s.historyEmpty, 14f, alpha = 0.7f))
            return
        }
        val format = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT)
        for (file in files) {
            val thumb = ImageView(activity).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = ui.rounded(Ui.CARD, 12)
                clipToOutline = true
                setImageBitmap(Jobs.thumbnail(file, 240))
            }
            val info = ui.column().apply {
                addView(ui.text(format.format(Date(Jobs.time(file))), 13f, bold = true))
                addView(ui.row(
                    ui.softButton(s.reprint) { if (activity.connected == null) activity.message(s.notConnectedYet) else activity.startPrinting(listOf(file), archive = false) },
                    ui.softButton(s.share) { Jobs.load(file)?.let { activity.exportAndShare(it) } },
                    gap = 6,
                ), LinearLayout.LayoutParams(ui.match, ui.wrap).apply { topMargin = ui.dp(6) })
                addView(ui.softButton(s.remove) {
                    AlertDialog.Builder(activity).setMessage(s.deleteEntry).setNegativeButton(s.cancel, null)
                        .setPositiveButton(s.remove) { _, _ -> file.delete(); refresh() }.show()
                }, LinearLayout.LayoutParams(ui.match, ui.wrap).apply { topMargin = ui.dp(6) })
            }
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(thumb, LinearLayout.LayoutParams(ui.dp(96), ui.dp(96)).apply { marginEnd = ui.dp(12) })
                addView(info, LinearLayout.LayoutParams(0, ui.wrap, 1f))
            }
            historyList.addView(ui.card(row, padding = 12), LinearLayout.LayoutParams(ui.match, ui.wrap).apply { bottomMargin = ui.dp(10) })
        }
    }

}
