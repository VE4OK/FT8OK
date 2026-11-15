package com.ft8ok.tx

import com.ft8ok.model.ContestSetup
import com.ft8ok.dsp.Decode
import com.ft8ok.model.Mode
import com.ft8ok.model.SlotParity
import com.ft8ok.model.StationProfile
import com.ft8ok.qso.QsoEngine
import com.ft8ok.qso.TxScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gate that decides whether the radio keys itself.
 *
 * Almost every test here asserts that **nothing happens**. That is the right shape
 * for this class: it is the first thing in the app that can transmit without a
 * finger on a button, and the failure that matters is not "it did not transmit", it
 * is "it transmitted when it should not have".
 */
class AutoTxTest {

    private val profile = StationProfile(callsign = "VE4OK", grid = "FN25")
    private val engine = QsoEngine(myCall = "VE4OK", myGrid = "FN25")

    /** An EVEN slot boundary, 1 s in — inside the transmit window. */
    private val evenSlot = (1_760_000_010_000L / 30_000) * 30_000
    private val inWindow = evenSlot + 1_000

    private fun autoTx() = AutoTx(Mode.FT8, TxScheduler(Mode.FT8))

    private fun decode(message: String, hz: Int = 1500) =
        Decode(score = 20, dtSeconds = 0.5, frequencyHz = hz, message = message)

    // --- arming ------------------------------------------------------------------

    @Test
    fun `it starts disarmed and transmits nothing`() {
        val tx = autoTx()
        assertTrue("TX arm must default off at every app start", !tx.armed)

        tx.callStation(engine, "K1ABC", inWindow, SlotParity.ODD)
        assertNotNull("there is something to say", tx.queued)
        assertNull("but it must not be sent", tx.decide(inWindow, profile, 14_074_000))
        assertEquals(TxScheduler.Verdict.NOT_ARMED, tx.lastVerdict)
    }

    @Test
    fun `arming is refused without a callsign`() {
        val tx = autoTx()
        assertNotNull(tx.arm(StationProfile()))
        assertTrue(!tx.armed)

        assertNull(tx.arm(profile))
        assertTrue(tx.armed)
    }

    @Test
    fun `an armed station with something to say transmits once per slot`() {
        val tx = autoTx()
        tx.arm(profile)
        tx.callStation(engine, "K1ABC", inWindow, SlotParity.ODD)

        val first = tx.decide(inWindow, profile, 14_074_000)
        assertEquals("K1ABC VE4OK FN25", first)

        // Called again inside the same slot - as it will be, ten times a second -
        // it must not start a second transmission.
        assertNull(tx.decide(inWindow + 100, profile, 14_074_000))
        assertNull(tx.decide(inWindow + 2_000, profile, 14_074_000))
    }

    // --- the gates it must not get past ------------------------------------------

    @Test
    fun `it will not transmit off frequency`() {
        val tx = autoTx()
        tx.arm(profile)
        tx.callStation(engine, "K1ABC", inWindow, SlotParity.ODD)
        assertNull(tx.decide(inWindow, profile, 14_250_000))
        assertEquals(TxScheduler.Verdict.OFF_FREQUENCY, tx.lastVerdict)
    }

    @Test
    fun `it will not transmit when the frequency is unknown`() {
        val tx = autoTx()
        tx.arm(profile)
        tx.callStation(engine, "K1ABC", inWindow, SlotParity.ODD)
        assertNull(tx.decide(inWindow, profile, null))
        assertEquals(TxScheduler.Verdict.FREQUENCY_UNKNOWN, tx.lastVerdict)
    }

    @Test
    fun `it will not transmit in the other station's slot`() {
        val tx = autoTx()
        tx.arm(profile)
        // Heard in the EVEN slot, so EVEN is theirs and we answer in the ODD one.
        tx.callStation(engine, "K1ABC", inWindow, SlotParity.EVEN)
        assertNull(tx.decide(inWindow, profile, 14_074_000))
        assertEquals(TxScheduler.Verdict.WRONG_PARITY, tx.lastVerdict)
    }

    @Test
    fun `it will not transmit past the late-start window, and keeps receiving`() {
        val tx = autoTx()
        tx.arm(profile)
        tx.callStation(engine, "K1ABC", inWindow, SlotParity.ODD)

        val late = evenSlot + Mode.FT8.latestTxStartMs + 1
        assertNull(tx.decide(late, profile, 14_074_000))
        assertEquals(TxScheduler.Verdict.TOO_LATE, tx.lastVerdict)
        // The message is still queued: the queue carries to the next slot of our
        // parity rather than being thrown away.
        assertNotNull(tx.queued)
    }

    @Test
    fun `an incomplete profile cannot transmit even if somehow armed`() {
        val tx = autoTx()
        tx.arm(profile)
        tx.callStation(engine, "K1ABC", inWindow, SlotParity.ODD)
        // Profile emptied after arming - the check is made at decision time, not
        // only at arming time.
        assertNull(tx.decide(inWindow, profile.copy(callsign = ""), 14_074_000))
    }

    @Test
    fun `nothing queued means nothing sent`() {
        val tx = autoTx()
        tx.arm(profile)
        assertNull(tx.decide(inWindow, profile, 14_074_000))
        assertEquals(TxScheduler.Verdict.NOTHING_TO_SEND, tx.lastVerdict)
    }

    // --- halting and disarming ---------------------------------------------------

    @Test
    fun `halt disarms, stops, and keeps the partner`() {
        val tx = autoTx()
        tx.arm(profile)
        tx.callStation(engine, "K1ABC", inWindow, SlotParity.ODD)

        tx.halt(engine)
        assertTrue("halt must disarm", !tx.armed)
        assertNull("and send nothing", tx.decide(inWindow, profile, 14_074_000))
        assertNotNull("but keep the contact", tx.qso)
        assertEquals("K1ABC", tx.qso!!.partner)
        assertTrue(tx.qso!!.paused)
        assertEquals("Halted", tx.disarmReason)
    }

    @Test
    fun `resuming a halted contact does not re-arm`() {
        val tx = autoTx()
        tx.arm(profile)
        tx.callStation(engine, "K1ABC", inWindow, SlotParity.ODD)
        tx.halt(engine)

        tx.resume(engine)
        assertTrue("arming is the operator's decision, every time", !tx.armed)
        assertTrue(!tx.qso!!.paused)
    }

    @Test
    fun `changing frequency disarms`() {
        // Rule 3. The operator has stopped doing whatever they were doing, and a
        // message queued for the old band is not one to send on the new one.
        val tx = autoTx()
        tx.arm(profile)
        tx.onFrequencyChanged()
        assertTrue(!tx.armed)
        assertEquals("Frequency changed", tx.disarmReason)
    }

    @Test
    fun `changing frequency clears the partner even while disarmed`() {
        // A partner picked on 20 m and left in the slot would be
        // called on 17 m by the next ARM.
        val tx = autoTx()
        tx.callStation(engine, "K1ABC", inWindow, SlotParity.ODD)
        assertTrue(!tx.armed)
        assertEquals("K1ABC", tx.qso?.partner)
        tx.onFrequencyChanged()
        assertNull(tx.qso)
        assertNull(tx.nextMessage)
    }

    @Test
    fun `changing frequency ends a CQ run`() {
        val tx = autoTx()
        tx.startCq(engine, SlotParity.EVEN)
        tx.arm(profile)
        tx.onFrequencyChanged()
        assertTrue(!tx.armed)
        assertTrue(!tx.callingCq)
        assertNull(tx.nextMessage)
    }

    @Test
    fun `nothing re-arms on its own`() {
        val tx = autoTx()
        tx.arm(profile)
        tx.disarm("test")
        // Everything that might plausibly look like activity.
        tx.callStation(engine, "K1ABC", inWindow, SlotParity.ODD)
        tx.onDecodes(engine, listOf(decode("VE4OK K1ABC -12")), { -10 }, inWindow, SlotParity.ODD)
        tx.onTransmitFinished(engine)
        assertTrue("only arm() may ever set armed", !tx.armed)
    }

    @Test
    fun `disarming with the ARMED button lets the transmission finish, sends nothing more, and keeps the message`() {
        // The ARMED button was acting like Halt.
        val tx = autoTx()
        tx.arm(profile)
        tx.callStation(engine, "K1ABC", inWindow, SlotParity.ODD)
        assertEquals("K1ABC VE4OK FN25", tx.decide(inWindow, profile, 14_074_000))
        tx.onTransmitStarted()
        tx.disarmByOperator()
        assertTrue("the transmission on the air carries on", tx.mayContinueTransmitting)
        tx.onTransmitFinished(engine, transmitted = true)
        assertTrue("and that is the last one", !tx.mayContinueTransmitting)

        assertNull(tx.decide(inWindow + 30_000, profile, 14_074_000))
        assertEquals("kept, so re-arming carries on", "K1ABC VE4OK FN25", tx.queued)
        tx.arm(profile)
        assertEquals("K1ABC VE4OK FN25", tx.decide(inWindow + 60_000, profile, 14_074_000))
    }

    @Test
    fun `an RR73 finished after the ARMED button still completes the contact`() {
        val tx = autoTx()
        val completed = mutableListOf<String>()
        tx.onContactComplete = { completed += it.partner }
        tx.arm(profile)
        tx.callStation(engine, "K1ABC", inWindow, SlotParity.ODD)
        heardInOdd(tx, "VE4OK K1ABC R-12")
        assertEquals("K1ABC VE4OK RR73", tx.decide(inWindow, profile, 14_074_000))
        tx.onTransmitStarted()
        tx.disarmByOperator()
        tx.onTransmitFinished(engine, transmitted = true)
        assertEquals(listOf("K1ABC"), completed)
    }

