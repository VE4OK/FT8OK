package com.ft8ok.rx

import com.ft8ok.dsp.Decode
import com.ft8ok.dsp.ReplyMatch
import com.ft8ok.model.SlotParity
import org.junit.Assert.assertEquals
import org.junit.Test

class DecodeLogTest {

    // 2026-11-07 15:38:00 UTC
    private val slotMs = 1_794_065_880_000L

    private fun decode(hz: Int = 1844, dt: Double = 0.64, message: String = "VE4OK IV3XAA R JN65") =
        Decode(score = 30, dtSeconds = dt, frequencyHz = hz, message = message)

    @Test
    fun `a decode gives its slot, pass, frequency, raw DT, report and message`() {
        val row = RxSession.DecodeRow(slotMs, SlotParity.EVEN, decode(), snrDb = -5.34, band = "20m")
        assertEquals(
            "RX\t2026-11-07 15:38:00\teven\t20m\t2\t1844\t0.64\t-5.3\tVE4OK IV3XAA R JN65",
            DecodeLog.row(row, 2)
        )
    }

    @Test
    fun `an unmeasured report and an unknown band read as a dash`() {
        val row = RxSession.DecodeRow(slotMs, SlotParity.EVEN, decode())
        assertEquals(
            "RX\t2026-11-07 15:38:00\teven\t-\t0\t1844\t0.64\t-\tVE4OK IV3XAA R JN65",
            DecodeLog.row(row, 0)
        )
    }

    @Test
    fun `our own transmission has no pass, DT or report`() {
        val row = RxSession.DecodeRow(
            slotMs, SlotParity.ODD, decode(hz = 462, dt = 0.0, message = "IV3XAA VE4OK RR73"),
            tx = true, band = "20m"
        )
        assertEquals(
            "TX\t2026-11-07 15:38:00\todd\t20m\t-\t462\t-\t-\tIV3XAA VE4OK RR73",
            DecodeLog.row(row, 0)
        )
    }

    private fun update(clipped: Boolean = false) = LiveRxPipeline.SlotUpdate(
        slotStartMs = slotMs,
        parity = SlotParity.EVEN,
        pass = 2,
        isFinal = true,
        newDecodes = emptyList(),
        allDecodes = listOf(decode(), decode(hz = 1803)),
        occupancy = null,
        clipped = clipped,
        decodeMillis = 20,
        audioMs = 14_700
    )

    @Test
    fun `the end of a slot counts its messages and says whether they stand`() {
        assertEquals("SLOT\t2026-11-07 15:38:00\teven\t20m\t2\tok", DecodeLog.slotEnd(update(), "20m", dropped = false))
        assertEquals("SLOT\t2026-11-07 15:38:00\teven\t20m\t2\tclipped", DecodeLog.slotEnd(update(clipped = true), "20m", dropped = false))
        assertEquals("SLOT\t2026-11-07 15:38:00\teven\t-\t2\tdropped", DecodeLog.slotEnd(update(), null, dropped = true))
    }

    @Test
    fun `a test of the partner's slot gives where it looked and how close it came`() {
        val plan = ReplyWatch.Plan("IV3XAA", "VE4OK", "W9XYZ", true, ReplyWatch.Heard.NOT_HEARD, 1844, 0.64)
        assertEquals(
            "LIST\t2026-11-07 15:38:00\teven\t20m\tIV3XAA\tarmed\tnot_heard\t1844\t0.64\t37\tVE4OK IV3XAA R-12\t71\t60\t-\t-",
            DecodeLog.reply(slotMs, SlotParity.EVEN, "20m", plan, ReplyMatch(37, "VE4OK IV3XAA R-12", 71), 60)
        )
        assertEquals(
            true,
            DecodeLog.reply(slotMs, SlotParity.EVEN, "20m", plan.copy(partnerGrid = "JN65"), ReplyMatch(37, "x", 71), 60)
                .endsWith("\t60\tJN65\t-")
        )
        assertEquals(
            true,
            DecodeLog.reply(slotMs, SlotParity.EVEN, "20m", plan, ReplyMatch(20, "VE4OK IV3XAA R-12", 71), 15, accepted = true)
                .endsWith("\t15\t-\theard")
        )
    }

    @Test
    fun `a reply the list found is marked in the pass column`() {
        val row = RxSession.DecodeRow(
            slotMs, SlotParity.EVEN,
            com.ft8ok.dsp.Decode(0, 0.64, 1844, "VE4OK IV3XAA R-12", fromReplyList = true),
            snrDb = -24.0, band = "20m"
        )
        assertEquals("RX\t2026-11-07 15:38:00\teven\t20m\tlist\t1844\t0.64\t-24.0\tVE4OK IV3XAA R-12", DecodeLog.row(row, 4))
    }
}
