package io.github.akaiikitsune.plsshare

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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
import java.io.File
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
        private const val REQUEST_PICK_MANY = 2
        private const val REQUEST_NOTIFICATIONS = 3
        private const val MENU_HISTORY = 5
        private const val MENU_EXPORT = 6
        private const val PREFS = "settings"
        private const val PREF_PIN = "pin"
        private const val PREF_HOST = "host"
        private const val PREF_DEBUG = "debug"
        private const val PREF_FILM = "filmPreview"
        private const val PREF_QUEUE = "queue"
        private const val PREF_COMPENSATE = "compensate"
        private const val PREF_FILM_TYPE = "filmType"
        private const val PREF_SHOOT = "shootUri"
        private const val MENU_QUEUE = 7
        private const val MENU_CALIBRATE = 8
        private const val MENU_COMPENSATE = 9
        private const val MENU_COLLAGE = 10
        private const val REQUEST_PICK_CELL = 4
        private const val REQUEST_PICK_CHART = 11
        private const val REQUEST_SHOOT_CHART = 12
        /** Longest side of the chart photo the reader works on (enough for 9×9 samples per patch). */
        private const val CHART_PHOTO_SIDE = 2000
        private const val JOURNAL_LINES = 400
        private const val PREVIEW_SIZE = 800
        private const val UNDO_DEPTH = 40
        /** Changes closer than this are merged into one undo step (slider nudges, typing). */
        private const val UNDO_MERGE_MS = 700L

        /** The SP-3 needs 60 to 90 s after a Wi-Fi association before it accepts connections. */
        private const val REACH_TIMEOUT_MS = 120_000L

        private const val MENU_GUIDE = 1
        private const val MENU_PIN = 2
        private const val MENU_DEBUG = 3
        private const val MENU_UPDATE = 4
        private const val THUMB_SIZE = 96

        /** Longest side of the copy the editor works on. */
        private const val EDITOR_SOURCE_SIDE = 1400
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
    private lateinit var historyView: View
    private lateinit var calibrateView: View
    private lateinit var collageView: View
    private lateinit var collage: CollageView
    private var pendingCell = -1
    private lateinit var measureView: View
    private lateinit var cornerPick: CornerPickView
    private lateinit var measureRunButton: Button
    private lateinit var measureResetButton: Button
    private lateinit var measureResultBox: LinearLayout
    private lateinit var measureReport: TextView
    private lateinit var measureCompare: ImageView
    private lateinit var measureColourBox: LinearLayout
    private lateinit var measureColourReport: TextView
    private lateinit var measureColourCompare: ImageView
    private lateinit var hueReadout: TextView
    private var measuredFilm: FilmModel? = null
    private var shootUri: Uri? = null
    private lateinit var chartView: ImageView
    private lateinit var film: FilmModel
    private var compensate = false
    private var filmType = FilmType.COLOR
    private var chartBase: Bitmap? = null
    private lateinit var filmTypeSelector: Ui.Segmented
    private lateinit var calibrationTitle: TextView
    private lateinit var monoNoteView: TextView
    /** Views of the editor that only make sense on colour film. */
    private val colourOnlyViews = ArrayList<View>()
    /** Calibration sliders that only make sense on colour film (bar + its header). */
    private val colourOnlyCal = ArrayList<View>()
    private lateinit var historyList: LinearLayout
    private lateinit var queueRow: LinearLayout
    private lateinit var queueEmptyText: TextView
    private lateinit var printQueueButton: Button
    private lateinit var queueButton: Button
    private lateinit var queueCard: View
    private var queueEnabled = false

    // Home widgets
    private lateinit var statusDot: View
    private lateinit var statusTitle: TextView
    private lateinit var statusDetail: TextView
    private lateinit var statusSpinner: ProgressBar
    private lateinit var statusAction: Button
    private lateinit var previewImage: PrintPreviewView
    private lateinit var previewHint: TextView
    private lateinit var pickButton: Button
    private lateinit var editButton: Button
    private lateinit var printButton: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var cancelButton: Button
    private lateinit var messageView: TextView
    private lateinit var debugCard: View
    private lateinit var hostField: EditText
    private lateinit var transferOnlySwitch: Switch
    private lateinit var journalView: TextView

    // Editor widgets
    private lateinit var editorView: PhotoEditorView
    private class SliderEntry(val bar: SeekBar, val value: TextView, val min: Int, val read: (EditState) -> Int)
    private val sliders = LinkedHashMap<String, SliderEntry>()
    private lateinit var histogramView: HistogramView
    private lateinit var histogramButton: Button
    private lateinit var clippingButton: Button
    private var showHistogram = false
    private lateinit var adjustPanel: View
    private lateinit var textsPanel: LinearLayout
    private lateinit var tabs: Ui.Segmented
    private lateinit var overlayList: LinearLayout
    private lateinit var overlayEditor: LinearLayout
    private lateinit var overlayTextField: EditText
    private lateinit var overlaySizeBar: SeekBar
    private lateinit var overlayDarkSwitch: Switch
    private lateinit var blackWhiteButton: Button
    private val fontButtons = LinkedHashMap<Overlay.Font, Button>()
    private lateinit var overlayMarginBar: SeekBar
    private lateinit var overlaySizeValue: TextView
    private lateinit var overlayRotationBar: SeekBar
    private lateinit var overlayRotationValue: TextView
    /** Overlay-editor rows that only apply to text (not emoji): dark switch, fonts, styles. */
    private val textOnlyViews = ArrayList<View>()
    private lateinit var presetRow2: LinearLayout
    private lateinit var filmButton: Button
    private val presets by lazy { Presets(prefs) }
    private val frameColors = listOf(
        Color.WHITE, Color.BLACK, 0xFFF5EBD8.toInt(), 0xFFF7C9D4.toInt(), 0xFFC9DDF7.toInt(), 0xFFC9A77C.toInt(), 0xFF2E3440.toInt(),
    )
    private val frameColorButtons = ArrayList<View>()
    private lateinit var overlayMarginValue: TextView
    private var photoDate: Long? = null
    private lateinit var presetRow: LinearLayout
    private val presetTiles = LinkedHashMap<String, Pair<ImageView, TextView>>()

    /** The theme's default label colour, restored when a toggle or a preset is deselected. */
    private var labelColor = 0

    private var photo: Bitmap? = null

    // Undo / redo in the editor: snapshots of the edit state before each user action.
    private val undoStack = ArrayDeque<EditState>()
    private val redoStack = ArrayDeque<EditState>()
    private var lastSnapshotAt = 0L
    private lateinit var undoButton: Button
    private lateinit var redoButton: Button

    /** A smaller copy of [photo] for the editor: enough for the screen, much faster to draw. */
    private var editorPhoto: Bitmap? = null
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
        queueEnabled = prefs.getBoolean(PREF_QUEUE, false)
        filmType = FilmType.fromKey(prefs.getString(PREF_FILM_TYPE, null))
        film = FilmModel.load(prefs, filmType)
        PrintRenderer.monochrome = filmType.monochrome
        compensate = prefs.getBoolean(PREF_COMPENSATE, false)

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
        historyView = buildHistory()
        calibrateView = buildCalibration()
        measureView = buildMeasure()
        collageView = buildCollage()
        container.addView(homeView)
        applyFilmType()
        applyFilmModel()
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
        PrintService.listener = serviceListener
        applyServiceStatus(PrintService.status)
        refreshQueue()
    }

    override fun onPause() {
        super.onPause()
        watcher.stop()
        if (PrintService.listener === serviceListener) PrintService.listener = null
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
        when {
            container.getChildAt(0) === measureView -> show(calibrateView)
            container.getChildAt(0) !== homeView -> show(homeView)
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    private fun show(screen: View) {
        container.removeAllViews()
        container.addView(screen)
    }

    // ---------------------------------------------------------------- Home screen

    /** A fixed title bar: title on the left, an optional action on the right. */
    private fun titleBar(title: String, action: View? = null) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(ui.dp(20), ui.dp(12), ui.dp(20), ui.dp(12))
        addView(ui.title(title), LinearLayout.LayoutParams(0, ui.wrap, 1f))
        if (action != null) addView(action, LinearLayout.LayoutParams(ui.wrap, ui.dp(44)))
    }

    /** A screen made of a fixed title bar and a scrolling body. */
    private fun fixedHeaderScreen(header: View, body: View): View = ui.column().apply {
        addView(header, LinearLayout.LayoutParams(ui.match, ui.wrap))
        addView(View(this@MainActivity).apply { setBackgroundColor(Ui.CARD_STRONG) }, LinearLayout.LayoutParams(ui.match, ui.dp(1)))
        addView(ScrollView(this@MainActivity).apply { addView(body) }, LinearLayout.LayoutParams(ui.match, 0, 1f))
    }

    private fun buildHome(): View {
        val root = ui.column(20)

        lateinit var menuButton: Button
        menuButton = ui.softButton("⋮") { showMenu(menuButton) }
        val header = titleBar(s.title, menuButton)

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
        val statusCard = ui.card(statusRow, statusAction)
        statusCard.setOnClickListener { watcher.refresh() }
        root.addView(statusCard)
        ui.space(root, 16)

        // Preview
        previewImage = PrintPreviewView(this).apply {
            contentDescription = s.pickPhoto
            filmPreview = prefs.getBoolean(PREF_FILM, false)
            onClick = { if (photo == null) pickPhoto() else openEditor() }
        }
        previewHint = ui.text(s.tapToPick, 14f, alpha = 0.6f).apply { gravity = Gravity.CENTER }
        val previewFrame = FrameLayout(this).apply {
            addView(previewImage, FrameLayout.LayoutParams(ui.match, ui.wrap))
            addView(previewHint, FrameLayout.LayoutParams(ui.wrap, ui.wrap, Gravity.CENTER))
        }
        root.addView(previewFrame)
        filmTypeSelector = ui.Segmented(listOf(s.filmColor, s.filmMono)) { index -> setFilmType(if (index == 0) FilmType.COLOR else FilmType.MONO) }
        val filmRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(ui.text(s.filmTypeLabel, 14f), LinearLayout.LayoutParams(ui.wrap, ui.wrap).apply { marginEnd = ui.dp(12) })
            addView(filmTypeSelector.view, LinearLayout.LayoutParams(0, ui.wrap, 1f))
        }
        root.addView(filmRow)
        ui.space(root, 12)

        pickButton = ui.softButton(s.pickPhoto) { pickPhoto() }
        editButton = ui.softButton(s.edit) { openEditor() }
        root.addView(ui.row(pickButton, editButton))
        ui.space(root, 12)

        printButton = ui.primaryButton(s.print) { print() }
        root.addView(printButton, LinearLayout.LayoutParams(ui.match, ui.wrap))
        ui.space(root, 8)
        queueButton = ui.softButton(s.addToQueue) { addCurrentToQueue() }
        root.addView(queueButton, LinearLayout.LayoutParams(ui.match, ui.wrap))
        ui.space(root, 12)

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            visibility = View.GONE
        }
        root.addView(progressBar, LinearLayout.LayoutParams(ui.match, ui.wrap))
        cancelButton = ui.softButton(s.cancelPrint) {
            PrintService.cancel(this)
            ui.setEnabledLook(cancelButton, false)
            toast(s.cancelRequested)
        }.apply { visibility = View.GONE }
        root.addView(cancelButton, LinearLayout.LayoutParams(ui.match, ui.wrap).apply { topMargin = ui.dp(8) })
        messageView = ui.text("", 14f).apply { visibility = View.GONE }
        root.addView(messageView, LinearLayout.LayoutParams(ui.match, ui.wrap).apply { topMargin = ui.dp(8) })
        ui.space(root, 16)

        // Queue card
        queueRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        queueEmptyText = ui.text(s.queueEmpty, 13f, alpha = 0.6f)
        printQueueButton = ui.softButton(s.printQueue.format(0)) { printQueue() }
        queueCard = ui.card(
            ui.text(s.queueTitle, 16f, bold = true),
            queueEmptyText,
            HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(queueRow) },
            ui.row(ui.softButton(s.addSeveral) { pickSeveral() }, ui.softButton(s.clearQueue) { clearQueue() }, gap = 6),
            printQueueButton,
        )
        root.addView(queueCard)
        ui.space(root, 24)

        debugCard = buildDebugCard()
        root.addView(debugCard)

        return fixedHeaderScreen(header, root)
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
        // The queue is an occasional tool: hidden unless enabled in the menu, but it shows up by
        // itself while something is waiting so queued photos are never forgotten.
        val hasJobs = Jobs.list(Jobs.queueDir(this)).isNotEmpty()
        queueCard.visibility = if (queueEnabled || hasJobs) View.VISIBLE else View.GONE
        queueButton.visibility = if (queueEnabled) View.VISIBLE else View.GONE
    }

    private fun showMenu(anchor: View) {
        val menu = PopupMenu(this, anchor)
        menu.menu.add(0, MENU_GUIDE, 0, s.menuGuide)
        menu.menu.add(0, MENU_PIN, 1, s.menuPin)
        menu.menu.add(0, MENU_HISTORY, 2, s.menuHistory)
        menu.menu.add(0, MENU_COLLAGE, 3, s.menuCollage)
        menu.menu.add(0, MENU_EXPORT, 3, s.menuExport)
        menu.menu.add(0, MENU_UPDATE, 4, s.menuUpdate)
        menu.menu.add(0, MENU_CALIBRATE, 5, s.menuCalibrate)
        menu.menu.add(0, MENU_COMPENSATE, 6, s.menuCompensate).apply { isCheckable = true; isChecked = compensate }
        menu.menu.add(0, MENU_QUEUE, 7, s.menuQueue).apply { isCheckable = true; isChecked = queueEnabled }
        menu.menu.add(0, MENU_DEBUG, 8, s.menuDebug).apply { isCheckable = true; isChecked = debug }
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_GUIDE -> show(guideView)
                MENU_PIN -> askPin()
                MENU_HISTORY -> { refreshHistory(); show(historyView) }
                MENU_EXPORT -> exportCurrent()
                MENU_COLLAGE -> show(collageView)
                MENU_UPDATE -> updater.check(interactive = true) { message(it) }
                MENU_DEBUG -> {
                    debug = !debug
                    prefs.edit().putBoolean(PREF_DEBUG, debug).apply()
                    applyDebugVisibility()
                }
                MENU_CALIBRATE -> { refreshChart(); show(calibrateView) }
                MENU_COMPENSATE -> {
                    compensate = !compensate
                    prefs.edit().putBoolean(PREF_COMPENSATE, compensate).apply()
                    applyFilmModel()
                    toast(if (compensate) s.compensationOn else s.compensationOff)
                }
                MENU_QUEUE -> {
                    queueEnabled = !queueEnabled
                    prefs.edit().putBoolean(PREF_QUEUE, queueEnabled).apply()
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
                    if (info.filmRemaining in 1..2) s.lowFilm.format(info.filmRemaining) else null,
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
        ui.setEnabledLook(pickButton, !busy)
        ui.setEnabledLook(editButton, !busy && photo != null)
        ui.setEnabledLook(printButton, !busy && photo != null && connected != null)
        ui.setEnabledLook(queueButton, photo != null)
        ui.setEnabledLook(printQueueButton, !busy && connected != null && Jobs.list(Jobs.queueDir(this)).isNotEmpty())
    }

    private fun message(text: String) {
        messageView.text = text
        messageView.visibility = if (text.isBlank()) View.GONE else View.VISIBLE
        if (text.isNotBlank()) journal(text)
    }

    /** A passing confirmation (a setting toggled, something saved): a toast, journaled, never left on screen. */
    private fun toast(text: String) {
        android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_SHORT).show()
        journal(text)
    }

    private fun setBusy(busy: Boolean) {
        updateButtons()
        progressBar.visibility = if (busy) View.VISIBLE else View.GONE
        cancelButton.visibility = if (busy) View.VISIBLE else View.GONE
        if (busy) ui.setEnabledLook(cancelButton, true)
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
        if (requestCode == REQUEST_PICK_CELL && resultCode == RESULT_OK && data?.data != null && pendingCell >= 0) {
            val cell = pendingCell
            val uri = data.data!!
            Thread {
                val result = runCatching { Photos.scaledForEditing(Photos.load(contentResolver, uri), 1200) }
                mainHandler.post { result.onSuccess { collage.setCell(cell, it) }.onFailure { message(s.photoError.format(it.message ?: "")) } }
            }.start()
        }
        if (requestCode == REQUEST_PICK_CHART && resultCode == RESULT_OK && data?.data != null) loadChartPhoto(data.data!!)
        if (requestCode == REQUEST_SHOOT_CHART) {
            // The camera app is heavy: this process is often killed meanwhile, so the pending
            // entry is remembered in the preferences, not only in a field.
            val uri = shootUri ?: prefs.getString(PREF_SHOOT, null)?.let { Uri.parse(it) }
            shootUri = null
            prefs.edit().remove(PREF_SHOOT).apply()
            journal("chart photo: result $resultCode, uri $uri")
            if (uri != null) {
                if (resultCode == RESULT_OK) {
                    runCatching { contentResolver.update(uri, android.content.ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null) }
                        .onFailure { journal("chart photo: could not publish the entry: $it") }
                    show(measureView)
                    loadChartPhoto(uri)
                } else {
                    runCatching { contentResolver.delete(uri, null, null) }
                }
            }
        }
        if (requestCode == REQUEST_PICK_MANY && resultCode == RESULT_OK && data != null) {
            val uris = ArrayList<Uri>()
            val clip = data.clipData
            if (clip != null) for (i in 0 until clip.itemCount) uris += clip.getItemAt(i).uri
            else data.data?.let { uris += it }
            if (uris.isNotEmpty()) queueMany(uris)
        }
    }

    // ---------------------------------------------------------------- Queue

    private fun pickSeveral() {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Intent(MediaStore.ACTION_PICK_IMAGES).putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, 10)
        } else {
            Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        startActivityForResult(intent, REQUEST_PICK_MANY)
    }

    /** Render several photos with the current look (default framing) and queue them. */
    private fun queueMany(uris: List<Uri>) {
        val look = EditState().apply { copyLookFrom(editState) }
        setBusy(true)
        Thread {
            var added = 0
            for (uri in uris) {
                try {
                    val bitmap = Photos.load(contentResolver, uri)
                    val state = EditState().apply { copyLookFrom(look); photoDate = Photos.exifDate(contentResolver, uri) }
                    val rendered = renderForPrint(bitmap, state)
                    Jobs.save(Jobs.queueDir(this), rendered)
                    rendered.recycle()
                    bitmap.recycle()
                    added++
                } catch (e: Exception) {
                    journal("queue: could not render $uri: $e")
                }
            }
            val count = added
            mainHandler.post {
                setBusy(false)
                refreshQueue()
                toast(s.queued.format(Jobs.list(Jobs.queueDir(this)).size))
                journal("queued $count photo(s)")
            }
        }.start()
    }

    private fun addCurrentToQueue() {
        val bitmap = photo ?: return message(s.noPhoto)
        Jobs.save(Jobs.queueDir(this), renderForPrint(bitmap, editState))
        refreshQueue()
        toast(s.queued.format(Jobs.list(Jobs.queueDir(this)).size))
    }

    private fun refreshQueue() {
        val files = Jobs.list(Jobs.queueDir(this))
        queueRow.removeAllViews()
        queueEmptyText.visibility = if (files.isEmpty()) View.VISIBLE else View.GONE
        for (file in files) {
            val thumb = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = ui.rounded(Ui.CARD, 12)
                clipToOutline = true
                setImageBitmap(Jobs.thumbnail(file, 160))
                setOnClickListener {
                    AlertDialog.Builder(this@MainActivity)
                        .setMessage(s.removeFromQueue)
                        .setNegativeButton(s.cancel, null)
                        .setPositiveButton(s.remove) { _, _ -> file.delete(); refreshQueue() }
                        .show()
                }
            }
            queueRow.addView(thumb, LinearLayout.LayoutParams(ui.dp(72), ui.dp(72)).apply { marginEnd = ui.dp(8) })
        }
        printQueueButton.text = s.printQueue.format(files.size)
        updateButtons()
        queueCard.visibility = if (queueEnabled || files.isNotEmpty()) View.VISIBLE else View.GONE
    }

    private fun clearQueue() {
        for (f in Jobs.list(Jobs.queueDir(this))) f.delete()
        refreshQueue()
    }

    private fun printQueue() {
        val info = connected ?: return message(s.notConnectedYet)
        val files = Jobs.list(Jobs.queueDir(this))
        if (files.isEmpty()) return
        val film = info.filmRemaining
        if (film in 1 until files.size) {
            AlertDialog.Builder(this)
                .setMessage(s.queueTooLong.format(files.size, film, film))
                .setNegativeButton(s.cancel, null)
                .setPositiveButton(s.printFirst.format(film)) { _, _ -> startPrinting(files.take(film)) }
                .show()
            return
        }
        startPrinting(files)
    }

    // ---------------------------------------------------------------- Film calibration

    private fun buildCalibration(): View {
        val root = ui.column(20)
        root.addView(ui.text(s.calibrateIntro, 14f))
        ui.space(root, 8)
        monoNoteView = ui.text(s.monoNote, 13f, alpha = 0.7f)
        root.addView(monoNoteView)
        ui.space(root, 12)
        root.addView(ui.text(s.chartSimulated, 13f, alpha = 0.7f))
        ui.space(root, 6)
        chartView = object : ImageView(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val w = MeasureSpec.getSize(widthMeasureSpec)
                setMeasuredDimension(w, w)
            }
        }.apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = ui.rounded(Ui.CARD, 12)
            clipToOutline = true
        }
        root.addView(chartView, LinearLayout.LayoutParams(ui.match, ui.wrap))
        ui.space(root, 10)
        root.addView(ui.softButton(s.printChart) { printChart() }, LinearLayout.LayoutParams(ui.match, ui.wrap))
        ui.space(root, 8)
        root.addView(ui.softButton(s.measureFromPhoto) { openMeasure() }, LinearLayout.LayoutParams(ui.match, ui.wrap))
        ui.space(root, 16)

        fun calSlider(label: String, min: Int, max: Int, read: () -> Int, colourOnly: Boolean = false, write: (Int) -> Unit) {
            val value = ui.text(read().toString(), 13f, alpha = 0.7f).apply { gravity = Gravity.END }
            val header = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(ui.text(label, 13f, alpha = 0.7f), LinearLayout.LayoutParams(0, ui.wrap, 1f))
                addView(value, LinearLayout.LayoutParams(ui.wrap, ui.wrap))
            }
            root.addView(header)
            val bar = SeekBar(this).apply {
                this.max = max - min
                progress = read() - min
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                        value.text = (progress + min).toString()
                        if (fromUser) { write(progress + min); onFilmChanged() }
                    }

                    override fun onStartTrackingTouch(seekBar: SeekBar) {}
                    override fun onStopTrackingTouch(seekBar: SeekBar) {}
                })
            }
            calBars += bar to { read() - min }
            root.addView(bar, LinearLayout.LayoutParams(ui.match, ui.wrap))
            val spacer = View(this)
            root.addView(spacer, ViewGroup.LayoutParams(ui.match, ui.dp(6)))
            if (colourOnly) { colourOnlyCal += header; colourOnlyCal += bar; colourOnlyCal += spacer }
        }
        calSlider(s.calExposure, -100, 100, { Math.round(film.exposure * 100f) }) { film.exposure = it / 100f }
        calSlider(s.calGamma, 30, 200, { Math.round(film.gamma * 100f) }) { film.gamma = it / 100f }
        calSlider(s.calBlack, 0, 30, { Math.round(film.blackClip * 100f) }) { film.blackClip = it / 100f }
        calSlider(s.calWhite, 70, 100, { Math.round(film.whiteClip * 100f) }) { film.whiteClip = it / 100f }
        calSlider(s.calBlackLevel, 0, 40, { Math.round(film.blackLevel * 100f) }) { film.blackLevel = it / 100f }
        calSlider(s.calWhiteLevel, 70, 100, { Math.round(film.whiteLevel * 100f) }) { film.whiteLevel = it / 100f }
        calSlider(s.calShadowWarmth, 0, 100, { Math.round(film.shadowWarmth * 100f) }) { film.shadowWarmth = it / 100f }
        calSlider(s.calSaturation, 40, 150, { Math.round(film.saturation * 100f) }, colourOnly = true) { film.saturation = it / 100f }
        calSlider(s.calWarmth, -60, 60, { Math.round(film.warmth * 100f) }) { film.warmth = it / 100f }
        calSlider(s.calTint, -60, 60, { Math.round(film.tint * 100f) }) { film.tint = it / 100f }
        calSlider(s.calBlue, 0, 100, { Math.round(film.blueDarkening * 100f) }, colourOnly = true) { film.blueDarkening = it / 100f }
        calSlider(s.calPrintSharpness, 0, 100, { Math.round(film.printSharpness) }) { film.printSharpness = it.toFloat() }
        ui.space(root, 8)
        val hueLabel = ui.text(s.calHues, 13f, alpha = 0.7f)
        root.addView(hueLabel)
        hueReadout = ui.text("", 13f)
        root.addView(hueReadout)
        val hueSpace = View(this)
        root.addView(hueSpace, ViewGroup.LayoutParams(ui.match, ui.dp(6)))
        val hueReset = ui.softButton(s.calHuesReset) {
            film.resetHues()
            onFilmChanged()
            toast(s.calHuesCleared)
        }
        root.addView(hueReset, LinearLayout.LayoutParams(ui.match, ui.wrap))
        colourOnlyCal += hueLabel; colourOnlyCal += hueReadout; colourOnlyCal += hueSpace; colourOnlyCal += hueReset
        ui.space(root, 8)
        root.addView(ui.softButton(s.calReset) {
            film = FilmModel.defaults(filmType)
            film.save(prefs)
            syncCalibration()
            onFilmChanged()
        }, LinearLayout.LayoutParams(ui.match, ui.wrap))
        ui.space(root, 16)
        root.addView(ui.text(s.compensateHint, 13f, alpha = 0.7f))
        ui.space(root, 16)
        val header = titleBar(s.calibrateTitle, ui.softButton(s.back) { show(homeView) }.apply { minimumWidth = 0 })
        calibrationTitle = header.getChildAt(0) as TextView
        return fixedHeaderScreen(header, root)
    }

    private val calBars = ArrayList<Pair<SeekBar, () -> Int>>()

    private fun syncCalibration() {
        for ((bar, read) in calBars) bar.progress = read()
        hueReadout.text = describeHues(film)
    }

    private fun onFilmChanged() {
        film.save(prefs)
        applyFilmModel()
        refreshChart()
    }

    private fun refreshChart() {
        // The chart is always printed raw, so the simulation shows it without compensation.
        val base = chartBase ?: TestChart.render(400, filmType).also { chartBase = it }
        syncCalibration()
        chartView.setImageBitmap(film.simulated(base))
    }

    private fun printChart() {
        if (connected == null) return message(s.notConnectedYet)
        val file = Jobs.save(Jobs.queueDir(this), TestChart.render(PREVIEW_SIZE, filmType))
        startPrinting(listOf(file), archive = false)
    }

    // ---------------------------------------------------------------- Chart measurement

    private fun buildMeasure(): View {
        val root = ui.column(20)
        root.addView(ui.text(s.measureIntro, 14f))
        ui.space(root, 12)
        val pick = ui.softButton(s.measurePick) { pickChartPhoto() }
        val shoot = ui.softButton(s.measureShoot) { shootChartPhoto() }
        root.addView(ui.row(pick, shoot), LinearLayout.LayoutParams(ui.match, ui.wrap))
        ui.space(root, 12)
        cornerPick = CornerPickView(this).apply {
            background = ui.rounded(Ui.CARD, 12)
            clipToOutline = true
            onChanged = { syncMeasureButtons() }
        }
        root.addView(cornerPick, LinearLayout.LayoutParams(ui.match, ui.wrap))
        ui.space(root, 8)
        root.addView(ui.text(s.measureHint, 12f, alpha = 0.6f))
        ui.space(root, 12)
        measureResetButton = ui.softButton(s.measureReset) { cornerPick.reset(); hideMeasureResult() }
        measureRunButton = ui.primaryButton(s.measureRun) { runMeasure() }
        root.addView(ui.row(measureResetButton, measureRunButton), LinearLayout.LayoutParams(ui.match, ui.wrap))
        ui.space(root, 16)

        measureResultBox = ui.column().apply { visibility = View.GONE }
        measureReport = ui.text("", 13f, alpha = 0.7f)
        measureResultBox.addView(measureReport)
        ui.space(measureResultBox, 8)
        measureCompare = object : ImageView(this) {
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
        measureColourBox = ui.column()
        ui.space(measureColourBox, 12)
        measureColourReport = ui.text("", 13f, alpha = 0.7f)
        measureColourBox.addView(measureColourReport)
        ui.space(measureColourBox, 8)
        measureColourCompare = object : ImageView(this) {
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
        val header = titleBar(s.measureTitle, ui.softButton(s.back) { show(calibrateView) }.apply { minimumWidth = 0 })
        return fixedHeaderScreen(header, root)
    }

    private fun openMeasure() {
        hideMeasureResult()
        show(measureView)
    }

    private fun syncMeasureButtons() {
        val hasPhoto = cornerPick.bitmap != null
        ui.setEnabledLook(measureResetButton, hasPhoto && cornerPick.corners.isNotEmpty())
        ui.setEnabledLook(measureRunButton, hasPhoto && cornerPick.complete)
        if (!cornerPick.complete) hideMeasureResult()
    }

    private fun hideMeasureResult() {
        measuredFilm = null
        measureResultBox.visibility = View.GONE
    }

    private fun pickChartPhoto() {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Intent(MediaStore.ACTION_PICK_IMAGES)
        else Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*")
        startActivityForResult(intent, REQUEST_PICK_CHART)
    }

    /** Take the photo with the camera app, straight into the gallery (a pending MediaStore entry, no file provider needed). */
    private fun shootChartPhoto() {
        val values = android.content.ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "instax-chart-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = try {
            contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        } catch (e: Exception) { null } ?: return message(s.measureFailed.format("MediaStore"))
        shootUri = uri
        prefs.edit().putString(PREF_SHOOT, uri.toString()).apply()
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            .putExtra(MediaStore.EXTRA_OUTPUT, uri)
            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivityForResult(intent, REQUEST_SHOOT_CHART)
        } catch (e: Exception) {
            shootUri = null
            prefs.edit().remove(PREF_SHOOT).apply()
            contentResolver.delete(uri, null, null)
            message(s.measureFailed.format(e.message ?: e.javaClass.simpleName))
        }
    }

    private fun loadChartPhoto(uri: Uri) {
        setBusy(true)
        Thread {
            val result = runCatching { Photos.scaledForEditing(Photos.load(contentResolver, uri), CHART_PHOTO_SIDE) }
            mainHandler.post {
                setBusy(false)
                result.onSuccess {
                    cornerPick.bitmap = it
                    hideMeasureResult()
                    syncMeasureButtons()
                }.onFailure { message(s.photoError.format(it.message ?: "")) }
            }
        }.start()
    }

    private fun runMeasure() {
        val photo = cornerPick.bitmap ?: return
        val corners = cornerPick.cornersArray() ?: return
        val type = filmType
        setBusy(true)
        Thread {
            val result = runCatching {
                val reading = ChartReader.read(photo, corners, type)
                reading to ChartReader.fit(reading, type)
            }
            mainHandler.post {
                setBusy(false)
                result.onSuccess { (reading, fit) ->
                    val model = fit.model
                    measuredFilm = model
                    measureReport.text = s.measureResult.format("%.1f %%".format(fit.greyRms))
                    measureCompare.setImageBitmap(comparisonStrip(reading, model))
                    val colour = !type.monochrome && reading.patches.isNotEmpty()
                    measureColourBox.visibility = if (colour) View.VISIBLE else View.GONE
                    if (colour) {
                        measureColourReport.text = s.measureColours.format("%.1f %%".format(fit.colourRmsBefore), "%.1f %%".format(fit.colourRmsAfter), describeHues(model))
                        measureColourCompare.setImageBitmap(patchStrip(reading, model))
                    }
                    measureResultBox.visibility = View.VISIBLE
                    journal("chart measured: greys %.1f %%, colours %.1f -> %.1f %%, hues %s, paper white %s".format(
                        fit.greyRms, fit.colourRmsBefore, fit.colourRmsAfter, describeHues(model), reading.paperWhite.joinToString { Math.round(it).toString() }))
                }.onFailure { message(s.measureFailed.format(it.message ?: it.javaClass.simpleName)) }
            }
        }.start()
    }

    /** Two rows of 11 grey steps: measured on the print above, simulated by the fitted model below. */
    private fun comparisonStrip(reading: ChartReader.Reading, model: FilmModel): Bitmap {
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
    private fun patchStrip(reading: ChartReader.Reading, model: FilmModel): Bitmap {
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

    /** The hue tables, one sector per line: hue shift, saturation gain, brightness gain; or "none". */
    private fun describeHues(model: FilmModel): String {
        if (!model.hueCorrected) return s.calHuesNone
        return (0 until FilmModel.HUES).joinToString("\n") { k ->
            "%s  %+.0f°   %s ×%.2f   %s ×%.2f".format(FilmModel.HUE_NAMES[k], model.hueShift[k], s.calHueSat, model.hueGain[k], s.calHueLum, model.hueValue[k])
        }
    }

    private fun applyMeasured() {
        val model = measuredFilm ?: return
        film = model
        film.save(prefs)
        syncCalibration()
        applyFilmModel()
        refreshChart()
        refreshPreview()
        toast(s.measureApplied)
        show(calibrateView)
    }

    // ---------------------------------------------------------------- Collage

    private fun buildCollage(): View {
        val root = ui.column(20)
        collage = CollageView(this).apply {
            onPickCell = { i ->
                pendingCell = i
                val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Intent(MediaStore.ACTION_PICK_IMAGES)
                else Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*")
                startActivityForResult(intent, REQUEST_PICK_CELL)
            }
        }
        root.addView(collage, LinearLayout.LayoutParams(ui.match, ui.wrap))
        ui.space(root, 8)
        root.addView(ui.text(s.collageHint, 12f, alpha = 0.6f).apply { gravity = Gravity.CENTER })
        ui.space(root, 12)
        // Layout pickers: small drawings of each layout.
        val layoutRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val layoutButtons = ArrayList<View>()
        for (layout in CollageLayout.values()) {
            val icon = object : View(this) {
                private val p = Paint(Paint.ANTI_ALIAS_FLAG)
                override fun onDraw(c: Canvas) {
                    val sz = minOf(width, height).toFloat()
                    p.color = if (collage.layout == layout) ui.accent else 0x60808080
                    val g = sz * 0.06f
                    for (cell in layout.cells) c.drawRect(cell[0] * sz + g / 2, cell[1] * sz + g / 2, cell[2] * sz - g / 2, cell[3] * sz - g / 2, p)
                }
            }.apply {
                setOnClickListener { collage.layout = layout; layoutButtons.forEach { it.invalidate() } }
            }
            layoutButtons += icon
            layoutRow.addView(icon, LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)).apply { marginEnd = ui.dp(12) })
        }
        root.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(layoutRow) })
        ui.space(root, 12)
        fun simpleSlider(label: String, max: Int, initial: Int, onValue: (Int) -> Unit) {
            root.addView(ui.text(label, 13f, alpha = 0.7f))
            root.addView(SeekBar(this).apply {
                this.max = max
                progress = initial
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) { if (fromUser) onValue(progress) }
                    override fun onStartTrackingTouch(seekBar: SeekBar) {}
                    override fun onStopTrackingTouch(seekBar: SeekBar) {}
                })
            }, LinearLayout.LayoutParams(ui.match, ui.wrap))
            ui.space(root, 6)
        }
        simpleSlider(s.collageGap, 10, 2) { collage.gap = it / 100f }
        simpleSlider(s.collageRounded, 10, 0) { collage.rounded = it / 100f }
        root.addView(ui.text(s.collageBackground, 13f, alpha = 0.7f))
        ui.space(root, 4)
        val bgRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (color in frameColors) {
            bgRow.addView(View(this).apply {
                background = ui.rounded(color, 18)
                setOnClickListener { collage.background = color }
            }, LinearLayout.LayoutParams(ui.dp(36), ui.dp(36)).apply { marginEnd = ui.dp(10) })
        }
        root.addView(bgRow)
        ui.space(root, 16)
        root.addView(ui.primaryButton(s.collageUse) { useCollage() }, LinearLayout.LayoutParams(ui.match, ui.wrap))
        ui.space(root, 8)
        return fixedHeaderScreen(titleBar(s.collageTitle, ui.softButton(s.back) { show(homeView) }.apply { minimumWidth = 0 }), root)
    }

    /** The collage becomes the current photo, ready for the editor and the printer. */
    private fun useCollage() {
        if (collage.filledCount() == 0) return message(s.collageEmpty)
        val rendered = collage.render(PREVIEW_SIZE * 2)
        photo?.recycle()
        photo = rendered
        editorPhoto = rendered
        photoDate = null
        editState.reset()
        editState.photoDate = null
        undoStack.clear(); redoStack.clear()
        refreshPreview()
        updateButtons()
        message("")
        show(homeView)
    }

    // ---------------------------------------------------------------- History and export

    private fun buildHistory(): View {
        val root = ui.column(20)
        historyList = ui.column()
        root.addView(historyList, LinearLayout.LayoutParams(ui.match, ui.wrap))
        return fixedHeaderScreen(titleBar(s.historyTitle, ui.softButton(s.back) { show(homeView) }.apply { minimumWidth = 0 }), root)
    }

    private fun refreshHistory() {
        historyList.removeAllViews()
        val files = Jobs.list(Jobs.historyDir(this)).reversed()
        if (files.isEmpty()) {
            historyList.addView(ui.text(s.historyEmpty, 14f, alpha = 0.6f))
            return
        }
        val format = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT)
        for (file in files) {
            val thumb = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = ui.rounded(Ui.CARD, 12)
                clipToOutline = true
                setImageBitmap(Jobs.thumbnail(file, 240))
            }
            val info = ui.column().apply {
                addView(ui.text(format.format(Date(Jobs.time(file))), 13f, bold = true))
                addView(ui.row(
                    ui.softButton(s.reprint) { if (connected == null) message(s.notConnectedYet) else startPrinting(listOf(file), archive = false) },
                    ui.softButton(s.share) { Jobs.load(file)?.let { exportAndShare(it) } },
                    gap = 6,
                ), LinearLayout.LayoutParams(ui.match, ui.wrap).apply { topMargin = ui.dp(6) })
                addView(ui.softButton(s.remove) {
                    AlertDialog.Builder(this@MainActivity).setMessage(s.deleteEntry).setNegativeButton(s.cancel, null)
                        .setPositiveButton(s.remove) { _, _ -> file.delete(); refreshHistory() }.show()
                }, LinearLayout.LayoutParams(ui.match, ui.wrap).apply { topMargin = ui.dp(6) })
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(thumb, LinearLayout.LayoutParams(ui.dp(96), ui.dp(96)).apply { marginEnd = ui.dp(12) })
                addView(info, LinearLayout.LayoutParams(0, ui.wrap, 1f))
            }
            historyList.addView(ui.card(row, padding = 12), LinearLayout.LayoutParams(ui.match, ui.wrap).apply { bottomMargin = ui.dp(10) })
        }
    }

    private fun exportCurrent() {
        val bitmap = photo ?: return message(s.noPhoto)
        exportAndShare(PrintRenderer.render(bitmap, editState, PREVIEW_SIZE))
    }

    private fun exportAndShare(rendered: Bitmap) {
        val uri = Jobs.exportToGallery(this, rendered)
        if (uri == null) return message(s.exportFailed)
        toast(s.exported)
        startActivity(Intent.createChooser(
            Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            s.share,
        ))
    }

    private fun handleShareIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND_MULTIPLE) {
            val uris: List<Uri> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java) ?: emptyList()
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM) ?: emptyList()
            }
            if (uris.isNotEmpty()) {
                // The queue is the natural home for several photos; show it even if it is hidden.
                queueCard.visibility = View.VISIBLE
                queueMany(uris)
                toast(s.sharedMany.format(uris.size))
            }
            return
        }
        if (intent?.action != Intent.ACTION_SEND) return
        val uri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
        if (uri != null) {
            loadPhoto(uri)
            toast(s.shared)
        }
    }

    private fun loadPhoto(uri: Uri) {
        Thread {
            val date = Photos.exifDate(contentResolver, uri)
            val result = runCatching { Photos.load(contentResolver, uri) }
            mainHandler.post {
                photoDate = date
                editState.photoDate = date
                result.onSuccess {
                    photo?.recycle()
                    editorPhoto?.recycle()
                    photo = it
                    editorPhoto = Photos.scaledForEditing(it, EDITOR_SOURCE_SIDE)
                    editState.reset()
                    undoStack.clear(); redoStack.clear()
                    refreshPreview()
                    updateButtons()
                }.onFailure { message(s.photoError.format(it.message ?: it.javaClass.simpleName)) }
            }
        }.start()
    }

    private fun refreshPreview() {
        val bitmap = photo ?: return
        previewImage.bitmap = PrintRenderer.render(bitmap, editState, PREVIEW_SIZE)
        previewHint.visibility = View.GONE
    }

    private fun setFilmPreview(on: Boolean) {
        prefs.edit().putBoolean(PREF_FILM, on).apply()
        previewImage.filmPreview = on
        editorView.filmPreview = on
        filmButton.setTextColor(if (on) ui.accent else labelColor)
    }

    // ---------------------------------------------------------------- Editor screen

    private fun buildEditor(): View {
        // Fixed header and photo on top; everything else scrolls underneath, so the photo stays in
        // view while a slider is dragged. The histogram floats over the photo's bottom-left corner.
        val screen = ui.column()
        val root = ui.column(16)

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.dp(16), ui.dp(10), ui.dp(16), ui.dp(8))
            addView(ui.softButton(s.cancel) { show(homeView) }, LinearLayout.LayoutParams(ui.wrap, ui.wrap))
            undoButton = ui.softButton("↶") { undo() }.apply { tooltipText = s.undo; setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f) }
            redoButton = ui.softButton("↷") { redo() }.apply { tooltipText = s.redo; setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f) }
            val middle = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                addView(undoButton, LinearLayout.LayoutParams(ui.dp(56), ui.dp(44)))
                addView(redoButton, LinearLayout.LayoutParams(ui.dp(56), ui.dp(44)).apply { marginStart = ui.dp(8) })
            }
            addView(middle, LinearLayout.LayoutParams(0, ui.wrap, 1f))
            addView(ui.primaryButton(s.ok) { applyEdits() }.apply { minimumHeight = ui.dp(44); setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f) }, LinearLayout.LayoutParams(ui.dp(80), ui.wrap))
        }
        screen.addView(header, LinearLayout.LayoutParams(ui.match, ui.wrap))

        // The photo area is a centred square (at most 40 % of the screen height), so an overlay
        // anchored to its corner is always inside the photo.
        val metrics = resources.displayMetrics
        val photoSide = minOf(metrics.widthPixels, (metrics.heightPixels * 0.40f).toInt())
        editorView = PhotoEditorView(this)
        editorView.onBeforeChange = { recordUndo() }
        histogramView = HistogramView(this).apply {
            visibility = View.GONE
            background = ui.rounded(0x66000000, 8)
            alpha = 0.9f
        }
        editorView.onFrame = { pixels, _ -> histogramView.update(Processing.histogram(pixels, stride = 1)) }
        val photoArea = FrameLayout(this).apply {
            addView(editorView, FrameLayout.LayoutParams(photoSide, photoSide))
            addView(histogramView, FrameLayout.LayoutParams(ui.dp(150), ui.dp(56), Gravity.BOTTOM or Gravity.START).apply {
                marginStart = ui.dp(8); bottomMargin = ui.dp(8)
            })
        }
        val photoWrapper = FrameLayout(this).apply {
            addView(photoArea, FrameLayout.LayoutParams(photoSide, photoSide, Gravity.CENTER_HORIZONTAL))
        }
        screen.addView(photoWrapper, LinearLayout.LayoutParams(ui.match, photoSide))
        // A hairline under the fixed photo marks where the scrolling part begins.
        screen.addView(View(this).apply { setBackgroundColor(Ui.CARD_STRONG) }, LinearLayout.LayoutParams(ui.match, ui.dp(1)).apply { topMargin = ui.dp(10) })

        ui.space(root, 10)

        // Tools. Compare and film preview apply to both tabs; histogram and clipping live in the
        // adjust tab only. Long-pressing a tool shows what it does.
        histogramButton = ui.softButton(s.histogram) { toggleHistogram() }.apply { tooltipText = s.histogramTip }
        clippingButton = ui.softButton(s.clipping) { toggleClipping() }.apply { tooltipText = s.clippingHint }
        val compareButton = ui.softButton(s.compare) {}.apply {
            tooltipText = s.compareTip
            setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> { editorView.showOriginal = true; v.isPressed = true }
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> { editorView.showOriginal = false; v.isPressed = false }
                }
                false // let the button handle the long press (tooltip) too
            }
        }
        filmButton = ui.softButton(s.filmPreview) { setFilmPreview(!editorView.filmPreview) }.apply { tooltipText = s.filmPreviewHint }
        val compareRow = ui.row(compareButton, filmButton, gap = 6)

        tabs = ui.Segmented(listOf(s.tabAdjust, s.tabTexts)) { index -> showTab(adjust = index == 0) }
        root.addView(tabs.view, LinearLayout.LayoutParams(ui.match, ui.wrap))
        ui.space(root, 14)

        val adjust = ui.column()
        adjustPanel = adjust
        root.addView(adjust, LinearLayout.LayoutParams(ui.match, ui.wrap))
        textsPanel = buildTextsPanel()
        textsPanel.visibility = View.GONE
        root.addView(textsPanel, LinearLayout.LayoutParams(ui.match, ui.wrap))

        adjust.addView(compareRow)
        ui.space(adjust, 6)
        adjust.addView(ui.row(histogramButton, clippingButton, gap = 6))
        ui.space(adjust, 14)
        adjust.addView(ui.row(
            ui.softButton(s.rotate) { editorView.update { rotation = (Math.round(rotation / 90f) + 1) * 90f % 360f } },
            ui.softButton(s.fill) { editorView.update { scale = 1f; offsetX = 0f; offsetY = 0f } },
            ui.softButton(s.whole) { editorView.update { scale = PrintRenderer.containScale(editorView.bitmap!!, this); offsetX = 0f; offsetY = 0f } },
            ui.softButton(s.blackWhite) { editorView.update { saturation = if (saturation == 0f) 1f else 0f }; syncSliders() }.also { blackWhiteButton = it; colourOnlyViews += it },
            ui.softButton(s.reset) { editorView.update { reset() }; syncSliders(); syncPresets(); syncFrameColors() },
            gap = 6,
        ))
        ui.space(adjust, 16)

        adjust.addView(ui.text(s.filters, 13f, alpha = 0.7f))
        ui.space(adjust, 6)
        presetRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (preset in Filters.presets) {
            val thumb = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = ui.rounded(Ui.CARD, 14)
                clipToOutline = true
            }
            val label = ui.text(s.filterName(preset.id), 12f).apply { gravity = Gravity.CENTER }
            labelColor = label.currentTextColor
            val tile = ui.column().apply {
                gravity = Gravity.CENTER_HORIZONTAL
                addView(thumb, LinearLayout.LayoutParams(ui.dp(68), ui.dp(68)))
                addView(label, LinearLayout.LayoutParams(ui.dp(68), ui.wrap).apply { topMargin = ui.dp(4) })
                setOnClickListener { editorView.update { filter = preset.id }; syncPresets() }
            }
            presetTiles[preset.id] = thumb to label
            if (preset.id in setOf("vivid", "warm", "cool", "sepia", "mono", "retro")) colourOnlyViews += tile
            presetRow.addView(tile, LinearLayout.LayoutParams(ui.wrap, ui.wrap).apply { marginEnd = ui.dp(10) })
        }
        adjust.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(presetRow) })
        ui.space(adjust, 16)

        adjust.addView(ui.text(s.myPresets, 14f, bold = true))
        ui.space(adjust, 6)
        presetRow2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        adjust.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(presetRow2) })
        adjust.addView(ui.text(s.presetHint, 12f, alpha = 0.6f))
        ui.space(adjust, 12)

        val lightHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(ui.text(s.light, 14f, bold = true), LinearLayout.LayoutParams(0, ui.wrap, 1f))
            addView(ui.softButton(s.auto) { autoLevels() }, LinearLayout.LayoutParams(ui.wrap, ui.wrap))
        }
        adjust.addView(lightHeader)
        ui.space(adjust, 6)
        slider(adjust, s.brightness, -100, 100, { it.brightness.toInt() }) { v -> editorView.update { brightness = v.toFloat() } }
        slider(adjust, s.contrast, -100, 100, { it.contrast.toInt() }) { v -> editorView.update { contrast = v.toFloat() } }
        slider(adjust, s.highlights, -100, 100, { it.highlights.toInt() }) { v -> editorView.update { highlights = v.toFloat() } }
        slider(adjust, s.shadows, -100, 100, { it.shadows.toInt() }) { v -> editorView.update { shadows = v.toFloat() } }
        slider(adjust, s.whites, -100, 100, { it.whites.toInt() }) { v -> editorView.update { whites = v.toFloat() } }
        slider(adjust, s.blacks, -100, 100, { it.blacks.toInt() }) { v -> editorView.update { blacks = v.toFloat() } }
        ui.space(adjust, 8)

        val colourSection = ui.column()
        colourSection.addView(ui.text(s.colour, 14f, bold = true))
        ui.space(colourSection, 6)
        slider(colourSection, s.temperature, -100, 100, { it.temperature.toInt() }) { v -> editorView.update { temperature = v.toFloat() } }
        slider(colourSection, s.tint, -100, 100, { it.tint.toInt() }) { v -> editorView.update { tint = v.toFloat() } }
        slider(colourSection, s.saturation, 0, 200, { (it.saturation * 100f).toInt() }, default = 100) { v -> editorView.update { saturation = v / 100f }; syncSliders() }
        ui.space(colourSection, 8)
        adjust.addView(colourSection, LinearLayout.LayoutParams(ui.match, ui.wrap))
        colourOnlyViews += colourSection

        adjust.addView(ui.text(s.details, 14f, bold = true))
        ui.space(adjust, 6)
        slider(adjust, s.sharpness, 0, 100, { it.sharpness.toInt() }) { v -> editorView.update { sharpness = v.toFloat() } }
        slider(adjust, s.vignette, 0, 100, { it.vignette.toInt() }) { v -> editorView.update { vignette = v.toFloat() } }
        ui.space(adjust, 8)

        adjust.addView(ui.text(s.frame, 14f, bold = true))
        ui.space(adjust, 6)
        adjust.addView(ui.text(s.ratio, 13f, alpha = 0.7f))
        ui.space(adjust, 4)
        // Width : height of the photo area. Wider than tall only: the square cannot hold a taller area, the band fills the rest.
        val ratios = listOf("1:1" to 1f, "5:4" to 5f / 4f, "4:3" to 4f / 3f, "3:2" to 3f / 2f, "16:9" to 16f / 9f)
        adjust.addView(ui.row(*ratios.map { (label, r) -> ui.softButton(label) { setPhotoRatio(r) } }.toTypedArray(), gap = 6))
        ui.space(adjust, 8)
        slider(adjust, s.frameWidth, 0, 15, { Math.round(it.frameWidth * 100f) }) { v -> editorView.update { frameWidth = v / 100f } }
        slider(adjust, s.frameBand, 0, 30, { Math.round(it.frameBand * 100f) }) { v -> editorView.update { frameBand = v / 100f } }
        slider(adjust, s.cornerRadius, 0, 20, { Math.round(it.cornerRadius * 100f) }) { v -> editorView.update { cornerRadius = v / 100f } }
        adjust.addView(ui.text(s.frameColor, 13f, alpha = 0.7f))
        ui.space(adjust, 4)
        val colorRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (color in frameColors) {
            val chip = View(this).apply {
                background = ui.rounded(color, 18)
                setOnClickListener { editorView.update { frameColor = color }; syncFrameColors() }
            }
            frameColorButtons += chip
            // On monochrome film only the neutral frames make sense.
            val neutral = Color.red(color) == Color.green(color) && Color.green(color) == Color.blue(color)
            if (!neutral) colourOnlyViews += chip
            colorRow.addView(chip, LinearLayout.LayoutParams(ui.dp(36), ui.dp(36)).apply { marginEnd = ui.dp(10) })
        }
        adjust.addView(colorRow)
        ui.space(adjust, 24)

        screen.addView(ScrollView(this).apply { addView(root) }, LinearLayout.LayoutParams(ui.match, 0, 1f))
        return screen
    }

    private fun showTab(adjust: Boolean) {
        adjustPanel.visibility = if (adjust) View.VISIBLE else View.GONE
        textsPanel.visibility = if (adjust) View.GONE else View.VISIBLE
        tabs.select(if (adjust) 0 else 1)
        editorView.overlayMode = !adjust
        if (!adjust) {
            if (showHistogram) toggleHistogram()
            if (editorView.showClipping) toggleClipping()
            editorView.selectedOverlay = editorView.selectedOverlay ?: editorView.state.overlays.lastOrNull()
        }
        refreshOverlayPanel()
    }

    private fun buildTextsPanel(): LinearLayout {
        val panel = ui.column()
        panel.addView(ui.row(
            ui.softButton(s.addText) { addOverlay(Overlay(Overlay.Kind.TEXT, text = "instax")) }.apply { tooltipText = s.textsHint },
            ui.softButton(s.addDate) { addOverlay(Overlay(Overlay.Kind.DATE)) }.apply { tooltipText = s.textsHint },
            gap = 6,
        ))
        ui.space(panel, 6)
        panel.addView(ui.row(
            ui.softButton(s.addTime) { addOverlay(Overlay(Overlay.Kind.TIME)) }.apply { tooltipText = s.textsHint },
            ui.softButton(s.addDateTime) { addOverlay(Overlay(Overlay.Kind.DATETIME, size = 0.035f)) }.apply { tooltipText = s.textsHint },
            gap = 6,
        ))
        ui.space(panel, 6)
        panel.addView(ui.softButton(s.addEmoji) { pickEmoji() }.apply { tooltipText = s.textsHint }, LinearLayout.LayoutParams(ui.match, ui.wrap))
        ui.space(panel, 12)
        overlayList = ui.column()
        panel.addView(overlayList, LinearLayout.LayoutParams(ui.match, ui.wrap))
        ui.space(panel, 8)

        overlayEditor = ui.column()
        overlayEditor.addView(ui.text(s.overlayText, 13f, alpha = 0.7f).also { textOnlyViews += it })
        overlayTextField = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(text: android.text.Editable?) {
                    val o = editorView.selectedOverlay ?: return
                    if (o.kind == Overlay.Kind.TEXT && o.text != text.toString()) {
                        editorView.overlaysEdit { o.text = text.toString() }
                        refreshOverlayList()
                    }
                }
            })
        }
        overlayEditor.addView(overlayTextField)
        ui.space(overlayEditor, 8)
        val sizeHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(ui.text(s.overlaySize, 13f, alpha = 0.7f), LinearLayout.LayoutParams(0, ui.wrap, 1f))
            overlaySizeValue = ui.text("", 13f, bold = true, color = ui.accent).apply { setOnClickListener { askOverlaySize() } }
            addView(overlaySizeValue, LinearLayout.LayoutParams(ui.wrap, ui.wrap))
        }
        overlayEditor.addView(sizeHeader)
        overlaySizeBar = SeekBar(this).apply {
            max = 235
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    overlaySizeValue.text = "${Math.round((progress + 15) / 1000f * PREVIEW_SIZE)} px"
                    if (!fromUser) return
                    val o = editorView.selectedOverlay ?: return
                    editorView.overlaysEdit {
                        o.size = (progress + 15) / 1000f
                        o.anchor?.let { o.place(it, PrintRenderer.overlayWidth(o, editorView.state.photoDate)) }
                    }
                }

                override fun onStartTrackingTouch(seekBar: SeekBar) {}
                override fun onStopTrackingTouch(seekBar: SeekBar) {}
            })
        }
        overlayEditor.addView(overlaySizeBar, LinearLayout.LayoutParams(ui.match, ui.wrap))
        overlayDarkSwitch = Switch(this).apply {
            text = s.overlayDark
            setOnCheckedChangeListener { _, checked ->
                val o = editorView.selectedOverlay ?: return@setOnCheckedChangeListener
                if (o.dark != checked) editorView.overlaysEdit { o.dark = checked }
            }
        }
        overlayEditor.addView(overlayDarkSwitch)
        textOnlyViews += overlayDarkSwitch
        ui.space(overlayEditor, 8)
        overlayEditor.addView(ui.text(s.overlayFont, 13f, alpha = 0.7f).also { textOnlyViews += it })
        ui.space(overlayEditor, 4)
        val fontRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (font in Overlay.Font.values()) {
            val b = ui.softButton(s.fontName(font)) { setOverlayFont(font) }.apply { typeface = font.typeface }
            fontButtons[font] = b
            fontRow.addView(b, LinearLayout.LayoutParams(ui.wrap, ui.wrap).apply { marginEnd = ui.dp(6) })
        }
        overlayEditor.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(fontRow); textOnlyViews += this })
        ui.space(overlayEditor, 8)
        overlayEditor.addView(ui.text(s.overlayPosition, 13f, alpha = 0.7f))
        ui.space(overlayEditor, 4)
        val arrows = listOf("↖", "↑", "↗", "←", "·", "→", "↙", "↓", "↘")
        val anchors = Overlay.Anchor.values()
        for (rowIndex in 0..2) {
            val cells = (0..2).map { col ->
                val anchor = anchors[rowIndex * 3 + col]
                ui.softButton(arrows[rowIndex * 3 + col]) { setOverlayAnchor(anchor) }
            }
            overlayEditor.addView(ui.row(cells[0], cells[1], cells[2], gap = 6), LinearLayout.LayoutParams(ui.match, ui.wrap).apply { bottomMargin = ui.dp(6) })
        }
        val marginHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(ui.text(s.overlayMargin, 13f, alpha = 0.7f), LinearLayout.LayoutParams(0, ui.wrap, 1f))
            overlayMarginValue = ui.text("", 13f, alpha = 0.7f)
            addView(overlayMarginValue, LinearLayout.LayoutParams(ui.wrap, ui.wrap))
        }
        overlayEditor.addView(marginHeader)
        overlayMarginBar = SeekBar(this).apply {
            max = 25
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    overlayMarginValue.text = "$progress %"
                    if (!fromUser) return
                    val o = editorView.selectedOverlay ?: return
                    editorView.overlaysEdit {
                        o.margin = progress / 100f
                        o.anchor?.let { o.place(it, PrintRenderer.overlayWidth(o, editorView.state.photoDate)) }
                    }
                }

                override fun onStartTrackingTouch(seekBar: SeekBar) {}
                override fun onStopTrackingTouch(seekBar: SeekBar) {}
            })
        }
        overlayEditor.addView(overlayMarginBar, LinearLayout.LayoutParams(ui.match, ui.wrap))
        ui.space(overlayEditor, 6)
        val rotationHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(ui.text(s.overlayRotation, 13f, alpha = 0.7f), LinearLayout.LayoutParams(0, ui.wrap, 1f))
            overlayRotationValue = ui.text("0°", 13f, alpha = 0.7f)
            addView(overlayRotationValue, LinearLayout.LayoutParams(ui.wrap, ui.wrap))
            setOnClickListener { editorView.selectedOverlay?.let { o -> editorView.overlaysEdit { o.rotation = 0f }; refreshOverlayPanel() } }
        }
        overlayEditor.addView(rotationHeader)
        overlayRotationBar = SeekBar(this).apply {
            max = 360
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    overlayRotationValue.text = "${(progress + 180) % 360}°"
                    if (!fromUser) return
                    val o = editorView.selectedOverlay ?: return
                    o.rotation = ((progress - 180).toFloat() % 360f + 360f) % 360f
                    editorView.overlaysChanged()
                }

                override fun onStartTrackingTouch(seekBar: SeekBar) = editorView.beginInteraction()
                override fun onStopTrackingTouch(seekBar: SeekBar) = editorView.endInteraction()
            })
        }
        overlayEditor.addView(overlayRotationBar, LinearLayout.LayoutParams(ui.match, ui.wrap))
        ui.space(overlayEditor, 6)
        overlayEditor.addView(ui.row(
            ui.softButton(s.styleShadow) { setOverlayStyle(Overlay.Style.SHADOW) },
            ui.softButton(s.styleOutline) { setOverlayStyle(Overlay.Style.OUTLINE) },
            ui.softButton(s.stylePill) { setOverlayStyle(Overlay.Style.PILL) },
            gap = 6,
        ).also { textOnlyViews += it })
        ui.space(overlayEditor, 6)
        overlayEditor.addView(ui.softButton(s.remove) { removeSelectedOverlay() }, LinearLayout.LayoutParams(ui.match, ui.wrap))
        panel.addView(overlayEditor, LinearLayout.LayoutParams(ui.match, ui.wrap))

        editorView.onOverlaySelected = { refreshOverlayPanel() }
        return panel
    }

    private val emojis = listOf(
        "😀", "😂", "😍", "🥰", "😎", "🤩", "😜", "🥳", "😭", "🤔", "😴", "🤯",
        "❤️", "🧡", "💛", "💚", "💙", "💜", "🖤", "💖", "✨", "⭐", "🔥", "💯",
        "🎉", "🎂", "🎁", "🎈", "🎄", "🎃", "👻", "🌈", "☀️", "🌙", "🌸", "🌻",
        "🐱", "🐶", "🦊", "🐼", "🦄", "🍕", "🍰", "☕", "🍺", "🥂", "✈️", "📷",
        "👍", "👏", "🙌", "🤘", "✌️", "💪", "🫶", "👑", "💀", "🫧", "🎵", "🏆",
    )

    private fun pickEmoji() {
        val grid = android.widget.GridLayout(this).apply { columnCount = 6; setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12)) }
        lateinit var dialog: AlertDialog
        for (e in emojis) {
            grid.addView(TextView(this).apply {
                text = e
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
                gravity = Gravity.CENTER
                setPadding(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(8))
                setOnClickListener { dialog.dismiss(); addOverlay(Overlay(Overlay.Kind.EMOJI, text = e, size = 0.12f, style = Overlay.Style.SHADOW)) }
            })
        }
        dialog = AlertDialog.Builder(this).setTitle(s.pickEmoji).setView(ScrollView(this).apply { addView(grid) }).setNegativeButton(s.cancel, null).create()
        dialog.show()
    }

    private fun addOverlay(overlay: Overlay) {
        // Stack new texts upwards so they do not land on top of each other.
        overlay.y = (0.92f - 0.07f * editorView.state.overlays.size).coerceAtLeast(0.1f)
        editorView.overlaysEdit {
            editorView.state.overlays += overlay
            editorView.selectedOverlay = overlay
        }
        refreshOverlayPanel()
    }

    /** Type the text height in print pixels directly. */
    private fun askOverlaySize() {
        val o = editorView.selectedOverlay ?: return
        val field = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(Math.round(o.size * PREVIEW_SIZE).toString())
        }
        AlertDialog.Builder(this)
            .setTitle(s.overlaySizePrompt)
            .setView(field)
            .setNegativeButton(s.cancel, null)
            .setPositiveButton(s.ok) { _, _ ->
                val px = field.text.toString().trim().toIntOrNull()?.coerceIn(12, 200) ?: return@setPositiveButton
                editorView.overlaysEdit {
                    o.size = px / PREVIEW_SIZE.toFloat()
                    o.anchor?.let { o.place(it, PrintRenderer.overlayWidth(o, editorView.state.photoDate)) }
                }
                refreshOverlayPanel()
            }
            .show()
    }

    private fun setOverlayFont(font: Overlay.Font) {
        val o = editorView.selectedOverlay ?: return
        editorView.overlaysEdit {
            o.font = font
            o.anchor?.let { o.place(it, PrintRenderer.overlayWidth(o, editorView.state.photoDate)) }
        }
        syncFontButtons()
    }

    private fun syncFontButtons() {
        val current = editorView.selectedOverlay?.font
        for ((font, button) in fontButtons) button.setTextColor(if (font == current) ui.accent else labelColor)
    }

    private fun setOverlayAnchor(anchor: Overlay.Anchor) {
        val o = editorView.selectedOverlay ?: return
        editorView.overlaysEdit { o.place(anchor, PrintRenderer.overlayWidth(o, editorView.state.photoDate)) }
    }

    private fun setOverlayStyle(style: Overlay.Style) {
        editorView.overlaysEdit { editorView.selectedOverlay?.style = style }
    }

    private fun removeSelectedOverlay() {
        val o = editorView.selectedOverlay ?: return
        editorView.overlaysEdit {
            editorView.state.overlays.remove(o)
            editorView.selectedOverlay = editorView.state.overlays.lastOrNull()
        }
        refreshOverlayPanel()
    }

    private fun refreshOverlayList() {
        overlayList.removeAllViews()
        val overlays = editorView.state.overlays
        if (overlays.isEmpty()) {
            overlayList.addView(ui.text(s.noOverlay, 13f, alpha = 0.6f))
            return
        }
        for (o in overlays) {
            val selected = o === editorView.selectedOverlay
            val label = o.resolve(editorView.state.photoDate).ifBlank { "…" }
            val rowButton = ui.softButton(label) {
                editorView.selectedOverlay = o
                refreshOverlayPanel()
            }.apply {
                setTextColor(if (selected) ui.accent else labelColor)
                typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            }
            overlayList.addView(rowButton, LinearLayout.LayoutParams(ui.match, ui.wrap).apply { bottomMargin = ui.dp(6) })
        }
    }

    private fun refreshOverlayPanel() {
        refreshOverlayList()
        val o = editorView.selectedOverlay
        overlayEditor.visibility = if (o == null) View.GONE else View.VISIBLE
        if (o == null) return
        overlayTextField.visibility = if (o.kind == Overlay.Kind.TEXT) View.VISIBLE else View.GONE
        if (o.kind != Overlay.Kind.TEXT) textOnlyViews.firstOrNull()?.visibility = View.GONE
        if (o.kind == Overlay.Kind.TEXT && overlayTextField.text.toString() != o.text) overlayTextField.setText(o.text)
        overlaySizeBar.progress = (o.size * 1000f).toInt() - 15
        overlaySizeValue.text = "${Math.round(o.size * PREVIEW_SIZE)} px"
        overlayDarkSwitch.isChecked = o.dark
        overlayMarginBar.progress = Math.round(o.margin * 100f)
        overlayMarginValue.text = "${overlayMarginBar.progress} %"
        overlayRotationBar.progress = (Math.round(o.rotation) + 180) % 360
        overlayRotationValue.text = "${Math.round(o.rotation)}°"
        for (v in textOnlyViews) v.visibility = if (o.kind == Overlay.Kind.EMOJI) View.GONE else View.VISIBLE
        syncFontButtons()
    }

    /**
     * A labelled slider over [min]..[max] whose current value is read from the editor state by
     * [read]. Tapping the label puts it back to [default].
     */
    private fun slider(parent: LinearLayout, label: String, min: Int, max: Int, read: (EditState) -> Int, default: Int = 0, onValue: (Int) -> Unit) {
        val title = ui.text(label, 13f, alpha = 0.7f)
        val value = ui.text(default.toString(), 13f, alpha = 0.7f).apply { gravity = Gravity.END }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(title, LinearLayout.LayoutParams(0, ui.wrap, 1f))
            addView(value, LinearLayout.LayoutParams(ui.wrap, ui.wrap))
        }
        parent.addView(header)
        val bar = SeekBar(this).apply {
            this.max = max - min
            progress = default - min
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    value.text = (progress + min).toString()
                    if (fromUser) onValue(progress + min)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar) = editorView.beginInteraction()
                override fun onStopTrackingTouch(seekBar: SeekBar) = editorView.endInteraction()
            })
        }
        header.setOnClickListener {
            onValue(default)
            bar.progress = default - min
            value.text = default.toString()
        }
        parent.addView(bar, LinearLayout.LayoutParams(ui.match, ui.wrap))
        ui.space(parent, 6)
        sliders[label] = SliderEntry(bar, value, min, read)
    }

    /** Give the photo area a width:height ratio by sizing the bottom band, keeping the border. */
    private fun setPhotoRatio(ratio: Float) {
        editorView.update {
            val width = 1f - 2f * frameWidth
            val height = (width / ratio).coerceAtMost(width)
            frameBand = (1f - 2f * frameWidth - height).coerceIn(0f, 0.6f)
            scale = 1f; offsetX = 0f; offsetY = 0f
        }
        syncSliders()
    }

    private fun syncFrameColors() {
        val current = editorView.state.frameColor
        frameColors.forEachIndexed { i, color ->
            val chip = frameColorButtons[i]
            chip.alpha = if (color == current) 1f else 0.45f
            chip.scaleX = if (color == current) 1.15f else 1f
            chip.scaleY = chip.scaleX
        }
    }

    private fun autoLevels() {
        val bitmap = editorView.bitmap ?: return
        // Measure the photo with its current framing and filter but neutral tones.
        val probe = EditState().apply { copyFrom(editorView.state); brightness = 0f; contrast = 0f; shadows = 0f; highlights = 0f; blacks = 0f; whites = 0f; overlays.clear() }
        val sample = PrintRenderer.render(bitmap, probe, 256, sharpen = false)
        val pixels = IntArray(256 * 256)
        sample.getPixels(pixels, 0, 256, 0, 0, 256, 256)
        // With the film preview on (and no compensation, which already maps the full range onto
        // the film), aim at the film's usable input range instead of the full scale.
        val forFilm = editorView.filmPreview && !compensate
        val levels = if (forFilm) Processing.autoLevels(pixels, film.blackClip, film.whiteClip) else Processing.autoLevels(pixels)
        if (levels == null) {
            toast(s.autoNothing)
            return
        }
        editorView.update { brightness = levels.first; contrast = levels.second }
        syncSliders()
        toast(if (forFilm) s.autoDoneFilm else s.autoDone)
    }

    private fun refreshPresetRow() {
        presetRow2.removeAllViews()
        val save = ui.softButton(s.savePreset) { askPresetName() }
        presetRow2.addView(save, LinearLayout.LayoutParams(ui.wrap, ui.wrap).apply { marginEnd = ui.dp(6) })
        for (entry in presets.load()) {
            val chip = ui.softButton(entry.name) {
                editorView.update { copyLookFrom(entry.look) }
                syncSliders(); syncPresets(); syncFrameColors()
            }
            chip.setOnLongClickListener {
                AlertDialog.Builder(this)
                    .setMessage(s.presetDelete.format(entry.name))
                    .setNegativeButton(s.cancel, null)
                    .setPositiveButton(s.remove) { _, _ -> presets.remove(entry.name); refreshPresetRow() }
                    .show()
                true
            }
            presetRow2.addView(chip, LinearLayout.LayoutParams(ui.wrap, ui.wrap).apply { marginEnd = ui.dp(6) })
        }
    }

    private fun askPresetName() {
        val field = EditText(this).apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES; hint = s.presetName }
        AlertDialog.Builder(this)
            .setTitle(s.savePreset)
            .setView(field)
            .setNegativeButton(s.cancel, null)
            .setPositiveButton(s.ok) { _, _ ->
                val name = field.text.toString().trim().ifEmpty { return@setPositiveButton }
                presets.add(name, editorView.state)
                refreshPresetRow()
                toast(s.presetSaved)
            }
            .show()
    }

    private fun syncSliders() {
        for (entry in sliders.values) {
            val v = entry.read(editorView.state)
            entry.bar.progress = v - entry.min
            entry.value.text = v.toString()
        }
        val mono = editorView.state.saturation == 0f
        blackWhiteButton.setTextColor(if (mono) ui.accent else labelColor)
        blackWhiteButton.typeface = if (mono) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    private fun toggleHistogram() {
        showHistogram = !showHistogram
        histogramView.visibility = if (showHistogram) View.VISIBLE else View.GONE
        histogramButton.setTextColor(if (showHistogram) ui.accent else labelColor)
        editorView.wantHistogram = showHistogram
    }

    private fun toggleClipping() {
        editorView.showClipping = !editorView.showClipping
        clippingButton.setTextColor(if (editorView.showClipping) ui.accent else labelColor)
    }

    private fun syncPresets() {
        val selected = editorView.state.filter
        for ((id, views) in presetTiles) {
            val (thumb, label) = views
            thumb.alpha = if (id == selected) 1f else 0.55f
            label.typeface = if (id == selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            label.setTextColor(if (id == selected) ui.accent else labelColor)
        }
    }

    /** Small filtered previews of the current photo for the preset row. */
    private fun buildThumbnails(bitmap: Bitmap) {
        val base = PrintRenderer.render(bitmap, EditState(), THUMB_SIZE)
        for ((id, views) in presetTiles) views.first.setImageBitmap(Filters.thumbnail(base, Filters.byId(id)))
    }

    private fun openEditor() {
        val bitmap = editorPhoto ?: photo ?: return pickPhoto()
        editorView.bitmap = bitmap
        editorView.state.copyFrom(editState)
        editorView.state.photoDate = photoDate
        editorView.selectedOverlay = null
        // The undo history survives leaving the editor; it is only reset for a new photo.
        syncUndoButtons()
        buildThumbnails(bitmap)
        syncSliders()
        syncPresets()
        syncFrameColors()
        refreshPresetRow()
        editorView.filmPreview = prefs.getBoolean(PREF_FILM, false)
        filmButton.setTextColor(if (editorView.filmPreview) ui.accent else labelColor)
        showTab(adjust = true)
        show(editorScreen)
    }

    private fun recordUndo() {
        val now = System.currentTimeMillis()
        if (now - lastSnapshotAt < UNDO_MERGE_MS && undoStack.isNotEmpty()) return
        lastSnapshotAt = now
        undoStack.addLast(EditState().apply { copyFrom(editorView.state) })
        while (undoStack.size > UNDO_DEPTH) undoStack.removeFirst()
        redoStack.clear()
        syncUndoButtons()
    }

    private fun undo() {
        val previous = undoStack.pollLast() ?: return
        redoStack.addLast(EditState().apply { copyFrom(editorView.state) })
        restoreEditorState(previous)
    }

    private fun redo() {
        val next = redoStack.pollLast() ?: return
        undoStack.addLast(EditState().apply { copyFrom(editorView.state) })
        restoreEditorState(next)
    }

    private fun restoreEditorState(state: EditState) {
        lastSnapshotAt = 0L
        editorView.state.copyFrom(state)
        editorView.state.photoDate = photoDate
        editorView.selectedOverlay = editorView.state.overlays.lastOrNull()
        editorView.bitmap?.let { PrintRenderer.clamp(it, editorView.state) }
        editorView.invalidateFrame()
        syncSliders(); syncPresets(); syncFrameColors()
        if (editorView.overlayMode) refreshOverlayPanel()
        syncUndoButtons()
    }

    private fun syncUndoButtons() {
        ui.setEnabledLook(undoButton, undoStack.isNotEmpty())
        ui.setEnabledLook(redoButton, redoStack.isNotEmpty())
    }

    private fun applyEdits() {
        editState.copyFrom(editorView.state)
        refreshPreview()
        message("")
        show(homeView)
    }

    // ---------------------------------------------------------------- Guide screen

    private fun buildGuide(): View {
        val root = ui.column(20)
        root.addView(ui.text(s.guideIntro, 15f))
        ui.space(root, 16)
        s.guideSteps.forEachIndexed { i, step ->
            val number = ui.text("${i + 1}", 16f, bold = true, color = ui.accent)
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
        return fixedHeaderScreen(titleBar(s.guideTitle, ui.softButton(s.back) { show(homeView) }.apply { minimumWidth = 0 }), root)
    }

    // ---------------------------------------------------------------- Printing

    /** The data for the printer: the look, then the inverse film model when compensation is on. */
    /** The data for the printer: the look, then print-time sharpening for the film, then the inverse film model when compensation is on. */
    private fun renderForPrint(bitmap: Bitmap, state: EditState): Bitmap {
        val rendered = PrintRenderer.render(bitmap, state, PREVIEW_SIZE)
        if (!compensate && film.printSharpness <= 0f) return rendered
        val pixels = IntArray(PREVIEW_SIZE * PREVIEW_SIZE)
        rendered.getPixels(pixels, 0, PREVIEW_SIZE, 0, 0, PREVIEW_SIZE, PREVIEW_SIZE)
        if (film.printSharpness > 0f) Processing.unsharpMask(pixels, PREVIEW_SIZE, PREVIEW_SIZE, film.printSharpness / 100f * 1.2f)
        if (compensate) film.compensate(pixels)
        rendered.setPixels(pixels, 0, PREVIEW_SIZE, 0, 0, PREVIEW_SIZE, PREVIEW_SIZE)
        return rendered
    }

    private fun applyFilmModel() {
        previewImage.film = film
        previewImage.compensated = compensate
        editorView.film = film
        editorView.compensated = compensate
    }

    private fun setFilmType(type: FilmType) {
        if (type == filmType) return
        filmType = type
        prefs.edit().putString(PREF_FILM_TYPE, type.key).apply()
        film = FilmModel.load(prefs, type)
        PrintRenderer.monochrome = type.monochrome
        chartBase = null
        applyFilmType()
        applyFilmModel()
        refreshPreview()
        if (container.getChildAt(0) === editorScreen) { buildThumbnails(editorView.bitmap ?: return); editorView.invalidateFrame() }
    }

    /** Show or hide what only applies to colour film, everywhere. */
    private fun applyFilmType() {
        val mono = filmType.monochrome
        filmTypeSelector.select(if (mono) 1 else 0)
        for (v in colourOnlyViews) v.visibility = if (mono) View.GONE else View.VISIBLE
        for (v in colourOnlyCal) v.visibility = if (mono) View.GONE else View.VISIBLE
        monoNoteView.visibility = if (mono) View.VISIBLE else View.GONE
        calibrationTitle.text = s.calibrateTitleFor.format(if (mono) s.filmMono else s.filmColor)
    }

    private fun print() {
        val bitmap = photo ?: return message(s.noPhoto)
        if (connected == null) return message(s.notConnectedYet)
        if (printing.get()) return message(s.busy)
        val file = Jobs.save(Jobs.queueDir(this), renderForPrint(bitmap, editState))
        startPrinting(listOf(file))
    }

    /** Hand a list of rendered files to the foreground service. */
    private fun startPrinting(files: List<File>, archive: Boolean = true) {
        if (!printing.compareAndSet(false, true)) return message(s.busy)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        }
        setBusy(true)
        val transferOnly = debug && transferOnlySwitch.isChecked
        journal("starting ${files.size} job(s)" + if (transferOnly) " (transfer only)" else "")
        PrintService.listener = serviceListener
        PrintService.start(this, files, host(), pin(), transferOnly, archive)
    }

    private val serviceListener = object : PrintService.Listener {
        override fun onStatus(status: PrintService.Status) = applyServiceStatus(status)

        override fun onJobDone(file: File, archived: File?) {
            refreshQueue()
        }

        override fun onJobFailed(file: File, error: Throwable) {
            journal("job failed: $error")
        }

        override fun onAllDone(printed: Int, failed: Int) {
            printing.set(false)
            setBusy(false)
            refreshQueue()
            watcher.refresh()
        }

        override fun log(line: String) = journal(line)
    }

    private fun applyServiceStatus(status: PrintService.Status) {
        if (status.running != printing.get()) {
            printing.set(status.running)
            setBusy(status.running)
        }
        if (status.message.isNotEmpty()) {
            messageView.text = if (status.total > 1) "${status.index}/${status.total} · ${status.message}" else status.message
            messageView.visibility = View.VISIBLE
        }
        if (status.running) {
            progressBar.isIndeterminate = status.progress < 0
            if (status.progress >= 0) progressBar.progress = status.progress
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
        toast(s.logCopied)
    }

    private fun shareJournal() {
        val intent = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "pls-share-sp3 log")
            .putExtra(Intent.EXTRA_TEXT, journalText())
        startActivity(Intent.createChooser(intent, s.shareLog))
    }
}
