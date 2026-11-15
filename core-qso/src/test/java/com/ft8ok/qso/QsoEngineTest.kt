package com.ft8ok.qso

import com.ft8ok.model.Ft8Message
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every sequence an FT8 contact can take, on the JVM in milliseconds.
 *
 * The textbook exchange is the easy half. What these mostly check is the awkward
 * half — repeats, missed messages, stations jumping straight to RR73, and people
 * calling us mid-contact — because that is what a real band does and what decides
 * whether a wrong number ends up in somebody's log.
 */
class QsoEngineTest {

    private val engine = QsoEngine(myCall = "W3XAA", myGrid = "EM13")
    private val t0 = 1_760_000_000_000L

    private fun heard(text: String) = Ft8Message.parse(text)

    // --- the textbook contact, both directions -----------------------------------

    @Test
    fun `we answer a CQ and work it through to a complete contact`() {
        val cq = heard("CQ K1ABC FN42") as Ft8Message.Cq

        val start = engine.begin("K1ABC", t0, cq)
        assertEquals("K1ABC W3XAA EM13", start.reply)
        assertEquals("FN42", start.qso!!.partnerGrid)

        // They come back with our report.
        val r1 = engine.onMessage(start.qso, heard("W3XAA K1ABC -12"), t0 + 15_000, ourReportDb = -9)
        assertEquals("K1ABC W3XAA R-09", r1.reply)
        assertEquals(-12, r1.qso!!.reportReceivedDb)
        assertEquals(-9, r1.qso.reportSentDb)
        assertTrue(r1.qso.reportsExchanged)
        assertTrue("not complete until it is closed", !r1.qso.loggable)

        // They close.
        val r2 = engine.onMessage(r1.qso, heard("W3XAA K1ABC RR73"), t0 + 30_000, ourReportDb = -9)
        assertEquals(QsoEngine.Reason.COMPLETE, r2.reason)
        assertEquals("K1ABC W3XAA 73", r2.reply)
        assertTrue(r2.qso!!.loggable)
        assertEquals(Qso.Stage.COMPLETE, r2.qso.stage)
    }

    @Test
    fun `someone answers our CQ and we work them through`() {
        // No contact in progress: they call us.
        val a = engine.onMessage(null, heard("W3XAA K1ABC FN42"), t0, ourReportDb = -5)
        assertEquals(QsoEngine.Reason.ANSWERED_CALL, a.reason)
        assertEquals("K1ABC W3XAA -05", a.reply)
        assertEquals("K1ABC", a.qso!!.partner)
        assertEquals(t0, a.qso.startedAtMs)

        // They roger our report and send theirs.
        val b = engine.onMessage(a.qso, heard("W3XAA K1ABC R-11"), t0 + 15_000, ourReportDb = -5)
        assertEquals("K1ABC W3XAA RR73", b.reply)
        assertTrue(b.qso!!.reportsExchanged)
        assertTrue("our RR73 closes it", b.qso.loggable)
    }

    // --- reports -----------------------------------------------------------------

    @Test
    fun `the report we send is the one we measured, formatted as FT8 expects`() {
        val a = engine.onMessage(null, heard("W3XAA K1ABC FN42"), t0, ourReportDb = -5)
        assertEquals("K1ABC W3XAA -05", a.reply)

        val b = engine.onMessage(null, heard("W3XAA K2DEF FN42"), t0, ourReportDb = 7)
        assertEquals("K2DEF W3XAA +07", b.reply)

        val c = engine.onMessage(null, heard("W3XAA K3GHI FN42"), t0, ourReportDb = -25)
        assertEquals("K3GHI W3XAA -25", c.reply)
    }

    @Test
    fun `an R-form report tells us they have ours, even if we never saw it go out`() {
        // We sent a report, it was not decoded back to us, and their R-form is the
        // first evidence the exchange happened. Believing the air over our own
        // bookkeeping is what stops a complete contact being treated as incomplete.
        val qso = Qso(partner = "K1ABC", startedAtMs = t0, lastActivityMs = t0)
        val d = engine.onMessage(qso, heard("W3XAA K1ABC R-11"), t0 + 15_000, ourReportDb = -5)
        assertEquals(-11, d.qso!!.reportReceivedDb)
        assertNotNull("their R means our report reached them", d.qso.reportSentDb)
        assertTrue(d.qso.reportsExchanged)
    }

