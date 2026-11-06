package instax

import java.io.EOFException
import java.io.InputStream
import java.nio.ByteBuffer

/**
 * Frame format of the Instax SHARE Wi-Fi protocol (SP-2 / SP-3).
 *
 * Every exchange is one command frame from the client and one response frame from the printer.
 * All integers are big-endian.
 *
 * Command:  0x24, opcode, length (u16), session id (u32), PIN (u16), 0x0000, payload, checksum (u16), 0x0D 0x0A
 * Response: 0x2A, opcode, length (u16), session id (u32), 4 unknown bytes, return code, 1 unknown byte,
 *           eject flags, status, payload, checksum (u16), 0x0D 0x0A
 *
 * The length counts the whole frame. The checksum is the bitwise NOT of the 16-bit sum of every byte before it.
 */
object Opcode {
    const val SPECIFICATIONS = 0x4F
    const val RESET = 0x50
    const val TRANSFER_START = 0x51
    const val SEND_CHUNK = 0x52
    const val TRANSFER_END = 0x53
    const val PRINT_START = 0xB0
    const val LOCK = 0xB3
    const val POWER_OFF = 0xB4
    const val CHANGE_PIN = 0xB6
    const val VERSION = 0xC0
    const val PRINT_COUNT = 0xC1
    const val MODEL_NAME = 0xC2
    const val PRINT_STATUS = 0xC3
    const val FUNCTION_VERSION = 0xC4

    fun name(opcode: Int): String = when (opcode) {
        SPECIFICATIONS -> "SPECIFICATIONS"
        RESET -> "RESET"
        TRANSFER_START -> "TRANSFER_START"
        SEND_CHUNK -> "SEND_CHUNK"
        TRANSFER_END -> "TRANSFER_END"
        PRINT_START -> "PRINT_START"
        LOCK -> "LOCK"
        POWER_OFF -> "POWER_OFF"
        CHANGE_PIN -> "CHANGE_PIN"
        VERSION -> "VERSION"
        PRINT_COUNT -> "PRINT_COUNT"
        MODEL_NAME -> "MODEL_NAME"
        PRINT_STATUS -> "PRINT_STATUS"
        FUNCTION_VERSION -> "FUNCTION_VERSION"
        else -> "0x%02X".format(opcode)
    }
}

object ReturnCode {
    const val OK = 0x00
    const val HOLD = 0x7F
    const val OTHER_CLIENT = 0xA0
    const val NOT_IMAGE_DATA = 0xA1
    const val BATTERY_EMPTY = 0xA2
    const val PRINTING = 0xA3
    const val EJECTING = 0xA4
    const val TESTING = 0xA5
    const val CHARGING = 0xB4
    const val CONNECT_ERROR = 0xE0
    const val FRAME_ERROR_1 = 0xF0
    const val FRAME_ERROR_2 = 0xF1
    const val FRAME_ERROR_3 = 0xF2
    const val FRAME_ERROR_4 = 0xF3
    const val FILM_EMPTY = 0xF4
    const val CAM_POINT = 0xF5
    const val MOTOR = 0xF6
    const val WRONG_PIN = 0xF7
    const val PI_SENSOR = 0xF8

    /** A readable message for a return code, including unknown ones. */
    fun describe(code: Int): String = when (code) {
        OK -> "ok"
        HOLD -> "the printer is busy"
        OTHER_CLIENT -> "another device is using the printer"
        NOT_IMAGE_DATA -> "the printer rejected the image data"
        BATTERY_EMPTY -> "the battery is empty"
        PRINTING -> "the printer is printing"
        EJECTING -> "the printer is ejecting a print"
        TESTING -> "the printer is in test mode"
        CHARGING -> "the printer is charging"
        CONNECT_ERROR -> "the printer reported a connection error"
        FRAME_ERROR_1, FRAME_ERROR_2, FRAME_ERROR_3, FRAME_ERROR_4 -> "the printer could not read the command"
        FILM_EMPTY -> "the printer is out of film"
        CAM_POINT -> "the print mechanism reported an error"
        MOTOR -> "the motor reported an error"
        WRONG_PIN -> "the PIN is wrong"
        PI_SENSOR -> "a sensor reported an error"
        else -> "the printer returned error code 0x%02X".format(code)
    }
}

