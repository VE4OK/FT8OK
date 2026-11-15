package com.ft8ok.rx

import com.ft8ok.model.Ft8Message
import com.ft8ok.model.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FeedLineTest {

    private fun line(message: String, snr: Double? = -12.4, dt: Double = 0.23, hz: Int = 1234) =
        FeedLine.build(snr, dt, hz, message, Ft8Message.parse(message))

    private fun FeedLine.Line.part(range: IntRange?) = range?.let { message.substring(it) }

    @Test
    fun `the columns are the report, DT to one decimal and the frequency`() {
        val l = line("CQ K1ABC FN42")
        assertEquals(listOf("-12", "+0.2", "1234"), listOf(l.snr, l.dt, l.frequency))
        val unmeasured = line("CQ K1ABC FN42", snr = null, dt = -1.26, hz = 987)
        assertEquals(listOf("--", "-1.3", "987"), listOf(unmeasured.snr, unmeasured.dt, unmeasured.frequency))
        val tx = FeedLine.buildTx(1500, "K1ABC VE4OK FN25")
        assertEquals(listOf("TX", "", "1500"), listOf(tx.snr, tx.dt, tx.frequency))
    }

    @Test
    fun `DT is measured from an on-time start, so the band reads zero`() {
        assertEquals(0.0, FeedLine.dtFromOnTime(0.65, Mode.FT8), 1e-9)
        assertEquals(0.07, FeedLine.dtFromOnTime(0.72, Mode.FT8), 1e-9)
        assertEquals(-0.41, FeedLine.dtFromOnTime(0.24, Mode.FT8), 1e-9)
        assertEquals("+0.1", line("x", dt = FeedLine.dtFromOnTime(0.72, Mode.FT8)).dt)
    }

    @Test
    fun `an on-time station reads 0_0 with no sign`() {
        assertEquals("0.0", line("x", dt = -0.01).dt)
        assertEquals("0.0", line("x", dt = 0.04).dt)
        assertEquals("+0.1", line("x", dt = 0.05).dt)
        assertEquals("-0.1", line("x", dt = -0.05).dt)
    }

    @Test
    fun `the widest column values are at least as wide as real ones`() {
        assertEquals(FeedLine.WIDEST_SNR.first().length, line("x", snr = -25.0).snr.length)
        assertEquals(FeedLine.WIDEST_DT.first().length, line("x", dt = -2.5).dt.length)
        assertEquals(FeedLine.WIDEST_FREQUENCY.first().length, line("x", hz = 2999).frequency.length)
    }

    @Test
    fun `the CQ word is marked, with the word after it when there is one`() {
        assertEquals("CQ", line("CQ K1ABC FN42").let { it.part(it.cq) })
        assertEquals("CQ POTA", line("CQ POTA K1ABC FN42").let { it.part(it.cq) })
        assertNull(line("VE4OK K1ABC -12").cq)
    }

    @Test
    fun `the sender is found, never the station being called`() {
        assertEquals("K1ABC", line("CQ POTA K1ABC FN42").let { it.part(it.sender) })
        assertEquals("K1ABC", line("VE4OK K1ABC R-09").let { it.part(it.sender) })
    }
}
