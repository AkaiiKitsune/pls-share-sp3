package io.github.akaiikitsune.plsshare

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
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
        private const val JOURNAL_LINES = 400
        private const val PREVIEW_SIZE = 800

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
    private lateinit var historyList: LinearLayout
    private lateinit var queueRow: LinearLayout
    private lateinit var queueEmptyText: TextView
    private lateinit var printQueueButton: Button
    private lateinit var queueButton: Button

    // Home widgets
    private lateinit var statusDot: View
    private lateinit var statusTitle: TextView
    private lateinit var statusDetail: TextView
    private lateinit var statusSpinner: ProgressBar
    private lateinit var statusAction: Button
    private lateinit var previewImage: PrintPreviewView
    private lateinit var filmSwitch: Switch
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
    private val sliders = LinkedHashMap<String, Pair<SeekBar, (EditState) -> Int>>()
    private lateinit var histogramView: HistogramView
    private lateinit var histogramButton: Button
    private lateinit var clippingButton: Button
    private lateinit var clippingHint: TextView
    private var showHistogram = false
    private lateinit var adjustPanel: View
    private lateinit var textsPanel: LinearLayout
    private lateinit var tabAdjust: Button
    private lateinit var tabTexts: Button
    private lateinit var overlayList: LinearLayout
    private lateinit var overlayEditor: LinearLayout
    private lateinit var overlayTextField: EditText
    private lateinit var overlaySizeBar: SeekBar
    private lateinit var overlayDarkSwitch: Switch
    private lateinit var blackWhiteButton: Button
    private val fontButtons = LinkedHashMap<Overlay.Font, Button>()
    private lateinit var overlayMarginBar: SeekBar
    private lateinit var overlaySizeValue: TextView
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
        filmSwitch = Switch(this).apply {
            text = s.filmPreview
            isChecked = prefs.getBoolean(PREF_FILM, false)
            setOnCheckedChangeListener { _, checked -> setFilmPreview(checked) }
            setPadding(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4))
        }
        root.addView(filmSwitch)
        ui.space(root, 8)

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
            visibility = View.INVISIBLE
        }
        root.addView(progressBar, LinearLayout.LayoutParams(ui.match, ui.wrap))
        messageView = ui.text("", 14f)
        root.addView(messageView)
        ui.space(root, 16)

        // Queue card
        queueRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        queueEmptyText = ui.text(s.queueEmpty, 13f, alpha = 0.6f)
        printQueueButton = ui.softButton(s.printQueue.format(0)) { printQueue() }
        val queueCard = ui.card(
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
        menu.menu.add(0, MENU_HISTORY, 2, s.menuHistory)
        menu.menu.add(0, MENU_EXPORT, 3, s.menuExport)
        menu.menu.add(0, MENU_UPDATE, 4, s.menuUpdate)
        menu.menu.add(0, MENU_DEBUG, 5, s.menuDebug).apply { isCheckable = true; isChecked = debug }
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_GUIDE -> show(guideView)
                MENU_PIN -> askPin()
                MENU_HISTORY -> { refreshHistory(); show(historyView) }
                MENU_EXPORT -> exportCurrent()
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
        queueButton.isEnabled = photo != null
        printQueueButton.isEnabled = !busy && connected != null && Jobs.list(Jobs.queueDir(this)).isNotEmpty()
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
                    val rendered = PrintRenderer.render(bitmap, state, PREVIEW_SIZE)
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
                message(s.queued.format(Jobs.list(Jobs.queueDir(this)).size))
                journal("queued $count photo(s)")
            }
        }.start()
    }

    private fun addCurrentToQueue() {
        val bitmap = photo ?: return message(s.noPhoto)
        Jobs.save(Jobs.queueDir(this), PrintRenderer.render(bitmap, editState, PREVIEW_SIZE))
        refreshQueue()
        message(s.queued.format(Jobs.list(Jobs.queueDir(this)).size))
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
    }

    private fun clearQueue() {
        for (f in Jobs.list(Jobs.queueDir(this))) f.delete()
        refreshQueue()
    }

    private fun printQueue() {
        if (connected == null) return message(s.notConnectedYet)
        val files = Jobs.list(Jobs.queueDir(this))
        if (files.isEmpty()) return
        startPrinting(files)
    }

    // ---------------------------------------------------------------- History and export

    private fun buildHistory(): View {
        val root = ui.column(20)
        root.addView(ui.title(s.historyTitle))
        ui.space(root, 12)
        historyList = ui.column()
        root.addView(historyList, LinearLayout.LayoutParams(ui.match, ui.wrap))
        ui.space(root, 16)
        root.addView(ui.softButton(s.back) { show(homeView) }, LinearLayout.LayoutParams(ui.match, ui.wrap))
        return ScrollView(this).apply { addView(root) }
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
        message(s.exported)
        startActivity(Intent.createChooser(
            Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            s.share,
        ))
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
        if (filmSwitch.isChecked != on) filmSwitch.isChecked = on
        filmButton.setTextColor(if (on) ui.accent else labelColor)
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
        ui.space(root, 10)

        // Tools under the photo, available in both tabs: compare, film preview, histogram, clipping.
        histogramView = HistogramView(this).apply { visibility = View.GONE }
        root.addView(histogramView, LinearLayout.LayoutParams(ui.match, ui.dp(72)))
        editorView.onFrame = { pixels, _ -> histogramView.update(Processing.histogram(pixels, stride = 1)) }
        histogramButton = ui.softButton(s.histogram) { toggleHistogram() }
        clippingButton = ui.softButton(s.clipping) { toggleClipping() }
        clippingHint = ui.text(s.clippingHint, 12f, alpha = 0.6f).apply { visibility = View.GONE; gravity = Gravity.CENTER }
        val compareButton = ui.softButton(s.compare) {}.apply {
            setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> { editorView.showOriginal = true; v.isPressed = true }
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> { editorView.showOriginal = false; v.isPressed = false }
                }
                true
            }
        }
        filmButton = ui.softButton(s.filmPreview) { setFilmPreview(!editorView.filmPreview) }
        root.addView(ui.row(compareButton, filmButton, gap = 6))
        ui.space(root, 6)
        root.addView(ui.row(histogramButton, clippingButton, gap = 6))
        root.addView(clippingHint)
        ui.space(root, 14)

        tabAdjust = ui.softButton(s.tabAdjust) { showTab(adjust = true) }
        tabTexts = ui.softButton(s.tabTexts) { showTab(adjust = false) }
        root.addView(ui.row(tabAdjust, tabTexts))
        ui.space(root, 12)

        val adjust = ui.column()
        adjustPanel = adjust
        root.addView(adjust, LinearLayout.LayoutParams(ui.match, ui.wrap))
        textsPanel = buildTextsPanel()
        textsPanel.visibility = View.GONE
        root.addView(textsPanel, LinearLayout.LayoutParams(ui.match, ui.wrap))

        adjust.addView(ui.row(
            ui.softButton(s.rotate) { editorView.update { rotation = (Math.round(rotation / 90f) + 1) * 90f % 360f } },
            ui.softButton(s.fill) { editorView.update { scale = 1f; offsetX = 0f; offsetY = 0f } },
            ui.softButton(s.whole) { editorView.update { scale = PrintRenderer.containScale(editorView.bitmap!!, this); offsetX = 0f; offsetY = 0f } },
            ui.softButton(s.blackWhite) { editorView.update { saturation = if (saturation == 0f) 1f else 0f }; syncSliders() }.also { blackWhiteButton = it },
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

        adjust.addView(ui.text(s.colour, 14f, bold = true))
        ui.space(adjust, 6)
        slider(adjust, s.temperature, -100, 100, { it.temperature.toInt() }) { v -> editorView.update { temperature = v.toFloat() } }
        slider(adjust, s.tint, -100, 100, { it.tint.toInt() }) { v -> editorView.update { tint = v.toFloat() } }
        slider(adjust, s.saturation, 0, 200, { (it.saturation * 100f).toInt() }) { v -> editorView.update { saturation = v / 100f }; syncSliders() }
        ui.space(adjust, 8)

        adjust.addView(ui.text(s.details, 14f, bold = true))
        ui.space(adjust, 6)
        slider(adjust, s.sharpness, 0, 100, { it.sharpness.toInt() }) { v -> editorView.update { sharpness = v.toFloat() } }
        slider(adjust, s.vignette, 0, 100, { it.vignette.toInt() }) { v -> editorView.update { vignette = v.toFloat() } }
        ui.space(adjust, 8)

        adjust.addView(ui.text(s.frame, 14f, bold = true))
        ui.space(adjust, 6)
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
            colorRow.addView(chip, LinearLayout.LayoutParams(ui.dp(36), ui.dp(36)).apply { marginEnd = ui.dp(10) })
        }
        adjust.addView(colorRow)

        return ScrollView(this).apply { addView(root) }
    }

    private fun showTab(adjust: Boolean) {
        adjustPanel.visibility = if (adjust) View.VISIBLE else View.GONE
        textsPanel.visibility = if (adjust) View.GONE else View.VISIBLE
        tabAdjust.setTextColor(if (adjust) ui.accent else labelColor)
        tabTexts.setTextColor(if (adjust) labelColor else ui.accent)
        editorView.overlayMode = !adjust
        if (!adjust && editorView.selectedOverlay == null) editorView.selectedOverlay = editorView.state.overlays.lastOrNull()
        refreshOverlayPanel()
    }

    private fun buildTextsPanel(): LinearLayout {
        val panel = ui.column()
        panel.addView(ui.text(s.textsHint, 12f, alpha = 0.6f))
        ui.space(panel, 10)
        panel.addView(ui.row(
            ui.softButton(s.addText) { addOverlay(Overlay(Overlay.Kind.TEXT, text = "instax")) },
            ui.softButton(s.addDate) { addOverlay(Overlay(Overlay.Kind.DATE)) },
            gap = 6,
        ))
        ui.space(panel, 6)
        panel.addView(ui.row(
            ui.softButton(s.addTime) { addOverlay(Overlay(Overlay.Kind.TIME)) },
            ui.softButton(s.addDateTime) { addOverlay(Overlay(Overlay.Kind.DATETIME, size = 0.035f)) },
            gap = 6,
        ))
        ui.space(panel, 12)
        overlayList = ui.column()
        panel.addView(overlayList, LinearLayout.LayoutParams(ui.match, ui.wrap))
        ui.space(panel, 8)

        overlayEditor = ui.column()
        overlayEditor.addView(ui.text(s.overlayText, 13f, alpha = 0.7f))
        overlayTextField = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(text: android.text.Editable?) {
                    val o = editorView.selectedOverlay ?: return
                    if (o.kind == Overlay.Kind.TEXT && o.text != text.toString()) {
                        o.text = text.toString()
                        editorView.overlaysChanged()
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
                    o.size = (progress + 15) / 1000f
                    o.anchor?.let { o.place(it, PrintRenderer.overlayWidth(o, editorView.state.photoDate)) }
                    editorView.overlaysChanged()
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
                if (o.dark != checked) { o.dark = checked; editorView.overlaysChanged() }
            }
        }
        overlayEditor.addView(overlayDarkSwitch)
        ui.space(overlayEditor, 8)
        overlayEditor.addView(ui.text(s.overlayFont, 13f, alpha = 0.7f))
        ui.space(overlayEditor, 4)
        val fontRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (font in Overlay.Font.values()) {
            val b = ui.softButton(s.fontName(font)) { setOverlayFont(font) }.apply { typeface = font.typeface }
            fontButtons[font] = b
            fontRow.addView(b, LinearLayout.LayoutParams(ui.wrap, ui.wrap).apply { marginEnd = ui.dp(6) })
        }
        overlayEditor.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(fontRow) })
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
                    o.margin = progress / 100f
                    o.anchor?.let { setOverlayAnchor(it) }
                }

                override fun onStartTrackingTouch(seekBar: SeekBar) {}
                override fun onStopTrackingTouch(seekBar: SeekBar) {}
            })
        }
        overlayEditor.addView(overlayMarginBar, LinearLayout.LayoutParams(ui.match, ui.wrap))
        ui.space(overlayEditor, 6)
        overlayEditor.addView(ui.row(
            ui.softButton(s.styleShadow) { setOverlayStyle(Overlay.Style.SHADOW) },
            ui.softButton(s.styleOutline) { setOverlayStyle(Overlay.Style.OUTLINE) },
            ui.softButton(s.stylePill) { setOverlayStyle(Overlay.Style.PILL) },
            gap = 6,
        ))
        ui.space(overlayEditor, 6)
        overlayEditor.addView(ui.softButton(s.remove) { removeSelectedOverlay() }, LinearLayout.LayoutParams(ui.match, ui.wrap))
        panel.addView(overlayEditor, LinearLayout.LayoutParams(ui.match, ui.wrap))

        editorView.onOverlaySelected = { refreshOverlayPanel() }
        return panel
    }

    private fun addOverlay(overlay: Overlay) {
        // Stack new texts upwards so they do not land on top of each other.
        overlay.y = (0.92f - 0.07f * editorView.state.overlays.size).coerceAtLeast(0.1f)
        editorView.state.overlays += overlay
        editorView.selectedOverlay = overlay
        editorView.overlaysChanged()
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
                o.size = px / PREVIEW_SIZE.toFloat()
                o.anchor?.let { o.place(it, PrintRenderer.overlayWidth(o, editorView.state.photoDate)) }
                editorView.overlaysChanged()
                refreshOverlayPanel()
            }
            .show()
    }

    private fun setOverlayFont(font: Overlay.Font) {
        val o = editorView.selectedOverlay ?: return
        o.font = font
        o.anchor?.let { o.place(it, PrintRenderer.overlayWidth(o, editorView.state.photoDate)) }
        editorView.overlaysChanged()
        syncFontButtons()
    }

    private fun syncFontButtons() {
        val current = editorView.selectedOverlay?.font
        for ((font, button) in fontButtons) button.setTextColor(if (font == current) ui.accent else labelColor)
    }

    private fun setOverlayAnchor(anchor: Overlay.Anchor) {
        val o = editorView.selectedOverlay ?: return
        o.place(anchor, PrintRenderer.overlayWidth(o, editorView.state.photoDate))
        editorView.overlaysChanged()
    }

    private fun setOverlayStyle(style: Overlay.Style) {
        editorView.selectedOverlay?.style = style
        editorView.overlaysChanged()
    }

    private fun removeSelectedOverlay() {
        val o = editorView.selectedOverlay ?: return
        editorView.state.overlays.remove(o)
        editorView.selectedOverlay = editorView.state.overlays.lastOrNull()
        editorView.overlaysChanged()
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
        if (o.kind == Overlay.Kind.TEXT && overlayTextField.text.toString() != o.text) overlayTextField.setText(o.text)
        overlaySizeBar.progress = (o.size * 1000f).toInt() - 15
        overlaySizeValue.text = "${Math.round(o.size * PREVIEW_SIZE)} px"
        overlayDarkSwitch.isChecked = o.dark
        overlayMarginBar.progress = Math.round(o.margin * 100f)
        overlayMarginValue.text = "${overlayMarginBar.progress} %"
        syncFontButtons()
    }

    /** A labelled slider over [min]..[max] whose current value is read from the editor state by [read]. */
    private fun slider(parent: LinearLayout, label: String, min: Int, max: Int, read: (EditState) -> Int, onValue: (Int) -> Unit) {
        val title = ui.text(label, 13f, alpha = 0.7f)
        val value = ui.text("", 13f, alpha = 0.7f).apply { gravity = Gravity.END }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(title, LinearLayout.LayoutParams(0, ui.wrap, 1f))
            addView(value, LinearLayout.LayoutParams(ui.wrap, ui.wrap))
        }
        parent.addView(header)
        val bar = SeekBar(this).apply {
            this.max = max - min
            progress = -min
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    value.text = (progress + min).toString()
                    if (fromUser) onValue(progress + min)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar) = editorView.beginInteraction()
                override fun onStopTrackingTouch(seekBar: SeekBar) = editorView.endInteraction()
            })
        }
        parent.addView(bar, LinearLayout.LayoutParams(ui.match, ui.wrap))
        ui.space(parent, 6)
        sliders[label] = bar to { st -> read(st) - min }
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
        val levels = Processing.autoLevels(pixels)
        if (levels == null) {
            message(s.autoNothing)
            return
        }
        editorView.update { brightness = levels.first; contrast = levels.second }
        syncSliders()
        message(s.autoDone)
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
                message(s.presetSaved)
            }
            .show()
    }

    private fun syncSliders() {
        for ((bar, read) in sliders.values) bar.progress = read(editorView.state)
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
        clippingHint.visibility = if (editorView.showClipping) View.VISIBLE else View.GONE
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
        root.addView(ui.softButton(s.back) { show(homeView) }, LinearLayout.LayoutParams(ui.match, ui.wrap))
        return ScrollView(this).apply { addView(root) }
    }

    // ---------------------------------------------------------------- Printing

    private fun print() {
        val bitmap = photo ?: return message(s.noPhoto)
        if (connected == null) return message(s.notConnectedYet)
        if (printing.get()) return message(s.busy)
        val file = Jobs.save(Jobs.queueDir(this), PrintRenderer.render(bitmap, editState, PREVIEW_SIZE))
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
        message(s.logCopied)
    }

    private fun shareJournal() {
        val intent = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "pls-share-sp3 log")
            .putExtra(Intent.EXTRA_TEXT, journalText())
        startActivity(Intent.createChooser(intent, s.shareLog))
    }
}