open class InstaxException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The frame could not be read or was malformed. */
class ProtocolException(message: String, cause: Throwable? = null) : InstaxException(message, cause)

/** The printer answered with an error return code. */
class PrinterException(message: String, val returnCode: Int = -1) : InstaxException(message)

/** The print did not finish in time. */
class PrintTimeoutException(message: String) : InstaxException(message)

/** The caller cancelled before the printer started exposing the film. */
class PrintCancelledException : InstaxException("the print was cancelled")

data class Command(val opcode: Int, val sessionId: Int, val pin: Int, val payload: ByteArray) {
    override fun equals(other: Any?): Boolean =
        other is Command && opcode == other.opcode && sessionId == other.sessionId && pin == other.pin &&
            payload.contentEquals(other.payload)

    override fun hashCode(): Int = (opcode * 31 + sessionId) * 31 + payload.contentHashCode()
}

data class Response(
    val opcode: Int,
    val sessionId: Int,
    val returnCode: Int,
    val ejecting: Boolean,
    val coverOpen: Boolean,
    /** Raw 3-bit battery state. See [batteryLevel] and [charging]. */
    val battery: Int,
    /** Raw film count. Values above 10 mean the printer is out of film. */
    val film: Int,
    val payload: ByteArray,
) {
    /** Charge level from 0 to 3. */
    val batteryLevel: Int get() = battery and 0x03

    /** Whether the printer is charging over USB. */
    val charging: Boolean get() = battery and 0x04 != 0

    val filmEmpty: Boolean get() = film > 10

    val filmRemaining: Int get() = if (filmEmpty) 0 else film

    val ok: Boolean get() = returnCode == ReturnCode.OK

    override fun equals(other: Any?): Boolean =
        other is Response && opcode == other.opcode && sessionId == other.sessionId &&
            returnCode == other.returnCode && ejecting == other.ejecting && coverOpen == other.coverOpen &&
            battery == other.battery && film == other.film && payload.contentEquals(other.payload)

    override fun hashCode(): Int = (opcode * 31 + sessionId) * 31 + payload.contentHashCode()
}

object Frames {
    const val COMMAND_START = 0x24
    const val RESPONSE_START = 0x2A
    const val COMMAND_HEADER_SIZE = 12
    const val RESPONSE_HEADER_SIZE = 16
    const val TRAILER_SIZE = 4
    const val MAX_FRAME_SIZE = 0xFFFF

    private val EMPTY = ByteArray(0)

    fun checksum(data: ByteArray, length: Int = data.size): Int {
        var sum = 0
        for (i in 0 until length) sum += data[i].toInt() and 0xFF
        return sum.inv() and 0xFFFF
    }

    private fun frameLength(payloadSize: Int, headerSize: Int): Int {
        val length = headerSize + payloadSize + TRAILER_SIZE
        require(length <= MAX_FRAME_SIZE) { "frame of $length bytes exceeds the maximum of $MAX_FRAME_SIZE" }
        return length
    }

    private fun finish(buffer: ByteBuffer): ByteArray {
        val array = buffer.array()
        val bodyLength = array.size - TRAILER_SIZE
        buffer.position(bodyLength)
        buffer.putShort(checksum(array, bodyLength).toShort())
        buffer.put(0x0D).put(0x0A)
        return array
    }

    /** Build a command frame. */
    fun encodeCommand(opcode: Int, sessionId: Int, pin: Int, payload: ByteArray = EMPTY): ByteArray {
        val length = frameLength(payload.size, COMMAND_HEADER_SIZE)
        val buffer = ByteBuffer.allocate(length)
        buffer.put(COMMAND_START.toByte()).put(opcode.toByte()).putShort(length.toShort())
        buffer.putInt(sessionId).putShort(pin.toShort()).putShort(0)
        buffer.put(payload)
        return finish(buffer)
    }