    @Test
    fun `Halt and every other disarm stop the transmission in its tracks`() {
        for (stop in listOf<(AutoTx) -> Unit>({ it.halt(engine) }, { it.disarm("App left the screen") })) {
            val tx = autoTx()
            tx.arm(profile)
            tx.callStation(engine, "K1ABC", inWindow, SlotParity.ODD)
            tx.decide(inWindow, profile, 14_074_000)
            tx.onTransmitStarted()
            stop(tx)
            assertTrue(!tx.mayContinueTransmitting)
        }
        // And the ARMED button followed by Halt is still a Halt.
        val tx = autoTx()
        tx.arm(profile)
        tx.callStation(engine, "K1ABC", inWindow, SlotParity.ODD)
        tx.decide(inWindow, profile, 14_074_000)
        tx.onTransmitStarted()
        tx.disarmByOperator()
        tx.halt(engine)
        assertTrue(!tx.mayContinueTransmitting)
        assertNull("Halt clears what was queued", tx.queued)
    }

    // --- timing (ADR 0005) ------------------------------------------------------

    @Test
    fun `the transmit path may start at the slot boundary, ahead of the signal`() {
        val tx = autoTx()
        tx.arm(profile)
        tx.callStation(engine, "K1ABC", evenSlot, SlotParity.ODD)
        assertEquals("K1ABC VE4OK FN25", tx.decide(evenSlot + 50, profile, 14_074_000))
        assertEquals("the signal still starts at the nominal time", evenSlot + 500, tx.txStartTargetMs(evenSlot))
    }

    @Test
    fun `while receiving, it waits for the last slot's decodes before preparing`() {
        val tx = autoTx()
        tx.arm(profile)
        tx.callStation(engine, "K1ABC", evenSlot - 30_000, SlotParity.ODD)
        // Receiving: the slot before last has been decoded, the last one not yet.
        tx.onDecodes(engine, emptyList(), { -10 }, evenSlot - 16_000, SlotParity.EVEN, slotStartMs = evenSlot - 30_000)
        assertNull("its decodes may change what we send", tx.decide(evenSlot + 100, profile, 14_074_000))
        tx.onDecodes(engine, listOf(decode("VE4OK K1ABC -12")), { -10 }, evenSlot + 200, SlotParity.ODD, slotStartMs = evenSlot - 15_000)
        assertEquals("the reply to what just came in", "K1ABC VE4OK R-10", tx.decide(evenSlot + 300, profile, 14_074_000))
    }

    @Test
    fun `a decision made while the decodes are being applied still waits for them`() {
        // VA3XAD: the TX ticker ran part way through onDecodes and sent a
        // second R-04 with their RR73 already decoded.
        val tx = autoTx()
        tx.arm(profile)
        tx.callStation(engine, "K1ABC", evenSlot - 30_000, SlotParity.ODD)
        tx.onDecodes(engine, emptyList(), { -10 }, evenSlot - 16_000, SlotParity.EVEN, slotStartMs = evenSlot - 30_000)

        var midway: String? = "not asked"
        tx.onDecodes(
            engine,
            listOf(decode("VE4OK K1ABC -12")),
            { midway = tx.decide(evenSlot + 100, profile, 14_074_000); -10 },
            evenSlot + 90,
            SlotParity.ODD,
            slotStartMs = evenSlot - 15_000
        )

        assertNull("the reply is not in place yet", midway)
        assertEquals("K1ABC VE4OK R-10", tx.decide(evenSlot + 100, profile, 14_074_000))
    }

    @Test
    fun `if the decodes never come, it goes anyway, before the nominal start`() {
        // Ten seconds of receive audio were lost, the partner's slot never
        // decoded, and waiting to half the late-start window sent our call 2.2 s late.
        val tx = autoTx()
        tx.arm(profile)
        tx.callStation(engine, "K1ABC", evenSlot - 30_000, SlotParity.ODD)
        tx.onDecodes(engine, emptyList(), { -10 }, evenSlot - 16_000, SlotParity.EVEN, slotStartMs = evenSlot - 30_000)
        val limit = Mode.FT8.decodeWaitMs
        assertTrue("still time to start on time", limit < Mode.FT8.nominalTxStartMs)
        assertNull(tx.decide(evenSlot + limit - 1, profile, 14_074_000))
        assertEquals("K1ABC VE4OK FN25", tx.decide(evenSlot + limit, profile, 14_074_000))
    }

    @Test
    fun `during a contact our start moves to the partner's measured time`() {
        val tx = autoTx()
        tx.callStation(engine, "K1ABC", inWindow, SlotParity.ODD)
        // DT 0.5 reads 150 ms early with the decoder's 0.15 s bias.
        heardInOdd(tx, "VE4OK K1ABC -12")
        assertEquals(evenSlot + 500 - 150, tx.txStartTargetMs(evenSlot))
        tx.clearPartner()
        assertEquals("back to the band's time", evenSlot + 500, tx.txStartTargetMs(evenSlot))
    }

    @Test
    fun `a late start's whole-message allowance counts from the band's time, not the partner's`() {
        val tx = autoTx()
        assertEquals("no partner: the mode's cap", Mode.FT8.maxLateShiftMs, tx.lateShiftAllowanceMs())

        tx.callStation(engine, "K1ABC", inWindow, SlotParity.ODD)
        heardInOdd(tx, "VE4OK K1ABC -12")
        assertEquals("150 ms early: more room", Mode.FT8.maxLateShiftMs + 150, tx.lateShiftAllowanceMs())

        // VE3XAC: heard at the +1 s shift limit.
        val late = autoTx()
        late.callStation(engine, "K1ABC", inWindow, SlotParity.ODD)
        late.onDecodes(engine, listOf(Decode(score = 20, dtSeconds = 2.0, frequencyHz = 1500, message = "VE4OK K1ABC -12")), { -10 }, inWindow, SlotParity.ODD)
        assertEquals(1_000L, late.txShiftMs())
        assertEquals(Mode.FT8.maxLateShiftMs - 1_000, late.lateShiftAllowanceMs())
        assertEquals("never behind the band by more than the cap", Mode.FT8.maxLateShiftMs, late.txShiftMs() + late.lateShiftAllowanceMs())
    }

    // --- which slot we answer in -------------------------------------------------

    
    fun `we answer in the slot opposite the one we heard them in`() {
        // The bug this pins cost a session on the air. The TX parity used to be
        // supplied by the caller as "the parity of the next slot", recomputed inside
        // a decision that runs ten times a second DURING the slot we mean to
        // transmit in - where the next slot is the wrong one. The scheduler compares
        // against the slot in progress, so the two were always opposite: armed auto
        // TX returned WRONG_PARITY in every slot and never transmitted once, while
        // the screen read "Armed - next: ..." throughout.
        val tx = autoTx()
        tx.arm(profile)
        tx.callStation(engine, "K1ABC", inWindow, SlotParity.ODD)

        assertEquals("heard in ODD, so we transmit in EVEN", SlotParity.EVEN, tx.txParity)
        assertNotNull("and the EVEN slot is ours", tx.decide(inWindow, profile, 14_074_000))
    }

    
    fun `the parity is latched, not recomputed as the slot advances`() {
        // Whatever moment inside our slot the decision is taken at, it is still our
        // slot. Anything derived from "now" drifts into the next slot and refuses.
        val tx = autoTx()
        tx.arm(profile)
        tx.callStation(engine, "K1ABC", inWindow, SlotParity.ODD)

        for (offset in 0L until Mode.FT8.slotMs step 500L) {
            assertEquals(
                "parity changed  ms into the slot",
                SlotParity.EVEN,
                tx.txParity
            )
            tx.decide(evenSlot + offset, profile, 14_074_000)
        }
    }

    
    fun `hearing the partner re-latches which slot is ours`() {
        // A partner who moves parity takes us with them, rather than leaving us
        // transmitting into the slot they are now using.
        val tx = autoTx()
        tx.callStation(engine, "K1ABC", inWindow, SlotParity.ODD)
        assertEquals(SlotParity.EVEN, tx.txParity)

        tx.onDecodes(engine, listOf(decode("VE4OK K1ABC FN42")), { -10 }, inWindow, SlotParity.EVEN)
        assertEquals("now heard in EVEN, so ours is ODD", SlotParity.ODD, tx.txParity)
    }

    
    fun `with no partner there is no parity and nothing is sent`() {
        val tx = autoTx()
        tx.arm(profile)
        assertNull(tx.txParity)
        assertNull(tx.decide(inWindow, profile, 14_074_000))
        assertEquals(TxScheduler.Verdict.NOTHING_TO_SEND, tx.lastVerdict)
    }

    
    fun `clearing the partner clears the parity`() {
        val tx = autoTx()
        tx.callStation(engine, "K1ABC", inWindow, SlotParity.ODD)
        tx.clearPartner()
        assertNull(tx.txParity)
    }

    // --- following the conversation ----------------------------------------------

    @Test
    fun `the QSO engine follows the contact even while disarmed`() {
        // So that arming mid-contact picks up where it is rather than restarting.
        val tx = autoTx()
        tx.callStation(engine, "K1ABC", inWindow, SlotParity.ODD)
        tx.onDecodes(engine, listOf(decode("VE4OK K1ABC FN42")), { -10 }, inWindow, SlotParity.ODD)

        assertEquals("K1ABC", tx.qso?.partner)
        assertEquals("K1ABC VE4OK -10", tx.queued)
        assertTrue("...but still disarmed", !tx.armed)
    }

