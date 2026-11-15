package com.ft8ok.qso

import com.ft8ok.model.ContestSetup
import com.ft8ok.model.Ft8Message
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contest mode's sequences, Field Day and the grid contests.
 *
 * The partner's lines are WSJT-X's own (lib/77bit/messages.txt and genStdMsgs):
 * `CQ FD K1ABC FN42`, `K1ABC W9XYZ 6A WI`, `W9XYZ K1ABC R 2B EMA`, `K1ABC W9XYZ RR73`.
 * Our answers are what WSJT-X's processMessage sends in its contest modes.
 */
class ContestEngineTest {

    private val t0 = 1_760_000_000_000L

    private fun fd(cls: String = "2B", sec: String = "EMA") = ContestSetup(ContestSetup.Kind.FIELD_DAY, "FD", "$cls $sec")
    private fun grid(g: String) = ContestSetup(ContestSetup.Kind.GRID, "TEST", g)
    private fun rg(word: String = "") = ContestSetup(ContestSetup.Kind.REPORT_GRID, word, "EN37")

    // --- Report+grid: standard messages in contest mode -----------

    @Test
    fun `report and grid - a plain CQ, or the chosen word`() {
        assertEquals("CQ W9XYZ EN37", QsoEngine("W9XYZ", "EN37", contest = rg()).cq("POTA"))
        assertEquals("CQ TEST W9XYZ EN37", QsoEngine("W9XYZ", "EN37", contest = rg("TEST")).cq())
    }

    @Test
    fun `report and grid - we answer a CQ with the standard sequence`() {
        val us = QsoEngine("W9XYZ", "EN37", contest = rg())
        val a = us.begin("K1ABC", t0, heard("CQ K1ABC FN42") as Ft8Message.Cq)
        assertEquals("our grid, as with no contest", "K1ABC W9XYZ EN37", a.reply)
        assertEquals(rg(), a.qso!!.contest)

        val b = us.onMessage(a.qso, heard("W9XYZ K1ABC -12"), t0 + 15_000, ourReportDb = -8)
        assertEquals("K1ABC W9XYZ R-08", b.reply)
        val c = us.onMessage(b.qso, heard("W9XYZ K1ABC RR73"), t0 + 30_000, ourReportDb = -8)
        assertEquals("K1ABC W9XYZ 73", c.reply)
        assertTrue(c.qso!!.loggable)
        assertNull("no contest exchange recorded", c.qso.exchangeSent)
        assertEquals(rg(), c.qso.contest)
    }

    @Test
    fun `report and grid - a caller with a grid gets a report, and the pick is a report`() {
        val us = QsoEngine("K1ABC", "FN42", contest = rg())
        val a = us.onMessage(null, heard("K1ABC W9XYZ EN37"), t0, ourReportDb = -11)
        assertEquals("W9XYZ K1ABC -11", a.reply)
        val pick = us.choose(a.qso!!, QsoEngine.Step.REPORT, ourReportDb = -11)
        assertEquals("W9XYZ K1ABC -11", pick.reply)
    }

    @Test
    fun `report and grid - no combined DXpedition message`() {
        assertNull(QsoEngine("K1ABC", "FN42", contest = rg()).dxpedition("W9XYZ", "N0CAL", -10))
        assertTrue(QsoEngine("K1ABC", "FN42").dxpedition("W9XYZ", "N0CAL", -10) != null)
    }

    private fun heard(text: String) = Ft8Message.parse(text)

    // --- Field Day ------------------------------------------------------------------

    @Test
    fun `field day - we answer a CQ FD and work it through`() {
        val us = QsoEngine("W9XYZ", "EN37", contest = fd("6A", "WI"))
        val cq = heard("CQ FD K1ABC FN42") as Ft8Message.Cq

        val a = us.begin("K1ABC", t0, cq)
        assertEquals("the caller opens with the exchange", "K1ABC W9XYZ 6A WI", a.reply)
        assertEquals(Qso.Stage.CALLING, a.qso!!.stage)

        val b = us.onMessage(a.qso, heard("W9XYZ K1ABC R 2B EMA"), t0 + 15_000, ourReportDb = -10)
        assertEquals("K1ABC W9XYZ RR73", b.reply)
        assertEquals("2B EMA", b.qso!!.exchangeReceived)
        assertEquals("6A WI", b.qso.exchangeSent)
        assertTrue("our RR73 closes it", b.qso.loggable)

        val c = us.onMessage(b.qso, heard("W9XYZ K1ABC 73"), t0 + 30_000, ourReportDb = -10)
        assertEquals(QsoEngine.Reason.COMPLETE, c.reason)
        assertNull("already closed: no 73 back", c.reply)
    }