    /** Build a response frame. The status arguments describe the printer's state. */
    fun encodeResponse(
        opcode: Int,
        sessionId: Int,
        payload: ByteArray = EMPTY,
        returnCode: Int = ReturnCode.OK,
        ejecting: Boolean = false,
        coverOpen: Boolean = false,
        battery: Int = 0,
        film: Int = 0,
    ): ByteArray {
        val length = frameLength(payload.size, RESPONSE_HEADER_SIZE)
        val ejectFlags = if (ejecting) 0x04 else 0
        val status = (if (coverOpen) 0x80 else 0) or ((battery and 0x07) shl 4) or (film and 0x0F)
        val buffer = ByteBuffer.allocate(length)
        buffer.put(RESPONSE_START.toByte()).put(opcode.toByte()).putShort(length.toShort())
        buffer.putInt(sessionId).putInt(0)
        buffer.put(returnCode.toByte()).put(0).put(ejectFlags.toByte()).put(status.toByte())
        buffer.put(payload)
        return finish(buffer)
    }

    /** The total frame length from the first four bytes of a frame. */
    fun frameLength(header: ByteArray): Int = ((header[2].toInt() and 0xFF) shl 8) or (header[3].toInt() and 0xFF)

    private fun check(data: ByteArray, start: Int, headerSize: Int) {
        if (data.size < headerSize + TRAILER_SIZE) throw ProtocolException("frame of ${data.size} bytes is too short")
        val first = data[0].toInt() and 0xFF
        if (first != start) throw ProtocolException("unexpected start byte 0x%02X".format(first))
        val declared = frameLength(data)
        if (declared != data.size) throw ProtocolException("length field says $declared bytes but the frame has ${data.size}")
        if (data[data.size - 2] != 0x0D.toByte() || data[data.size - 1] != 0x0A.toByte()) {
            throw ProtocolException("frame does not end with the expected trailer")
        }
        val bodyLength = data.size - TRAILER_SIZE
        val declaredChecksum = ((data[bodyLength].toInt() and 0xFF) shl 8) or (data[bodyLength + 1].toInt() and 0xFF)
        if (declaredChecksum != checksum(data, bodyLength)) throw ProtocolException("checksum mismatch")
    }

    /** Decode and validate a command frame. */
    fun decodeCommand(data: ByteArray): Command {
        check(data, COMMAND_START, COMMAND_HEADER_SIZE)
        val buffer = ByteBuffer.wrap(data)
        val opcode = buffer.get(1).toInt() and 0xFF
        val sessionId = buffer.getInt(4)
        val pin = buffer.getShort(8).toInt() and 0xFFFF
        return Command(opcode, sessionId, pin, data.copyOfRange(COMMAND_HEADER_SIZE, data.size - TRAILER_SIZE))
    }

    /** Decode and validate a response frame, optionally checking it answers the expected command. */
    fun decodeResponse(data: ByteArray, opcode: Int? = null, sessionId: Int? = null): Response {
        check(data, RESPONSE_START, RESPONSE_HEADER_SIZE)
        val buffer = ByteBuffer.wrap(data)
        val responseOpcode = buffer.get(1).toInt() and 0xFF
        val responseSession = buffer.getInt(4)
        val returnCode = buffer.get(12).toInt() and 0xFF
        val ejectFlags = buffer.get(14).toInt() and 0xFF
        val status = buffer.get(15).toInt() and 0xFF
        if (opcode != null && responseOpcode != opcode) {
            throw ProtocolException("expected a reply to %s but got %s".format(Opcode.name(opcode), Opcode.name(responseOpcode)))
        }
        if (sessionId != null && responseSession != sessionId) {
            throw ProtocolException("expected session id 0x%08X but got 0x%08X".format(sessionId, responseSession))
        }
        return Response(
            opcode = responseOpcode,
            sessionId = responseSession,
            returnCode = returnCode,
            ejecting = ejectFlags and 0x04 != 0,
            coverOpen = status and 0x80 != 0,
            battery = (status shr 4) and 0x07,
            film = status and 0x0F,
            payload = data.copyOfRange(RESPONSE_HEADER_SIZE, data.size - TRAILER_SIZE),
        )
    }

