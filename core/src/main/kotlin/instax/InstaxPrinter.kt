package instax

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.SocketFactory

/** What the printer reports about itself. */
data class PrinterInfo(
    val model: String,
    val firmware: String,
    val hardware: String,
    /** Charge level from 0 to 3. */
    val batteryLevel: Int,
    /** Whether the printer is charging over USB. */
    val charging: Boolean,
    val filmRemaining: Int,
    val coverOpen: Boolean,
    val printCount: Long,
    val width: Int,
    val height: Int,
)

/** Progress of a print job, reported on the thread that runs it. */
sealed class PrintEvent {
    object Locking : PrintEvent()
    data class Ready(val info: PrinterInfo) : PrintEvent()
    data class Transferring(val sentBytes: Int, val totalBytes: Int) : PrintEvent()
    object WaitingForPrinter : PrintEvent()
    data class Printing(val estimatedSeconds: Double) : PrintEvent()
    data class Finished(val filmRemaining: Int) : PrintEvent()
    /** A transfer-only run ended: the image reached the printer and nothing was printed. */
    object TransferOnlyFinished : PrintEvent()
    object Unlocked : PrintEvent()
}

fun interface PrintListener {
    fun onEvent(event: PrintEvent)
}

/** Something that produces pixels at the size the printer asks for. */
fun interface ImageSource {
    /** Return exactly `width * height` packed ARGB pixels. */
    fun render(width: Int, height: Int): IntArray
}

/**
 * An Instax SHARE SP-2 or SP-3 printer reached over its own Wi-Fi network.
 *
 * Each method opens its own TCP connections, so an instance can be reused. The [socketFactory]
 * lets a caller bind the connections to a specific network interface, which Android needs when
 * the printer's Wi-Fi has no internet access (see the app's `PrinterNetwork`).
 */