    // --- the awkward half --------------------------------------------------------

    @Test
    fun `a repeated message does not advance or corrupt the contact`() {
        val a = engine.onMessage(null, heard("W3XAA K1ABC FN42"), t0, ourReportDb = -5)
        val b = engine.onMessage(a.qso, heard("W3XAA K1ABC FN42"), t0 + 15_000, ourReportDb = -5)
        // They did not hear our report, so we send it again. Nothing else moves.
        assertEquals("K1ABC W3XAA -05", b.reply)
        assertNull(b.qso!!.reportReceivedDb)
        assertTrue(!b.qso.loggable)
    }

    @Test
    fun `a message to somebody else is ignored, however interesting`() {
        val a = engine.onMessage(null, heard("W3XAA K1ABC FN42"), t0, ourReportDb = -5)
        // Someone else sends us something mid-contact: not ours to answer.

        val stranger = engine.onMessage(a.qso, heard("W3XAA N0XYZ FN31"), t0 + 15_000, ourReportDb = -5)
        assertEquals(QsoEngine.Reason.IGNORED, stranger.reason)
        assertNull("answering would abandon the partner mid-contact", stranger.reply)
    }

    @Test
    fun `RRR is answered with 73, and the contact is complete`() {
        val qso = Qso(
            partner = "K1ABC", reportSentDb = -5, reportReceivedDb = -11,
            startedAtMs = t0, lastActivityMs = t0
        )
        val d = engine.onMessage(qso, heard("W3XAA K1ABC RRR"), t0 + 15_000, ourReportDb = -5)
        assertEquals("K1ABC W3XAA 73", d.reply)
        assertEquals(QsoEngine.Reason.COMPLETE, d.reason)
        assertTrue(d.qso!!.loggable)
    }

    @Test
    fun `a contact that is closed but never exchanged reports is not loggable`() {
        // An incomplete QSO is never auto-logged. A friendly 73
        // from someone who never sent a report is not a contact.
        val qso = Qso(partner = "K1ABC", startedAtMs = t0, lastActivityMs = t0)
        val d = engine.onMessage(qso, heard("W3XAA K1ABC 73"), t0 + 15_000, ourReportDb = -5)
        assertTrue(d.qso!!.closed)
        assertTrue("no reports exchanged", !d.qso.reportsExchanged)
        assertTrue("so it must not auto-log", !d.qso.loggable)
    }

    @Test
    fun `73s do not ping-pong forever`() {
        val qso = Qso(
            partner = "K1ABC", reportSentDb = -5, reportReceivedDb = -11,
            closed = true, startedAtMs = t0, lastActivityMs = t0
        )
        val d = engine.onMessage(qso, heard("W3XAA K1ABC 73"), t0 + 15_000, ourReportDb = -5)
        assertNull("already closed; saying 73 again would never end", d.reply)
        assertEquals(QsoEngine.Reason.COMPLETE, d.reason)
    }

    @Test
    fun `a contest exchange is never answered, to us or in a contact`() {
        // We send standard messages only.
        for (text in listOf("W3XAA K1ABC 6A WI", "W3XAA K1ABC R 579 MA", "<W3XAA> <K1ABC> 570001 FN42AA", "W3XAA K1ABC R FN42")) {
            val unsolicited = engine.onMessage(null, heard(text), t0, ourReportDb = -5)
            assertEquals(text, QsoEngine.Reason.IGNORED, unsolicited.reason)
            assertNull(text, unsolicited.reply)
        }
        val calling = engine.begin("K1ABC", t0)
        val during = engine.onMessage(calling.qso, heard("W3XAA K1ABC 2A EMA"), t0 + 15_000, ourReportDb = -5)
        assertEquals(QsoEngine.Reason.IGNORED, during.reason)
        assertEquals(calling.qso, during.qso)
    }