    /**
     * Read one frame from a stream. Returns null if the peer closed the connection before sending anything.
     */
    fun readFrame(input: InputStream): ByteArray? {
        val header = ByteArray(4)
        val got = readFully(input, header, 0, 4)
        if (got == 0) return null
        if (got < 4) throw ProtocolException("connection closed in the middle of a frame")
        val length = frameLength(header)
        if (length < 4) throw ProtocolException("length field of $length bytes is too short")
        val frame = header.copyOf(length)
        if (readFully(input, frame, 4, length - 4) < length - 4) {
            throw ProtocolException("connection closed in the middle of a frame")
        }
        return frame
    }

    private fun readFully(input: InputStream, target: ByteArray, offset: Int, size: Int): Int {
        var read = 0
        while (read < size) {
            val n = try {
                input.read(target, offset + read, size - read)
            } catch (e: EOFException) {
                -1
            }
            if (n < 0) break
            read += n
        }
        return read
    }

    fun hex(data: ByteArray, limit: Int = data.size): String =
        data.take(limit).joinToString(" ") { "%02x".format(it) } + if (data.size > limit) " …" else ""
}

private fun ByteArray.u16(offset: Int): Int {
    if (offset + 2 > size) throw ProtocolException("payload of $size bytes is too short")
    return ((this[offset].toInt() and 0xFF) shl 8) or (this[offset + 1].toInt() and 0xFF)
}

private fun ByteArray.u32(offset: Int): Long {
    if (offset + 4 > size) throw ProtocolException("payload of $size bytes is too short")
    return (u16(offset).toLong() shl 16) or u16(offset + 2).toLong()
}

/** The image size and transfer limits the printer reports. */
data class Specifications(val width: Int, val height: Int, val colours: Int, val chunkSize: Int) {
    companion object {
        fun fromPayload(payload: ByteArray) =
            Specifications(payload.u16(0), payload.u16(2), payload.u16(4), payload.u16(12))
    }
}

/** Firmware and hardware versions, formatted as two hex bytes such as `01.13`. */
data class Version(val firmware: String, val hardware: String) {
    companion object {
        private fun format(value: Int) = "%02X.%02X".format(value shr 8, value and 0xFF)

        fun fromPayload(payload: ByteArray) = Version(format(payload.u16(2)), format(payload.u16(4)))
    }
}

object Payloads {
    /** The payload that locks the printer to this client or releases it. */
    fun lock(locked: Boolean): ByteArray = byteArrayOf(if (locked) 1 else 0, 0, 0, 0)

    /** The payload that announces an image transfer. */
    fun transferStart(imageFormat: Int, options: Int, length: Int): ByteArray =
        ByteBuffer.allocate(12).put(imageFormat.toByte()).put(options.toByte()).putInt(length).array()

    fun chunk(index: Int, data: ByteArray, offset: Int, chunkSize: Int): ByteArray {
        val out = ByteBuffer.allocate(4 + chunkSize).putInt(index)
        val n = minOf(chunkSize, data.size - offset)
        out.put(data, offset, n) // the remainder stays zero-padded
        return out.array()
    }

    fun chunkIndex(payload: ByteArray): Long = payload.u32(0)

    fun transferLength(payload: ByteArray): Long = payload.u32(2)

    fun chunkSize(payload: ByteArray): Int = payload.u16(2)

    fun printCount(payload: ByteArray): Long = payload.u32(0)

    fun printEstimateTenths(payload: ByteArray): Long = payload.u32(0)
}
