package io.github.akaiikitsune.plsshare

import android.os.Handler
import android.os.Looper
import instax.InstaxPrinter
import instax.PrinterInfo
import java.io.IOException
import java.util.concurrent.Executors

/**
 * Keeps the connection status up to date without bothering the printer.
 *
 * The Wi-Fi state is read locally every few seconds (no traffic). The printer itself is only
 * asked for its status when the phone arrives on its network (retrying until it answers, since
 * the SP-3 needs 1 to 2 minutes after switching on), after a print, or on an explicit [refresh].
 * Once it has answered, nothing more is sent until the network changes.
 */
class PrinterWatcher(
    private val network: PrinterNetwork,
    private val printerFactory: () -> InstaxPrinter,
    private val onState: (State) -> Unit,
) {
    sealed class State {
        object NoWifi : State()
        object OtherWifi : State()
        object WaitingForPrinter : State()
        data class Connected(val info: PrinterInfo) : State()
    }

    companion object {
        /** How often the local Wi-Fi state is checked. */
        private const val WIFI_INTERVAL_MS = 3_000L

        /** How often the printer is asked again while it does not answer yet. */
        private const val PROBE_INTERVAL_MS = 5_000L
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "printer-watcher").apply { isDaemon = true } }

    @Volatile var paused = false
    private var running = false
    private var lastProbe = 0L

    var state: State = State.NoWifi
        private set

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            if (!paused) executor.execute { check(force = false) }
            mainHandler.postDelayed(this, WIFI_INTERVAL_MS)
        }
    }

    fun start() {
        if (running) return
        running = true
        mainHandler.post(tick)
    }

    fun stop() {
        running = false
        mainHandler.removeCallbacks(tick)
    }

    /** Ask the printer right now, for example after a print or when the user taps the status. */
    fun refresh() {
        executor.execute { check(force = true) }
    }

    private fun check(force: Boolean) {
        val wifiState: State? = when {
            network.currentWifi() == null -> State.NoWifi
            !network.isPrinterNetwork() -> State.OtherWifi
            else -> null
        }
        if (wifiState != null) {
            publish(wifiState)
            return
        }
        // On the printer's network. Only talk to the printer when we do not know it is there yet.
        val now = System.currentTimeMillis()
        val needProbe = force || state !is State.Connected
        if (!needProbe || (!force && now - lastProbe < PROBE_INTERVAL_MS)) {
            if (state !is State.Connected && state !is State.WaitingForPrinter) publish(State.WaitingForPrinter)
            return
        }
        lastProbe = now
        val next = try {
            State.Connected(printerFactory().info())
        } catch (e: IOException) {
            State.WaitingForPrinter
        } catch (e: instax.InstaxException) {
            // Wrong PIN or similar: the printer is there, let the print attempt report it.
            State.WaitingForPrinter
        }
        publish(next)
    }

    private fun publish(next: State) {
        mainHandler.post {
            val changed = next != state
            state = next
            if (changed) onState(next)
        }
    }
}