class InstaxPrinter(
    val host: String = DEFAULT_HOST,
    val port: Int = DEFAULT_PORT,
    val pin: Int = DEFAULT_PIN,
    val timeoutMillis: Int = 15_000,
    private val socketFactory: SocketFactory = SocketFactory.getDefault(),
    private val sessionId: Int = (System.currentTimeMillis() and 0xFFFFFFFFL).toInt(),
    private val sleeper: (Long) -> Unit = Thread::sleep,
    private val logger: ((String) -> Unit)? = null,
) {
    init {
        require(pin in 0..9999) { "PIN must be between 0 and 9999, not $pin" }
    }

    companion object {
        const val DEFAULT_HOST = "192.168.0.251"
        const val DEFAULT_PORT = 8080
        const val DEFAULT_PIN = 1111

        /** Milliseconds between status checks while the printer is busy. */
        const val POLL_INTERVAL = 1_000L

        /** The printer needs this long between the end of a transfer and the print start. */
        const val TRANSFER_SETTLE_TIME = 1_000L

        /** How long to wait for a busy printer before giving up on starting a print. */
        const val READY_TIMEOUT = 30_000L

        /** A print may take up to three times the printer's own estimate, but no less than this. */
        const val MIN_PRINT_TIMEOUT = 30_000L

        /** The printer sometimes refuses or ignores a connection right after another one; try again. */
        const val CONNECT_ATTEMPTS = 3
        const val CONNECT_RETRY_DELAY = 1_500L

        const val RAW_RGB_FORMAT = 16

        /** Chunk size to use when the printer's transfer start reply does not give one. */
        const val DEFAULT_CHUNK_SIZE = 480

        /** The SP-1 takes JPEG data with model-specific colour correction, which is not implemented. */
        val UNSUPPORTED_MODELS = setOf("SP-1")

        private val BUSY = setOf(ReturnCode.PRINTING, ReturnCode.EJECTING)
        private val PRINT_FINISHED = setOf(ReturnCode.OK, ReturnCode.FILM_EMPTY)
        private val STILL_PRINTING = setOf(ReturnCode.PRINTING, ReturnCode.HOLD)
    }

    private inner class Connection(private val socket: Socket) : AutoCloseable {
        private val input = BufferedInputStream(socket.getInputStream())
        private val output = BufferedOutputStream(socket.getOutputStream())

        /** Send a command and return the reply. Throws [PrinterException] unless the return code is OK or allowed. */
        fun send(opcode: Int, payload: ByteArray = ByteArray(0), allow: Set<Int> = emptySet()): Response {
            val frame = Frames.encodeCommand(opcode, sessionId, pin, payload)
            logger?.invoke("sending ${Opcode.name(opcode)}: ${Frames.hex(frame, 32)}")
            output.write(frame)
            output.flush()
            val reply = Frames.readFrame(input) ?: throw ProtocolException("the printer closed the connection")
            logger?.invoke("received: ${Frames.hex(reply, 48)}")
            val response = Frames.decodeResponse(reply, opcode = opcode, sessionId = sessionId)
            if (!response.ok && response.returnCode !in allow) {
                throw PrinterException(ReturnCode.describe(response.returnCode), response.returnCode)
            }
            return response
        }

        override fun close() = socket.close()
    }

    private fun connect(): Connection {
        var attempt = 1
        while (true) {
            val socket = socketFactory.createSocket()
            try {
                socket.soTimeout = timeoutMillis
                socket.connect(InetSocketAddress(host, port), timeoutMillis)
                return Connection(socket)
            } catch (e: IOException) {
                socket.close()
                if (attempt >= CONNECT_ATTEMPTS) throw e
                logger?.invoke("connection attempt $attempt failed (${e.message}), retrying")
                attempt++
                sleeper(CONNECT_RETRY_DELAY)
            }
        }
    }

    /** The printer's model, versions, battery, film and print size. */
    fun info(): PrinterInfo = connect().use { c -> readInfo(c) }

    private fun readInfo(c: Connection): PrinterInfo {
        val model = modelName(c)
        val version = Version.fromPayload(c.send(Opcode.VERSION).payload)
        val specs = Specifications.fromPayload(c.send(Opcode.SPECIFICATIONS).payload)
        val count = c.send(Opcode.PRINT_COUNT)
        return PrinterInfo(
            model = model,
            firmware = version.firmware,
            hardware = version.hardware,
            batteryLevel = count.batteryLevel,
            charging = count.charging,
            filmRemaining = count.filmRemaining,
            coverOpen = count.coverOpen,
            printCount = Payloads.printCount(count.payload),
            width = specs.width,
            height = specs.height,
        )
    }

    /** Whether a printer answers at all on this network. Does not need the right PIN. */
    fun ping(): Boolean = try {
        connect().use { c -> c.send(Opcode.VERSION, allow = setOf(ReturnCode.WRONG_PIN, ReturnCode.OTHER_CLIENT)) }
        true
    } catch (e: IOException) {
        false
    }

    /** Turn the printer off. */
    fun powerOff() {
        connect().use { c -> c.send(Opcode.POWER_OFF, ByteArray(4)) }
    }

    /**
     * Print an image and block until the print has finished.
     *
     * The [source] is asked for pixels at the size the printer reports, so the same call works
     * on an SP-2 (600x800) and an SP-3 (800x800).
     *
     * With [transferOnly] the image is sent and the printer is reset instead of printing, which
     * exercises the whole transfer without using a sheet of film.
     */
    fun print(source: ImageSource, transferOnly: Boolean = false, listener: PrintListener = PrintListener {}) {
        listener.onEvent(PrintEvent.Locking)
        setLock(true)
        try {
            doPrint(source, listener, transferOnly)
        } finally {
            try {
                setLock(false)
                listener.onEvent(PrintEvent.Unlocked)
            } catch (e: Exception) {
                logger?.invoke("could not unlock the printer: $e")
            }
        }
    }

    /** Lock the printer to this client, or release it. The printer releases the lock by itself after 60 s idle. */
    private fun setLock(locked: Boolean) {
        connect().use { c -> c.send(Opcode.LOCK, Payloads.lock(locked)) }
    }

    private fun doPrint(source: ImageSource, listener: PrintListener, transferOnly: Boolean) {
        val info = connect().use { c ->
            val info = readInfo(c)
            if (info.model in UNSUPPORTED_MODELS) throw PrinterException("the ${info.model} is not supported")
            if (info.coverOpen) throw PrinterException("the film cover is open")
            if (info.filmRemaining == 0) throw PrinterException(ReturnCode.describe(ReturnCode.FILM_EMPTY), ReturnCode.FILM_EMPTY)
            c.send(Opcode.RESET)
            info
        }
        listener.onEvent(PrintEvent.Ready(info))

        logger?.invoke("printer: ${info.model} fw ${info.firmware} battery ${info.batteryLevel}/3${if (info.charging) " charging" else ""} film ${info.filmRemaining} prints ${info.printCount}")
        val data = RawImage.encode(source.render(info.width, info.height), info.width, info.height)
        logger?.invoke("sending a ${info.width}x${info.height} image (${data.size} bytes) to the ${info.model}")
        connect().use { c -> transfer(c, data, listener) }
        val transferEnd = System.nanoTime()

        listener.onEvent(PrintEvent.WaitingForPrinter)
        if (transferOnly) {
            connect().use { c ->
                sendWhenIdle(c, Opcode.VERSION, checkEjecting = true)
                c.send(Opcode.RESET)
            }
            logger?.invoke("transfer-only run: the image was sent and the printer reset, nothing printed")
            listener.onEvent(PrintEvent.TransferOnlyFinished)
            return
        }
        val reply = connect().use { c ->
            sendWhenIdle(c, Opcode.VERSION, checkEjecting = true)
            val elapsed = (System.nanoTime() - transferEnd) / 1_000_000
            if (elapsed < TRANSFER_SETTLE_TIME) sleeper(TRANSFER_SETTLE_TIME - elapsed)
            sendWhenIdle(c, Opcode.PRINT_START)
        }
        val estimate = if (reply.payload.size >= 4) Payloads.printEstimateTenths(reply.payload) / 10.0 else 0.0
        listener.onEvent(PrintEvent.Printing(estimate))
        val last = waitForPrint(estimate)
        listener.onEvent(PrintEvent.Finished(last.filmRemaining))
    }

    private fun transfer(c: Connection, data: ByteArray, listener: PrintListener) {
        val start = c.send(Opcode.TRANSFER_START, Payloads.transferStart(RAW_RGB_FORMAT, 0, data.size))
        val chunkSize = if (start.payload.size >= 4) Payloads.chunkSize(start.payload) else DEFAULT_CHUNK_SIZE
        if (chunkSize == 0) throw ProtocolException("the printer asked for a chunk size of 0")
        listener.onEvent(PrintEvent.Transferring(0, data.size))
        var index = 0
        var offset = 0
        while (offset < data.size) {
            // Every chunk is sent at full size. The image length in the transfer start tells the
            // printer where the zero padding of the last chunk begins.
            c.send(Opcode.SEND_CHUNK, Payloads.chunk(index, data, offset, chunkSize))
            offset = minOf(offset + chunkSize, data.size)
            index++
            listener.onEvent(PrintEvent.Transferring(offset, data.size))
        }
        c.send(Opcode.TRANSFER_END)
    }

    /** Send a command, repeating it while the printer reports that it is busy. */
    private fun sendWhenIdle(c: Connection, opcode: Int, checkEjecting: Boolean = false): Response {
        val deadline = System.nanoTime() + READY_TIMEOUT * 1_000_000
        while (true) {
            val response = c.send(opcode, allow = BUSY)
            if (response.returnCode !in BUSY && !(checkEjecting && response.ejecting)) return response
            if (System.nanoTime() >= deadline) throw PrinterException("the printer stayed busy", response.returnCode)
            sleeper(POLL_INTERVAL)
        }
    }

    private fun waitForPrint(estimateSeconds: Double): Response {
        val timeout = maxOf((3 * estimateSeconds * 1000).toLong(), MIN_PRINT_TIMEOUT)
        val deadline = System.nanoTime() + timeout * 1_000_000
        while (true) {
            sleeper(POLL_INTERVAL)
            val response = connect().use { c -> c.send(Opcode.PRINT_STATUS, allow = STILL_PRINTING + PRINT_FINISHED) }
            if (response.returnCode in PRINT_FINISHED) return response
            if (System.nanoTime() >= deadline) throw PrintTimeoutException("the printer did not report that the print finished")
        }
    }

    private fun modelName(c: Connection): String =
        String(c.send(Opcode.MODEL_NAME).payload, Charsets.US_ASCII).trimEnd('\u0000')
}