    @Test
    fun `the report we queue is the one we measured`() {
        val tx = autoTx()
        tx.callStation(engine, "K1ABC", inWindow, SlotParity.ODD)
        tx.onDecodes(engine, listOf(decode("VE4OK K1ABC FN42")), { -3 }, inWindow, SlotParity.ODD)
        assertEquals("K1ABC VE4OK -03", tx.queued)
    }

    @Test
    fun `a decode with no SNR is not answered with a made-up report`() {
        // The report goes in someone else's log. No measurement, no reply.
        val tx = autoTx()
        tx.onDecodes(engine, listOf(decode("VE4OK K1ABC FN42")), { null }, inWindow, SlotParity.ODD)
        assertNull(tx.queued)
    }

    @Test
    fun `a new slot allows a new transmission`() {
        val tx = autoTx()
        tx.arm(profile)
        tx.callStation(engine, "K1ABC", inWindow, SlotParity.ODD)
        assertNotNull(tx.decide(inWindow, profile, 14_074_000))

        // Two slots later it is our parity again, with something still queued.
        val next = inWindow + 30_000
        tx.queuedForTest("K1ABC VE4OK -10")
        assertNotNull(tx.decide(next, profile, 14_074_000))
    }

    @Test
    fun `other stations' traffic in the same slot does not wipe our queued reply`() {
        // A real slot carries twenty-odd decodes and almost none are ours. The engine
        // returns IGNORED for those, with a null reply; applying it would destroy the
        // reply queued moments earlier by the one decode that WAS ours, and the app
        // would never transmit. Found by review before it reached the air.
        val tx = autoTx()
        tx.arm(profile)
        tx.callStation(engine, "K1ABC", inWindow, SlotParity.ODD)

        tx.onDecodes(
            engine,
            listOf(
                decode("VE4OK K1ABC FN42"),        // ours: queues a report
                decode("CQ W1XYZ FN31", 1700),     // somebody else
                decode("N0ABC W2DEF -05", 1800),   // somebody else's contact
                decode("CQ DX K3GHI EM13", 1900)
            ),
            { -10 },
            inWindow,
            SlotParity.ODD
        )

        assertEquals("K1ABC", tx.qso?.partner)
        assertEquals("K1ABC VE4OK -10", tx.queued)
        assertNotNull(
            "the reply must survive the rest of the slot",
            tx.decide(inWindow, profile, 14_074_000)
        )
    }

    @Test
    fun `a slot containing nothing of ours leaves the contact untouched`() {
        val tx = autoTx()
        tx.arm(profile)
        tx.callStation(engine, "K1ABC", inWindow, SlotParity.ODD)
        val queuedBefore = tx.queued

        tx.onDecodes(engine, listOf(decode("CQ W1XYZ FN31"), decode("N0ABC W2DEF -05")), { -10 }, inWindow, SlotParity.ODD)
        assertEquals(queuedBefore, tx.queued)
        assertEquals("K1ABC", tx.qso?.partner)
    }

    @Test
    fun `an unanswered transmission is repeated in our next slot`() {
        // The normal case on a real band: our message was not decoded. Clearing the
        // queue after transmitting meant we went silent while the other station
        // waited - the exchange harness could not complete a contact whose first
        // message was lost.
        val tx = autoTx()
        tx.arm(profile)
        tx.callStation(engine, "K1ABC", inWindow, SlotParity.ODD)

        val first = tx.decide(inWindow, profile, 14_074_000)
        assertEquals("K1ABC VE4OK FN25", first)
        tx.onTransmitStarted()
        tx.onTransmitFinished(engine)

        // Nothing came back. Two slots on it is our parity again, and we repeat.
        val again = tx.decide(inWindow + 30_000, profile, 14_074_000)
        assertEquals("the same message, not silence", first, again)
    }

    @Test
    fun `repeating stops at the retry limit, and disarms rather than calling forever`() {
        val tx = autoTx()
        tx.arm(profile)
        tx.callStation(engine, "K1ABC", inWindow, SlotParity.ODD)

        var slot = 0
        var transmissions = 0
        while (tx.queued != null && slot < 40) {
            val now = inWindow + slot * 30_000L
            if (tx.decide(now, profile, 14_074_000) != null) {
                transmissions++
                tx.onTransmitStarted()
                tx.onTransmitFinished(engine)
            }
            slot++
        }

        assertNull("the queue must be cleared when we give up", tx.queued)
        assertTrue("and TX disarmed", !tx.armed)
        assertEquals(QsoEngine.DEFAULT_CALL_RETRY_LIMIT, transmissions)
    }

    // --- when a contact counts as complete (the auto-log trigger) ------------------

    private fun heardInOdd(tx: AutoTx, vararg messages: String, nowMs: Long = inWindow, engineUsed: QsoEngine = engine) =
        tx.onDecodes(engineUsed, messages.map { decode(it) }, { -10 }, nowMs, SlotParity.ODD)

    @Test
    fun `their RR73 after both reports completes the contact, once`() {
        val tx = autoTx()
        val completed = mutableListOf<String>()
        tx.onContactComplete = { completed += it.partner }
        tx.callStation(engine, "K1ABC", inWindow, SlotParity.ODD)

        heardInOdd(tx, "VE4OK K1ABC -10")
        assertEquals("a report alone is not a contact", emptyList<String>(), completed)
        heardInOdd(tx, "VE4OK K1ABC RR73")
        heardInOdd(tx, "VE4OK K1ABC RR73")
        assertEquals("logged once, not once per repeat", listOf("K1ABC"), completed)
    }

    @Test
    fun `our RR73 completes the contact when it has been sent, not when it is queued`() {
        val tx = autoTx()
        val completed = mutableListOf<String>()
        tx.onContactComplete = { completed += it.partner }
        tx.arm(profile)
        tx.callStation(engine, "K1ABC", inWindow, SlotParity.ODD)

        heardInOdd(tx, "VE4OK K1ABC R-10")
        assertEquals("K1ABC VE4OK RR73", tx.queued)
        assertEquals("queued is not sent", emptyList<String>(), completed)

        assertEquals("K1ABC VE4OK RR73", tx.decide(inWindow, profile, 14_074_000))
        tx.onTransmitStarted()
        tx.onTransmitFinished(engine, transmitted = true)
        assertEquals(listOf("K1ABC"), completed)
    }

    @Test
    fun `a close cut short by Halt or a fault is not logged by the app`() {
        for (halted in listOf(true, false)) {
            val tx = autoTx()
            val completed = mutableListOf<String>()
            tx.onContactComplete = { completed += it.partner }
            tx.arm(profile)
            tx.callStation(engine, "K1ABC", inWindow, SlotParity.ODD)
            heardInOdd(tx, "VE4OK K1ABC R-10")
            tx.decide(inWindow, profile, 14_074_000)
            tx.onTransmitStarted()
            if (halted) tx.halt(engine)
            tx.onTransmitFinished(engine, transmitted = halted)
            assertEquals(if (halted) "halted" else "SWR abort", emptyList<String>(), completed)
        }
    }

    // --- logging by hand ------------------------------------------

    @Test
    fun `a contact leaves for the Log tab only once our report has been on the air`() {
        val tx = autoTx()
        val left = mutableListOf<String>()
        tx.onContactLeft = { left += it.partner }
        tx.arm(profile)
        tx.callStation(engine, "K1ABC", inWindow, SlotParity.ODD)
        heardInOdd(tx, "VE4OK K1ABC -10")
        assertEquals("K1ABC VE4OK R-10", tx.queued)
        // Their report heard, ours only queued: they never heard it.
        tx.clearPartner()
        assertEquals("queued is not exchanged", emptyList<String>(), left)

        tx.callStation(engine, "K1ABC", inWindow + 30_000, SlotParity.ODD)
        heardInOdd(tx, "VE4OK K1ABC -10", nowMs = inWindow + 30_000)
        assertEquals("K1ABC VE4OK R-10", tx.decide(inWindow + 30_000, profile, 14_074_000))
        tx.onTransmitStarted()
        tx.onTransmitFinished(engine, transmitted = true)
        // The tap on another row that used to lose it.
        tx.callStation(engine, "W9XYZ", inWindow + 60_000, SlotParity.ODD)
        assertEquals(listOf("K1ABC"), left)
    }

    @Test
    fun `their R-report proves ours got through, so a halted close is offered`() {
        val tx = autoTx()
        val left = mutableListOf<String>()
        val completed = mutableListOf<String>()
        tx.onContactLeft = { left += it.partner }
        tx.onContactComplete = { completed += it.partner }
        tx.arm(profile)
        tx.callStation(engine, "K1ABC", inWindow, SlotParity.ODD)
        heardInOdd(tx, "VE4OK K1ABC R-10")
        tx.decide(inWindow, profile, 14_074_000)
        tx.onTransmitStarted()
        tx.halt(engine)
        tx.onTransmitFinished(engine, transmitted = true)
        assertNotNull("halted, the partner is kept", tx.qso)
        assertTrue(tx.qso!!.reportsOnAir)
        tx.clearPartner()
        assertEquals(emptyList<String>(), completed)
        assertEquals(listOf("K1ABC"), left)
    }

    @Test
    fun `a contact logged by hand is never logged or offered again`() {
        val tx = autoTx()
        val left = mutableListOf<String>()
        val completed = mutableListOf<String>()
        tx.onContactLeft = { left += it.partner }
        tx.onContactComplete = { completed += it.partner }
        tx.arm(profile)
        tx.callStation(engine, "K1ABC", inWindow, SlotParity.ODD)
        heardInOdd(tx, "VE4OK K1ABC R-10")
        tx.markLogged(tx.qso!!)
        // Our RR73 goes out after all: already logged.
        assertEquals("K1ABC VE4OK RR73", tx.decide(inWindow, profile, 14_074_000))
        tx.onTransmitStarted()
        tx.onTransmitFinished(engine, transmitted = true)
        tx.clearPartner()
        assertEquals(emptyList<String>(), completed)
        assertEquals(emptyList<String>(), left)
    }