    @Test
    fun `a hashed callsign is never called`() {
        // We cannot name the station, so we cannot address one. Calling "<...>"
        // would transmit to nobody.
        assertNull(engine.begin(Ft8Message.HASHED, t0).qso)
        assertNull(engine.begin(Ft8Message.HASHED, t0).reply)

        val unsolicited = engine.onMessage(null, heard("W3XAA <...> FN42"), t0, ourReportDb = -5)
        assertEquals(QsoEngine.Reason.IGNORED, unsolicited.reason)
        assertNull(unsolicited.reply)
    }

    @Test
    fun `a bare 73 from nobody in particular starts nothing`() {
        val d = engine.onMessage(null, heard("W3XAA K1ABC 73"), t0, ourReportDb = -5)
        assertNull("there is nothing to log and nothing useful to say", d.qso)
        assertEquals(QsoEngine.Reason.IGNORED, d.reason)
    }

    // --- giving up, and halting --------------------------------------------------

    @Test
    fun `we give up after the retry limit and free the slot`() {
        // begin() is itself the first transmission, so count from one and stop when
        // the engine lets go. Counting rather than assuming avoids baking an
        // off-by-one into the test and calling it the specification.
        var decision = engine.begin("K1ABC", t0)
        var transmissions = 1
        while (decision.qso != null && transmissions < 20) {
            decision = engine.onUnanswered(decision.qso)
            if (decision.qso != null) transmissions++
        }

        assertNull("the station goes on the failed-call list", decision.qso)
        assertEquals(QsoEngine.Reason.GAVE_UP, decision.reason)
        assertEquals(
            "a station that never answers is called exactly callRetryLimit times",
            engine.callRetryLimit, transmissions
        )
    }

    @Test
    fun `hearing from them resets the patience`() {
        var d = engine.begin("K1ABC", t0)
        d = engine.onUnanswered(d.qso)
        d = engine.onUnanswered(d.qso)
        assertTrue(d.qso!!.unansweredTransmissions > 0)

        val answered = engine.onMessage(d.qso, heard("W3XAA K1ABC -12"), t0 + 60_000, ourReportDb = -9)
        assertEquals(0, answered.qso!!.unansweredTransmissions)
    }

    @Test
    fun `halt keeps the partner and everything already exchanged`() {
        val a = engine.onMessage(null, heard("W3XAA K1ABC FN42"), t0, ourReportDb = -5)
        val halted = engine.halt(a.qso)

        assertEquals(Qso.Stage.PAUSED, halted.qso!!.stage)
        assertEquals("K1ABC", halted.qso.partner)
        assertEquals(-5, halted.qso.reportSentDb)

        // Nothing is sent while halted, whatever arrives.
        val whileHalted = engine.onMessage(halted.qso, heard("W3XAA K1ABC R-11"), t0 + 15_000, ourReportDb = -5)
        assertNull("halt means halt", whileHalted.reply)
        assertEquals(QsoEngine.Reason.PAUSED, whileHalted.reason)

        // Resuming carries on from where it stopped.
        val resumed = engine.resume(whileHalted.qso)
        assertTrue(!resumed.qso!!.paused)
        assertEquals(-5, resumed.qso.reportSentDb)
    }

    // --- bookkeeping for the log -------------------------------------------------

    @Test
    fun `the contact remembers when it started and when it last moved`() {
        // ADIF TIME_ON is the first message, TIME_OFF the last.
        val a = engine.onMessage(null, heard("W3XAA K1ABC FN42"), t0, ourReportDb = -5)
        val b = engine.onMessage(a.qso, heard("W3XAA K1ABC R-11"), t0 + 45_000, ourReportDb = -5)
        assertEquals(t0, b.qso!!.startedAtMs)
        assertEquals(t0 + 45_000, b.qso.lastActivityMs)
    }

    @Test
    fun `the stage never contradicts what was exchanged`() {
        val calling = Qso(partner = "K1ABC", startedAtMs = t0, lastActivityMs = t0)
        assertEquals(Qso.Stage.CALLING, calling.stage)
        assertEquals(Qso.Stage.REPORTING, calling.copy(reportSentDb = -5).stage)
        assertEquals(
            Qso.Stage.CONFIRMING,
            calling.copy(reportSentDb = -5, reportReceivedDb = -11).stage
        )
        assertEquals(
            Qso.Stage.COMPLETE,
            calling.copy(reportSentDb = -5, reportReceivedDb = -11, closed = true).stage
        )
    }

