package com.ft8ok.qso

import com.ft8ok.model.Ft8Message
import com.ft8ok.model.SlotParity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallerQueueTest {

    private val queue = CallerQueue(staleAfterMs = 75_000)

    private fun hear(call: String, atMs: Long, parity: SlotParity = SlotParity.ODD) =
        queue.heard(call, Ft8Message.parse("VE4OK $call FN42"), -10, 0.5, parity, atMs)

    @Test
    fun `first heard goes first, and calling again keeps the place`() {
        hear("K1ABC", 0)
        hear("W9XYZ", 1_000)
        hear("K1ABC", 30_000)
        assertEquals("K1ABC", queue.next(30_000)?.call)
        assertEquals(0L, queue.callers.first().firstHeardMs)
        assertEquals(30_000L, queue.callers.first().lastHeardMs)
    }

    @Test
    fun `a tap moves a caller to the front`() {
        hear("K1ABC", 0)
        hear("W9XYZ", 1_000)
        assertTrue(queue.moveToFront("W9XYZ"))
        assertEquals("W9XYZ", queue.next(1_000)?.call)
        assertFalse(queue.moveToFront("N0CAL"))
    }

    @Test
    fun `a caller not heard for a while is dropped`() {
        hear("K1ABC", 0)
        hear("W9XYZ", 30_000)
        assertEquals("W9XYZ", queue.next(80_000)?.call)
        assertNull(queue.next(110_000))
    }

    /** Freshness as the app sets it for FT8: heard within 22.5 s. */
    private val fresh = CallerQueue(staleAfterMs = 52_500, freshWithinMs = 22_500)

    private fun hearFresh(call: String, atMs: Long) =
        fresh.heard(call, Ft8Message.parse("VE4OK $call FN42"), -10, 0.5, SlotParity.ODD, atMs)

    @Test
    fun `a caller heard in their latest slot goes ahead of one heard a cycle earlier`() {
        // SP4Q and DL1XA.
        hearFresh("SP4Q", 0)
        hearFresh("DL1XA", 30_000)
        assertEquals("DL1XA", fresh.next(30_000)?.call)
    }

    @Test
    fun `among fresh callers, first heard still goes first`() {
        hearFresh("K1ABC", 0)
        hearFresh("W9XYZ", 1_000)
        hearFresh("W9XYZ", 30_000)
        hearFresh("K1ABC", 30_000)
        assertEquals("K1ABC", fresh.next(30_000)?.call)
    }

    @Test
    fun `with nobody fresh, the first heard of the rest goes`() {
        hearFresh("K1ABC", 0)
        hearFresh("W9XYZ", 1_000)
        assertEquals("K1ABC", fresh.next(30_000)?.call)
    }

    @Test
    fun `a tapped caller goes first even when not fresh, until taken`() {
        hearFresh("SP4Q", 0)
        hearFresh("DL1XA", 30_000)
        assertTrue(fresh.moveToFront("SP4Q"))
        assertEquals("SP4Q", fresh.next(30_000)?.call)
        fresh.take("SP4Q")
        hearFresh("SP4Q", 60_000)
        hearFresh("DL1XA", 60_000)
        assertEquals("the tap is used up", "DL1XA", fresh.next(60_000)?.call)
    }

    @Test
    fun `freshness respects parity and the partner`() {
        fresh.heard("K1ABC", Ft8Message.parse("VE4OK K1ABC FN42"), -10, 0.5, SlotParity.EVEN, 30_000)
        hearFresh("W9XYZ", 0)
        hearFresh("N0CAL", 30_000)
        assertEquals("N0CAL", fresh.next(30_000, heardIn = SlotParity.ODD)?.call)
        assertEquals("W9XYZ", fresh.next(30_000, heardIn = SlotParity.ODD, except = "N0CAL")?.call)
    }

    @Test
    fun `parity and the partner can be excluded`() {
        hear("K1ABC", 0, SlotParity.EVEN)
        hear("W9XYZ", 0)
        assertEquals("W9XYZ", queue.next(0, heardIn = SlotParity.ODD)?.call)
        assertEquals("W9XYZ", queue.next(0, except = "K1ABC")?.call)
    }

    @Test
    fun `only a grid or a plain report to us is a call`() {
        assertTrue(queue.isCallTo("VE4OK", Ft8Message.parse("VE4OK K1ABC FN42")))
        assertTrue(queue.isCallTo("VE4OK", Ft8Message.parse("VE4OK K1ABC -10")))
        assertFalse("an R-report is a contact already under way", queue.isCallTo("VE4OK", Ft8Message.parse("VE4OK K1ABC R-10")))
        assertFalse(queue.isCallTo("VE4OK", Ft8Message.parse("VE4OK K1ABC RR73")))
        assertFalse(queue.isCallTo("VE4OK", Ft8Message.parse("N0CAL K1ABC FN42")))
        assertFalse(queue.isCallTo("VE4OK", Ft8Message.parse("VE4OK <...> FN42")))
    }
}
