package com.ft8ok.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class TimelineGuardTest {

    /** Ten caught-up reads of 100 ms each, starting at [startMono], with [lostMs] missing. */
    private fun TimelineGuard.second(startMono: Double, heldStart: Double, lostMs: Double = 0.0, jitter: Boolean = true): Double {
        var slip = 0.0
        for (i in 1..10) {
            val noise = if (jitter) ((i * 7) % 5 - 2).toDouble() else 0.0
            val s = onRead(startMono + i * 100 + noise, heldStart + i * 100 - lostMs, waited = true)
            if (s != 0.0) slip = s
        }
        return slip
    }

    @Test
    fun `steady capture with jitter reports nothing`() {
        val g = TimelineGuard()
        assertEquals(0.0, g.second(0.0, 0.0), 0.0)
        assertEquals(0.0, g.second(1000.0, 1000.0), 0.0)
        assertEquals(0.0, g.second(2000.0, 2000.0), 0.0)
    }

    @Test
    fun `lost audio is reported once, by the amount lost`() {
        val g = TimelineGuard()
        g.second(0.0, 0.0)
        assertEquals(2500.0, g.second(1000.0, 1000.0, lostMs = 2500.0, jitter = false), 1.0)
        assertEquals("measured afresh after", 0.0, g.second(2000.0, 2000.0, lostMs = 2500.0), 0.0)
    }

    @Test
    fun `reads draining a backlog are ignored`() {
        val g = TimelineGuard()
        g.second(0.0, 0.0)
        // Behind: samples held lag the clock, but we are not caught up.
        repeat(20) { assertEquals(0.0, g.onRead(1000.0 + it, 500.0, waited = false), 0.0) }
        assertEquals(0.0, g.second(3000.0, 3000.0), 0.0)
    }

    @Test
    fun `a small slow drift waits for the threshold`() {
        val g = TimelineGuard()
        g.second(0.0, 0.0)
        assertEquals(0.0, g.second(1000.0, 1000.0, lostMs = 20.0, jitter = false), 0.0)
        assertEquals(45.0, g.second(2000.0, 2000.0, lostMs = 45.0, jitter = false), 1.0)
    }
}