    @Test
    fun `calling CQ says who and where we are`() {
        assertEquals("CQ W3XAA EM13", engine.cq())
    }

    @Test
    fun `a CQ word goes between CQ and our call, and parses back as a directed CQ`() {
        assertEquals("CQ POTA W3XAA EM13", engine.cq("POTA"))
        assertEquals("CQ W3XAA EM13", engine.cq(""))
        val parsed = Ft8Message.parse(engine.cq("SOTA")) as Ft8Message.Cq
        assertEquals("SOTA", parsed.directedTo)
        assertEquals("W3XAA", parsed.caller)
        assertEquals("EM13", parsed.grid)
    }

    @Test
    fun `our RR73 is repeated once if they go quiet, then the contact ends`() {
        // "max 2 RR73's before stopping auto".
        val done = Qso(
            partner = "K1ABC", reportSentDb = -10, reportReceivedDb = -12,
            weAcknowledged = true, theyAcknowledged = true, closed = true,
            startedAtMs = t0, lastActivityMs = t0
        )
        val afterFirst = engine.onUnanswered(done)
        assertNotNull("one RR73 unanswered: send it again", afterFirst.qso)
        val afterSecond = engine.onUnanswered(afterFirst.qso)
        assertNull(afterSecond.qso)
        assertEquals(QsoEngine.Reason.CLOSED_UNANSWERED, afterSecond.reason)
    }

    @Test
    fun `a contact under way gets more patience than an unanswered call`() {
        // They answered once (we sent their report), then went quiet.
        var qso: Qso? = Qso(partner = "K1ABC", reportSentDb = -10, startedAtMs = t0, lastActivityMs = t0)
        repeat(QsoEngine.DEFAULT_RETRY_LIMIT) { qso = engine.onUnanswered(qso).qso }
        assertNotNull(qso)
        assertEquals(QsoEngine.Reason.GAVE_UP, engine.onUnanswered(qso).reason)
    }

    // --- partner moves on -------------------------------

    @Test
    fun `our partner sending an R-report to someone else is reported, and the contact kept live`() {
        val a = engine.onMessage(null, heard("W3XAA K1ABC FN42"), t0, ourReportDb = -5)
        val moved = engine.onMessage(a.qso, heard("N0XYZ K1ABC R-03"), t0 + 15_000, ourReportDb = -5)
        assertEquals(QsoEngine.Reason.PARTNER_MOVED_ON, moved.reason)
        assertNull(moved.reply)
        assertTrue("not paused: an answer to us next carries on", !moved.qso!!.paused)
        assertEquals("what was exchanged is kept", -5, moved.qso!!.reportSentDb)

        val answered = engine.onMessage(moved.qso, heard("W3XAA K1ABC R-08"), t0 + 45_000, ourReportDb = -5)
        assertEquals("K1ABC W3XAA RR73", answered.reply)
    }

    @Test
    fun `our partner calling CQ, calling, reporting or closing to someone else does not stop us`() {
        // Any of these may be followed by an answer to us.
        for (text in listOf("CQ K1ABC FN42", "N0XYZ K1ABC FN42", "N0XYZ K1ABC -03", "N0XYZ K1ABC RR73", "N0XYZ K1ABC 73")) {
            val a = engine.begin("K1ABC", t0)
            val d = engine.onMessage(a.qso, heard(text), t0 + 15_000, ourReportDb = -5)
            assertEquals(text, QsoEngine.Reason.IGNORED, d.reason)
            assertTrue(text, d.qso?.paused == false)
        }
    }

    @Test
    fun `a complete contact simply ends when the partner moves on`() {
        val done = Qso(
            partner = "K1ABC", reportSentDb = -10, reportReceivedDb = -12,
            weAcknowledged = true, theyAcknowledged = true, closed = true,
            startedAtMs = t0, lastActivityMs = t0
        )
        val moved = engine.onMessage(done, heard("N0XYZ K1ABC FN42"), t0 + 15_000, ourReportDb = -5)
        assertEquals(QsoEngine.Reason.PARTNER_MOVED_ON, moved.reason)
        assertNull(moved.qso)
    }

