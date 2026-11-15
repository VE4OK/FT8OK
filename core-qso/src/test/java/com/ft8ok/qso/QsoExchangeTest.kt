package com.ft8ok.qso

import com.ft8ok.model.Ft8Message
import com.ft8ok.model.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whole contacts, slot by slot, against a simulated partner.
 *
 * Every other test of [QsoEngine] checks one transition. These run the conversation
 * to its end and ask the question that actually matters: **does a contact finish,
 * and do both stations agree about what was exchanged?** That is what decides
 * whether a correct entry reaches a logbook, and it had never been tested before an
 * antenna was proposed.
 *
 * **The simulated partner is another `QsoEngine`.** That is deliberate and it is
 * also the limitation: two instances of the same code can share a misconception and
 * agree with each other about it. What this proves is that the state machine is
 * complete, terminates, and is self-consistent. What it cannot prove is that the
 * sequence matches what WSJT-X does — only a real contact can, which is why the
 * exchange gets tested here first and on the air second.
 */
class QsoExchangeTest {

    private val us = QsoEngine(myCall = "VE4OK", myGrid = "FN25")
    private val them = QsoEngine(myCall = "K1ABC", myGrid = "FN42")

    /** Our measurement of them, and theirs of us. Different, as they would be. */
    private val ourReport = -11
    private val theirReport = -7

    private val slotMs = Mode.FT8.slotMs
    private val start = (1_760_000_010_000L / 30_000) * 30_000

    /** One run of a contact, recording what crossed the air. */
    private class Transcript {
        val lines = mutableListOf<String>()
        fun record(who: String, message: String?) {
            if (message != null) lines += "$who: $message"
        }
        override fun toString() = lines.joinToString("\n")
    }

    /**
     * Run a contact to completion, alternating slots.
     *
     * @param lose a slot index whose transmission is dropped, simulating the message
     *   that did not get through — which is the normal case on a real band, not an
     *   exotic one.
     */
    private fun runExchange(
        weCallThem: Boolean,
        maxSlots: Int = 12,
        lose: Int = -1
    ): Triple<Qso?, Qso?, Transcript> {
        val transcript = Transcript()
        var ourQso: Qso? = null
        var theirQso: Qso? = null
        var ourNext: String?
        var theirNext: String? = null

        if (weCallThem) {
            // They are calling CQ; we answer it.
            val cq = Ft8Message.parse(them.cq()) as Ft8Message.Cq
            val begin = us.begin("K1ABC", start, cq)
            ourQso = begin.qso
            ourNext = begin.reply
        } else {
            // We are calling CQ; they answer.
            val cq = Ft8Message.parse(us.cq()) as Ft8Message.Cq
            val begin = them.begin("VE4OK", start, cq)
            theirQso = begin.qso
            theirNext = begin.reply
            ourNext = null
        }

        for (slot in 0 until maxSlots) {
            val nowMs = start + slot * slotMs
            val dropped = slot == lose

            // Our slot. The message is NOT cleared after sending: a station that is
            // not answered repeats in its next slot, which is what the app does and
            // what makes a contact survive a message that did not get through.
            if (ourNext != null) {
                transcript.record("us", if (dropped) "${ourNext} (LOST)" else ourNext)
                if (!dropped) {
                    val heard = them.onMessage(theirQso, Ft8Message.parse(ourNext), nowMs, theirReport)
                    if (heard.reason != QsoEngine.Reason.IGNORED) {
                        theirQso = heard.qso
                        theirNext = heard.reply
                    }
                }
            }

            if (ourQso?.loggable == true && theirQso?.loggable == true) break

            // Their slot.
            if (theirNext != null) {
                transcript.record("them", theirNext)
                val heard = us.onMessage(ourQso, Ft8Message.parse(theirNext), nowMs + slotMs, ourReport)
                if (heard.reason != QsoEngine.Reason.IGNORED) {
                    ourQso = heard.qso
                    // Only replace what we are sending when they actually moved the
                    // contact on; otherwise we keep repeating.
                    if (heard.reply != null) ourNext = heard.reply
                }
            }

            if (ourQso?.loggable == true && theirQso?.loggable == true) break
        }
        return Triple(ourQso, theirQso, transcript)
    }

