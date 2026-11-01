package io.github.akaiikitsune.plsshare

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import instax.ImageSource
import instax.InstaxException
import instax.InstaxPrinter
import instax.PrintEvent
import instax.PrinterException
import java.io.File
import java.io.IOException

/**
 * Prints a list of rendered files one after the other as a foreground service, so the job
 * survives the app going to the background and shows its progress in a notification.
 */
class PrintService : Service() {
    companion object {
        const val CHANNEL = "print"
        const val NOTIFICATION_ID = 1
        const val EXTRA_FILES = "files"
        const val EXTRA_HOST = "host"
        const val EXTRA_PIN = "pin"
        const val EXTRA_TRANSFER_ONLY = "transferOnly"
        const val EXTRA_ARCHIVE = "archive"

        /** Pause between two prints, on top of the printer's own readiness wait. */
        private const val PAUSE_BETWEEN_MS = 3_000L

        /** Who is watching: the activity when it is in front. Set from the main thread. */
        @Volatile var listener: Listener? = null

        /** The last known state, for an activity coming back to the front. */
        @Volatile var status: Status = Status(false, 0, 0, "", -1)

        fun start(context: Context, files: List<File>, host: String, pin: Int, transferOnly: Boolean, archive: Boolean) {
            val intent = Intent(context, PrintService::class.java)
                .putExtra(EXTRA_FILES, files.map { it.path }.toTypedArray())
                .putExtra(EXTRA_HOST, host)
                .putExtra(EXTRA_PIN, pin)
                .putExtra(EXTRA_TRANSFER_ONLY, transferOnly)
                .putExtra(EXTRA_ARCHIVE, archive)
            context.startForegroundService(intent)
        }
    }

    data class Status(val running: Boolean, val index: Int, val total: Int, val message: String, val progress: Int)

