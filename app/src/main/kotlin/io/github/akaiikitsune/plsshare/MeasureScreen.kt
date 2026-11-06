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

/** The hue tables, one sector per line: hue shift, saturation gain, brightness gain; or "none". */
internal fun describeHues(s: Strings, model: FilmModel): String {
    if (!model.hueCorrected) return s.calHuesNone
    return (0 until FilmModel.HUES).joinToString("\n") { k ->
        "%s  %+.0f°   %s ×%.2f   %s ×%.2f".format(FilmModel.HUE_NAMES[k], model.hueShift[k], s.calHueSat, model.hueGain[k], s.calHueLum, model.hueValue[k])
    }
}

/** The printer's geometry in a sentence, or "not measured". */
internal fun describeGeometry(s: Strings, g: PrinterGeometry): String {
    val lines = ArrayList<String>()
    if (g.card != null) {
        val size = g.printedSizeMm
        lines += s.geometryCard.format(size[0], size[1], g.rotationDegrees, g.keystone * 100f)
    } else {
        lines += if (g.linesMeasured) s.geometryStretch.format((g.stretch - 1f) * 100f, (g.stretchX - 1f) * 100f) else s.geometryNone
    }
    lines += if (g.cutsKnown) s.geometryCuts.format(g.cutTop * 100f, g.cutBottom * 100f, g.cutLeft * 100f, g.cutRight * 100f) else s.geometryCutsNone
    val target = g.targetMm
    if (target != null) lines += if (g.placed) s.geometryPlaced.format(target[2]) else s.geometryNotPlaced.format(target[2])
    if (g.rotationByHand) lines += s.geometryTrim.format(g.rotation, if (g.card != null) g.rotationDegrees else 0f)
    return lines.joinToString("\n")
}

/**
 * The chart measurement screen: photos of the printed chart, their markers, the readings averaged
 * and fitted into a film model and a printer geometry, and the result shown before it is applied.
 */
class MeasureScreen(private val activity: MainActivity) {
    companion object {
        const val REQUEST_PICK_CHART = 11
        const val REQUEST_SHOOT_CHART = 12
        /** Longest side kept per chart photo: enough for 9×9 samples per patch, small enough for ten photos in memory. */
        private const val CHART_PHOTO_SIDE = 1600
        /** Longest side of the photo reloaded for reading the ring and the card's edges, one photo at a time. */
        private const val MEASURE_SIDE = 3200
        private const val PREF_SHOOT = "shootUri"
    }

    private val s get() = activity.s
    private val ui get() = activity.ui
    private val mainHandler = Handler(Looper.getMainLooper())
    lateinit var view: View
        private set

    /**
     * One photo of the printed chart in the measurement screen. On colour film the user marks the
     * centres of the four corner patches ([anchors]) and the image corners follow; on monochrome
     * film the corners are marked directly. [reading] is kept with the corners it was read with.
     */
    class ChartPhoto(val bitmap: Bitmap, var corners: FloatArray?, val uri: Uri? = null) {
        var anchors: FloatArray? = corners?.let { ChartReader.anchorsFromCorners(it) }
        /** The card's corners and the ring's cuts measured on this photo (file coordinates, percent), after a measurement. */
        var card: FloatArray? = null
        var ring: FloatArray? = null
        var reading: ChartReader.Reading? = null
        var readingCorners: FloatArray? = null
        val complete get() = corners != null
        var thumb: ImageView? = null
        /** Uniform patches of the photo, for the magnet; computed on first use. */
        var candidates: List<FloatArray>? = null
    }
    private lateinit var markPanel: View
    private lateinit var markPanelButton: Button
    private lateinit var rectifiedBox: View
    private lateinit var rectifiedLabel: TextView
    private var rectifiedGeneration = 0
    /** Every chart photo loaded so far; all those with corners are measured and averaged. */
    private val chartPhotos = ArrayList<ChartPhoto>()
    private var chartCurrent = -1
    private lateinit var chartStrip: LinearLayout
    private lateinit var chartStripScroll: HorizontalScrollView
    private lateinit var chartStripHint: TextView
    private lateinit var cornerPick: CornerPickView
    private lateinit var measureRunButton: Button
    private lateinit var measureResetButton: Button
    private lateinit var measureResultBox: LinearLayout
    private lateinit var measureReport: TextView
    private lateinit var measureCompare: ImageView
    private lateinit var measureCardReport: TextView
    private lateinit var measureCardView: ImageView
    private lateinit var measureColourBox: LinearLayout
    private lateinit var measureColourReport: TextView
    private lateinit var measureColourCompare: ImageView
    private var measuredFilm: FilmModel? = null
    private var measuredGeometry: FloatArray? = null
    /** What the frame hides on each side (top, bottom, left, right), in percent of the file, read on the ring. */
    private var measuredRing: FloatArray? = null
    /** The card's corners in file coordinates, measured on the photos. */
    private var measuredCard: FloatArray? = null
    private lateinit var measureWarped: ImageView
    private var shootUri: Uri? = null


