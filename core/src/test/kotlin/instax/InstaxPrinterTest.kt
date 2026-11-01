package instax

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class InstaxPrinterTest {
    private val simulator = Simulator(port = 0, printPolls = 2).start()

    private fun printer(pin: Int = 1111, sessionId: Int = 42) =
        InstaxPrinter("127.0.0.1", simulator.port, pin, sessionId = sessionId, sleeper = {})

    private val gradient = ImageSource { w, h -> IntArray(w * h) { i -> (0xFF shl 24) or ((i % w) * 255 / (w - 1) shl 16) or ((i / w) * 255 / (h - 1)) } }

    @AfterEach
    fun stop() = simulator.close()

    @Test
    fun `info reports what the simulator says`() {
        val info = printer().info()
        assertEquals("SP-3", info.model)
        assertEquals("01.13", info.firmware)
        assertEquals(3, info.batteryLevel)
        assertEquals(10, info.filmRemaining)
        assertEquals(20L, info.printCount)
        assertEquals(800 to 800, info.width to info.height)
    }

    @Test
    fun `print sends the image in the right sequence`() {
        val events = mutableListOf<PrintEvent>()
        printer().print(gradient) { events += it }

        assertEquals(1, simulator.images.size)
        val expected = RawImage.encode(gradient.render(800, 800), 800, 800)
        assertArrayEquals(expected, simulator.images[0])
        assertEquals(listOf(true, false), simulator.lockHistory)
        assertEquals(9, simulator.film)
        assertEquals(21L, simulator.printCount)

        val ops = simulator.opcodes
        assertEquals(Opcode.LOCK, ops.first())
        assertEquals(Opcode.LOCK, ops.last())
        assertEquals(32, ops.count { it == Opcode.SEND_CHUNK })
        assertEquals(2 + 1, ops.count { it == Opcode.PRINT_STATUS })
        assertTrue(ops.indexOf(Opcode.TRANSFER_START) < ops.indexOf(Opcode.TRANSFER_END))
        assertTrue(ops.indexOf(Opcode.TRANSFER_END) < ops.indexOf(Opcode.PRINT_START))

        assertEquals(PrintEvent.Locking, events.first())
        assertTrue(events.any { it is PrintEvent.Ready && it.info.model == "SP-3" })
        assertTrue(events.any { it is PrintEvent.Transferring && it.sentBytes == 1_920_000 })
        assertTrue(events.any { it is PrintEvent.Printing && it.estimatedSeconds == 10.0 })
        assertTrue(events.any { it is PrintEvent.Finished && it.filmRemaining == 9 })
        assertEquals(PrintEvent.Unlocked, events.last())
    }

    @Test
    fun `wrong pin is reported`() {
        val e = assertThrows(PrinterException::class.java) { printer(pin = 1234).info() }
        assertEquals(ReturnCode.WRONG_PIN, e.returnCode)
        assertEquals("the PIN is wrong", e.message)
        assertTrue(printer(pin = 1234).ping())
    }

    @Test
    fun `another client holding the lock is reported and nothing is printed`() {
        val first = printer(sessionId = 1)
        val other = printer(sessionId = 2)
        // Take the lock with session 1, then try printing with session 2.
        val holdMethod = InstaxPrinter::class.java.getDeclaredMethod("setLock", Boolean::class.java).apply { isAccessible = true }
        holdMethod.invoke(first, true)
        val e = assertThrows(PrinterException::class.java) { other.print(gradient) }
        assertEquals(ReturnCode.OTHER_CLIENT, e.returnCode)
        assertTrue(simulator.images.isEmpty())
        holdMethod.invoke(first, false)
        other.print(gradient)
        assertEquals(1, simulator.images.size)
    }

    @Test
    fun `empty film and open cover stop before the transfer, and the lock is released`() {
        simulator.film = 11
        val e = assertThrows(PrinterException::class.java) { printer().print(gradient) }
        assertEquals(ReturnCode.FILM_EMPTY, e.returnCode)
        assertFalse(simulator.opcodes.contains(Opcode.TRANSFER_START))
        assertEquals(listOf(true, false), simulator.lockHistory)

        simulator.film = 5
        simulator.coverOpen = true
        assertEquals("the film cover is open", assertThrows(PrinterException::class.java) { printer().print(gradient) }.message)
    }

    @Test
    fun `busy printer is retried`() {
        simulator.startBusy = 2
        printer().print(gradient)
        assertEquals(3, simulator.opcodes.count { it == Opcode.PRINT_START })
    }

    @Test
    fun `print that never finishes times out`() {
        simulator.printPolls = 1_000_000
        var now = 0L
        val printer = InstaxPrinter("127.0.0.1", simulator.port, sessionId = 7, sleeper = { now += it })
        assertThrows(PrintTimeoutException::class.java) { printer.print(gradient) }
        assertEquals(listOf(true, false), simulator.lockHistory)
    }

    @Test
    fun `sp1 is refused`() {
        simulator.close()
        Simulator(port = 0, model = "SP-1").start().use { sp1 ->
            val e = assertThrows(PrinterException::class.java) {
                InstaxPrinter("127.0.0.1", sp1.port, sessionId = 3, sleeper = {}).print(gradient)
            }
            assertEquals("the SP-1 is not supported", e.message)
        }
    }

    @Test
    fun `nothing listening is a network error`() {
        simulator.close()
        var sleeps = 0
        val printer = InstaxPrinter("127.0.0.1", simulator.port, timeoutMillis = 500, sleeper = { sleeps++ })
        assertFalse(printer.ping())
        assertThrows(java.io.IOException::class.java) { printer.info() }
        assertEquals(2 * (InstaxPrinter.CONNECT_ATTEMPTS - 1), sleeps)
    }
}