    @Test
    fun `a completed contact is logged and never offered`() {
        val tx = autoTx()
        val left = mutableListOf<String>()
        val completed = mutableListOf<String>()
        tx.onContactLeft = { left += it.partner }
        tx.onContactComplete = { completed += it.partner }
        tx.callStation(engine, "K1ABC", inWindow, SlotParity.ODD)
        heardInOdd(tx, "VE4OK K1ABC R-10")
        heardInOdd(tx, "VE4OK K1ABC RR73")
        tx.clearPartner()
        assertEquals(listOf("K1ABC"), completed)
        assertEquals(emptyList<String>(), left)
    }

    @Test
    fun `after our 73 has gone out the contact is over and nothing more is sent`() {
        // On air, VE2XAA: their RR73, our 73 - then 73 again in our next
        // slot, until the operator pressed Halt.
        val tx = autoTx()
        tx.arm(profile)
        tx.callStation(engine, "VE2XAA", inWindow, SlotParity.ODD)
        heardInOdd(tx, "VE4OK VE2XAA +18")
        assertEquals("VE2XAA VE4OK R-10", tx.decide(inWindow, profile, 14_074_000))
        tx.onTransmitStarted()
        tx.onTransmitFinished(engine, transmitted = true)

        heardInOdd(tx, "VE4OK VE2XAA RR73", nowMs = inWindow + 30_000)
        assertEquals("VE2XAA VE4OK 73", tx.decide(inWindow + 30_000, profile, 14_074_000))
        tx.onTransmitStarted()
        tx.onTransmitFinished(engine, transmitted = true)

        assertNull("no second 73", tx.decide(inWindow + 60_000, profile, 14_074_000))
        assertNull(tx.queued)
        assertNull("the contact is over", tx.qso)
        assertTrue("search and pounce disarms when the contact is over", !tx.armed)
        assertEquals("Contact complete", tx.disarmReason)
    }

    @Test
    fun `a CQ run stays armed when a contact is over`() {
        val tx = autoTx()
        tx.arm(profile)
        tx.startCq(engine, SlotParity.EVEN)
        heardInOdd(tx, "VE4OK VE2XAA FN25")
        heardInOdd(tx, "VE4OK VE2XAA R-07", nowMs = inWindow + 30_000)
        assertEquals("VE2XAA VE4OK RR73", tx.decide(inWindow + 30_000, profile, 14_074_000))
        tx.onTransmitStarted()
        tx.onTransmitFinished(engine, transmitted = true)
        heardInOdd(tx, "VE4OK VE2XAA 73", nowMs = inWindow + 60_000)

        assertNull("the contact is over", tx.qso)
        assertTrue("still armed: the run goes on", tx.armed)
        assertEquals("CQ VE4OK FN25", tx.decide(inWindow + 60_000, profile, 14_074_000))
    }

    @Test
    fun `our RR73 goes out at most twice when they go quiet, then TX stops`() {
        val tx = autoTx()
        tx.arm(profile)
        tx.callStation(engine, "K1ABC", inWindow, SlotParity.ODD)
        heardInOdd(tx, "VE4OK K1ABC R-10")

        var rr73s = 0
        for (slot in 0 until 6) {
            val now = inWindow + slot * 30_000L
            if (tx.decide(now, profile, 14_074_000) == "K1ABC VE4OK RR73") {
                rr73s++
                tx.onTransmitStarted()
                tx.onTransmitFinished(engine, transmitted = true)
            }
        }
        assertEquals(2, rr73s)
        assertNull(tx.qso)
        assertEquals("a finished contact, not a failure", "Contact complete", tx.disarmReason)
    }

    @Test
    fun `a 73 cut short by Halt keeps the partner for the operator`() {
        val tx = autoTx()
        tx.arm(profile)
        tx.callStation(engine, "VE2XAA", inWindow, SlotParity.ODD)
        heardInOdd(tx, "VE4OK VE2XAA +18")
        heardInOdd(tx, "VE4OK VE2XAA RR73")
        assertEquals("VE2XAA VE4OK 73", tx.decide(inWindow, profile, 14_074_000))
        tx.onTransmitStarted()
        tx.halt(engine)
        tx.onTransmitFinished(engine, transmitted = true)
        assertNotNull(tx.qso)
        assertTrue(tx.qso!!.paused)
    }

    @Test
    fun `armed and idle, a station calling us is not answered unless we are calling CQ`() {
        // Answer callers automatically
        // only while calling CQ. Highlighted, and answered by a tap.
        val tx = autoTx()
        tx.arm(profile)
        heardInOdd(tx, "VE4OK K1ABC FN42")
        assertNull(tx.qso)
        assertNull(tx.queued)
        assertNull(tx.decide(inWindow + 15_000, profile, 14_074_000))
    }

    @Test
    fun `a partner heard sending an R-report to someone else is still called, armed`() {
        // Near the end of that contact, and VA3XAE came back
        // to us after one. The disarm cost the slot they could have heard.
        val tx = autoTx()
        tx.arm(profile)
        tx.callStation(engine, "VA3XAC", inWindow, SlotParity.ODD)

        heardInOdd(tx, "KI5XAA VA3XAC R-21", nowMs = inWindow + 30_000)
        assertTrue("still armed", tx.armed)
        assertEquals("the grid is kept", "VA3XAC VE4OK FN25", tx.queued)
        assertTrue("and the contact live", !tx.qso!!.paused)

        // Everyone else in the slot leaves our parity and their report alone.
        heardInOdd(tx, "CQ W2XYZ FN20", "N0ABC K1ABC -03", nowMs = inWindow + 45_000)
        assertEquals(SlotParity.EVEN, tx.committedParity)

        assertEquals("VA3XAC VE4OK FN25", tx.decide(inWindow + 60_000, profile, 14_074_000))
    }

    @Test
    fun `a tap on the partner's R-report to someone else keeps the queued message`() {
        // VE3XA, 5B4XAA: the tap read "next nothing", and so did
        // every tap after it.
        val tx = autoTx()
        tx.callStation(engine, "VE3XA", inWindow, SlotParity.ODD)
        tx.onRowTapped(engine, "LU6XAA VE3XA R-13", ourReportDb = -10, nowMs = inWindow + 15_000, heardIn = SlotParity.ODD)
        assertEquals("VE3XA VE4OK FN25", tx.queued)
        assertEquals("VE3XA", tx.qso?.partner)
        assertEquals("a tap is not arming", false, tx.armed)
    }

    @Test
    fun `a tap on the partner's R grid to someone else keeps the queued message in a contest`() {
        val contest = QsoEngine("VE4OK", "FN25", contest = ContestSetup(ContestSetup.Kind.GRID, "YBDX", "FN25"))
        val tx = autoTx()
        tx.onRowTapped(contest, "CQ YBDX 5B4XAA KM64", ourReportDb = -10, nowMs = inWindow, heardIn = SlotParity.ODD)
        assertEquals("5B4XAA VE4OK FN25", tx.queued)
        tx.onRowTapped(contest, "YG9XAA 5B4XAA R KM64", ourReportDb = -10, nowMs = inWindow + 30_000, heardIn = SlotParity.ODD)
        assertEquals("5B4XAA VE4OK FN25", tx.queued)
    }

    @Test
    fun `a busy partner in a contest is still called, and answers with R grid`() {
        val contest = QsoEngine("VE4OK", "FN25", contest = ContestSetup(ContestSetup.Kind.GRID, "YBDX", "FN25"))
        val tx = autoTx()
        tx.arm(profile)
        tx.callStation(contest, "5B4XAA", inWindow, SlotParity.ODD)
        heardInOdd(tx, "YG9XAA 5B4XAA R KM64", nowMs = inWindow + 30_000, engineUsed = contest)
        assertTrue(tx.armed)
        assertEquals("5B4XAA VE4OK FN25", tx.queued)
        heardInOdd(tx, "VE4OK 5B4XAA R KM64", nowMs = inWindow + 60_000, engineUsed = contest)
        assertEquals("5B4XAA VE4OK RR73", tx.queued)
    }

    @Test
    fun `a busy partner in a CQ run is still dropped, and CQ resumes`() {
        val tx = autoTx()
        tx.arm(profile)
        tx.startCq(engine, SlotParity.EVEN)
        heardInOdd(tx, "VE4OK K1ABC FN42", nowMs = inWindow)
        assertEquals("K1ABC", tx.qso?.partner)
        heardInOdd(tx, "W2XYZ K1ABC R-05", nowMs = inWindow + 30_000)
        assertNull(tx.qso)
        assertTrue(tx.armed)
    }

