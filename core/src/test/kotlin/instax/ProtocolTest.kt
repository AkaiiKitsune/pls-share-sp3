package instax

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream

/** Frames captured from an SP-2 by the original jpwsutton/instax_api project. */
object Captures {
    val commands = mapOf(
        "model name" to "24c2 0010 0b8d c2b4 0457 0000 fca0 0d0a",
        "function version" to "24c4 0014 4e40 684c 0457 0000 0000 0008 fd5e 0d0a",
        "lock" to "24b3 0014 9619 02df 0457 0000 0100 0000 fd28 0d0a",
        "reset" to "2450 0010 96c9 aada 0457 0000 fc3d 0d0a",
        "transfer start" to "2451 001c 9b60 d511 0457 0000 1000 0015 f900 0000 0000 0000 fc14 0d0a",
        "transfer end" to "2453 0010 c9a9 b71e 0457 0000 fcd6 0d0a",
        "print start" to "24b0 0010 f776 ecbe 0457 0000 fba9 0d0a",
        "print status" to "24c3 0010 f130 d7cc 0457 0000 fbe9 0d0a",
    )
    val responses = mapOf(
        "specifications" to "2a4f 0030 e759 eede 0000 0000 0000 0027 0258 0320 0100 000a 0000 0000 ea60 1000" +
            " 0000 0000 0000 0000 0000 0000 fa41 0d0a",
        "version" to "2ac0 001c e759 eede 0000 0000 0000 0027 0101 0113 0000 0000 fbb0 0d0a",
        "print count" to "2ac1 0024 e759 eede 0000 0000 0000 0027 0000 0003 00f3 c048 0000 1645 001e 0000 f946 0d0a",
        "lock" to "2ab3 0014 75b8 bd8e 0000 0000 0000 003a fc5c 0d0a",
        "reset" to "2a50 0014 75b8 bd8e 0000 0000 0000 003a fcbf 0d0a",
        "send chunk" to "2a52 0018 75b8 bd8e 0000 0000 0000 003a 0000 0014 fca5 0d0a",
        "transfer end" to "2a53 0014 75b8 bd8e 0000 0000 0000 003a fcbc 0d0a",
        "print start" to "2ab0 0018 f130 d7cc 0000 0000 0000 0036 0000 0064 fbaf 0d0a",
        "print status busy" to "2ac3 0014 f130 d7cc 0000 0000 7f00 0436 fb81 0d0a",
        "print status done" to "2ac3 0014 f130 d7cc 0000 0000 0000 0035 fc05 0d0a",
    )

    fun frame(hex: String): ByteArray = hex.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}

class ProtocolTest {
    @Test
    fun `commands round-trip through decode and encode`() {
        for ((name, hex) in Captures.commands) {
            val frame = Captures.frame(hex)
            val command = Frames.decodeCommand(frame)
            assertEquals(1111, command.pin, name)
            assertArrayEquals(frame, Frames.encodeCommand(command.opcode, command.sessionId, command.pin, command.payload), name)
        }
    }

    @Test
    fun `responses round-trip through decode and encode`() {
        for ((name, hex) in Captures.responses) {
            val frame = Captures.frame(hex)
            val r = Frames.decodeResponse(frame)
            val encoded = Frames.encodeResponse(r.opcode, r.sessionId, r.payload, r.returnCode, r.ejecting, r.coverOpen, r.battery, r.film)
            assertArrayEquals(frame, encoded, name)
        }
    }

    @Test
    fun `decode command`() {
        val command = Frames.decodeCommand(Captures.frame(Captures.commands.getValue("model name")))
        assertEquals(Opcode.MODEL_NAME, command.opcode)
        assertEquals(0x0B8DC2B4, command.sessionId)
        assertEquals(0, command.payload.size)
    }

    @Test
    fun `lock and transfer start payloads`() {
        assertArrayEquals(Payloads.lock(true), Frames.decodeCommand(Captures.frame(Captures.commands.getValue("lock"))).payload)
        assertArrayEquals(ByteArray(4), Payloads.lock(false))
        val transfer = Frames.decodeCommand(Captures.frame(Captures.commands.getValue("transfer start")))
        assertArrayEquals(Payloads.transferStart(16, 0, 1_440_000), transfer.payload)
        assertEquals(1_440_000L, Payloads.transferLength(transfer.payload))
    }