    @Test
    fun `field day - a station answers our CQ FD with its exchange`() {
        val us = QsoEngine("K1ABC", "FN42", contest = fd())
        assertEquals("CQ FD K1ABC FN42", us.cq())

        val a = us.onMessage(null, heard("K1ABC W9XYZ 6A WI"), t0, ourReportDb = -3)
        assertEquals(QsoEngine.Reason.ANSWERED_CALL, a.reason)
        assertEquals("W9XYZ K1ABC R 2B EMA", a.reply)
        assertEquals("6A WI", a.qso!!.exchangeReceived)

        val b = us.onMessage(a.qso, heard("K1ABC W9XYZ RR73"), t0 + 15_000, ourReportDb = -3)
        assertEquals(QsoEngine.Reason.COMPLETE, b.reason)
        assertEquals("WSJT-X sends 73 after RR73, in contests too", "W9XYZ K1ABC 73", b.reply)
        assertTrue(b.qso!!.loggable)
        assertNull("no signal report was exchanged", b.qso.reportReceivedDb)
    }

    @Test
    fun `field day - their exchange repeated gets our R exchange again`() {
        val us = QsoEngine("K1ABC", "FN42", contest = fd())
        val a = us.onMessage(null, heard("K1ABC W9XYZ 6A WI"), t0, ourReportDb = -3)
        val b = us.onMessage(a.qso, heard("K1ABC W9XYZ 6A WI"), t0 + 30_000, ourReportDb = -3)
        assertEquals("W9XYZ K1ABC R 2B EMA", b.reply)
    }

    @Test
    fun `field day - a plain caller with a grid gets our exchange without the R`() {
        val us = QsoEngine("K1ABC", "FN42", contest = fd())
        val a = us.onMessage(null, heard("K1ABC W9XYZ EN37"), t0, ourReportDb = -3)
        assertEquals("W9XYZ K1ABC 2B EMA", a.reply)
        assertEquals("EN37", a.qso!!.partnerGrid)
        assertNull(a.qso.exchangeReceived)
        // Then their exchange with the R: complete.
        val b = us.onMessage(a.qso, heard("K1ABC W9XYZ R 6A WI"), t0 + 15_000, ourReportDb = -3)
        assertEquals("W9XYZ K1ABC RR73", b.reply)
        assertTrue(b.qso!!.loggable)
    }

    @Test
    fun `field day - an exchange in another contest's format is not answered`() {
        val us = QsoEngine("K1ABC", "FN42", contest = fd())
        val a = us.begin("W9XYZ", t0)
        val b = us.onMessage(a.qso, heard("K1ABC W9XYZ 579 WI"), t0 + 15_000, ourReportDb = -3)
        assertEquals(QsoEngine.Reason.IGNORED, b.reason)
        assertNull(us.onMessage(null, heard("K1ABC W9XYZ 579 WI"), t0, -3).qso)
    }

    @Test
    fun `field day - a station that is not in the contest sends a report`() {
        val us = QsoEngine("W9XYZ", "EN37", contest = fd("6A", "WI"))
        val a = us.begin("K1ABC", t0)
        val b = us.onMessage(a.qso, heard("W9XYZ K1ABC -12"), t0 + 15_000, ourReportDb = -10)
        assertEquals("K1ABC W9XYZ R 6A WI", b.reply)
        val c = us.onMessage(b.qso, heard("W9XYZ K1ABC RR73"), t0 + 30_000, ourReportDb = -10)
        assertTrue(c.qso!!.loggable)
        assertEquals(-12, c.qso.reportReceivedDb)
    }