    @Test
    fun `an RR73 still waiting for our slot goes out after the partner returns to CQ`() {
        // 5B4XAA: tapped too late for the slot, then their CQ ended the
        // contact with the RR73 unsent.
        val contest = QsoEngine("VE4OK", "FN25", contest = ContestSetup(ContestSetup.Kind.GRID, "YBDX", "FN25"))
        val tx = autoTx()
        val completed = mutableListOf<String>()
        tx.onContactComplete = { completed += it.partner }
        tx.arm(profile)
        tx.callStation(contest, "5B4XAA", inWindow, SlotParity.ODD)
        tx.onRowTapped(contest, "VE4OK 5B4XAA R KM64", ourReportDb = -10, nowMs = inWindow + 15_000, heardIn = SlotParity.ODD)
        assertEquals("5B4XAA VE4OK RR73", tx.queued)

        heardInOdd(tx, "CQ YBDX 5B4XAA KM64", nowMs = inWindow + 45_000, engineUsed = contest)
        assertTrue(tx.armed)
        assertEquals("5B4XAA VE4OK RR73", tx.decide(inWindow + 60_000, profile, 14_074_000))
        tx.onTransmitStarted()
        tx.onTransmitFinished(contest, transmitted = true)
        assertEquals(listOf("5B4XAA"), completed)

        // Sent once: their next CQ ends it.
        heardInOdd(tx, "CQ YBDX 5B4XAA KM64", nowMs = inWindow + 75_000, engineUsed = contest)
        assertNull(tx.qso)
    }

    @Test
    fun `our 73 still waiting for our slot goes out after the partner returns to CQ`() {
        val tx = autoTx()
        tx.arm(profile)
        tx.callStation(engine, "K1ABC", inWindow, SlotParity.ODD)
        heardInOdd(tx, "VE4OK K1ABC -12", nowMs = inWindow + 15_000)
        assertEquals("K1ABC VE4OK R-10", tx.decide(inWindow + 30_000, profile, 14_074_000))
        tx.onTransmitStarted()
        tx.onTransmitFinished(engine, transmitted = true)
        heardInOdd(tx, "VE4OK K1ABC RR73", nowMs = inWindow + 45_000)
        assertEquals("K1ABC VE4OK 73", tx.queued)

        heardInOdd(tx, "CQ K1ABC FN42", nowMs = inWindow + 75_000)
        assertEquals("K1ABC VE4OK 73", tx.decide(inWindow + 90_000, profile, 14_074_000))
        tx.onTransmitStarted()
        tx.onTransmitFinished(engine, transmitted = true)
        assertNull("73 is the end", tx.qso)
    }

    @Test
    fun `a busy partner does not stop us giving up`() {
        assertEquals(callsUntilGivingUp(), callsUntilGivingUp("W2XYZ K1ABC R-05"))
    }

    @Test
    fun `a busy partner who then answers us carries the contact on`() {
        val tx = autoTx()
        tx.arm(profile)
        tx.callStation(engine, "K1ABC", inWindow, SlotParity.ODD)
        heardInOdd(tx, "W2XYZ K1ABC R-05", nowMs = inWindow + 30_000)
        heardInOdd(tx, "VE4OK K1ABC -12", nowMs = inWindow + 60_000)
        assertEquals("K1ABC VE4OK R-10", tx.queued)
        assertTrue("armed throughout", tx.armed)
    }

    @Test
    fun `while calling CQ a caller who goes off with someone else is dropped and CQ resumes`() {
        val tx = autoTx()
        tx.arm(profile)
        tx.startCq(engine, SlotParity.EVEN)
        heardInOdd(tx, "VE4OK K1ABC FN42")
        assertEquals("K1ABC", tx.qso?.partner)
        heardInOdd(tx, "W2XYZ K1ABC R-05", nowMs = inWindow + 30_000)
        assertNull(tx.qso)
        assertTrue("still armed", tx.armed)
        assertEquals("CQ VE4OK FN25", tx.decide(inWindow + 60_000, profile, 14_074_000))
    }

    @Test
    fun `a halted contact is not disturbed by the rest of the slot`() {
        val tx = autoTx()
        tx.arm(profile)
        tx.callStation(engine, "K1ABC", inWindow, SlotParity.ODD)
        heardInOdd(tx, "VE4OK K1ABC -12")
        tx.halt(engine)
        // Heard in EVEN: applied, these would have flipped our parity to ODD.
        tx.onDecodes(engine, listOf(decode("CQ W2XYZ FN20")), { -3 }, inWindow, SlotParity.EVEN)
        assertEquals(SlotParity.EVEN, tx.txParity)
        assertEquals(-10, tx.partnerReportDb)
    }

    @Test
    fun `a partner heard in our parity moves us to the other one, whoever they were talking to`() {
        // VE3XAB: tapped from their old odd-slot message, so we were set
        // for even, then their CQ on even was ignored and we called them on top of it.
        val tx = autoTx()
        tx.arm(profile)
        tx.callStation(engine, "VE3XAB", inWindow, SlotParity.ODD)
        assertEquals(SlotParity.EVEN, tx.txParity)

        tx.onDecodes(engine, listOf(decode("CQ VE3XAB FN03")), { -9 }, inWindow, SlotParity.EVEN)
        assertEquals(SlotParity.ODD, tx.txParity)
        assertNull("not in their slot", tx.decide(inWindow + 30_000, profile, 14_074_000))
        assertEquals("VE3XAB VE4OK FN25", tx.decide(inWindow + 15_000, profile, 14_074_000))

        // Someone else in that slot moves nothing.
        tx.onDecodes(engine, listOf(decode("CQ W2XYZ FN20")), { -3 }, inWindow + 30_000, SlotParity.ODD)
        assertEquals(SlotParity.ODD, tx.txParity)
    }

    @Test
    fun `a station we are calling that calls CQ is still called`() {
        // On air, VE2XAA: the old rule paused on their CQ and refused to
        // answer it until the operator tapped it.
        val tx = autoTx()
        tx.arm(profile)
        tx.callStation(engine, "VE2XAA", inWindow - 15_000, SlotParity.ODD)
        heardInOdd(tx, "CQ VE2XAA FN35", "W2XYZ VE2XAA RR73", nowMs = inWindow)
        assertEquals("VE2XAA VE4OK FN25", tx.decide(inWindow, profile, 14_074_000))
    }

    // --- calling CQ -----------------------------------------------

    @Test
    fun `calling CQ queues our CQ in the chosen parity, and does not arm`() {
        val tx = autoTx()
        tx.startCq(engine, SlotParity.EVEN)
        assertEquals("CQ VE4OK FN25", tx.nextMessage)
        assertNull("not armed", tx.decide(inWindow, profile, 14_074_000))
        tx.arm(profile)
        assertNull("not in the other parity", tx.decide(inWindow + 15_000, profile, 14_074_000))
        assertEquals("CQ VE4OK FN25", tx.decide(inWindow + 30_000, profile, 14_074_000))
    }

    @Test
    fun `while calling CQ the first station to call us is answered, and CQ resumes after`() {
        val tx = autoTx()
        val completed = mutableListOf<String>()
        tx.onContactComplete = { completed += it.partner }
        tx.arm(profile)
        tx.startCq(engine, SlotParity.EVEN)
        tx.decide(inWindow, profile, 14_074_000)
        tx.onTransmitStarted()
        tx.onTransmitFinished(engine, transmitted = true)
        assertEquals(1, tx.unansweredCqs)

        heardInOdd(tx, "VE4OK K1ABC FN42", "VE4OK W2XYZ EM10", nowMs = inWindow + 15_000)
        assertEquals("K1ABC", tx.qso!!.partner)
        assertEquals(0, tx.unansweredCqs)
        assertEquals("K1ABC VE4OK -10", tx.decide(inWindow + 30_000, profile, 14_074_000))
        tx.onTransmitStarted()
        tx.onTransmitFinished(engine, transmitted = true)

        // W2XYZ called too, and waited: their report rides with our RR73.
        heardInOdd(tx, "VE4OK K1ABC R-12", nowMs = inWindow + 45_000)
        assertEquals("K1ABC RR73; W2XYZ <VE4OK> -10", tx.decide(inWindow + 60_000, profile, 14_074_000))
        tx.onTransmitStarted()
        tx.onTransmitFinished(engine, transmitted = true)
        assertEquals(listOf("K1ABC"), completed)
        assertEquals("W2XYZ", tx.qso!!.partner)

        heardInOdd(tx, "VE4OK W2XYZ R-07", nowMs = inWindow + 75_000)
        assertEquals("W2XYZ VE4OK RR73", tx.decide(inWindow + 90_000, profile, 14_074_000))
        tx.onTransmitStarted()
        tx.onTransmitFinished(engine, transmitted = true)
        assertEquals(listOf("K1ABC", "W2XYZ"), completed)

        heardInOdd(tx, "VE4OK W2XYZ 73", nowMs = inWindow + 105_000)
        assertNull(tx.qso)
        assertEquals("CQ VE4OK FN25", tx.decide(inWindow + 120_000, profile, 14_074_000))
    }

    @Test
    fun `CQ stops and TX disarms after the CQ limit goes unanswered`() {
        val tx = autoTx()
        tx.arm(profile)
        tx.startCq(engine, SlotParity.EVEN)
        repeat(AutoTx.CQ_LIMIT) { i ->
            assertEquals("CQ VE4OK FN25", tx.decide(inWindow + i * 30_000L, profile, 14_074_000))
            tx.onTransmitStarted()
            tx.onTransmitFinished(engine, transmitted = true)
        }
        assertTrue("a caller may still answer the last CQ", tx.armed)
        val lastCq = evenSlot + (AutoTx.CQ_LIMIT - 1) * 30_000L
        tx.onDecodes(engine, emptyList(), { -10 }, lastCq + 29_800, SlotParity.ODD, slotStartMs = lastCq + 15_000)
        assertTrue(!tx.armed)
        assertTrue(!tx.callingCq)
        assertNull(tx.nextMessage)
        assertEquals("No reply to CQ", tx.disarmReason)
    }