    @Test
    fun `status bits`() {
        val busy = Frames.decodeResponse(Captures.frame(Captures.responses.getValue("print status busy")))
        assertEquals(Opcode.PRINT_STATUS, busy.opcode)
        assertEquals(ReturnCode.HOLD, busy.returnCode)
        assertTrue(busy.ejecting)
        assertFalse(busy.coverOpen)
        assertEquals(3, busy.battery)
        assertEquals(6, busy.film)

        val done = Frames.decodeResponse(Captures.frame(Captures.responses.getValue("print status done")))
        assertEquals(ReturnCode.OK, done.returnCode)
        assertFalse(done.ejecting)
        assertEquals(5, done.film)
    }

    @Test
    fun `film count and battery`() {
        for ((film, remaining, empty) in listOf(Triple(0, 0, false), Triple(10, 10, false), Triple(11, 0, true), Triple(15, 0, true))) {
            val r = Frames.decodeResponse(Frames.encodeResponse(Opcode.VERSION, 1, film = film))
            assertEquals(remaining, r.filmRemaining)
            assertEquals(empty, r.filmEmpty)
        }
        for ((battery, level, charging) in listOf(Triple(0, 0, false), Triple(3, 3, false), Triple(4, 0, true), Triple(7, 3, true))) {
            val r = Frames.decodeResponse(Frames.encodeResponse(Opcode.VERSION, 1, battery = battery))
            assertEquals(level, r.batteryLevel)
            assertEquals(charging, r.charging)
        }
        val all = Frames.decodeResponse(Frames.encodeResponse(Opcode.VERSION, 1, ejecting = true, coverOpen = true, battery = 7, film = 15))
        assertEquals(listOf(true, true, 7, 15), listOf(all.ejecting, all.coverOpen, all.battery, all.film))
    }

    @Test
    fun `specifications and version`() {
        val specs = Specifications.fromPayload(Frames.decodeResponse(Captures.frame(Captures.responses.getValue("specifications"))).payload)
        assertEquals(Specifications(600, 800, 256, 60000), specs)
        val version = Version.fromPayload(Frames.decodeResponse(Captures.frame(Captures.responses.getValue("version"))).payload)
        assertEquals(Version("01.13", "00.00"), version)
        assertThrows(ProtocolException::class.java) { Specifications.fromPayload(ByteArray(3)) }
        assertThrows(ProtocolException::class.java) { Version.fromPayload(ByteArray(3)) }
    }

    @Test
    fun `malformed frames are rejected`() {
        val good = Captures.frame(Captures.responses.getValue("lock"))
        assertThrows(ProtocolException::class.java) { Frames.decodeResponse(good.copyOf(5)) }
        assertThrows(ProtocolException::class.java) { Frames.decodeCommand(good) }
        assertThrows(ProtocolException::class.java) { Frames.decodeResponse(good.copyOf().also { it[it.size - 3] = 0 }) }
        assertThrows(ProtocolException::class.java) { Frames.decodeResponse(good.copyOf().also { it[it.size - 1] = 0 }) }
        assertThrows(ProtocolException::class.java) { Frames.decodeResponse(good.copyOf().also { it[3] = 0x15 }) }
        assertThrows(ProtocolException::class.java) { Frames.decodeResponse(good, opcode = Opcode.VERSION) }
        assertThrows(ProtocolException::class.java) { Frames.decodeResponse(good, sessionId = 1) }
        assertThrows(IllegalArgumentException::class.java) { Frames.encodeCommand(Opcode.SEND_CHUNK, 1, 1111, ByteArray(0xFFF0)) }
    }

    @Test
    fun `read frame from a stream`() {
        val a = Captures.frame(Captures.responses.getValue("lock"))
        val b = Captures.frame(Captures.responses.getValue("print start"))
        val input = ByteArrayInputStream(a + b + a.copyOf(6))
        assertArrayEquals(a, Frames.readFrame(input))
        assertArrayEquals(b, Frames.readFrame(input))
        assertThrows(ProtocolException::class.java) { Frames.readFrame(input) }
        assertEquals(null, Frames.readFrame(ByteArrayInputStream(ByteArray(0))))
    }

    @Test
    fun `chunk payload is zero padded`() {
        val data = byteArrayOf(1, 2, 3, 4, 5)
        val chunk = Payloads.chunk(7, data, 3, 4)
        assertArrayEquals(byteArrayOf(0, 0, 0, 7, 4, 5, 0, 0), chunk)
        assertEquals(7L, Payloads.chunkIndex(chunk))
    }
}