    @Test
    fun `field day - the report pick sends the exchange, with the R once theirs is in`() {
        val us = QsoEngine("W9XYZ", "EN37", contest = fd("6A", "WI"))
        val a = us.begin("K1ABC", t0)
        assertEquals("K1ABC W9XYZ 6A WI", us.choose(a.qso!!, QsoEngine.Step.REPORT, null).reply)
        val b = us.onMessage(a.qso, heard("W9XYZ K1ABC 2B EMA"), t0 + 15_000, ourReportDb = -10)
        assertEquals("K1ABC W9XYZ R 6A WI", us.choose(b.qso!!, QsoEngine.Step.REPORT, null).reply)
        assertEquals("K1ABC W9XYZ RR73", us.choose(b.qso, QsoEngine.Step.CLOSE, null).reply)
    }

    @Test
    fun `field day - no combined DXpedition message`() {
        val us = QsoEngine("K1ABC", "FN42", contest = fd())
        assertNull(us.dxpedition("W9XYZ", "N0AAA", -10))
    }

    // --- grid contests ------------------------------------------------------------

    @Test
    fun `grid - we answer a CQ TEST and work it through`() {
        val us = QsoEngine("W9XYZ", "EN37", contest = grid("EN37"))
        val a = us.begin("K1ABC", t0, heard("CQ TEST K1ABC FN42") as Ft8Message.Cq)
        assertEquals("K1ABC W9XYZ EN37", a.reply)
        val b = us.onMessage(a.qso, heard("W9XYZ K1ABC R FN42"), t0 + 15_000, ourReportDb = -10)
        assertEquals("K1ABC W9XYZ RR73", b.reply)
        assertEquals("FN42", b.qso!!.exchangeReceived)
        assertEquals("FN42", b.qso.partnerGrid)
        assertTrue(b.qso.loggable)
    }

    @Test
    fun `grid - a caller's grid is their exchange, answered with R and ours`() {
        val us = QsoEngine("K1ABC", "FN42", contest = grid("FN42"))
        assertEquals("CQ TEST K1ABC FN42", us.cq("POTA"))
        val a = us.onMessage(null, heard("K1ABC W9XYZ EN37"), t0, ourReportDb = -3)
        assertEquals("W9XYZ K1ABC R FN42", a.reply)
        assertEquals("EN37", a.qso!!.exchangeReceived)
        val b = us.onMessage(a.qso, heard("K1ABC W9XYZ RR73"), t0 + 15_000, ourReportDb = -3)
        assertEquals("W9XYZ K1ABC 73", b.reply)
        assertTrue(b.qso!!.loggable)
    }

    @Test
    fun `grid - a caller with no grid gets our grid, then R once theirs comes`() {
        val us = QsoEngine("K1ABC", "FN42", contest = grid("FN42"))
        val a = us.onMessage(null, heard("K1ABC W9XYZ"), t0, ourReportDb = -3)
        assertEquals("W9XYZ K1ABC FN42", a.reply)
        val b = us.onMessage(a.qso, heard("K1ABC W9XYZ EN37"), t0 + 15_000, ourReportDb = -3)
        assertEquals("W9XYZ K1ABC R FN42", b.reply)
    }

    // --- the format follows the setting -------------------------------------------

    @Test
    fun `a contact follows contest mode as it is set now`() {
        val contestEngine = QsoEngine("VE4OK", "FN25", contest = fd("1D", "MB"))
        val plainEngine = QsoEngine("VE4OK", "FN25")
        // On air, AC9XA: a station not in Field Day answered our CQ FD with
        // its grid and got our exchange. Contest mode switched off, their grid again
        // gets a report, so the contact can finish.
        val a = contestEngine.onMessage(null, heard("VE4OK AC9XA EM69"), t0, ourReportDb = -12)
        assertEquals("AC9XA VE4OK 1D MB", a.reply)
        val b = plainEngine.onMessage(a.qso, heard("VE4OK AC9XA EM69"), t0 + 30_000, ourReportDb = -12)
        assertEquals("AC9XA VE4OK -12", b.reply)
        assertNull(b.qso!!.contest)
        assertEquals("AC9XA VE4OK -12", plainEngine.choose(a.qso!!, QsoEngine.Step.REPORT, -12).reply)
        // Started standard; contest mode then switched on: the next answer is the exchange.
        val c = plainEngine.begin("K1ABC", t0)
        val d = contestEngine.onMessage(c.qso, heard("VE4OK K1ABC -12"), t0 + 15_000, ourReportDb = -10)
        assertEquals("K1ABC VE4OK R 1D MB", d.reply)
        assertEquals(fd("1D", "MB"), d.qso!!.contest)
    }

