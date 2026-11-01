package io.github.akaiikitsune.plsshare

import android.os.Handler
import android.os.Looper
import instax.InstaxPrinter
import instax.PrinterInfo
import java.io.IOException
import java.util.concurrent.Executors

/**
 * Watches the phone's Wi-Fi and asks the printer for its status every few seconds, so the
 * screen shows "connected" by itself once the user has joined the INSTAX network.
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
        private const val INTERVAL_MS = 4_000L
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "printer-watcher").apply { isDaemon = true } }

    @Volatile var paused = false
    private var running = false

    var state: State = State.NoWifi
        private set

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            if (!paused) executor.execute { probe() }
            mainHandler.postDelayed(this, INTERVAL_MS)
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

    /** Probe right now, for example after a print. */
    fun refresh() {
        executor.execute { probe() }
    }

    private fun probe() {
        val next: State = when {
            network.currentWifi() == null -> State.NoWifi
            !network.isPrinterNetwork() -> State.OtherWifi
            else -> try {
                State.Connected(printerFactory().info())
            } catch (e: IOException) {
                State.WaitingForPrinter
            } catch (e: instax.InstaxException) {
                // Wrong PIN or similar: the printer is there, let the print attempt report it.
                State.WaitingForPrinter
            }
        }
        mainHandler.post {
            state = next
            onState(next)
        }
    }
}
