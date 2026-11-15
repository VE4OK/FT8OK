package com.ft8ok.qso

import com.ft8ok.model.Mode
import com.ft8ok.model.SlotParity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gate that decides whether a transmitter may be keyed.
 *
 * Tested harder than anything else in the app, and deliberately mostly for things
 * it must REFUSE. Every one of these is a case where getting it wrong puts a signal
 * on the air that should not be there — on the wrong frequency, in someone else's
 * slot, too late to be decoded, or when the operator never armed the radio at all.
 */
class TxSchedulerTest {

    private val scheduler = TxScheduler(Mode.FT8)

    /** A slot boundary whose parity is EVEN, far from the epoch. */
    private val evenSlot = (1_760_000_010_000L / 30_000) * 30_000

    private fun request(
        message: String? = "K1ABC W3XAA -12",
        armed: Boolean = true,
        parity: SlotParity = SlotParity.EVEN,
        dialHz: Long? = 14_074_000,
        override: Boolean = false
    ) = TxScheduler.Request(message, armed, parity, dialHz, override)

    // --- arm ---------------------------------------------------------------------

    @Test
    fun `an unarmed radio never transmits, whatever else is true`() {
        // Everything else perfect: armed is still the first gate, because TX arm
        // defaults OFF and is cleared by anything surprising (rule 3).
        val d = scheduler.decide(request(armed = false), evenSlot + 1_000)
        assertEquals(TxScheduler.Verdict.NOT_ARMED, d.verdict)
        assertTrue(!d.mayTransmit)
    }

    @Test
    fun `nothing queued means nothing sent`() {
        assertEquals(
            TxScheduler.Verdict.NOTHING_TO_SEND,
            scheduler.decide(request(message = null), evenSlot + 1_000).verdict
        )
        assertEquals(
            TxScheduler.Verdict.NOTHING_TO_SEND,
            scheduler.decide(request(message = "   "), evenSlot + 1_000).verdict
        )
    }

    // --- the frequency guard -----------------------------------------------------

    @Test
    fun `we transmit on a conventional FT8 frequency`() {
        val d = scheduler.decide(request(dialHz = 14_074_000), evenSlot + 1_000)
        assertEquals(TxScheduler.Verdict.TRANSMIT, d.verdict)
        assertEquals("K1ABC W3XAA -12", d.message)
    }

    @Test
    fun `the guard allows the DXpedition alternates`() {
        // Blocking 14.090 would stop the operator working a DXpedition, which is
        // exactly when they most want to transmit.
        assertEquals(
            TxScheduler.Verdict.TRANSMIT,
            scheduler.decide(request(dialHz = 14_090_000), evenSlot + 1_000).verdict
        )
        assertEquals(
            TxScheduler.Verdict.TRANSMIT,
            scheduler.decide(request(dialHz = 7_056_000), evenSlot + 1_000).verdict
        )
    }

    @Test
    fun `we do not transmit in the middle of the phone band`() {
        val d = scheduler.decide(request(dialHz = 14_250_000), evenSlot + 1_000)
        assertEquals(TxScheduler.Verdict.OFF_FREQUENCY, d.verdict)
    }

    @Test
    fun `not knowing the frequency is a refusal, not a free pass`() {
        // The CAT link may be absent, unplugged, or the radio may have Transceive
        // off. "We have not been told" must never read as "fine".
        val d = scheduler.decide(request(dialHz = null), evenSlot + 1_000)
        assertEquals(TxScheduler.Verdict.FREQUENCY_UNKNOWN, d.verdict)
    }

    @Test
    fun `a deliberate override lets the operator transmit anywhere`() {
        // The guard prevents ACCIDENTAL out-of-convention transmission. It does not
        // overrule the licensed operator, who may have good reason.
        val d = scheduler.decide(
            request(dialHz = 14_250_000, override = true), evenSlot + 1_000
        )
        assertEquals(TxScheduler.Verdict.TRANSMIT, d.verdict)

        // ...and an override still cannot transmit while unarmed or off-slot.
        assertEquals(
            TxScheduler.Verdict.NOT_ARMED,
            scheduler.decide(
                request(dialHz = 14_250_000, override = true, armed = false), evenSlot + 1_000
            ).verdict
        )
    }

    // --- parity ------------------------------------------------------------------

    @Test
    fun `we do not transmit in the other station's slot`() {
        val d = scheduler.decide(request(parity = SlotParity.ODD), evenSlot + 1_000)
        assertEquals(TxScheduler.Verdict.WRONG_PARITY, d.verdict)

        // One slot later it is our turn.
        val next = scheduler.decide(request(parity = SlotParity.ODD), evenSlot + 15_000 + 1_000)
        assertEquals(TxScheduler.Verdict.TRANSMIT, next.verdict)
    }

