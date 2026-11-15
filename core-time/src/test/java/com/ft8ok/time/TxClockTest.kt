package com.ft8ok.time

import com.ft8ok.model.Mode
import org.junit.Assert.assertEquals
import org.junit.Test

class TxClockTest {

    private val bias = 0.15
    private fun clock() = TxClock(Mode.FT8, decoderDtBiasSec = bias)

    /** The DT the decoder reports for a station [ms] late against the band. */
    private fun dt(ms: Long) = 0.5 + bias + ms / 1000.0

    @Test
    fun `a station on the band's time gives no shift`() {
        val c = clock()
        c.observe("K1ABC", dt(0))
        assertEquals(0, c.shiftMsFor("K1ABC"))
    }

    @Test
    fun `a late partner moves our start later by the same amount`() {
        val c = clock()
        c.observe("K1ABC", dt(400))
        assertEquals(400, c.shiftMsFor("K1ABC"))
    }

    @Test
    fun `the median of recent readings is used, so one odd reading does not move us`() {
        val c = clock()
        c.observe("K1ABC", dt(300))
        c.observe("K1ABC", dt(320))
        c.observe("K1ABC", dt(-900))
        assertEquals(300, c.shiftMsFor("K1ABC"))
    }

    @Test
    fun `the shift is clamped, and wild readings are ignored`() {
        val c = clock()
        c.observe("K1ABC", dt(1_800))
        assertEquals(TxClock.MAX_SHIFT_MS, c.shiftMsFor("K1ABC"))
        val d = clock()
        d.observe("K1ABC", dt(4_000))
        assertEquals(0, d.shiftMsFor("K1ABC"))
    }

    @Test
    fun `another station, or none, gets no shift, and a new partner starts afresh`() {
        val c = clock()
        c.observe("K1ABC", dt(400))
        assertEquals(0, c.shiftMsFor("W2XYZ"))
        assertEquals(0, c.shiftMsFor(null))
        c.observe("W2XYZ", dt(-200))
        assertEquals(-200, c.shiftMsFor("W2XYZ"))
        assertEquals(0, c.shiftMsFor("K1ABC"))
    }
}