    fun build(): View {
        view = buildView()
        return view
    }

    private fun buildView(): View {
        val root = ui.column(20)
        root.addView(ui.text(s.measureIntro, 14f))
        ui.space(root, 12)
        val pick = ui.softButton(s.measurePick) { pickChartPhoto() }
        val shoot = ui.softButton(s.measureShoot) { shootChartPhoto() }
        root.addView(ui.row(pick, shoot), LinearLayout.LayoutParams(ui.match, ui.wrap))
        ui.space(root, 12)
        // The photos loaded so far: tap one to check or fix its corners, long-press to drop it.
        chartStrip = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        chartStripScroll = HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            addView(chartStrip, LinearLayout.LayoutParams(ui.wrap, ui.wrap))
            visibility = View.GONE
        }
        root.addView(chartStripScroll, LinearLayout.LayoutParams(ui.match, ui.wrap))
        chartStripHint = ui.text(s.measureStripHint, 12f, alpha = 0.7f).apply { visibility = View.GONE }
        root.addView(chartStripHint, LinearLayout.LayoutParams(ui.match, ui.wrap).apply { topMargin = ui.dp(6) })
        ui.space(root, 10)
        // The marking panel folds away once the marks are right; the strip reopens it.
        markPanelButton = ui.softButton("") { setMarkPanel(markPanel.visibility != View.VISIBLE) }
        root.addView(markPanelButton, LinearLayout.LayoutParams(ui.match, ui.wrap))
        ui.space(root, 8)
        markPanel = ui.column().apply {
            cornerPick = CornerPickView(activity).apply {
                background = ui.rounded(Ui.CARD, 12)
                clipToOutline = true
                onChanged = { onChartCornersChanged() }
                onReleased = { index -> onChartMarkReleased(index) }
            }
            addView(cornerPick, LinearLayout.LayoutParams(ui.match, ui.wrap))
            addView(View(activity), ViewGroup.LayoutParams(ui.match, ui.dp(8)))
            addView(ui.text(s.measureHint, 12f, alpha = 0.7f))
            addView(View(activity), ViewGroup.LayoutParams(ui.match, ui.dp(12)))
        }
        root.addView(markPanel, LinearLayout.LayoutParams(ui.match, ui.wrap))
        // The rectified chart: the selected photo's while marking, all photos averaged once folded.
        rectifiedBox = ui.column().apply {
            rectifiedLabel = ui.text(s.measureWarped, 13f, alpha = 0.7f)
            addView(rectifiedLabel)
            addView(View(activity), ViewGroup.LayoutParams(ui.match, ui.dp(6)))
            measureWarped = object : ImageView(activity) {
                override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                    val w = MeasureSpec.getSize(widthMeasureSpec) * 3 / 5
                    setMeasuredDimension(w, w)
                }
            }.apply {
                scaleType = ImageView.ScaleType.FIT_XY
                background = ui.rounded(Ui.CARD, 12)
                clipToOutline = true
            }
            addView(measureWarped, LinearLayout.LayoutParams(ui.wrap, ui.wrap).apply { gravity = Gravity.CENTER_HORIZONTAL })
            addView(View(activity), ViewGroup.LayoutParams(ui.match, ui.dp(12)))
            visibility = View.GONE
        }
        root.addView(rectifiedBox, LinearLayout.LayoutParams(ui.match, ui.wrap))
        measureResetButton = ui.softButton(s.measureReset) { resetChartPhotos() }
        measureRunButton = ui.primaryButton(s.measureRun) { runMeasure() }
        root.addView(ui.row(measureResetButton, measureRunButton), LinearLayout.LayoutParams(ui.match, ui.wrap))
        ui.space(root, 16)

        measureResultBox = ui.column().apply { visibility = View.GONE }
        measureReport = ui.text("", 13f, alpha = 0.7f)
        measureResultBox.addView(measureReport)
        ui.space(measureResultBox, 8)
        measureCompare = object : ImageView(activity) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val w = MeasureSpec.getSize(widthMeasureSpec)
                setMeasuredDimension(w, w * 2 / 11)
            }
        }.apply {
            scaleType = ImageView.ScaleType.FIT_XY
            background = ui.rounded(Ui.CARD, 12)
            clipToOutline = true
        }
        measureResultBox.addView(measureCompare, LinearLayout.LayoutParams(ui.match, ui.wrap))
        // The selected photo with what was found on it: the content square, the card's edges, the frame's window.
        ui.space(measureResultBox, 12)
        measureCardReport = ui.text("", 13f, alpha = 0.7f)
        measureResultBox.addView(measureCardReport)
        ui.space(measureResultBox, 8)
        measureCardView = ImageView(activity).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = ui.rounded(Ui.CARD, 12)
            clipToOutline = true
            contentDescription = s.measureCardViewDescription
        }
        measureResultBox.addView(measureCardView, LinearLayout.LayoutParams(ui.match, ui.wrap))
        measureColourBox = ui.column()
        ui.space(measureColourBox, 12)
        measureColourReport = ui.text("", 13f, alpha = 0.7f)
        measureColourBox.addView(measureColourReport)
        ui.space(measureColourBox, 8)
        measureColourCompare = object : ImageView(activity) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val w = MeasureSpec.getSize(widthMeasureSpec)
                setMeasuredDimension(w, w * 2 / 16)
            }
        }.apply {
            scaleType = ImageView.ScaleType.FIT_XY
            background = ui.rounded(Ui.CARD, 12)
            clipToOutline = true
        }
        measureColourBox.addView(measureColourCompare, LinearLayout.LayoutParams(ui.match, ui.wrap))
        measureResultBox.addView(measureColourBox, LinearLayout.LayoutParams(ui.match, ui.wrap))
        ui.space(measureResultBox, 12)
        measureResultBox.addView(ui.primaryButton(s.measureApply) { applyMeasured() }, LinearLayout.LayoutParams(ui.match, ui.wrap))
        root.addView(measureResultBox, LinearLayout.LayoutParams(ui.match, ui.wrap))
        ui.space(root, 16)

        syncMeasureButtons()
        val header = activity.titleBar(s.measureTitle, ui.softButton(s.back) { activity.show(activity.calibrateView) }.apply { minimumWidth = 0 })
        return activity.fixedHeaderScreen(header, root)
    }

    fun open() {
        hideMeasureResult()
        cornerPick.joinMarkers = activity.filmType.monochrome
        activity.show(view)
    }

    fun syncMeasureButtons() {
        val any = chartPhotos.isNotEmpty()
        ui.setEnabledLook(measureResetButton, any)
        ui.setEnabledLook(measureRunButton, chartPhotos.any { it.complete })
        chartStripScroll.visibility = if (any) View.VISIBLE else View.GONE
        chartStripHint.visibility = if (any) View.VISIBLE else View.GONE
        markPanelButton.visibility = if (any) View.VISIBLE else View.GONE
        if (!any) { markPanel.visibility = View.GONE; rectifiedBox.visibility = View.GONE }
        val open = markPanel.visibility == View.VISIBLE
        markPanelButton.text = (if (open) "▾ " else "▸ ") + (if (chartCurrent >= 0) s.measureMarksOf.format(chartCurrent + 1, chartPhotos.size) else s.measureMarks)
    }

    fun setMarkPanel(open: Boolean) {
        markPanel.visibility = if (open) View.VISIBLE else View.GONE
        syncMeasureButtons()
        updateRectified()
    }

    fun hideMeasureResult() {
        measuredFilm = null
        measureResultBox.visibility = View.GONE
    }

    fun resetChartPhotos() {
        chartPhotos.clear()
        chartCurrent = -1
        chartStrip.removeAllViews()
        cornerPick.bitmap = null
        cornerPick.outline = null
        hideMeasureResult()
        syncMeasureButtons()
    }

    /** The markers moved: keep them on the current photo, derive the corners, and its reading is stale. */
    fun onChartCornersChanged() {
        val entry = chartPhotos.getOrNull(chartCurrent) ?: return
        val marks = cornerPick.cornersArray()
        if (activity.filmType.monochrome) {
            entry.corners = marks
            entry.anchors = marks?.let { ChartReader.anchorsFromCorners(it) }
        } else {
            entry.anchors = marks
            entry.corners = marks?.let { runCatching { ChartReader.cornersFromAnchors(it) }.getOrNull() }
        }
        cornerPick.outline = if (activity.filmType.monochrome) null else entry.corners
        if (entry.readingCorners?.contentEquals(entry.corners ?: FloatArray(0)) != true) { entry.reading = null; hideMeasureResult() }
        refreshChartThumb(entry)
        syncMeasureButtons()
    }

    /** A marker was released: on colour film it snaps to the centre of the patch under it; then the rectified chart follows. */
    fun onChartMarkReleased(index: Int) {
        val entry = chartPhotos.getOrNull(chartCurrent) ?: return
        if (activity.filmType.monochrome) { updateRectified(); return }
        val mark = cornerPick.corners.getOrNull(index) ?: return
        val x = mark[0]; val y = mark[1]
        val generation = ++rectifiedGeneration
        Thread {
            val candidates = entry.candidates ?: runCatching { ChartReader.patchCandidates(entry.bitmap) }.getOrDefault(emptyList()).also { entry.candidates = it }
            val snapped = ChartReader.snapToPatch(candidates, x, y, maxOf(entry.bitmap.width, entry.bitmap.height))
            mainHandler.post {
                if (generation != rectifiedGeneration || chartPhotos.getOrNull(chartCurrent) !== entry) return@post
                if (snapped != null && cornerPick.corners.size > index) {
                    cornerPick.corners[index] = snapped
                    cornerPick.invalidate()
                    onChartCornersChanged()
                }
                updateRectified()
            }
        }.start()
    }

    /**
     * The rectified chart under the strip: the selected photo's while its marks are being edited,
     * every measured photo averaged pixel by pixel once the panel is folded. Readings are computed
     * in the background and kept with the corners they were read with.
     */
    fun updateRectified() {
        val open = markPanel.visibility == View.VISIBLE
        val targets = if (open) listOfNotNull(chartPhotos.getOrNull(chartCurrent)?.takeIf { it.complete }) else chartPhotos.filter { it.complete }
        if (targets.isEmpty()) { rectifiedBox.visibility = View.GONE; return }
        rectifiedLabel.text = if (open) s.measureWarped else s.measureWarpedAll.format(targets.size)
        val type = activity.filmType
        val generation = ++rectifiedGeneration
        Thread {
            val result = runCatching {
                for (e in targets) {
                    val c = e.corners!!
                    if (e.reading == null || e.readingCorners?.contentEquals(c) != true) { e.reading = ChartReader.read(e.bitmap, c, type); e.readingCorners = c.clone() }
                }
                ChartReader.averageWarped(targets.map { it.reading!! })
            }
            mainHandler.post {
                if (generation != rectifiedGeneration) return@post
                result.onSuccess { measureWarped.setImageBitmap(it); rectifiedBox.visibility = View.VISIBLE }
                    .onFailure { rectifiedBox.visibility = View.GONE }
            }
        }.start()
    }

    fun selectChartPhoto(index: Int) {
        val entry = chartPhotos.getOrNull(index) ?: return
        chartCurrent = index
        cornerPick.joinMarkers = activity.filmType.monochrome
        cornerPick.bitmap = entry.bitmap
        val marks = if (activity.filmType.monochrome) entry.corners else entry.anchors
        marks?.let { c -> for (k in 0 until 4) cornerPick.corners += floatArrayOf(c[2 * k], c[2 * k + 1]) }
        cornerPick.outline = if (activity.filmType.monochrome) null else entry.corners
        cornerPick.invalidate()
        for (e in chartPhotos) refreshChartThumb(e)
        entry.thumb?.let { t -> chartStripScroll.post { chartStripScroll.smoothScrollTo(t.left - ui.dp(20), 0) } }
        markPanel.visibility = View.VISIBLE
        syncMeasureButtons()
        updateRectified()
        if (measureResultBox.visibility == View.VISIBLE) updateCardView()
    }

    /**
     * The selected photo, reduced, with what the measurement found on it: the content square
     * (accent), the card's edges (white) and the frame's window (yellow) when they were found.
     */
    private fun updateCardView() {
        val entry = chartPhotos.getOrNull(chartCurrent)?.takeIf { it.complete } ?: run { measureCardView.visibility = View.GONE; return }
        val full = Photos.scaledForEditing(entry.bitmap, 1200)
        val k = full.width.toFloat() / entry.bitmap.width
        val h = ChartReader.homography(FloatArray(8) { entry.corners!![it] * k })
        fun photoQuad(file: FloatArray): FloatArray {
            val out = FloatArray(8)
            for (i in 0 until 4) {
                val p = ChartReader.map(h, TestChart.fileToContent(file[2 * i]).toDouble(), TestChart.fileToContent(file[2 * i + 1]).toDouble())
                out[2 * i] = p[0].toFloat(); out[2 * i + 1] = p[1].toFloat()
            }
            return out
        }
        val inset = TestChart.CONTENT_INSET; val far = 1f - inset
        val content = photoQuad(floatArrayOf(inset, inset, far, inset, far, far, inset, far))
        val card = entry.card?.let { photoQuad(it) }
        val window = entry.card?.let { c -> measuredRing?.let { ring -> PrinterGeometry().apply { this.card = c; setCuts(ring) }.windowCornersFile()?.let { photoQuad(it) } } }
        // Cropped around the card (or around the content, generously) so that the edges can be checked.
        val around = card ?: content
        var left = Float.MAX_VALUE; var top = Float.MAX_VALUE; var right = 0f; var bottom = 0f
        for (i in 0 until 4) { left = minOf(left, around[2 * i]); right = maxOf(right, around[2 * i]); top = minOf(top, around[2 * i + 1]); bottom = maxOf(bottom, around[2 * i + 1]) }
        val margin = (if (card != null) 0.12f else 0.5f) * maxOf(right - left, bottom - top)
        val x0 = (left - margin).toInt().coerceIn(0, full.width - 1); val y0 = (top - margin).toInt().coerceIn(0, full.height - 1)
        val x1 = (right + margin).toInt().coerceIn(x0 + 1, full.width); val y1 = (bottom + margin).toInt().coerceIn(y0 + 1, full.height)
        val bitmap = Bitmap.createBitmap(full, x0, y0, x1 - x0, y1 - y0).copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = maxOf(2f, bitmap.width / 300f) }
        fun quad(q: FloatArray, colour: Int) {
            paint.color = colour
            val path = android.graphics.Path()
            for (i in 0 until 4) { if (i == 0) path.moveTo(q[0] - x0, q[1] - y0) else path.lineTo(q[2 * i] - x0, q[2 * i + 1] - y0) }
            path.close()
            canvas.drawPath(path, paint)
        }
        quad(content, ui.accent)
        card?.let { quad(it, Color.MAGENTA) }
        window?.let { quad(it, Color.YELLOW) }
        measureCardView.setImageBitmap(bitmap)
        measureCardView.visibility = View.VISIBLE
    }

    /** Selected: accent frame; without corners: dimmed, so the ones to fix stand out. */
    private fun refreshChartThumb(entry: ChartPhoto) {
        val t = entry.thumb ?: return
        val selected = chartPhotos.indexOf(entry) == chartCurrent
        t.alpha = if (entry.complete) 1f else 0.45f
        t.background = ui.rounded(if (selected) ui.accent else Ui.CARD, 12)
    }

    fun addChartPhoto(bitmap: Bitmap, corners: FloatArray?, select: Boolean) = addChartPhoto(bitmap, corners, select, null)

    fun addChartPhoto(bitmap: Bitmap, corners: FloatArray?, select: Boolean, uri: Uri?) {
        val entry = ChartPhoto(bitmap, corners, uri)
        chartPhotos += entry
        val thumb = ImageView(activity).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            setPadding(ui.dp(3), ui.dp(3), ui.dp(3), ui.dp(3))
            setImageBitmap(Photos.scaledForEditing(bitmap, 160))
            setOnClickListener { selectChartPhoto(chartPhotos.indexOf(entry)) }
            setOnLongClickListener { removeChartPhoto(entry); true }
            tooltipText = s.measureStripHint
        }
        entry.thumb = thumb
        chartStrip.addView(thumb, LinearLayout.LayoutParams(ui.dp(72), ui.dp(72)).apply { marginEnd = ui.dp(8) })
        refreshChartThumb(entry)
        hideMeasureResult()
        if (select || chartCurrent < 0) selectChartPhoto(chartPhotos.size - 1) else { syncMeasureButtons(); updateRectified() }
    }

    private fun removeChartPhoto(entry: ChartPhoto) {
        val index = chartPhotos.indexOf(entry)
        if (index < 0) return
        chartPhotos.removeAt(index)
        chartStrip.removeView(entry.thumb)
        hideMeasureResult()
        when {
            chartPhotos.isEmpty() -> { chartCurrent = -1; cornerPick.bitmap = null; cornerPick.outline = null }
            index == chartCurrent -> selectChartPhoto(minOf(index, chartPhotos.size - 1))
            index < chartCurrent -> chartCurrent--
        }
        syncMeasureButtons()
        updateRectified()
    }

    /** Results of the photo picker and of the camera app, routed here by the activity. */
    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQUEST_PICK_CHART && resultCode == Activity.RESULT_OK && data != null) {
            val uris = ArrayList<Uri>()
            val clip = data.clipData
            if (clip != null) for (i in 0 until clip.itemCount) uris += clip.getItemAt(i).uri
            else data.data?.let { uris += it }
            loadChartPhotos(uris)
        }
        if (requestCode == REQUEST_SHOOT_CHART) {
            // The camera app is heavy: this process is often killed meanwhile, so the pending
            // entry is remembered in the preferences, not only in a field.
            val uri = shootUri ?: activity.prefs.getString(PREF_SHOOT, null)?.let { Uri.parse(it) }
            shootUri = null
            activity.prefs.edit().remove(PREF_SHOOT).apply()
            val file = uri?.let { CaptureProvider.fileFor(activity, it) }
            activity.journal("chart photo: result $resultCode, uri $uri, file ${file?.length() ?: -1} bytes")
            activity.show(view)
            if (file == null) {
                if (resultCode == Activity.RESULT_OK) measureStatus(s.measureFailed.format("no capture file"))
            } else if (resultCode == Activity.RESULT_OK && file.length() > 0) {
                loadChartPhotos(listOf(uri))
            } else if (resultCode == Activity.RESULT_OK) {
                measureStatus(s.measureFailed.format(s.measureEmptyCapture))
                file.delete()
            } else {
                file.delete()
            }
        }
    }

    /** Several photos at once: they are loaded and aligned in the background, and appear in the strip as they come. */
    fun pickChartPhoto() {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Intent(MediaStore.ACTION_PICK_IMAGES).putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, 10)
        } else {
            Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        activity.startActivityForResult(intent, REQUEST_PICK_CHART)
    }

    /**
     * Take the photo with the camera app, into a private cache file served by [CaptureProvider].
     * The write grant only covers the URIs in the intent's clip data, not the output extra, so the
     * URI goes in both.
     */
    fun shootChartPhoto() {
        val (file, uri) = CaptureProvider.newCapture(activity)
        shootUri = uri
        activity.prefs.edit().putString(PREF_SHOOT, uri.toString()).apply()
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            .putExtra(MediaStore.EXTRA_OUTPUT, uri)
            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        intent.clipData = ClipData.newRawUri("chart", uri)
        measureStatus("")
        try {
            activity.startActivityForResult(intent, REQUEST_SHOOT_CHART)
            activity.journal("chart photo: camera started for $uri")
        } catch (e: Exception) {
            shootUri = null
            activity.prefs.edit().remove(PREF_SHOOT).apply()
            file.delete()
            measureStatus(s.measureFailed.format(e.message ?: e.javaClass.simpleName))
        }
    }

    /** What went wrong with the chart photo: a toast like any other message. */
    fun measureStatus(text: String) = activity.message(text)

    fun loadChartPhotos(uris: List<Uri>) {
        if (uris.isEmpty()) return
        activity.setBusy(true)
        val type = activity.filmType
        Thread {
            var found = 0; var failed = 0
            for ((i, uri) in uris.withIndex()) {
                val result = runCatching {
                    val bitmap = Photos.scaledForEditing(Photos.load(activity.contentResolver, uri), CHART_PHOTO_SIDE)
                    bitmap to runCatching { ChartReader.detectCorners(bitmap, type) }.getOrNull()
                }
                mainHandler.post {
                    result.onSuccess { (bitmap, corners) ->
                        if (corners != null) found++
                        addChartPhoto(bitmap, corners, i == 0, uri)
                        activity.journal("chart photo: loaded ${bitmap.width}x${bitmap.height}, corners ${corners?.joinToString { Math.round(it).toString() } ?: "not found"}")
                    }.onFailure { failed++; activity.journal("chart photo: $uri: $it") }
                }
            }
            mainHandler.post {
                activity.setBusy(false)
                when {
                    failed > 0 -> measureStatus(s.photoError.format("$failed / ${uris.size}"))
                    uris.size == 1 && found == 1 -> activity.toast(s.measureCornersFound)
                    uris.size > 1 -> activity.toast(s.measureLoaded.format(uris.size, found))
                }
            }
        }.start()
    }

    fun runMeasure() {
        val entries = chartPhotos.filter { it.complete }
        if (entries.isEmpty()) return
        val type = activity.filmType
        activity.setBusy(true)
        Thread {
            val result = runCatching {
                for (e in entries) {
                    val c = e.corners!!
                    if (e.reading == null || e.readingCorners?.contentEquals(c) != true) { e.reading = ChartReader.read(e.bitmap, c, type); e.readingCorners = c.clone() }
                }
                val reading = ChartReader.average(entries.map { it.reading!! })
                val geometries = entries.mapNotNull { runCatching { ChartReader.measureGeometry(it.bitmap, it.corners!!) }.getOrNull() }
                val geo = if (geometries.isEmpty()) null else {
                    val n = if (geometries.count { it.size >= 4 } >= maxOf(1, geometries.size / 2)) 4 else 2
                    FloatArray(n) { c -> geometries.filter { it.size > c }.map { it[c] }.sorted().let { v -> v[v.size / 2] } }
                }
                // The card's edges and the framing ring, read on each photo at twice the editing
                // size when it can be reloaded (the markers scale with it), then the median of the
                // photos: of the corners coordinate by coordinate, of the cuts side by side.
                for (e in entries) {
                    val large = e.uri?.let { uri -> runCatching { Photos.loadForMeasure(activity.contentResolver, uri, MEASURE_SIDE) }.getOrNull() }
                        ?.takeIf { it.width > e.bitmap.width && Math.abs(it.width.toFloat() / it.height - e.bitmap.width.toFloat() / e.bitmap.height) < 0.01f }
                    val photo = large ?: e.bitmap
                    val k = photo.width.toFloat() / e.bitmap.width
                    val c = FloatArray(8) { e.corners!![it] * k }
                    e.card = runCatching { ChartReader.measureCard(photo, c) }.getOrNull()
                    e.ring = runCatching { ChartReader.readRing(photo, c, e.card) }.getOrNull()
                    activity.journal("chart photo ${entries.indexOf(e) + 1}: read at ${photo.width}x${photo.height}, card ${e.card?.joinToString { "%.3f".format(it) } ?: "none"}, ring ${e.ring?.joinToString { "%.2f".format(it) } ?: "none"}")
                }
                val rings = entries.mapNotNull { it.ring }
                val ring = if (rings.isEmpty()) null else FloatArray(4) { c -> rings.map { it[c] }.sorted().let { v -> v[v.size / 2] } }
                val cards = entries.mapNotNull { it.card }
                val card = if (cards.isEmpty()) null else FloatArray(8) { c -> cards.map { it[c] }.sorted().let { v -> v[v.size / 2] } }
                Measured(reading, ChartReader.fit(reading, type), geo, ring, card)
            }
            mainHandler.post {
                activity.setBusy(false)
                result.onSuccess { (reading, fit, geo, ring, card) ->
                    val model = fit.model
                    measuredFilm = model
                    measuredGeometry = geo
                    measuredRing = ring
                    measuredCard = card
                    markPanel.visibility = View.GONE
                    syncMeasureButtons()
                    updateRectified()
                    val count = entries.size
                    val measuredGeo = PrinterGeometry().apply { geo?.let { set(it) }; ring?.let { setCuts(it) }; this.card = card }
                    val geoText = if (geo != null || ring != null || card != null) "\n" + s.measureGeometry.format(describeGeometry(s, measuredGeo)) else ""
                    val ringText = if (ring == null) "\n" + s.measureRingNone else ""
                    measureReport.text = (if (count > 1) s.measurePhotos.format(count) + " " else "") + s.measureResult.format("%.1f %%".format(fit.greyRms)) + geoText + ringText
                    val found = entries.count { it.card != null }
                    measureCardReport.text = if (found == 0) s.measureCardNone else s.measureCardFound.format(found, count)
                    updateCardView()
                    measureCompare.setImageBitmap(comparisonStrip(reading, model))
                    val colour = !type.monochrome && reading.patches.isNotEmpty()
                    measureColourBox.visibility = if (colour) View.VISIBLE else View.GONE
                    if (colour) {
                        measureColourReport.text = s.measureColours.format("%.1f %%".format(fit.colourRmsBefore), "%.1f %%".format(fit.colourRmsAfter), describeHues(s, model))
                        measureColourCompare.setImageBitmap(patchStrip(reading, model))
                    }
                    measureResultBox.visibility = View.VISIBLE
                    activity.journal("chart measured: greys %.1f %%, colours %.1f -> %.1f %%, hues %s, paper white %s, geometry %s, ring %s".format(
                        fit.greyRms, fit.colourRmsBefore, fit.colourRmsAfter, describeHues(s, model), reading.paperWhite.joinToString { Math.round(it).toString() },
                        geo?.joinToString { "%.3f".format(it) } ?: "none", ring?.joinToString { "%.1f".format(it) } ?: "none") +
                        ", card " + (card?.joinToString { "%.3f".format(it) } ?: "none"))
                }.onFailure { activity.message(s.measureFailed.format(it.message ?: it.javaClass.simpleName)) }
            }
        }.start()
    }

    /** Two rows of 11 grey steps: measured on the print above, simulated by the fitted model below. */
    fun comparisonStrip(reading: ChartReader.Reading, model: FilmModel): Bitmap {
        val n = reading.greys.size
        val cell = 40
        val bitmap = Bitmap.createBitmap(n * cell, 2 * cell, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint()
        for (i in 0 until n) {
            val g = reading.greys[i]
            paint.color = Color.rgb(Math.round(g[0].coerceIn(0f, 1f) * 255), Math.round(g[1].coerceIn(0f, 1f) * 255), Math.round(g[2].coerceIn(0f, 1f) * 255))
            canvas.drawRect(i * cell.toFloat(), 0f, (i + 1) * cell.toFloat(), cell.toFloat(), paint)
            val v = Math.round(i * 255f / (n - 1))
            val px = intArrayOf(Color.rgb(v, v, v))
            model.simulate(px)
            paint.color = px[0]
            canvas.drawRect(i * cell.toFloat(), cell.toFloat(), (i + 1) * cell.toFloat(), 2 * cell.toFloat(), paint)
        }
        return bitmap
    }

    /** Two rows of the 16 colour patches: measured on the print above, simulated by the fitted model below. */
    fun patchStrip(reading: ChartReader.Reading, model: FilmModel): Bitmap {
        val n = reading.patches.size
        val cell = 40
        val bitmap = Bitmap.createBitmap(n * cell, 2 * cell, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint()
        for (i in 0 until n) {
            val g = reading.patches[i]
            paint.color = Color.rgb(Math.round(g[0].coerceIn(0f, 1f) * 255), Math.round(g[1].coerceIn(0f, 1f) * 255), Math.round(g[2].coerceIn(0f, 1f) * 255))
            canvas.drawRect(i * cell.toFloat(), 0f, (i + 1) * cell.toFloat(), cell.toFloat(), paint)
            val px = intArrayOf(TestChart.patches[i])
            model.simulate(px)
            paint.color = px[0]
            canvas.drawRect(i * cell.toFloat(), cell.toFloat(), (i + 1) * cell.toFloat(), 2 * cell.toFloat(), paint)
        }
        return bitmap
    }


    private fun applyMeasured() {
        val model = measuredFilm ?: return
        activity.applyMeasuredCalibration(model, measuredGeometry, measuredRing, measuredCard)
    }

    /** Everything one measurement yields. */
    private data class Measured(val reading: ChartReader.Reading, val fit: ChartReader.Fit, val geometry: FloatArray?, val ring: FloatArray?, val card: FloatArray?)

}
