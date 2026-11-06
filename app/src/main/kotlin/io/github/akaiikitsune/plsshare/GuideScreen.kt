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

/** The connection guide: numbered steps, notes, the film note, and a shortcut to the Wi-Fi settings. */
class GuideScreen(private val activity: MainActivity) {
    private val s get() = activity.s
    private val ui get() = activity.ui


    fun build(): View {
        val root = ui.column(20)
        root.addView(ui.text(s.guideIntro, 15f))
        ui.space(root, 16)
        s.guideSteps.forEachIndexed { i, step ->
            val number = ui.text("${i + 1}", 16f, bold = true, color = ui.accent)
            val body = ui.text(step, 15f)
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(number, LinearLayout.LayoutParams(ui.dp(28), ui.wrap))
                addView(body, LinearLayout.LayoutParams(0, ui.wrap, 1f))
            }
            root.addView(ui.card(row, padding = 14))
            ui.space(root, 8)
        }
        ui.space(root, 8)
        root.addView(ui.text(s.guideNotes, 13f, alpha = 0.7f))
        ui.space(root, 20)
        root.addView(ui.card(ui.text(s.guideFilmTitle, 16f, bold = true), ui.text(s.guideFilm, 14f).apply { setPadding(0, ui.dp(6), 0, 0) }))
        ui.space(root, 20)
        root.addView(ui.primaryButton(s.wifiSettings) { activity.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) }, LinearLayout.LayoutParams(ui.match, ui.wrap))
        ui.space(root, 8)
        return activity.fixedHeaderScreen(activity.titleBar(s.guideTitle, ui.softButton(s.back) { activity.show(activity.homeView) }.apply { minimumWidth = 0 }), root)
    }

}