    @Test
    fun `a hashed addressee is not taken as moving on, since it could be us`() {
        val a = engine.begin("K1ABC", t0)
        val d = engine.onMessage(a.qso, heard("<...> K1ABC R-03"), t0 + 15_000, ourReportDb = -5)
        assertEquals(QsoEngine.Reason.IGNORED, d.reason)
    }

    // --- the operator overriding the sequence ------------------

    @Test
    fun `a repeated RRR is answered with 73 again, because our close never reached them`() {
        // On air: 73 was queued, their RRR repeat answered it with
        // nothing, and the 73 the screen promised was never sent.
        val qso = Qso(
            partner = "K1ABC", reportSentDb = -5, reportReceivedDb = -11,
            weAcknowledged = true, closed = true, startedAtMs = t0, lastActivityMs = t0
        )
        val d = engine.onMessage(qso, heard("W3XAA K1ABC RRR"), t0 + 30_000, ourReportDb = -5)
        assertEquals("K1ABC W3XAA 73", d.reply)
    }

    @Test
    fun `tapping the tail of a finished contact answers 73 and cannot log twice`() {
        val d = engine.answerTail(heard("W3XAA K1ABC RRR") as Ft8Message.Acknowledge, t0)
        assertEquals("K1ABC W3XAA 73", d.reply)
        assertTrue("already logged when it completed", !d.qso!!.loggable)

        val notUs = engine.answerTail(heard("N0XYZ K1ABC RRR") as Ft8Message.Acknowledge, t0)
        assertNull(notUs.reply)
    }

    @Test
    fun `the operator can pick any step, and a report step needs a measured report`() {
        val qso = Qso(partner = "K1ABC", startedAtMs = t0, lastActivityMs = t0, paused = true)
        assertEquals("K1ABC W3XAA EM13", engine.choose(qso, QsoEngine.Step.GRID, null).reply)
        assertEquals("K1ABC W3XAA -07", engine.choose(qso, QsoEngine.Step.REPORT, -7).reply)
        val close = engine.choose(qso, QsoEngine.Step.CLOSE, null)
        assertEquals("K1ABC W3XAA RR73", close.reply)
        assertTrue("picking resumes a halted contact", !close.qso!!.paused)

        assertNull("no report is ever made up", engine.choose(qso, QsoEngine.Step.REPORT, null).reply)
    }

    @Test
    fun `a contact with a DXpedition runs on the halves of its messages addressed to us`() {
        val start = engine.begin("KH1/KH7Z", t0).qso!!

        // Its report to us, in the same transmission as its RR73 to someone else.
        val r1 = engine.onMessage(start, heard("K1ABC RR73; W3XAA <KH1/KH7Z> -12"), t0, -7)
        assertEquals(QsoEngine.Reason.SENT_ROGER_REPORT, r1.reason)
        assertEquals("KH1/KH7Z W3XAA R-07", r1.reply)
        assertEquals(-12, r1.qso!!.reportReceivedDb)

        // Its RR73 to us, while it sends the next station a report.
        val r2 = engine.onMessage(r1.qso, heard("W3XAA RR73; W9XYZ <KH1/KH7Z> -15"), t0, -7)
        assertEquals(QsoEngine.Reason.COMPLETE, r2.reason)
        assertTrue(r2.qso!!.loggable)
        // No 73 to its RR73: a WSJT-X station sends none to a dual message.
        assertNull(r2.reply)
    }

    @Test
    fun `a DXpedition working others leaves our call standing, and a hashed one is nobody`() {
        val start = engine.begin("KH1/KH7Z", t0).qso!!
        val others = engine.onMessage(start, heard("K1ABC RR73; W9XYZ <KH1/KH7Z> -12"), t0, -7)
        assertEquals(QsoEngine.Reason.IGNORED, others.reason)
        assertEquals(start, others.qso)

        val hashed = engine.onMessage(start, heard("K1ABC RR73; W3XAA <...> -12"), t0, -7)
        assertEquals(QsoEngine.Reason.IGNORED, hashed.reason)
        assertEquals(QsoEngine.Reason.IGNORED, engine.onMessage(null, heard("K1ABC RR73; W3XAA <...> -12"), t0, -7).reason)
    }

