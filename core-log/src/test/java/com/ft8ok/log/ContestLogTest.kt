package com.ft8ok.log

import com.ft8ok.model.ContestSetup
import com.ft8ok.model.Mode
import com.ft8ok.model.StationProfile
import com.ft8ok.qso.Qso
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Contest fields in the log and dupes. */
class ContestLogTest {

    private val t0 = 1_782_000_000_000L
    private val station = StationProfile(callsign = "VE4OK", grid = "FN25")
    private val fd = ContestSetup(ContestSetup.Kind.FIELD_DAY, "FD", "2A MB")
    private val grid = ContestSetup(ContestSetup.Kind.GRID, "TEST", "FN25")

    private fun contact(c: ContestSetup?, sent: String?, rcvd: String?) = Qso(
        partner = "W1AW", startedAtMs = t0, lastActivityMs = t0 + 45_000,
        contest = c, exchangeSent = sent, exchangeReceived = rcvd,
        weAcknowledged = true, theyAcknowledged = true, closed = true
    )

    @Test
    fun `a field day contact logs its exchange the way WSJT-X does`() {
        val q = LoggedQso.fromQso(contact(fd, "2A MB", "3A CT"), station, 7_074_000, Mode.FT8)
        assertEquals("ARRL-FIELD-DAY", q.extra["CONTEST_ID"])
        assertEquals("2A MB", q.extra["STX_STRING"])
        assertEquals("3A CT", q.extra["SRX_STRING"])
        assertEquals("3A", q.extra["CLASS"])
        assertEquals("CT", q.extra["ARRL_SECT"])
        assertEquals("FIELD_DAY", q.extra["APP_FT8OK_CONTEST"])
        assertNull("no dB report was exchanged", q.rstSent)
        assertNull(q.rstRcvd)
    }

    @Test
    fun `a grid contest contact logs the grids, with no contest id`() {
        val q = LoggedQso.fromQso(contact(grid, "FN25", "FN31").copy(partnerGrid = "FN31"), station, 50_313_000, Mode.FT8)
        assertNull(q.extra["CONTEST_ID"])
        assertEquals("FN25", q.extra["STX_STRING"])
        assertEquals("FN31", q.extra["SRX_STRING"])
        assertEquals("FN31", q.grid)
        assertEquals("GRID", q.extra["APP_FT8OK_CONTEST"])
    }

    @Test
    fun `a report and grid contest contact keeps its reports and is marked for dupes`() {
        val rg = ContestSetup(ContestSetup.Kind.REPORT_GRID, "", "FN25")
        val q = LoggedQso.fromQso(
            Qso(partner = "W1AW", startedAtMs = t0, lastActivityMs = t0, reportSentDb = -5, reportReceivedDb = -9, closed = true, contest = rg),
            station, 7_047_500, Mode.FT4
        )
        assertEquals("REPORT_GRID", q.extra["APP_FT8OK_CONTEST"])
        assertNull("one setting, several contests: no CONTEST_ID", q.extra["CONTEST_ID"])
        assertNull(q.extra["STX_STRING"])
        assertEquals("-05", q.rstSent)
        assertEquals("-09", q.rstRcvd)
        assertTrue(ContestLog.isDupe(listOf(q), "W1AW", "40m", ContestSetup.Kind.REPORT_GRID, t0 + 60_000))
        assertFalse("another contest mode's contact is no dupe", ContestLog.isDupe(listOf(q), "W1AW", "40m", ContestSetup.Kind.GRID, t0 + 60_000))
    }

    @Test
    fun `a standard contact gets no contest fields`() {
        val q = LoggedQso.fromQso(
            Qso(partner = "W1AW", startedAtMs = t0, lastActivityMs = t0, reportSentDb = -5, reportReceivedDb = -9, closed = true),
            station, 7_074_000, Mode.FT8
        )
        assertTrue(q.extra.isEmpty())
    }

    @Test
    fun `contest fields round-trip through ADIF`() {
        val q = LoggedQso.fromQso(contact(fd, "2A MB", "3A CT"), station, 7_074_000, Mode.FT8)
        val back = Adif.read(Adif.write(listOf(q))).qsos.single()
        assertEquals(q.extra, back.extra)
        assertEquals(q, back)
    }

    @Test
    fun `a dupe is the same call, band and contest mode within 48 hours`() {
        val logged = LoggedQso.fromQso(contact(fd, "2A MB", "3A CT"), station, 7_074_000, Mode.FT8)
        val log = listOf(logged)
        val k = ContestSetup.Kind.FIELD_DAY
        assertTrue(ContestLog.isDupe(log, "w1aw", "40m", k, t0 + 3_600_000))
        assertFalse("other band", ContestLog.isDupe(log, "W1AW", "20m", k, t0 + 3_600_000))
        assertFalse("other call", ContestLog.isDupe(log, "W1AX", "40m", k, t0 + 3_600_000))
        assertFalse("other contest", ContestLog.isDupe(log, "W1AW", "40m", ContestSetup.Kind.GRID, t0 + 3_600_000))
        assertFalse("long ago", ContestLog.isDupe(log, "W1AW", "40m", k, t0 + 49 * 3_600_000L))
        assertFalse("band unknown", ContestLog.isDupe(log, "W1AW", null, k, t0))
        val plain = logged.copy(extra = emptyMap())
        assertFalse("worked outside contest mode", ContestLog.isDupe(listOf(plain), "W1AW", "40m", k, t0))
    }
}