    @Test
    fun `standard mode still ignores contest exchanges`() {
        val us = QsoEngine("K1ABC", "FN42")
        assertNull(us.onMessage(null, heard("K1ABC W9XYZ 6A WI"), t0, -3).qso)
        assertEquals("CQ POTA K1ABC FN42", us.cq("POTA"))
    }

    @Test
    fun `a partner sending its R exchange to someone else is busy`() {
        val us = QsoEngine("W9XYZ", "EN37", contest = fd("6A", "WI"))
        val a = us.begin("K1ABC", t0)
        val b = us.onMessage(a.qso, heard("N0AAA K1ABC R 2B EMA"), t0 + 15_000, ourReportDb = -10)
        assertEquals(QsoEngine.Reason.PARTNER_MOVED_ON, b.reason)
    }

    // --- both sides in contest mode ------------------------------------------------

    @Test
    fun `two field day stations complete a contact`() {
        run {
            val cqer = QsoEngine("K1ABC", "FN42", contest = fd("2B", "EMA"))
            val caller = QsoEngine("W9XYZ", "EN37", contest = fd("6A", "WI"))
            val (c, q) = run(cqer, caller)
            assertTrue("cqer loggable", c!!.loggable)
            assertTrue("caller loggable", q!!.loggable)
            assertEquals("6A WI", c.exchangeReceived)
            assertEquals("2B EMA", q.exchangeReceived)
            assertEquals("2B EMA", c.exchangeSent)
            assertEquals("6A WI", q.exchangeSent)
        }
    }

    @Test
    fun `two grid stations complete a contact`() {
        val (c, q) = run(QsoEngine("K1ABC", "FN42", contest = grid("FN42")), QsoEngine("W9XYZ", "EN37", contest = grid("EN37")))
        assertTrue(c!!.loggable)
        assertTrue(q!!.loggable)
        assertEquals("EN37", c.partnerGrid)
        assertEquals("FN42", q.partnerGrid)
    }

    @Test
    fun `contest exchange counts as reports for logging by hand`() {
        val us = QsoEngine("K1ABC", "FN42", contest = fd())
        val a = us.onMessage(null, heard("K1ABC W9XYZ 6A WI"), t0, ourReportDb = -3)
        assertFalse("not aired yet", a.qso!!.reportsOnAir)
        assertTrue(a.qso.copy(ourReportAired = true).reportsOnAir)
    }

    /** The caller answers the CQer's CQ; alternate until both are loggable. Returns (cqer, caller). */
    private fun run(cqer: QsoEngine, caller: QsoEngine): Pair<Qso?, Qso?> {
        val cq = Ft8Message.parse(cqer.cq()) as Ft8Message.Cq
        val begin = caller.begin(cqer.myCall, t0, cq)
        var callerQso = begin.qso
        var callerNext: String? = begin.reply
        var cqerQso: Qso? = null
        var cqerNext: String? = null
        for (slot in 0 until 10) {
            val now = t0 + slot * 30_000L
            callerNext?.let { msg ->
                val d = cqer.onMessage(cqerQso, Ft8Message.parse(msg), now, -5)
                if (d.reason != QsoEngine.Reason.IGNORED) { cqerQso = d.qso; cqerNext = d.reply }
            }
            if (cqerQso?.loggable == true && callerQso?.loggable == true) break
            cqerNext?.let { msg ->
                val d = caller.onMessage(callerQso, Ft8Message.parse(msg), now + 15_000, -7)
                if (d.reason != QsoEngine.Reason.IGNORED) { callerQso = d.qso; if (d.reply != null) callerNext = d.reply }
            }
            if (cqerQso?.loggable == true && callerQso?.loggable == true) break
        }
        return cqerQso to callerQso
    }
}