    interface Listener {
        fun onStatus(status: Status)
        fun onJobDone(file: File, archived: File?)
        fun onJobFailed(file: File, error: Throwable)
        fun onAllDone(printed: Int, failed: Int)
        fun log(line: String)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val s = Strings.forDefaultLocale()
    private var thread: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A started foreground service must call startForeground promptly, whatever happens next.
        createChannel()
        startForeground(NOTIFICATION_ID, notification(s.printingTitle, s.printingStart, -1), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        val paths = intent?.getStringArrayExtra(EXTRA_FILES)
        if (paths == null || paths.isEmpty()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (thread?.isAlive == true) {
            log("already printing, ignoring a new request")
            return START_NOT_STICKY
        }
        val files = paths.map { File(it) }
        val host = intent.getStringExtra(EXTRA_HOST) ?: InstaxPrinter.DEFAULT_HOST
        val pin = intent.getIntExtra(EXTRA_PIN, InstaxPrinter.DEFAULT_PIN)
        val transferOnly = intent.getBooleanExtra(EXTRA_TRANSFER_ONLY, false)
        val archive = intent.getBooleanExtra(EXTRA_ARCHIVE, true)
        publish(Status(true, 0, files.size, s.printingStart, -1))

        thread = Thread({ run(files, host, pin, transferOnly, archive) }, "print-service").apply { start() }
        return START_NOT_STICKY
    }

    private fun run(files: List<File>, host: String, pin: Int, transferOnly: Boolean, archive: Boolean) {
        val network = PrinterNetwork(this)
        var printed = 0
        var failed = 0
        for ((i, file) in files.withIndex()) {
            val index = i + 1
            val printer = InstaxPrinter(
                host = host, pin = pin, reachTimeoutMillis = 120_000,
                socketFactory = network.socketFactory(), logger = { log(it) },
            )
            log("job $index/${files.size}: ${file.name}" + if (transferOnly) " (transfer only)" else "")
            val bitmap = Jobs.load(file)
            if (bitmap == null) {
                failed++
                notifyJobFailed(file, IOException("cannot read ${file.name}"))
                continue
            }
            // Rendered files are square (SP-3). An SP-2 asks for 600x800: cover and crop the centre.
            val source = ImageSource { w, h ->
                val pixels = IntArray(w * h)
                if (bitmap.width == w && bitmap.height == h) {
                    bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
                } else {
                    val target = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
                    val scale = maxOf(w.toFloat() / bitmap.width, h.toFloat() / bitmap.height)
                    val dw = bitmap.width * scale
                    val dh = bitmap.height * scale
                    val dest = android.graphics.RectF((w - dw) / 2, (h - dh) / 2, (w + dw) / 2, (h + dh) / 2)
                    android.graphics.Canvas(target).drawBitmap(bitmap, null, dest, android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
                    target.getPixels(pixels, 0, w, 0, 0, w, h)
                    target.recycle()
                }
                pixels
            }
            var filmLeft = -1
            val result = runCatching {
                printer.print(source, transferOnly) { event ->
                    if (event is PrintEvent.Finished) filmLeft = event.filmRemaining
                    publish(Status(true, index, files.size, describe(event), progressOf(event)))
                }
            }
            bitmap.recycle()
            result.onSuccess {
                printed++
                val archived = if (archive && !transferOnly) Jobs.archive(this, file) else null
                if (file.parentFile?.name == "queue") file.delete()
                mainHandler.post { listener?.onJobDone(file, archived) }
            }.onFailure { e ->
                failed++
                log("job $index failed: $e")
                notifyJobFailed(file, e)
            }
            if (filmLeft == 0 && index < files.size) {
                log("out of film, stopping the queue")
                publish(Status(true, index, files.size, s.noFilmStop, -1))
                failed += files.size - index
                break
            }
            if (index < files.size) Thread.sleep(PAUSE_BETWEEN_MS)
        }
        val summary = s.printingDone.format(printed, failed)
        publish(Status(false, files.size, files.size, summary, -1))
        mainHandler.post { listener?.onAllDone(printed, failed) }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID + 1, notification(s.printingTitle, summary, -1, ongoing = false))
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun notifyJobFailed(file: File, e: Throwable) {
        publish(Status(true, status.index, status.total, describeError(e), -1))
        mainHandler.post { listener?.onJobFailed(file, e) }
    }

    private fun describe(event: PrintEvent): String = when (event) {
        is PrintEvent.Reaching -> s.reaching.format(event.reason, event.attempt)
        PrintEvent.Locking -> s.locking
        is PrintEvent.Ready -> s.locking
        is PrintEvent.Transferring -> "${s.sending} ${100L * event.sentBytes / event.totalBytes} %"
        PrintEvent.WaitingForPrinter -> s.waiting
        is PrintEvent.Printing -> s.printing.format(event.estimatedSeconds.toInt())
        is PrintEvent.Finished -> s.done.format(event.filmRemaining)
        PrintEvent.TransferOnlyFinished -> s.transferOnlyDone
        PrintEvent.Unlocked -> status.message
    }

    private fun progressOf(event: PrintEvent): Int = when (event) {
        is PrintEvent.Transferring -> (1000L * event.sentBytes / event.totalBytes).toInt()
        else -> -1
    }

    private fun describeError(e: Throwable): String = when (e) {
        is PrinterException -> s.printerError.format(s.describeReturnCode(e.returnCode, e.message ?: ""))
        is InstaxException -> s.printerError.format(e.message)
        is IOException -> if (e.message?.contains("ECONNREFUSED") == true) s.refused.format("ECONNREFUSED") else s.networkError.format(e.message ?: e.javaClass.simpleName)
        else -> e.toString()
    }

    private fun publish(next: Status) {
        status = next
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val title = if (next.total > 1) "${s.printingTitle} ${next.index}/${next.total}" else s.printingTitle
        manager.notify(NOTIFICATION_ID, notification(title, next.message, next.progress))
        mainHandler.post { listener?.onStatus(next) }
    }

    private fun log(line: String) {
        val l = listener
        if (l != null) mainHandler.post { l.log(line) } else android.util.Log.d("PrintService", line)
    }

    private fun createChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, s.printingTitle, NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun notification(title: String, text: String, progress: Int, ongoing: Boolean = true): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
        if (progress >= 0) builder.setProgress(1000, progress, false)
        else if (ongoing) builder.setProgress(0, 0, true)
        return builder.build()
    }
}