    // --- the late-start window ---------------------------------------------------

    @Test
    fun `the transmission window opens at the nominal start and closes at the limit`() {
        assertEquals(
            TxScheduler.Verdict.TOO_EARLY,
            scheduler.decide(request(), evenSlot + 100).verdict
        )
        assertEquals(
            TxScheduler.Verdict.TRANSMIT,
            scheduler.decide(request(), evenSlot + Mode.FT8.nominalTxStartMs).verdict
        )
        assertEquals(
            TxScheduler.Verdict.TRANSMIT,
            scheduler.decide(request(), evenSlot + Mode.FT8.latestTxStartMs).verdict
        )
        assertEquals(
            TxScheduler.Verdict.TOO_LATE,
            scheduler.decide(request(), evenSlot + Mode.FT8.latestTxStartMs + 1).verdict
        )
    }

    @Test
    fun `a lead opens the window early, never before the slot boundary, and leaves the late limit alone`() {
        val lead = TxScheduler.Request("K1ABC W3XAA -12", true, SlotParity.EVEN, 14_074_000, leadMs = 500)
        assertEquals(TxScheduler.Verdict.TRANSMIT, scheduler.decide(lead, evenSlot).verdict)
        assertEquals(TxScheduler.Verdict.TOO_LATE, scheduler.decide(lead, evenSlot + Mode.FT8.latestTxStartMs + 1).verdict)
        // A lead longer than the nominal start cannot reach into the previous slot,
        // which is the other parity's.
        val huge = lead.copy(leadMs = 5_000)
        assertEquals(TxScheduler.Verdict.WRONG_PARITY, scheduler.decide(huge, evenSlot - 100).verdict)
        assertEquals(TxScheduler.Verdict.TRANSMIT, scheduler.decide(huge, evenSlot).verdict)
    }

    @Test
    fun `a late click is refused and costs only the transmission`() {
        // The rule: refusing keeps the receive slot, keying late loses the
        // transmission AND the slot it was sent in, and usually the reply. The
        // scheduler's only job is to say no; nothing here stops receiving.
        val halfway = scheduler.decide(request(), evenSlot + 7_000)
        assertEquals(TxScheduler.Verdict.TOO_LATE, halfway.verdict)
        assertEquals(7_000, halfway.positionInSlotMs)
        assertTrue(!halfway.mayTransmit)
    }

    // --- the hard stop -----------------------------------------------------------

    @Test
    fun `transmission stops by the hard stop, so nothing runs into the next slot`() {
        assertTrue(!scheduler.mustStop(evenSlot + 1_000))
        assertTrue(!scheduler.mustStop(evenSlot + Mode.FT8.hardStopMs - 1))
        assertTrue(scheduler.mustStop(evenSlot + Mode.FT8.hardStopMs))
        assertTrue(scheduler.mustStop(evenSlot + 14_999))
    }

    @Test
    fun `a maximally late transmission is cut short rather than overrunning`() {
        // Starting at the limit leaves less than a full message before the hard
        // stop. Accepted: the tail is lost, the receiver may still decode it, and
        // the alternative is transmitting over the next slot.
        val start = Mode.FT8.latestTxStartMs
        val remaining = scheduler.remainingMs(evenSlot + start)
        assertTrue(
            "a late start has $remaining ms left but the message needs ${Mode.FT8.txDurationMs}",
            remaining < Mode.FT8.txDurationMs
        )
        assertEquals(Mode.FT8.hardStopMs - start, remaining)
    }

    @Test
    fun `remaining time never goes negative`() {
        assertEquals(0, scheduler.remainingMs(evenSlot + 14_999))
    }

    // --- ordering ----------------------------------------------------------------

    @Test
    fun `the gates are checked in an order that cannot be talked around`() {
        // Every refusal reported must be the FIRST thing wrong, so the operator is
        // told the most fundamental problem rather than an incidental one. Unarmed
        // and off-frequency and too late at once reports unarmed.
        val everythingWrong = TxScheduler.Request(
            message = "K1ABC W3XAA -12",
            armed = false,
            txParity = SlotParity.ODD,
            dialHz = 14_250_000
        )
        assertEquals(
            TxScheduler.Verdict.NOT_ARMED,
            scheduler.decide(everythingWrong, evenSlot + 9_000).verdict
        )
    }
}