    @Test
    fun `we answer their CQ and the contact completes for both stations`() {
        val (ours, theirs, transcript) = runExchange(weCallThem = true)

        assertNotNull(transcript.toString(), ours)
        assertTrue("our side must be loggable:\n$transcript", ours!!.loggable)
        assertTrue("their side must be loggable:\n$transcript", theirs!!.loggable)

        // The reports must be the right way round in each log: we logged what THEY
        // sent us, they logged what WE sent them. Crossing these is the mistake that
        // puts a wrong number in a stranger's logbook.
        assertEquals("we received their measurement of us", theirReport, ours.reportReceivedDb)
        assertEquals("we sent our measurement of them", ourReport, ours.reportSentDb)
        assertEquals(ourReport, theirs.reportReceivedDb)
        assertEquals(theirReport, theirs.reportSentDb)

        assertEquals("K1ABC", ours.partner)
        assertEquals("VE4OK", theirs.partner)
    }

    @Test
    fun `they answer our CQ and the contact completes for both stations`() {
        val (ours, theirs, transcript) = runExchange(weCallThem = false)

        assertTrue("our side:\n$transcript", ours!!.loggable)
        assertTrue("their side:\n$transcript", theirs!!.loggable)
        assertEquals(theirReport, ours.reportReceivedDb)
        assertEquals(ourReport, ours.reportSentDb)
    }

    @Test
    fun `a contact survives a message that does not get through`() {
        // The normal case on a real band. A lost transmission means the other station
        // repeats, and the contact takes an extra cycle rather than failing.
        for (lost in 0..3) {
            val (ours, theirs, transcript) = runExchange(weCallThem = true, lose = lost)
            assertTrue(
                "losing slot $lost broke the contact:\n$transcript",
                ours?.loggable == true && theirs?.loggable == true
            )
        }
    }

    @Test
    fun `the contact records when it started and when it finished`() {
        val (ours, _, _) = runExchange(weCallThem = true)
        // ADIF TIME_ON and TIME_OFF.
        assertEquals(start, ours!!.startedAtMs)
        assertTrue("TIME_OFF must be after TIME_ON", ours.lastActivityMs > ours.startedAtMs)
    }

    @Test
    fun `a contact does not drag on forever`() {
        // Termination is the property that matters most: an exchange that never
        // reaches loggable would keep the partner slot occupied and keep
        // transmitting at somebody.
        val (ours, theirs, transcript) = runExchange(weCallThem = true, maxSlots = 8)
        assertTrue("took too many slots:\n$transcript", ours!!.loggable && theirs!!.loggable)
        assertTrue("a plain contact should not need many overs:\n$transcript", transcript.lines.size <= 6)
    }

    @Test
    fun `nothing is logged when the reports never crossed`() {
        // The precondition that protects the logbook. A contact that closed without
        // both reports is not a contact, however polite the ending.
        val partial = Qso(
            partner = "K1ABC",
            reportSentDb = ourReport,
            startedAtMs = start,
            lastActivityMs = start
        )
        val closed = us.onMessage(partial, Ft8Message.parse("VE4OK K1ABC 73"), start + slotMs, ourReport)
        assertTrue(closed.qso!!.closed)
        assertTrue("only one report crossed", !closed.qso!!.loggable)
    }

    @Test
    fun `the transcript reads like a real contact`() {
        // Printed on failure above; asserted here so the SHAPE is pinned and a
        // regression that still "completes" but sends nonsense is visible.
        val (_, _, transcript) = runExchange(weCallThem = true)
        val lines = transcript.lines

        assertEquals("us: K1ABC VE4OK FN25", lines[0])
        assertEquals("them: VE4OK K1ABC -07", lines[1])
        assertEquals("us: K1ABC VE4OK R-11", lines[2])
        assertTrue("they should acknowledge and close: ${lines[3]}", lines[3].contains("RR73"))
    }

    @Test
    fun `we do not answer a station working somebody else`() {
        // A whole slot of other people's traffic must leave us untouched, which is
        // what stops the app transmitting at a station mid-contact with a third party.
        val begin = us.begin("K1ABC", start)
        var qso = begin.qso
        for (text in listOf("CQ W1XYZ FN31", "N0ABC W2DEF -05", "W2DEF N0ABC R-12", "K1ABC W9ZZZ FN20")) {
            val decision = us.onMessage(qso, Ft8Message.parse(text), start, ourReport)
            assertEquals("'$text' should be ignored", QsoEngine.Reason.IGNORED, decision.reason)
            assertNull(decision.reply)
            qso = decision.qso
        }
        assertEquals("K1ABC", qso!!.partner)
    }
}
