package instax

import java.io.BufferedInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A fake printer for tests and for trying the client without hardware.
 *
 * It keeps every image it receives in [images] and every opcode in [opcodes]. Call [start] to
 * serve on a background thread and [close] to stop.
 */
class Simulator(
    val host: String = "127.0.0.1",
    port: Int = 0,
    val model: String = "SP-3",
    val width: Int = 800,
    val height: Int = 800,
    val chunkSize: Int = 60000,
    val reportChunkSize: Boolean = true,
    var pin: Int = 1111,
    @Volatile var battery: Int = 3,
    @Volatile var film: Int = 10,
    @Volatile var coverOpen: Boolean = false,
    @Volatile var printCount: Long = 20,
    /** Print estimate in tenths of a second. */
    var printTime: Int = 100,
    /** How many status polls report printing before [printResult]. */
    var printPolls: Int = 1,
    var printResult: Int = ReturnCode.OK,
    /** How many print start commands are refused as busy. */
    var startBusy: Int = 0,
    var lockTimeoutMillis: Long = 60_000,
    private val onImage: ((ByteArray) -> Unit)? = null,
) : AutoCloseable {
    private val server = ServerSocket().apply {
        reuseAddress = true
        bind(InetSocketAddress(host, port))
    }
    val port: Int get() = server.localPort

    val images = CopyOnWriteArrayList<ByteArray>()
    val opcodes = CopyOnWriteArrayList<Int>()
    val lockHistory = CopyOnWriteArrayList<Boolean>()

    private val lock = Any()
    private var expectedLength = 0
    private var received = java.io.ByteArrayOutputStream()
    private var pollsLeft = 0
    private var printPending = false
    private var lockHolder: Int? = null
    private var lockExpiry = 0L
    private var thread: Thread? = null

    fun start(): Simulator {
        thread = Thread({ serve() }, "instax-simulator").apply { isDaemon = true; start() }
        return this
    }

    fun serve() {
        try {
            while (!server.isClosed) {
                val socket = server.accept()
                Thread({ handle(socket) }, "instax-simulator-client").apply { isDaemon = true; start() }
            }
        } catch (e: IOException) {
            // closed
        }
    }

    private fun handle(socket: Socket) {
        socket.use {
            try {
                val input = BufferedInputStream(socket.getInputStream())
                val output = socket.getOutputStream()
                while (true) {
                    val frame = Frames.readFrame(input) ?: break
                    output.write(respond(frame))
                    output.flush()
                }
            } catch (e: Exception) {
                // dropped connection
            }
        }
    }

    /** The response frame for a command frame. */
    fun respond(frame: ByteArray): ByteArray {
        val command = Frames.decodeCommand(frame)
        val (returnCode, payload) = synchronized(lock) {
            opcodes += command.opcode
            when {
                command.pin != pin -> ReturnCode.WRONG_PIN to ByteArray(0)
                lockedByOther(command.sessionId) -> ReturnCode.OTHER_CLIENT to ByteArray(0)
                else -> handle(command.opcode, command.sessionId, command.payload)
            }
        }
        return Frames.encodeResponse(
            command.opcode, command.sessionId, payload,
            returnCode = returnCode, coverOpen = coverOpen, battery = battery, film = film,
        )
    }

    private fun lockedByOther(sessionId: Int): Boolean {
        val now = System.currentTimeMillis()
        if (lockHolder != null && now >= lockExpiry) lockHolder = null
        val holder = lockHolder ?: return false
        if (holder != sessionId) return true
        lockExpiry = now + lockTimeoutMillis
        return false
    }

    private fun handle(opcode: Int, sessionId: Int, payload: ByteArray): Pair<Int, ByteArray> {
        val ok = ReturnCode.OK
        return when (opcode) {
            Opcode.MODEL_NAME -> ok to model.toByteArray(Charsets.US_ASCII)
            Opcode.VERSION -> ok to ByteBuffer.allocate(8).putShort(0x0101).putShort(0x0113).putShort(0).array()
            Opcode.SPECIFICATIONS -> ok to ByteBuffer.allocate(32)
                .putShort(width.toShort()).putShort(height.toShort()).putShort(256).putShort(10)
                .putInt(0).putShort(chunkSize.toShort()).put(16).put(0).putInt(0).array()
            Opcode.PRINT_COUNT -> ok to ByteBuffer.allocate(16).putInt(printCount.toInt()).array()
            Opcode.LOCK -> {
                val locked = payload.isNotEmpty() && payload[0].toInt() == 1
                lockHistory += locked
                lockHolder = if (locked) sessionId else null
                lockExpiry = System.currentTimeMillis() + lockTimeoutMillis
                ok to ByteArray(0)
            }
            Opcode.RESET -> ok to ByteArray(0)
            Opcode.TRANSFER_START -> {
                expectedLength = Payloads.transferLength(payload).toInt()
                received = java.io.ByteArrayOutputStream(expectedLength)
                ok to if (reportChunkSize) ByteBuffer.allocate(4).putShort(0).putShort(chunkSize.toShort()).array() else ByteArray(0)
            }
            Opcode.SEND_CHUNK -> {
                received.write(payload, 4, payload.size - 4)
                ok to payload.copyOf(4)
            }
            Opcode.TRANSFER_END -> {
                val image = received.toByteArray().copyOf(expectedLength)
                images += image
                onImage?.invoke(image)
                ok to ByteArray(0)
            }
            Opcode.PRINT_START -> {
                if (startBusy > 0) {
                    startBusy--
                    ReturnCode.PRINTING to ByteArray(0)
                } else {
                    pollsLeft = printPolls
                    printPending = true
                    ok to ByteBuffer.allocate(4).putInt(printTime).array()
                }
            }
            Opcode.PRINT_STATUS -> {
                if (pollsLeft > 0) {
                    pollsLeft--
                    ReturnCode.HOLD to ByteArray(0)
                } else {
                    if (printPending && printResult == ReturnCode.OK) {
                        printPending = false
                        printCount++
                        if (film in 1..10) film--
                    }
                    printResult to ByteArray(0)
                }
            }
            Opcode.POWER_OFF -> ok to ByteArray(0)
            else -> ReturnCode.FRAME_ERROR_1 to ByteArray(0)
        }
    }

    override fun close() {
        server.close()
    }
}