    @Test
    fun `with the CQ stop limit off, CQ never stops by itself`() {
        val tx = autoTx()
        tx.cqStopLimit = { 0 }
        tx.arm(profile)
        tx.startCq(engine, SlotParity.EVEN)
        val count = 3 * AutoTx.CQ_LIMIT
        repeat(count) { i ->
            assertEquals("CQ VE4OK FN25", tx.decide(inWindow + i * 30_000L, profile, 14_074_000))
            tx.onTransmitStarted()
            tx.onTransmitFinished(engine, transmitted = true)
            val cq = evenSlot + i * 30_000L
            tx.onDecodes(engine, emptyList(), { -10 }, cq + 29_800, SlotParity.ODD, slotStartMs = cq + 15_000)
        }
        assertTrue(tx.armed)
        assertTrue(tx.callingCq)
        assertEquals(count, tx.unansweredCqs)
    }

    @Test
    fun `a chosen CQ stop limit stops CQ at that count`() {
        val tx = autoTx()
        tx.cqStopLimit = { 5 }
        tx.arm(profile)
        tx.startCq(engine, SlotParity.EVEN)
        repeat(5) { i ->
            assertEquals("CQ VE4OK FN25", tx.decide(inWindow + i * 30_000L, profile, 14_074_000))
            tx.onTransmitStarted()
            tx.onTransmitFinished(engine, transmitted = true)
        }
        val lastCq = evenSlot + 4 * 30_000L
        tx.onDecodes(engine, emptyList(), { -10 }, lastCq + 29_800, SlotParity.ODD, slotStartMs = lastCq + 15_000)
        assertTrue(!tx.armed)
        assertEquals("No reply to CQ", tx.disarmReason)
    }

    @Test
    fun `a caller answering the last CQ is answered`() {
        val tx = autoTx()
        tx.arm(profile)
        tx.startCq(engine, SlotParity.EVEN)
        repeat(AutoTx.CQ_LIMIT) { i ->
            tx.decide(inWindow + i * 30_000L, profile, 14_074_000)
            tx.onTransmitStarted()
            tx.onTransmitFinished(engine, transmitted = true)
        }
        val lastCq = evenSlot + (AutoTx.CQ_LIMIT - 1) * 30_000L
        tx.onDecodes(engine, listOf(decode("VE4OK K1ABC FN31")), { -10 }, lastCq + 29_800, SlotParity.ODD, slotStartMs = lastCq + 15_000)
        assertTrue(tx.armed)
        assertEquals("K1ABC", tx.qso?.partner)
        assertEquals("K1ABC VE4OK -10", tx.decide(lastCq + 31_000, profile, 14_074_000))
    }

    /** Calls K1ABC every even slot with no answer; returns how many went out before TX gave up. */
    private fun callsUntilGivingUp(vararg heard: String): Int {
        val tx = autoTx()
        tx.arm(profile)
        tx.callStation(engine, "K1ABC", evenSlot - 30_000, SlotParity.ODD)
        var sent = 0
        for (k in 0 until 20) {
            val slot = evenSlot + k * 30_000L
            if (tx.decide(slot + 1_000, profile, 14_074_000) == null) break
            sent++
            tx.onTransmitStarted()
            tx.onTransmitFinished(engine, transmitted = true)
            tx.onDecodes(engine, heard.map { decode(it) }, { -10 }, slot + 29_800, SlotParity.ODD, slotStartMs = slot + 15_000)
            if (!tx.armed) break
        }
        assertEquals("No answer - gave up", tx.disarmReason)
        return sent
    }

    @Test
    fun `a reply to the last try is answered, not dropped by giving up first`() {
        // VE2XB: we gave up at 21:35:14, as our last call ended, and their
        // report came in the 21:35:15 slot. They were answered only by a tap.
        val tries = callsUntilGivingUp()
        val tx = autoTx()
        tx.arm(profile)
        tx.callStation(engine, "K1ABC", evenSlot - 30_000, SlotParity.ODD)
        for (k in 0 until tries) {
            val slot = evenSlot + k * 30_000L
            assertEquals("K1ABC VE4OK FN25", tx.decide(slot + 1_000, profile, 14_074_000))
            tx.onTransmitStarted()
            tx.onTransmitFinished(engine, transmitted = true)
            val reply = if (k == tries - 1) listOf(decode("VE4OK K1ABC -05")) else emptyList()
            tx.onDecodes(engine, reply, { -10 }, slot + 29_800, SlotParity.ODD, slotStartMs = slot + 15_000)
        }
        assertTrue("still armed", tx.armed)
        assertEquals("K1ABC", tx.qso?.partner)
        assertEquals("K1ABC VE4OK R-10", tx.decide(evenSlot + tries * 30_000L + 1_000, profile, 14_074_000))
    }

    @Test
    fun `giving up waits for the partner's slot, and goes ahead if its decodes never come`() {
        val tries = callsUntilGivingUp()
        val tx = autoTx()
        tx.arm(profile)
        tx.callStation(engine, "K1ABC", evenSlot - 30_000, SlotParity.ODD)
        for (k in 0 until tries) {
            val slot = evenSlot + k * 30_000L
            tx.decide(slot + 1_000, profile, 14_074_000)
            tx.onTransmitStarted()
            tx.onTransmitFinished(engine, transmitted = true)
            // The last partner slot is lost: no decodes at all.
            if (k < tries - 1) tx.onDecodes(engine, emptyList(), { -10 }, slot + 29_800, SlotParity.ODD, slotStartMs = slot + 15_000)
        }
        assertTrue("not before their slot", tx.armed)
        assertNull("no further repeat", tx.decide(evenSlot + tries * 30_000L + 1_000, profile, 14_074_000))
        assertTrue(!tx.armed)
        assertEquals("No answer - gave up", tx.disarmReason)
    }

    @Test
    fun `a contact started by tapping a caller also resets the CQ count`() {
        val tx = autoTx()
        tx.arm(profile)
        tx.startCq(engine, SlotParity.EVEN)
        repeat(3) { i ->
            tx.decide(inWindow + i * 30_000L, profile, 14_074_000)
            tx.onTransmitStarted()
            tx.onTransmitFinished(engine, transmitted = true)
        }
        assertEquals(3, tx.unansweredCqs)
        tx.onRowTapped(engine, "VE4OK K1ABC FN42", ourReportDb = -10, nowMs = inWindow + 100_000, heardIn = SlotParity.ODD)
        assertEquals(0, tx.unansweredCqs)
        assertTrue("answering a caller keeps CQ on", tx.callingCq)
    }

    @Test
    fun `tapping a station to call stops CQ, and Halt stops it too`() {
        val tx = autoTx()
        tx.startCq(engine, SlotParity.EVEN)
        tx.onRowTapped(engine, "CQ K1ABC FN42", ourReportDb = -10, nowMs = inWindow, heardIn = SlotParity.ODD)
        assertTrue(!tx.callingCq)
        assertEquals("K1ABC VE4OK FN25", tx.nextMessage)

        tx.startCq(engine, SlotParity.EVEN)
        assertNull("starting CQ drops the call", tx.qso)
        tx.halt(engine)
        assertTrue(!tx.callingCq)
        assertNull(tx.nextMessage)
    }

    // --- tapping a contest station ------------------------------

    @Test
    fun `tapping a contest CQ calls them, and marks them a contest partner while they are`() {
        val tx = autoTx()
        tx.onRowTapped(engine, "CQ FD K1ABC FN42", ourReportDb = -10, nowMs = inWindow, heardIn = SlotParity.ODD)
        assertEquals("K1ABC VE4OK FN25", tx.nextMessage)
        assertEquals("K1ABC", tx.contestPartner?.call)

        tx.onRowTapped(engine, "CQ W9XYZ EN37", ourReportDb = -10, nowMs = inWindow + 30_000, heardIn = SlotParity.ODD)
        assertNull("an ordinary CQ is no contest", tx.contestPartner)
    }

    @Test
    fun `tapping a contest exchange calls its sender with a standard message`() {
        val tx = autoTx()
        tx.onRowTapped(engine, "W1AW K1ABC R 2B EMA", ourReportDb = -10, nowMs = inWindow, heardIn = SlotParity.ODD)
        assertEquals("K1ABC VE4OK FN25", tx.nextMessage)
        assertEquals("K1ABC", tx.contestPartner?.call)
    }

    // --- tapping a row ------------------------------------------------------------

    @Test
    fun `tapping their report to us answers it with R and our report, keeping theirs`() {
        // On air, VE2XA: the tap restarted the call and dropped the -14.
        val tx = autoTx()
        tx.callStation(engine, "VE2XA", inWindow, SlotParity.ODD)
        tx.onRowTapped(engine, "VE4OK VE2XA -14", ourReportDb = 10, nowMs = inWindow, heardIn = SlotParity.ODD)
        assertEquals("VE2XA VE4OK R+10", tx.queued)
        assertEquals(-14, tx.qso!!.reportReceivedDb)
        assertEquals("the contact continues, not restarts", inWindow, tx.qso!!.startedAtMs)
        assertEquals(SlotParity.EVEN, tx.txParity)
    }

    @Test
    fun `tapping their report after a Halt resumes the contact but does not arm`() {
        val tx = autoTx()
        tx.arm(profile)
        tx.callStation(engine, "VE2XA", inWindow, SlotParity.ODD)
        tx.halt(engine)
        tx.onRowTapped(engine, "VE4OK VE2XA -14", ourReportDb = 10, nowMs = inWindow + 30_000, heardIn = SlotParity.ODD)
        assertEquals("VE2XA VE4OK R+10", tx.queued)
        assertTrue(!tx.qso!!.paused)
        assertTrue("tapping is not arming", !tx.armed)
    }