    @Test
    fun `the report carries R once we have theirs, and the close is 73 once they have closed`() {
        val fresh = Qso(partner = "K1ABC", startedAtMs = t0, lastActivityMs = t0)
        assertEquals("K1ABC W3XAA -07", engine.choose(fresh, QsoEngine.Step.REPORT, -7).reply)

        // Their plain report arrives: the report step now says we have it.
        val theirs = engine.onMessage(fresh, heard("W3XAA K1ABC -12"), t0, -7).qso!!
        val roger = engine.choose(theirs, QsoEngine.Step.REPORT, -7)
        assertEquals("K1ABC W3XAA R-07", roger.reply)
        assertTrue(roger.qso!!.weAcknowledged)
        assertEquals("K1ABC W3XAA RR73", engine.choose(theirs, QsoEngine.Step.CLOSE, null).reply)

        // Their RR73 (or RRR, or 73): all that is left to say is 73.
        for (ack in listOf("RR73", "RRR", "73")) {
            val closed = engine.onMessage(theirs, heard("W3XAA K1ABC $ack"), t0, -7).qso!!
            assertEquals(ack, "K1ABC W3XAA 73", engine.choose(closed, QsoEngine.Step.CLOSE, null).reply)
        }
    }

    // --- a non-standard call ------------------------------------

    @Test
    fun `a station with a non-standard call is worked through, hashes and all`() {
        // WSJT-X's sequence for VE3XAA/QRP working a standard call (genStdMsgs), as
        // our decoder prints it once their CQ is in its call table: their call by
        // hash in the reports, OUR call by hash in their close. On air the
        // reports came out "<...>" and the contact stalled.
        val cq = heard("CQ VE3XAA/QRP") as Ft8Message.Cq
        val start = engine.begin(cq.caller, t0, cq)
        assertEquals("VE3XAA/QRP W3XAA EM13", start.reply)

        val r1 = engine.onMessage(start.qso, heard("W3XAA <VE3XAA/QRP> -12"), t0 + 15_000, ourReportDb = -9)
        assertEquals("VE3XAA/QRP W3XAA R-09", r1.reply)
        assertEquals(-12, r1.qso!!.reportReceivedDb)

        val r2 = engine.onMessage(r1.qso, heard("<W3XAA> VE3XAA/QRP RR73"), t0 + 30_000, ourReportDb = -9)
        assertEquals(QsoEngine.Reason.COMPLETE, r2.reason)
        assertEquals("VE3XAA/QRP W3XAA 73", r2.reply)
        assertTrue(r2.qso!!.loggable)
        assertEquals("VE3XAA/QRP", r2.qso.partner)
    }

    @Test
    fun `a non-standard call answering our CQ is answered with a report`() {
        // Their Tx1 has no grid: "<mycall> theircall", a type 4.
        val a = engine.onMessage(null, heard("<W3XAA> VE3XAA/QRP"), t0, ourReportDb = -5)
        assertEquals(QsoEngine.Reason.ANSWERED_CALL, a.reason)
        assertEquals("VE3XAA/QRP W3XAA -05", a.reply)

        val b = engine.onMessage(a.qso, heard("W3XAA <VE3XAA/QRP> R-11"), t0 + 15_000, ourReportDb = -5)
        assertEquals("VE3XAA/QRP W3XAA RR73", b.reply)
        assertTrue(b.qso!!.loggable)
    }

    @Test
    fun `an unresolved hash is still not our partner`() {
        val start = engine.begin("VE3XAA/QRP", t0, heard("CQ VE3XAA/QRP") as Ft8Message.Cq)
        val d = engine.onMessage(start.qso, heard("W3XAA <...> -12"), t0 + 15_000, ourReportDb = -9)
        assertEquals(QsoEngine.Reason.IGNORED, d.reason)
        assertNull(d.reply)
    }
}
