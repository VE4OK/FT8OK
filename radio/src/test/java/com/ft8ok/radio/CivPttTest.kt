package com.ft8ok.radio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The watchdog and the command bytes, tested without a radio.
 *
 * Everything here is about the transmitter being released. A PTT bug does not
 * produce a wrong answer on a screen; it leaves a transmitter keyed on a shared
 * band, which is the worst thing this app can do.
 */
class CivPttTest {

    private class FakePort {
        val writes = mutableListOf<List<Int>>()
        var accept = true
        fun write(bytes: ByteArray): Boolean {
            writes += bytes.map { it.toInt() and 0xFF }
            return accept
        }
        fun sawTransmit() = writes.any { it.contains(0x1C) && it.last() == 0xFD && it.contains(0x01) }
        fun sawReceive() = writes.any { frame ->
            val i = frame.indexOf(0x1C)
            i >= 0 && frame.getOrNull(i + 2) == 0x00
        }
    }

    @Test
    fun `keying sends the documented CI-V command`() {
        val port = FakePort()
        val ptt = CivPtt(port::write)
        assertEquals(null, ptt.key(5_000))

        // FE FE <radio> <controller> 1C 00 01 FD
        val frame = port.writes.single()
        assertEquals(listOf(0xFE, 0xFE, Civ.ADDR_IC7300, Civ.ADDR_CONTROLLER, 0x1C, 0x00, 0x01, 0xFD), frame)
        assertTrue(ptt.isKeyed)
    }

    @Test
    fun `releasing unkeys and says so`() {
        val port = FakePort()
        val ptt = CivPtt(port::write)
        ptt.key(5_000)
        ptt.release()

        assertTrue(!ptt.isKeyed)
        val frame = port.writes.last()
        assertEquals(listOf(0xFE, 0xFE, Civ.ADDR_IC7300, Civ.ADDR_CONTROLLER, 0x1C, 0x00, 0x00, 0xFD), frame)
    }

    @Test
    fun `a release is sent even when we believe we are not keyed`() {
        // The state we are wrong about is the dangerous one. An unkey command to an
        // unkeyed radio costs nothing.
        val port = FakePort()
        val ptt = CivPtt(port::write)
        ptt.key(5_000)
        port.writes.clear()

        ptt.release()
        ptt.release()
        assertTrue("the first release must reach the radio", port.writes.isNotEmpty())
    }

    @Test
    fun `a failed key command leaves us unkeyed rather than hopeful`() {
        val port = FakePort().apply { accept = false }
        val ptt = CivPtt(port::write)
        val failure = ptt.key(5_000)
        assertTrue("must report the failure", failure != null)
        assertTrue("and must not claim to be transmitting", !ptt.isKeyed)
    }

    @Test
    fun `the watchdog releases the transmitter on its own`() {
        val port = FakePort()
        val ptt = CivPtt(port::write)
        ptt.key(200)
        assertTrue(ptt.isKeyed)

        // Nothing refreshes it. The radio must come back by itself.
        val deadline = System.currentTimeMillis() + 3_000
        while (ptt.isKeyed && System.currentTimeMillis() < deadline) Thread.sleep(20)

        assertTrue("the watchdog did not fire", !ptt.isKeyed)
        assertTrue("and must have sent the unkey command", port.sawReceive())
    }

    @Test
    fun `refreshing keeps it keyed, and stopping refreshing releases it`() {
        val port = FakePort()
        val ptt = CivPtt(port::write)
        ptt.key(300)

        // Held up by refreshes for a while...
        repeat(8) {
            Thread.sleep(50)
            ptt.refresh()
        }
        assertTrue("refreshes should have held it keyed", ptt.isKeyed)

        // ...then abandoned.
        val deadline = System.currentTimeMillis() + 5_000
        while (ptt.isKeyed && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue("it must not stay keyed once refreshes stop", !ptt.isKeyed)
    }

    @Test
    fun `an absurd watchdog is refused rather than honoured`() {
        // A caller cannot ask for a watchdog that would let the radio transmit
        // indefinitely. Nothing legitimate needs longer than one slot.
        val ptt = CivPtt({ true })
        for (bad in listOf(0L, -1L, CivPtt.MAX_WATCHDOG_MS + 1, 10 * 60_000L)) {
            var refused = false
            try {
                ptt.key(bad)
            } catch (e: IllegalArgumentException) {
                refused = true
            }
            assertTrue("a watchdog of $bad ms must be refused", refused)
            ptt.release()
        }
    }

    @Test
    fun `keying twice refreshes rather than starting a second watchdog`() {
        val port = FakePort()
        val ptt = CivPtt(port::write)
        ptt.key(5_000)
        val afterFirst = port.writes.size
        assertEquals(null, ptt.key(5_000))
        assertEquals("the second key must not re-send", afterFirst, port.writes.size)
        ptt.release()
    }
}