    @Test
    fun `tapping a station calling us with no contact in progress answers them`() {
        val tx = autoTx()
        tx.onRowTapped(engine, "VE4OK K1ABC FN42", ourReportDb = -7, nowMs = inWindow, heardIn = SlotParity.ODD)
        assertEquals("K1ABC VE4OK -07", tx.queued)
        assertEquals("FN42", tx.qso!!.partnerGrid)
    }

    @Test
    fun `tapping a CQ still starts a fresh call, and keeps the grid from the CQ`() {
        val tx = autoTx()
        tx.onRowTapped(engine, "CQ K1ABC FN42", ourReportDb = -7, nowMs = inWindow, heardIn = SlotParity.ODD)
        assertEquals("K1ABC VE4OK FN25", tx.queued)
        assertEquals("FN42", tx.qso!!.partnerGrid)
    }

    @Test
    fun `a report to us with no measured SNR is not answered with a made-up one`() {
        val tx = autoTx()
        tx.onRowTapped(engine, "VE4OK VE2XA -14", ourReportDb = null, nowMs = inWindow, heardIn = SlotParity.ODD)
        assertEquals("falls back to calling them", "VE2XA VE4OK FN25", tx.queued)
    }

    @Test
    fun `sending a report is not a close`() {
        val tx = autoTx()
        val completed = mutableListOf<String>()
        tx.onContactComplete = { completed += it.partner }
        tx.arm(profile)
        tx.callStation(engine, "K1ABC", inWindow, SlotParity.ODD)
        heardInOdd(tx, "VE4OK K1ABC -10")
        tx.decide(inWindow, profile, 14_074_000)
        tx.onTransmitStarted()
        tx.onTransmitFinished(engine, transmitted = true)
        assertEquals(emptyList<String>(), completed)
    }
    // --- a station that missed our 73 (on air) --------

    /** Report, R-report, their RRR, our 73 sent: a finished, logged contact. */
    private fun finishedWithRrr(tx: AutoTx, completed: MutableList<String>) {
        tx.onContactComplete = { completed += it.partner }
        tx.arm(profile)
        tx.callStation(engine, "VA3XAA", inWindow, SlotParity.ODD)
        heardInOdd(tx, "VE4OK VA3XAA -07")
        assertEquals("VA3XAA VE4OK R-10", tx.decide(inWindow, profile, 7_074_000))
        tx.onTransmitStarted()
        tx.onTransmitFinished(engine, transmitted = true)
        heardInOdd(tx, "VE4OK VA3XAA RRR", nowMs = inWindow + 30_000)
        assertEquals("VA3XAA VE4OK 73", tx.decide(inWindow + 30_000, profile, 7_074_000))
        tx.onTransmitStarted()
        tx.onTransmitFinished(engine, transmitted = true)
        assertNull("the contact is over", tx.qso)
        assertTrue("and search and pounce disarmed", !tx.armed)
        // Answering the tail is the operator's choice, and so is re-arming for it.
        tx.arm(profile)
    }

    @Test
    fun `their repeated RRR after our 73 is not answered on its own`() {
        val tx = autoTx()
        val completed = mutableListOf<String>()
        finishedWithRrr(tx, completed)
        heardInOdd(tx, "VE4OK VA3XAA RRR", nowMs = inWindow + 60_000)
        assertNull("no automatic reply once the contact is over", tx.decide(inWindow + 60_000, profile, 7_074_000))
    }

    @Test
    fun `tapping their repeated RRR queues our 73, not a fresh call with our grid`() {
        val tx = autoTx()
        val completed = mutableListOf<String>()
        finishedWithRrr(tx, completed)

        tx.onRowTapped(engine, "VE4OK VA3XAA RRR", -10, inWindow + 60_000, SlotParity.ODD)
        assertEquals("VA3XAA VE4OK 73", tx.queued)
        assertEquals("VA3XAA VE4OK 73", tx.decide(inWindow + 60_000, profile, 7_074_000))
        tx.onTransmitStarted()
        tx.onTransmitFinished(engine, transmitted = true)

        assertEquals("logged once, not again for the repeat", listOf("VA3XAA"), completed)
        assertNull("and over again after the second 73", tx.qso)
        assertNull(tx.decide(inWindow + 90_000, profile, 7_074_000))
    }

    @Test
    fun `a queued 73 survives their next RRR and is sent`() {
        // The screen said 73 was next; their RRR repeat, decoded before our slot,
        // cleared it and nothing went out.
        val tx = autoTx()
        tx.arm(profile)
        tx.callStation(engine, "VA3XAA", inWindow, SlotParity.ODD)
        heardInOdd(tx, "VE4OK VA3XAA -07")
        heardInOdd(tx, "VE4OK VA3XAA RRR")
        assertEquals("VA3XAA VE4OK 73", tx.queued)
        heardInOdd(tx, "VE4OK VA3XAA RRR")
        assertEquals("VA3XAA VE4OK 73", tx.queued)
        assertEquals("VA3XAA VE4OK 73", tx.decide(inWindow, profile, 7_074_000))
    }

    @Test
    fun `the picker replaces the queued message, and works after the contact ended`() {
        val tx = autoTx()
        val completed = mutableListOf<String>()
        finishedWithRrr(tx, completed)
        assertEquals("VA3XAA", tx.pickerPartner)

        // The contact has ended, so the close is our 73, not another RR73.
        assertEquals("73", tx.pickLabel(QsoEngine.Step.CLOSE))
        assertTrue(tx.choose(engine, QsoEngine.Step.CLOSE, inWindow + 60_000))
        assertEquals("VA3XAA VE4OK 73", tx.queued)
        assertEquals(QsoEngine.Step.CLOSE, tx.queuedStep)
        assertEquals("in our parity", "VA3XAA VE4OK 73", tx.decide(inWindow + 60_000, profile, 7_074_000))

        assertTrue(tx.choose(engine, QsoEngine.Step.REPORT, inWindow + 60_000))
        assertEquals("VA3XAA VE4OK -10", tx.queued)
    }

    @Test
    fun `a pick sticks when the partner answers, until AUTO`() {
        // On air with VA3XAB: RR73 picked, and their repeated plain report
        // replaced it with R-05 before our slot.
        val tx = autoTx()
        tx.arm(profile)
        tx.callStation(engine, "VA3XAB", inWindow, SlotParity.ODD)
        heardInOdd(tx, "VE4OK VA3XAB -07")
        assertEquals("VA3XAB VE4OK R-10", tx.queued)
        assertEquals("RR73", tx.pickLabel(QsoEngine.Step.CLOSE))

        assertTrue(tx.choose(engine, QsoEngine.Step.CLOSE, inWindow))
        heardInOdd(tx, "VE4OK VA3XAB -07")
        assertEquals("VA3XAB VE4OK RR73", tx.queued)
        assertEquals(QsoEngine.Step.CLOSE, tx.pinned)
        heardInOdd(tx, "VE4OK VA3XAB -08")
        assertEquals("still pinned after another", "VA3XAB VE4OK RR73", tx.queued)
        assertEquals("their report is still recorded", -8, tx.qso!!.reportReceivedDb)

        tx.auto(inWindow)
        assertNull(tx.pinned)
        assertEquals("the automatic answer to their latest", "VA3XAB VE4OK R-10", tx.queued)
        assertEquals(QsoEngine.Step.REPORT, tx.queuedStep)
    }

    @Test
    fun `a pinned report gains its R when their report arrives`() {
        val tx = autoTx()
        tx.callStation(engine, "K1ABC", inWindow, SlotParity.ODD)
        heardInOdd(tx, "VE4OK K1ABC FN42")
        assertTrue(tx.choose(engine, QsoEngine.Step.REPORT, inWindow))
        assertEquals("K1ABC VE4OK -10", tx.queued)
        heardInOdd(tx, "VE4OK K1ABC -15")
        assertEquals("K1ABC VE4OK R-10", tx.queued)
        assertEquals("R-10", tx.pickLabel(QsoEngine.Step.REPORT))
    }

    @Test
    fun `their close ends the contact and with it the pick`() {
        val tx = autoTx()
        tx.callStation(engine, "K1ABC", inWindow, SlotParity.ODD)
        heardInOdd(tx, "VE4OK K1ABC -07")
        assertTrue(tx.choose(engine, QsoEngine.Step.REPORT, inWindow))
        heardInOdd(tx, "VE4OK K1ABC RR73")
        assertNull(tx.pinned)
        assertEquals("K1ABC VE4OK 73", tx.queued)
    }

    @Test
    fun `a pick early in our transmission restarts it, a late one waits for the next slot`() {
        val tx = autoTx()
        tx.arm(profile)
        tx.callStation(engine, "K1ABC", inWindow, SlotParity.ODD)
        heardInOdd(tx, "VE4OK K1ABC -07")
        assertEquals("K1ABC VE4OK R-10", tx.decide(inWindow, profile, 14_074_000))
        tx.onTransmitStarted()

        // 1.5 s into our slot: inside the late-start window with room to restart.
        assertTrue(tx.choose(engine, QsoEngine.Step.CLOSE, evenSlot + 1_500))
        assertTrue("the transmission on the air stops", !tx.mayContinueTransmitting)
        tx.onTransmitFinished(engine)
        assertTrue("still armed", tx.armed)
        assertEquals("and starts again in the same slot", "K1ABC VE4OK RR73", tx.decide(evenSlot + 1_800, profile, 14_074_000))
        tx.onTransmitStarted()

        // 3.5 s in: too late to restart. The transmission carries on; the pick waits.
        assertTrue(tx.choose(engine, QsoEngine.Step.REPORT, evenSlot + 3_500))
        assertTrue(tx.mayContinueTransmitting)
        assertEquals("K1ABC VE4OK R-10", tx.queued)
    }

