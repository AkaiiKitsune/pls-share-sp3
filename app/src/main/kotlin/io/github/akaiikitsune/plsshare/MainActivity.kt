package io.github.akaiikitsune.plsshare

import android.app.Activity
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
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import instax.InstaxException
import instax.InstaxPrinter
import instax.PrintEvent
import instax.PrinterException
import instax.PrinterInfo
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One screen: connect to the printer, pick a photo, print.
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
        private const val PREF_FIT = "fit"
    }

    private val s = Strings.forDefaultLocale()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val printing = AtomicBoolean(false)

    private lateinit var network: PrinterNetwork
    private lateinit var prefs: SharedPreferences

    private lateinit var statusView: TextView
    private lateinit var infoView: TextView
    private lateinit var previewView: ImageView
    private lateinit var progressBar: ProgressBar
    private lateinit var logView: TextView
    private lateinit var connectButton: Button
    private lateinit var infoButton: Button
    private lateinit var pickButton: Button
    private lateinit var printButton: Button
    private lateinit var fitSwitch: Switch
    private lateinit var pinField: EditText
    private lateinit var hostField: EditText

    private var photo: Bitmap? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        network = PrinterNetwork(this)
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        setContentView(buildUi())
        setStatus(s.notConnected)
        handleShareIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShareIntent(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!printing.get()) network.disconnect()
    }

    // ---------------------------------------------------------------- UI

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
        }
        fun space(h: Int) = root.addView(View(this), ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(h)))

        root.addView(TextView(this).apply {
            text = s.title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            typeface = Typeface.DEFAULT_BOLD
        })
        root.addView(TextView(this).apply {
            text = s.subtitle
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        })
        space(16)

        statusView = TextView(this).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f) }
        root.addView(statusView)
        infoView = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            visibility = View.GONE
        }
        root.addView(infoView)
        space(8)

        root.addView(row(
            button(s.connect) { connect() }.also { connectButton = it },
            button(s.info) { showInfo() }.also { infoButton = it },
        ))
        space(16)

        previewView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(0x22808080)
            adjustViewBounds = true
            contentDescription = s.pickPhoto
            setOnClickListener { pickPhoto() }
        }
        root.addView(previewView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(300)))
        space(8)

        pickButton = button(s.pickPhoto) { pickPhoto() }
        root.addView(pickButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        fitSwitch = Switch(this).apply {
            isChecked = prefs.getString(PREF_FIT, "crop") == "contain"
            text = if (isChecked) s.fitContain else s.fitCrop
            setOnCheckedChangeListener { _, checked ->
                text = if (checked) s.fitContain else s.fitCrop
                prefs.edit().putString(PREF_FIT, if (checked) "contain" else "crop").apply()
                refreshPreview()
            }
            setPadding(dp(4), dp(8), dp(4), dp(8))
        }
        root.addView(fitSwitch)
        space(8)

        printButton = button(s.print) { print() }.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            minimumHeight = dp(56)
        }
        root.addView(printButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            visibility = View.INVISIBLE
        }
        root.addView(progressBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        logView = TextView(this).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f) }
        root.addView(logView)
        space(24)

        root.addView(TextView(this).apply { text = s.hint; setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f); alpha = 0.75f })
        space(16)

        root.addView(TextView(this).apply { text = s.pin; setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f) })
        pinField = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(prefs.getString(PREF_PIN, InstaxPrinter.DEFAULT_PIN.toString()))
        }
        root.addView(pinField)
        root.addView(TextView(this).apply { text = s.host; setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f) })
        hostField = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(prefs.getString(PREF_HOST, InstaxPrinter.DEFAULT_HOST))
        }
        root.addView(hostField)

        return ScrollView(this).apply { addView(root) }
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        for (v in views) addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun setStatus(text: String) {
        statusView.text = text
    }

    private fun log(text: String) {
        logView.text = text
    }

    private fun setBusy(busy: Boolean) {
        for (b in listOf(connectButton, infoButton, pickButton, printButton)) b.isEnabled = !busy
        fitSwitch.isEnabled = !busy
        progressBar.visibility = if (busy) View.VISIBLE else View.INVISIBLE
        progressBar.isIndeterminate = busy
        if (busy) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    // ---------------------------------------------------------------- Printer network

    private fun connect() {
        setStatus(s.connecting)
        network.connect(object : PrinterNetwork.Listener {
            override fun onConnected() {
                setStatus(s.connected)
                showInfo()
            }

            override fun onFailed() = setStatus(s.connectFailed)

            override fun onLost() = setStatus(s.lost)
        })
    }

    private fun fit(): Fit = if (fitSwitch.isChecked) Fit.CONTAIN else Fit.CROP

    private fun printer(): InstaxPrinter? {
        val pin = pinField.text.toString().trim().toIntOrNull()
        if (pin == null || pin !in 0..9999) {
            log(s.badPin)
            return null
        }
        val host = hostField.text.toString().trim().ifEmpty { InstaxPrinter.DEFAULT_HOST }
        prefs.edit().putString(PREF_PIN, pin.toString()).putString(PREF_HOST, host).apply()
        return InstaxPrinter(host = host, pin = pin, socketFactory = network.socketFactory())
    }

    private fun showInfo() {
        val printer = printer() ?: return
        if (!printing.compareAndSet(false, true)) return
        setBusy(true)
        log(s.locking)
        Thread {
            val result = runCatching { printer.info() }
            mainHandler.post {
                printing.set(false)
                setBusy(false)
                result.onSuccess { log(""); showInfo(it) }.onFailure { showError(it) }
            }
        }.start()
    }

    private fun showInfo(info: PrinterInfo) {
        infoView.text = s.infoLine.format(
            info.model, info.firmware, info.batteryLevel, if (info.charging) s.charging else "",
            info.filmRemaining, info.printCount,
        ) + if (info.coverOpen) s.coverOpen else ""
        infoView.visibility = View.VISIBLE
    }

    private fun showError(e: Throwable) {
        log(
            when (e) {
                is PrinterException -> s.printerError.format(s.describeReturnCode(e.returnCode, e.message ?: ""))
                is InstaxException -> s.printerError.format(e.message)
                is IOException -> s.networkError.format(e.message ?: e.javaClass.simpleName)
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
            log(s.shared)
        }
    }

    private fun loadPhoto(uri: Uri) {
        Thread {
            val result = runCatching { Photos.load(contentResolver, uri) }
            mainHandler.post {
                result.onSuccess {
                    photo?.recycle()
                    photo = it
                    refreshPreview()
                }.onFailure { log(s.photoError.format(it.message ?: it.javaClass.simpleName)) }
            }
        }.start()
    }

    private fun refreshPreview() {
        val bitmap = photo ?: return
        previewView.setImageBitmap(Photos.prepare(bitmap, 800, 800, fit()))
    }

    // ---------------------------------------------------------------- Printing

    private fun print() {
        val bitmap = photo
        if (bitmap == null) {
            log(s.noPhoto)
            return
        }
        val printer = printer() ?: return
        if (!printing.compareAndSet(false, true)) {
            log(s.busy)
            return
        }
        setBusy(true)
        val source = Photos.source(bitmap, fit())
        Thread {
            val result = runCatching {
                printer.print(source) { event -> mainHandler.post { onPrintEvent(event) } }
            }
            mainHandler.post {
                printing.set(false)
                setBusy(false)
                result.onFailure { showError(it) }
            }
        }.start()
    }

    private fun onPrintEvent(event: PrintEvent) {
        when (event) {
            PrintEvent.Locking -> log(s.locking)
            is PrintEvent.Ready -> showInfo(event.info)
            is PrintEvent.Transferring -> {
                progressBar.isIndeterminate = false
                progressBar.progress = (1000L * event.sentBytes / event.totalBytes).toInt()
                log("${s.sending} ${100L * event.sentBytes / event.totalBytes} %")
            }
            PrintEvent.WaitingForPrinter -> {
                progressBar.isIndeterminate = true
                log(s.waiting)
            }
            is PrintEvent.Printing -> log(s.printing.format(event.estimatedSeconds.toInt()))
            is PrintEvent.Finished -> log(s.done.format(event.filmRemaining))
            PrintEvent.Unlocked -> {}
        }
    }
}