class TransferOnlyTest {
    @Test
    fun `transfer only sends the image, resets and never starts a print`() {
        Simulator(port = 0).start().use { simulator ->
            val events = mutableListOf<PrintEvent>()
            val source = ImageSource { w, h -> IntArray(w * h) { 0xFF336699.toInt() } }
            InstaxPrinter("127.0.0.1", simulator.port, sessionId = 9, sleeper = {}).print(source, transferOnly = true) { events += it }
            assertEquals(1, simulator.images.size)
            assertFalse(simulator.opcodes.contains(Opcode.PRINT_START))
            assertFalse(simulator.opcodes.contains(Opcode.PRINT_STATUS))
            assertEquals(2, simulator.opcodes.count { it == Opcode.RESET })
            assertEquals(10, simulator.film)
            assertEquals(listOf(true, false), simulator.lockHistory)
            assertTrue(events.contains(PrintEvent.TransferOnlyFinished))
            assertEquals(PrintEvent.Unlocked, events.last())
        }
    }
}

class ReachabilityTest {
    @Test
    fun `waits for a printer that starts late`() {
        val probe = Simulator(port = 0)
        val port = probe.port
        probe.close()
        var sleeps = 0
        var late: Simulator? = null
        val printer = InstaxPrinter(
            "127.0.0.1", port, timeoutMillis = 500, reachTimeoutMillis = 60_000, sessionId = 5,
            sleeper = { if (++sleeps == 6) late = Simulator(port = port).start() },
        )
        try {
            val attempts = mutableListOf<Int>()
            val info = printer.waitUntilReachable { attempt, _ -> attempts += attempt }
            assertEquals("SP-3", info.model)
            assertTrue(attempts.isNotEmpty())
            assertEquals((1..attempts.size).toList(), attempts)

            val events = mutableListOf<PrintEvent>()
            printer.print({ w, h -> IntArray(w * h) }, listener = { events += it })
            assertFalse(events.any { it is PrintEvent.Reaching })
            assertTrue(events.any { it is PrintEvent.Finished })
        } finally {
            late?.close()
        }
    }

    @Test
    fun `gives up after the reach timeout`() {
        val probe = Simulator(port = 0)
        val port = probe.port
        probe.close()
        var waited = 0L
        val printer = InstaxPrinter("127.0.0.1", port, timeoutMillis = 300, reachTimeoutMillis = 0, sessionId = 6, sleeper = { waited += it })
        assertThrows(java.io.IOException::class.java) { printer.waitUntilReachable() }
        assertEquals(2 * InstaxPrinter.CONNECT_RETRY_DELAY, waited)
    }
}
