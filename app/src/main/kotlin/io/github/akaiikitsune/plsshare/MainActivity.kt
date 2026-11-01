package io.github.akaiikitsune.plsshare

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import instax.InstaxException
import instax.InstaxPrinter
import instax.PrintEvent
import instax.PrinterException
import instax.PrinterInfo
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The app: one activity, three screens swapped in a container (home, editor, connection guide).
 *
 * The UI is built in code with framework widgets only, so the app has no library dependencies
 * beyond the `core` protocol module.
 */
class MainActivity : Activity() {
    companion object {
        private const val REQUEST_PICK = 1
        private const val PREFS = "settings"
        private const val PREF_PIN = "pin"
        private const val PREF_HOST = "host"
        private const val PREF_DEBUG = "debug"
        private const val JOURNAL_LINES = 400
        private const val PREVIEW_SIZE = 800

        /** The SP-3 needs 60 to 90 s after a Wi-Fi association before it accepts connections. */
        private const val REACH_TIMEOUT_MS = 120_000L

        private const val MENU_GUIDE = 1
        private const val MENU_PIN = 2
        private const val MENU_DEBUG = 3
        private const val MENU_UPDATE = 4
        private const val THUMB_SIZE = 96
    }

    private val s = Strings.forDefaultLocale()
    private val ui by lazy { Ui(this) }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val printing = AtomicBoolean(false)

    private lateinit var network: PrinterNetwork
    private lateinit var watcher: PrinterWatcher
    private lateinit var updater: Updater
    private lateinit var prefs: SharedPreferences

    private lateinit var container: FrameLayout
    private lateinit var homeView: View
    private lateinit var editorScreen: View
    private lateinit var guideView: View

    // Home widgets
    private lateinit var statusDot: View
    private lateinit var statusTitle: TextView
    private lateinit var statusDetail: TextView
    private lateinit var statusSpinner: ProgressBar
    private lateinit var statusAction: Button
    private lateinit var previewImage: ImageView
    private lateinit var previewHint: TextView
    private lateinit var pickButton: Button
    private lateinit var editButton: Button
    private lateinit var printButton: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var messageView: TextView
    private lateinit var debugCard: View
    private lateinit var hostField: EditText
    private lateinit var transferOnlySwitch: Switch
    private lateinit var journalView: TextView

    // Editor widgets
    private lateinit var editorView: PhotoEditorView
    private lateinit var brightnessBar: SeekBar
    private lateinit var contrastBar: SeekBar
    private lateinit var saturationBar: SeekBar
    private lateinit var presetRow: LinearLayout
    private val presetTiles = LinkedHashMap<String, Pair<ImageView, TextView>>()

    private var photo: Bitmap? = null
    private val editState = EditState()

    /** The printer address, mirrored from the debug field so background threads never touch a view. */
    @Volatile private var currentHost = InstaxPrinter.DEFAULT_HOST
    private var connected: PrinterInfo? = null
    private var debug = false

    /** Every step and every frame, newest last. Shown in debug mode and copyable for bug reports. */
    private val journal = ArrayDeque<String>()
    private val journalLock = Any()
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    // ---------------------------------------------------------------- Lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actionBar?.hide()
        network = PrinterNetwork(this)
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        debug = prefs.getBoolean(PREF_DEBUG, false)

        container = FrameLayout(this)
        // Android 15 draws apps edge to edge: keep the content clear of the status bar, the
        // navigation bar and the camera cutout.
        container.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
        homeView = buildHome()
        editorScreen = buildEditor()
        guideView = buildGuide()
        container.addView(homeView)
        setContentView(container)
        applyDebugVisibility()

        watcher = PrinterWatcher(network, { probePrinter() }, ::onWatcherState)
        onWatcherState(PrinterWatcher.State.NoWifi)
        handleShareIntent(intent)