    @Test
    fun `a 73 on the air keeps the contact when a pick came during it`() {
        val tx = autoTx()
        tx.arm(profile)
        tx.callStation(engine, "K1ABC", inWindow, SlotParity.ODD)
        heardInOdd(tx, "VE4OK K1ABC -07")
        heardInOdd(tx, "VE4OK K1ABC RR73")
        assertEquals("K1ABC VE4OK 73", tx.decide(inWindow, profile, 14_074_000))
        tx.onTransmitStarted()
        assertTrue(tx.choose(engine, QsoEngine.Step.REPORT, evenSlot + 5_000))
        tx.onTransmitFinished(engine)
        assertEquals("K1ABC", tx.qso?.partner)
        assertEquals("K1ABC VE4OK R-10", tx.queued)
    }

    @Test
    fun `picking does not arm`() {
        val tx = autoTx()
        tx.callStation(engine, "K1ABC", inWindow, SlotParity.ODD)
        assertTrue(tx.choose(engine, QsoEngine.Step.CLOSE, inWindow))
        assertEquals("K1ABC VE4OK RR73", tx.queued)
        assertNull(tx.decide(inWindow, profile, 14_074_000))
        assertEquals(TxScheduler.Verdict.NOT_ARMED, tx.lastVerdict)
    }

    @Test
    fun `with nobody to address the picker does nothing`() {
        val tx = autoTx()
        assertTrue(!tx.choose(engine, QsoEngine.Step.GRID, inWindow))
        assertNull(tx.queued)
    }

    // --- the CQ ⋯ panel ------------------------------------------

    @Test
    fun `a CQ word goes into the CQ, and a change reaches a run in progress`() {
        val tx = autoTx()
        tx.arm(profile)
        tx.startCq(engine, SlotParity.EVEN, "POTA")
        assertEquals("CQ POTA VE4OK FN25", tx.decide(inWindow, profile, 14_074_000))
        tx.onTransmitStarted()
        tx.onTransmitFinished(engine, transmitted = true)

        tx.changeCqWord(engine, "SOTA")
        assertEquals("CQ SOTA VE4OK FN25", tx.decide(inWindow + 30_000, profile, 14_074_000))
    }

    @Test
    fun `changing the CQ word starts nothing`() {
        val tx = autoTx()
        tx.changeCqWord(engine, "POTA")
        assertTrue(!tx.callingCq)
        assertNull(tx.nextMessage)
    }

    @Test
    fun `a station called from the panel transmits in the side given, and is marked unheard`() {
        val tx = autoTx()
        tx.arm(profile)
        tx.callByCallsign(engine, "W1AW", inWindow, txIn = SlotParity.EVEN, heard = false)
        assertTrue(tx.partnerUnheard)
        assertTrue("not armed by calling", tx.armed)
        assertEquals(SlotParity.EVEN, tx.committedParity)
        assertEquals("W1AW VE4OK FN25", tx.decide(inWindow, profile, 14_074_000))
        tx.onTransmitStarted()
        tx.onTransmitFinished(engine, transmitted = true)

        // They answer: heard now, and the exchange runs on its own.
        heardInOdd(tx, "VE4OK W1AW -14", nowMs = inWindow + 15_000)
        assertTrue(!tx.partnerUnheard)
        assertEquals("W1AW VE4OK R-10", tx.decide(inWindow + 30_000, profile, 14_074_000))
    }

    @Test
    fun `calling a station from the panel does not arm`() {
        val tx = autoTx()
        tx.callByCallsign(engine, "W1AW", inWindow, txIn = SlotParity.EVEN, heard = true)
        assertTrue(!tx.armed)
        assertTrue(!tx.partnerUnheard)
        assertNull(tx.decide(inWindow, profile, 14_074_000))
    }

    @Test
    fun `calling from the panel stops a CQ run, like a tap`() {
        val tx = autoTx()
        tx.startCq(engine, SlotParity.EVEN, "POTA")
        tx.callByCallsign(engine, "W1AW", inWindow, txIn = SlotParity.EVEN, heard = false)
        assertTrue(!tx.callingCq)
    }

    @Test
    fun `text in a contact goes once, ahead of the exchange, then the exchange carries on`() {
        val tx = autoTx()
        tx.arm(profile)
        tx.callStation(engine, "K1ABC", inWindow, SlotParity.ODD)
        heardInOdd(tx, "VE4OK K1ABC -12")
        tx.queueText(AutoTx.QueuedText("TNX 73 GL", freeText = true), SlotParity.ODD)

        assertEquals("TNX 73 GL", tx.decide(inWindow, profile, 14_074_000))
        assertTrue(tx.lastSentFreeText)
        tx.onTransmitStarted()
        tx.onTransmitFinished(engine, transmitted = true)

        assertNull("sent once", tx.text)
        assertTrue("still armed", tx.armed)
        assertEquals("the exchange resumes", "K1ABC VE4OK R-10", tx.decide(inWindow + 30_000, profile, 14_074_000))
        assertTrue(!tx.lastSentFreeText)
        assertEquals("text is not an unanswered try", "K1ABC", tx.qso?.partner)
    }

    @Test
    fun `text with no contact goes in the side given, then TX disarms and the text stays for ARM`() {
        val tx = autoTx()
        tx.arm(profile)
        tx.queueText(AutoTx.QueuedText("QRT 73", freeText = true), SlotParity.EVEN)
        assertEquals(SlotParity.EVEN, tx.committedParity)
        assertEquals("QRT 73", tx.decide(inWindow, profile, 14_074_000))
        tx.onTransmitStarted()
        tx.onTransmitFinished(engine, transmitted = true)

        assertTrue(!tx.armed)
        assertEquals("Text sent", tx.disarmReason)
        assertNull("nothing more without ARM", tx.decide(inWindow + 30_000, profile, 14_074_000))

        // Still queued, so ARM sends it again.
        assertEquals("QRT 73", tx.text?.text)
        tx.arm(profile)
        assertEquals("QRT 73", tx.decide(inWindow + 60_000, profile, 14_074_000))
        assertTrue(tx.lastSentFreeText)
    }

    @Test
    fun `a text already sent never goes ahead of a new contact or CQ`() {
        val tx = autoTx()
        tx.arm(profile)
        tx.queueText(AutoTx.QueuedText("QRT 73", freeText = true), SlotParity.EVEN)
        assertEquals("QRT 73", tx.decide(inWindow, profile, 14_074_000))
        tx.onTransmitStarted()
        tx.onTransmitFinished(engine, transmitted = true)

        tx.callStation(engine, "K1ABC", inWindow + 30_000, SlotParity.ODD)
        assertNull(tx.text)
        tx.arm(profile)
        assertEquals("K1ABC VE4OK FN25", tx.decide(inWindow + 60_000, profile, 14_074_000))

        // The same for a CQ run.
        val cq = autoTx()
        cq.arm(profile)
        cq.queueText(AutoTx.QueuedText("QRT 73", freeText = true), SlotParity.EVEN)
        cq.decide(inWindow, profile, 14_074_000)
        cq.onTransmitStarted()
        cq.onTransmitFinished(engine, transmitted = true)
        cq.startCq(engine, SlotParity.EVEN)
        assertNull(cq.text)
    }

    @Test
    fun `a text not yet sent still goes ahead of a station tapped after it`() {
        val tx = autoTx()
        tx.arm(profile)
        tx.queueText(AutoTx.QueuedText("TNX 73 GL", freeText = true), SlotParity.EVEN)
        tx.callStation(engine, "K1ABC", inWindow, SlotParity.ODD)
        assertEquals("TNX 73 GL", tx.decide(inWindow, profile, 14_074_000))
    }

    @Test
    fun `text during a CQ run goes once and the run carries on, uncounted`() {
        val tx = autoTx()
        tx.arm(profile)
        tx.startCq(engine, SlotParity.EVEN, "POTA")
        tx.queueText(AutoTx.QueuedText("QRT 73", freeText = true), SlotParity.ODD)
        assertEquals("the run's side, not the text's", SlotParity.EVEN, tx.committedParity)
        assertEquals("QRT 73", tx.decide(inWindow, profile, 14_074_000))
        tx.onTransmitStarted()
        tx.onTransmitFinished(engine, transmitted = true)

        assertTrue(tx.armed)
        assertEquals(0, tx.unansweredCqs)
        assertEquals("CQ POTA VE4OK FN25", tx.decide(inWindow + 30_000, profile, 14_074_000))
    }

    @Test
    fun `text cut short stays queued, and Halt drops it`() {
        val tx = autoTx()
        tx.arm(profile)
        tx.queueText(AutoTx.QueuedText("QRT 73", freeText = true), SlotParity.EVEN)
        assertEquals("QRT 73", tx.decide(inWindow, profile, 14_074_000))
        tx.onTransmitStarted()
        tx.onTransmitFinished(engine, transmitted = false)
        assertEquals("QRT 73", tx.text?.text)

        tx.halt(engine)
        assertNull(tx.text)
        assertNull(tx.nextMessage)
    }

    @Test
    fun `queuing text does not arm, and a band change drops it`() {
        val tx = autoTx()
        tx.queueText(AutoTx.QueuedText("QRT 73", freeText = true), SlotParity.EVEN)
        assertTrue(!tx.armed)
        assertNull(tx.decide(inWindow, profile, 14_074_000))
        tx.onFrequencyChanged()
        assertNull(tx.text)
    }
}

/** Test-only: put a message in the queue without driving the whole QSO. */
private fun AutoTx.queuedForTest(message: String) {
    val engine = QsoEngine("VE4OK", "FN25")
    callStation(engine, "K1ABC", 0L, SlotParity.ODD)
}