        updater = Updater(this, s) { journal(it) }
        mainHandler.postDelayed({ updater.checkDaily() }, 3_000)
    }

    override fun onResume() {
        super.onResume()
        watcher.start()
    }

    override fun onPause() {
        super.onPause()
        watcher.stop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShareIntent(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        updater.release()
        if (!printing.get()) network.disconnect()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (container.getChildAt(0) !== homeView) show(homeView) else @Suppress("DEPRECATION") super.onBackPressed()
    }

    private fun show(screen: View) {
        container.removeAllViews()
        container.addView(screen)
    }

    // ---------------------------------------------------------------- Home screen

    private fun buildHome(): View {
        val root = ui.column(20)

        lateinit var menuButton: Button
        menuButton = ui.softButton("⋮") { showMenu(menuButton) }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(ui.title(s.title), LinearLayout.LayoutParams(0, ui.wrap, 1f))
            addView(menuButton, LinearLayout.LayoutParams(ui.dp(48), ui.dp(44)))
        }
        root.addView(header)
        ui.space(root, 16)

        // Status card
        statusDot = ui.dot(Ui.WARN)
        statusTitle = ui.text("", 16f, bold = true)
        statusDetail = ui.text("", 13f, alpha = 0.8f)
        statusSpinner = ProgressBar(this).apply { visibility = View.GONE }
        statusAction = ui.softButton(s.guide) { show(guideView) }
        val statusTexts = ui.column().apply {
            addView(statusTitle)
            addView(statusDetail)
        }
        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(statusDot, LinearLayout.LayoutParams(ui.dp(12), ui.dp(12)).apply { marginEnd = ui.dp(12) })
            addView(statusTexts, LinearLayout.LayoutParams(0, ui.wrap, 1f))
            addView(statusSpinner, LinearLayout.LayoutParams(ui.dp(24), ui.dp(24)).apply { marginStart = ui.dp(8) })
        }
        val statusCard = ui.card(statusRow)
        statusCard.addView(statusAction, LinearLayout.LayoutParams(ui.match, ui.wrap).apply { topMargin = ui.dp(12) })
        statusCard.setOnClickListener { watcher.refresh() }
        root.addView(statusCard)
        ui.space(root, 16)

        // Preview
        previewImage = object : ImageView(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val w = MeasureSpec.getSize(widthMeasureSpec)
                setMeasuredDimension(w, w)
            }
        }.apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = ui.rounded(Ui.CARD, 20)
            clipToOutline = true
            contentDescription = s.pickPhoto
            setOnClickListener { if (photo == null) pickPhoto() else openEditor() }
        }
        previewHint = ui.text(s.tapToPick, 14f, alpha = 0.6f).apply { gravity = Gravity.CENTER }
        val previewFrame = FrameLayout(this).apply {
            addView(previewImage, FrameLayout.LayoutParams(ui.match, ui.wrap))
            addView(previewHint, FrameLayout.LayoutParams(ui.wrap, ui.wrap, Gravity.CENTER))
        }
        root.addView(previewFrame)
        ui.space(root, 12)

        pickButton = ui.softButton(s.pickPhoto) { pickPhoto() }
        editButton = ui.softButton(s.edit) { openEditor() }
        root.addView(ui.row(pickButton, editButton))
        ui.space(root, 12)

        printButton = ui.primaryButton(s.print) { print() }
        root.addView(printButton, LinearLayout.LayoutParams(ui.match, ui.wrap))
        ui.space(root, 12)

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            visibility = View.INVISIBLE
        }
        root.addView(progressBar, LinearLayout.LayoutParams(ui.match, ui.wrap))
        messageView = ui.text("", 14f)
        root.addView(messageView)
        ui.space(root, 24)

        debugCard = buildDebugCard()
        root.addView(debugCard)

        return ScrollView(this).apply { addView(root) }
    }

    private fun buildDebugCard(): View {
        currentHost = prefs.getString(PREF_HOST, InstaxPrinter.DEFAULT_HOST) ?: InstaxPrinter.DEFAULT_HOST
        hostField = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(currentHost)
            hint = s.host
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(text: android.text.Editable?) {
                    currentHost = text.toString().trim().ifEmpty { InstaxPrinter.DEFAULT_HOST }
                    prefs.edit().putString(PREF_HOST, currentHost).apply()
                }
            })
        }
        transferOnlySwitch = Switch(this).apply { text = s.transferOnly }
        val requestButton = ui.softButton(s.requestNetwork) { requestNetwork() }
        journalView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setTextIsSelectable(true)
        }
        return ui.card(
            ui.text(s.debugTitle, 16f, bold = true),
            ui.text(s.host, 13f, alpha = 0.7f),
            hostField,
            transferOnlySwitch,
            requestButton,
            ui.text(s.journal, 13f, alpha = 0.7f),
            ui.row(ui.softButton(s.copyLog) { copyJournal() }, ui.softButton(s.shareLog) { shareJournal() }),
            journalView,
        )
    }

    private fun applyDebugVisibility() {
        debugCard.visibility = if (debug) View.VISIBLE else View.GONE
        if (debug) journalView.text = journalText()
    }

    private fun showMenu(anchor: View) {
        val menu = PopupMenu(this, anchor)
        menu.menu.add(0, MENU_GUIDE, 0, s.menuGuide)
        menu.menu.add(0, MENU_PIN, 1, s.menuPin)
        menu.menu.add(0, MENU_UPDATE, 2, s.menuUpdate)
        menu.menu.add(0, MENU_DEBUG, 3, s.menuDebug).apply { isCheckable = true; isChecked = debug }
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_GUIDE -> show(guideView)
                MENU_PIN -> askPin()
                MENU_UPDATE -> updater.check(interactive = true) { message(it) }
                MENU_DEBUG -> {
                    debug = !debug
                    prefs.edit().putBoolean(PREF_DEBUG, debug).apply()
                    applyDebugVisibility()
                }
            }
            true
        }
        menu.show()
    }

    private fun askPin() {
        val field = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = s.pinHint
            setText(prefs.getString(PREF_PIN, InstaxPrinter.DEFAULT_PIN.toString()))
        }
        AlertDialog.Builder(this)
            .setTitle(s.pinTitle)
            .setView(field)
            .setNegativeButton(s.cancel, null)
            .setPositiveButton(s.ok) { _, _ ->
                val pin = field.text.toString().trim().toIntOrNull()
                if (pin == null || pin !in 0..9999) message(s.badPin)
                else prefs.edit().putString(PREF_PIN, pin.toString()).apply()
            }
            .show()
    }

    // ---------------------------------------------------------------- Status

    private fun onWatcherState(state: PrinterWatcher.State) {
        connected = (state as? PrinterWatcher.State.Connected)?.info
        statusSpinner.visibility = View.GONE
        statusAction.visibility = View.GONE
        when (state) {
            PrinterWatcher.State.NoWifi -> {
                statusDot.background = ui.rounded(Ui.WARN, 6)
                statusTitle.text = s.noWifi
                statusDetail.text = s.noWifiDetail
                statusAction.text = s.guide
                statusAction.setOnClickListener { show(guideView) }
                statusAction.visibility = View.VISIBLE
            }
            PrinterWatcher.State.OtherWifi -> {
                statusDot.background = ui.rounded(Ui.WARN, 6)
                statusTitle.text = s.otherWifi
                statusDetail.text = s.otherWifiDetail
                statusAction.text = s.wifiSettings
                statusAction.setOnClickListener { startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) }
                statusAction.visibility = View.VISIBLE
            }
            PrinterWatcher.State.WaitingForPrinter -> {
                statusDot.background = ui.rounded(Ui.WARN, 6)
                statusTitle.text = s.waitingPrinter
                statusDetail.text = s.waitingPrinterDetail
                statusSpinner.visibility = View.VISIBLE
            }
            is PrinterWatcher.State.Connected -> {
                val info = state.info
                statusDot.background = ui.rounded(Ui.OK_GREEN, 6)
                statusTitle.text = s.connected
                val warnings = listOfNotNull(
                    if (info.coverOpen) s.coverOpen else null,
                    if (info.filmRemaining == 0) s.noFilm else null,
                    if (info.batteryLevel == 0 && !info.charging) s.lowBattery else null,
                )
                statusDetail.text = s.connectedDetail.format(
                    info.model, info.filmRemaining, info.batteryLevel, if (info.charging) s.charging else "",
                ) + warnings.joinToString("") { "\n⚠️ $it" }
            }
        }
        updateButtons()
    }

    private fun updateButtons() {
        val busy = printing.get()
        pickButton.isEnabled = !busy
        editButton.isEnabled = !busy && photo != null
        printButton.isEnabled = !busy && photo != null && connected != null
        printButton.alpha = if (printButton.isEnabled) 1f else 0.45f
    }

    private fun message(text: String) {
        messageView.text = text
        if (text.isNotBlank()) journal(text)
    }

    private fun setBusy(busy: Boolean) {
        updateButtons()
        progressBar.visibility = if (busy) View.VISIBLE else View.INVISIBLE
        progressBar.isIndeterminate = busy
        watcher.paused = busy
        if (busy) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    // ---------------------------------------------------------------- Printer access

    private fun host(): String = currentHost

    private fun pin(): Int = prefs.getString(PREF_PIN, null)?.toIntOrNull()?.takeIf { it in 0..9999 } ?: InstaxPrinter.DEFAULT_PIN

    /** A quick, single-attempt client for the status watcher. */
    private fun probePrinter() = InstaxPrinter(
        host = host(), pin = pin(), timeoutMillis = 2_000, connectAttempts = 1,
        socketFactory = network.socketFactory(), logger = if (debug) { m -> journal("probe: $m") } else null,
    )

    /** The patient client used for printing. */
    private fun printPrinter(): InstaxPrinter {
        journal("printer ${host()} pin ${pin()}, bound to ${network.describeBinding()}")
        return InstaxPrinter(
            host = host(), pin = pin(), reachTimeoutMillis = REACH_TIMEOUT_MS,
            socketFactory = network.socketFactory(), logger = { journal(it) },
        )
    }

    /** Debug only: ask Android for the INSTAX network (DHCP), the way the first versions did. */
    private fun requestNetwork() {
        message(s.requesting)
        journal("requesting an INSTAX- network")
        network.connect(object : PrinterNetwork.Listener {
            override fun onConnected() {
                journal("network available: ${network.network}")
                message("")
                watcher.refresh()
            }

            override fun onFailed() {
                journal("network request failed or timed out")
                message(s.requestFailed)
            }

            override fun onLost() {
                journal("network lost")
                watcher.refresh()
            }
        })
    }

    private fun showError(e: Throwable) {
        journal("error: $e")
        message(
            when (e) {
                is PrinterException -> s.printerError.format(s.describeReturnCode(e.returnCode, e.message ?: ""))
                is InstaxException -> s.printerError.format(e.message)
                is IOException -> if (e.message?.contains("ECONNREFUSED") == true) s.refused.format("ECONNREFUSED")
                else s.networkError.format(e.message ?: e.javaClass.simpleName)
                else -> e.toString()
            },
        )
    }

    // ---------------------------------------------------------------- Photos

    private fun pickPhoto() {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Intent(MediaStore.ACTION_PICK_IMAGES)
        } else {
            Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*")
        }
        startActivityForResult(intent, REQUEST_PICK)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_PICK && resultCode == RESULT_OK) data?.data?.let { loadPhoto(it) }
    }

    private fun handleShareIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val uri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
        if (uri != null) {
            loadPhoto(uri)
            message(s.shared)
        }
    }

    private fun loadPhoto(uri: Uri) {
        Thread {
            val result = runCatching { Photos.load(contentResolver, uri) }
            mainHandler.post {
                result.onSuccess {
                    photo?.recycle()
                    photo = it
                    editState.reset()
                    refreshPreview()
                    updateButtons()
                }.onFailure { message(s.photoError.format(it.message ?: it.javaClass.simpleName)) }
            }
        }.start()
    }

    private fun refreshPreview() {
        val bitmap = photo ?: return
        previewImage.setImageBitmap(PrintRenderer.render(bitmap, editState, PREVIEW_SIZE))
        previewHint.visibility = View.GONE
    }

    // ---------------------------------------------------------------- Editor screen

    private fun buildEditor(): View {
        val root = ui.column(16)

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(ui.softButton(s.cancel) { show(homeView) }, LinearLayout.LayoutParams(ui.wrap, ui.wrap))
            addView(ui.text(s.editorTitle, 17f, bold = true).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(0, ui.wrap, 1f))
            addView(ui.primaryButton(s.ok) { applyEdits() }.apply { minimumHeight = ui.dp(44); setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f) }, LinearLayout.LayoutParams(ui.dp(80), ui.wrap))
        }
        root.addView(header)
        ui.space(root, 12)

        editorView = PhotoEditorView(this)
        root.addView(editorView, LinearLayout.LayoutParams(ui.match, ui.wrap))
        ui.space(root, 8)
        root.addView(ui.text(s.editorHint, 12f, alpha = 0.6f).apply { gravity = Gravity.CENTER })
        ui.space(root, 12)

        root.addView(ui.row(
            ui.softButton(s.rotate) { editorView.update { rotation = (rotation + 90) % 360 } },
            ui.softButton(s.fill) { editorView.update { scale = 1f; offsetX = 0f; offsetY = 0f } },
            ui.softButton(s.whole) { editorView.update { scale = PrintRenderer.containScale(editorView.bitmap!!, this); offsetX = 0f; offsetY = 0f } },
            ui.softButton(s.blackWhite) { editorView.update { saturation = if (saturation == 0f) 1f else 0f }; syncSliders() },
            ui.softButton(s.reset) { editorView.update { reset() }; syncSliders(); syncPresets() },
            gap = 6,
        ))
        ui.space(root, 16)

        root.addView(ui.text(s.filters, 13f, alpha = 0.7f))
        ui.space(root, 6)
        presetRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (preset in Filters.presets) {
            val thumb = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = ui.rounded(Ui.CARD, 14)
                clipToOutline = true
            }
            val label = ui.text(s.filterName(preset.id), 12f).apply { gravity = Gravity.CENTER }
            val tile = ui.column().apply {
                gravity = Gravity.CENTER_HORIZONTAL
                addView(thumb, LinearLayout.LayoutParams(ui.dp(68), ui.dp(68)))
                addView(label, LinearLayout.LayoutParams(ui.dp(68), ui.wrap).apply { topMargin = ui.dp(4) })
                setOnClickListener { editorView.update { filter = preset.id }; syncPresets() }
            }
            presetTiles[preset.id] = thumb to label
            presetRow.addView(tile, LinearLayout.LayoutParams(ui.wrap, ui.wrap).apply { marginEnd = ui.dp(10) })
        }
        root.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(presetRow) })
        ui.space(root, 16)

        brightnessBar = slider(root, s.brightness) { v -> editorView.update { brightness = v - 100f } }
        contrastBar = slider(root, s.contrast) { v -> editorView.update { contrast = v - 100f } }
        saturationBar = slider(root, s.saturation) { v -> editorView.update { saturation = v / 100f } }

        return ScrollView(this).apply { addView(root) }
    }

    private fun slider(parent: LinearLayout, label: String, onValue: (Int) -> Unit): SeekBar {
        parent.addView(ui.text(label, 13f, alpha = 0.7f))
        val bar = SeekBar(this).apply {
            max = 200
            progress = 100
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser) onValue(progress)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar) {}
                override fun onStopTrackingTouch(seekBar: SeekBar) {}
            })
        }
        parent.addView(bar, LinearLayout.LayoutParams(ui.match, ui.wrap))
        ui.space(parent, 8)
        return bar
    }

    private fun syncSliders() {
        brightnessBar.progress = (editorView.state.brightness + 100f).toInt()
        contrastBar.progress = (editorView.state.contrast + 100f).toInt()
        saturationBar.progress = (editorView.state.saturation * 100f).toInt()
    }

    private fun syncPresets() {
        val selected = editorView.state.filter
        for ((id, views) in presetTiles) {
            val (thumb, label) = views
            thumb.alpha = if (id == selected) 1f else 0.55f
            label.typeface = if (id == selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            label.setTextColor(if (id == selected) Ui.ACCENT else label.currentTextColor and 0x00FFFFFF or (0xFF shl 24))
        }
    }

    /** Small filtered previews of the current photo for the preset row. */
    private fun buildThumbnails(bitmap: Bitmap) {
        val base = PrintRenderer.render(bitmap, EditState(), THUMB_SIZE)
        for ((id, views) in presetTiles) views.first.setImageBitmap(Filters.thumbnail(base, Filters.byId(id)))
    }

    private fun openEditor() {
        val bitmap = photo ?: return pickPhoto()
        editorView.bitmap = bitmap
        editorView.state.copyFrom(editState)
        buildThumbnails(bitmap)
        syncSliders()
        syncPresets()
        show(editorScreen)
    }

    private fun applyEdits() {
        editState.copyFrom(editorView.state)
        refreshPreview()
        show(homeView)
    }

    // ---------------------------------------------------------------- Guide screen

    private fun buildGuide(): View {
        val root = ui.column(20)
        root.addView(ui.title(s.guideTitle))
        ui.space(root, 12)
        root.addView(ui.text(s.guideIntro, 15f))
        ui.space(root, 16)
        s.guideSteps.forEachIndexed { i, step ->
            val number = ui.text("${i + 1}", 16f, bold = true, color = Ui.ACCENT)
            val body = ui.text(step, 15f)
            val row = LinearLayout(this).apply {
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
        root.addView(ui.primaryButton(s.wifiSettings) { startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) }, LinearLayout.LayoutParams(ui.match, ui.wrap))
        ui.space(root, 8)
        root.addView(ui.softButton(s.back) { show(homeView) }, LinearLayout.LayoutParams(ui.match, ui.wrap))
        return ScrollView(this).apply { addView(root) }
    }

    // ---------------------------------------------------------------- Printing

    private fun print() {
        val bitmap = photo
        if (bitmap == null) return message(s.noPhoto)
        if (connected == null) return message(s.notConnectedYet)
        if (!printing.compareAndSet(false, true)) return message(s.busy)
        setBusy(true)
        val printer = printPrinter()
        val source = PrintRenderer.source(bitmap, editState)
        val transferOnly = debug && transferOnlySwitch.isChecked
        journal(if (transferOnly) "starting a transfer-only test" else "starting a print")
        Thread {
            val result = runCatching {
                printer.print(source, transferOnly) { event -> mainHandler.post { onPrintEvent(event) } }
            }
            mainHandler.post {
                printing.set(false)
                setBusy(false)
                result.onFailure { showError(it) }
                watcher.refresh()
            }
        }.start()
    }

    private fun onPrintEvent(event: PrintEvent) {
        when (event) {
            is PrintEvent.Reaching -> messageView.text = s.reaching.format(event.reason, event.attempt)
            PrintEvent.Locking -> message(s.locking)
            is PrintEvent.Ready -> {}
            is PrintEvent.Transferring -> {
                progressBar.isIndeterminate = false
                progressBar.progress = (1000L * event.sentBytes / event.totalBytes).toInt()
                messageView.text = "${s.sending} ${100L * event.sentBytes / event.totalBytes} %"
            }
            PrintEvent.WaitingForPrinter -> {
                progressBar.isIndeterminate = true
                message(s.waiting)
            }
            is PrintEvent.Printing -> message(s.printing.format(event.estimatedSeconds.toInt()))
            is PrintEvent.Finished -> message(s.done.format(event.filmRemaining))
            PrintEvent.TransferOnlyFinished -> message(s.transferOnlyDone)
            PrintEvent.Unlocked -> journal("unlocked")
        }
    }

    // ---------------------------------------------------------------- Journal

    /** Append a line to the journal from any thread. */
    private fun journal(text: String) {
        val line = "${timeFormat.format(Date())} $text"
        val snapshot: String
        synchronized(journalLock) {
            journal.addLast(line)
            while (journal.size > JOURNAL_LINES) journal.removeFirst()
            snapshot = if (debug) journal.joinToString("\n") else ""
        }
        if (!debug) return
        if (Looper.myLooper() == Looper.getMainLooper()) journalView.text = snapshot
        else mainHandler.post { journalView.text = snapshot }
    }

    private fun journalText(): String = synchronized(journalLock) { journal.joinToString("\n") }

    private fun copyJournal() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("pls-share-sp3 log", journalText()))
        message(s.logCopied)
    }

    private fun shareJournal() {
        val intent = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "pls-share-sp3 log")
            .putExtra(Intent.EXTRA_TEXT, journalText())
        startActivity(Intent.createChooser(intent, s.shareLog))
    }
}
