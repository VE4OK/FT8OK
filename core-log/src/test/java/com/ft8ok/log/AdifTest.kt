package com.ft8ok.log

import com.ft8ok.model.DialFrequencies
import com.ft8ok.model.Mode
import com.ft8ok.model.StationProfile
import com.ft8ok.qso.Qso
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rule 8: ADIF export and import must round-trip. A log that comes back different
 * from what went out has lost someone's contact.
 */
class AdifTest {

    /** 2026-10-14 17:30:15 UTC, a whole second, because ADIF keeps whole seconds. */
    private val t0 = 1_791_999_015_000L

    private val station = StationProfile(callsign = "ve4ok", grid = "fn25")

    private fun complete(partner: String = "VE6XA") = Qso(
        partner = partner,
        partnerGrid = "DO33",
        reportSentDb = -12,
        reportReceivedDb = 5,
        weAcknowledged = true,
        theyAcknowledged = true,
        closed = true,
        startedAtMs = t0,
        lastActivityMs = t0 + 60_000
    )

    // --- from the QSO engine -----------------------------------------------------

    @Test
    fun `a complete FT8 contact becomes a POTA-ready record`() {
        val q = LoggedQso.fromQso(complete(), station, 7_074_000, Mode.FT8)
        val f = Adif.fieldsOf(q).toMap()
        assertEquals("VE6XA", f["CALL"])
        assertEquals("40m", f["BAND"])
        assertEquals("7.074000", f["FREQ"])
        assertEquals("FT8", f["MODE"])
        assertNull("FT8 is a MODE in ADIF, not a submode", f["SUBMODE"])
        assertEquals("VE4OK", f["STATION_CALLSIGN"])
        assertEquals("FN25", f["MY_GRIDSQUARE"])
        assertEquals("-12", f["RST_SENT"])
        assertEquals("+05", f["RST_RCVD"])
        // Every field POTA requires.
        for (k in listOf("STATION_CALLSIGN", "CALL", "QSO_DATE", "TIME_ON", "BAND", "MODE")) {
            assertTrue("missing $k", f.containsKey(k))
        }
    }

    @Test
    fun `FT4 is MFSK with an FT4 submode, per ADIF 3_1_6`() {
        assertEquals("MFSK" to "FT4", LoggedQso.adifModeOf(Mode.FT4))
        assertEquals("FT8" to null, LoggedQso.adifModeOf(Mode.FT8))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `an incomplete contact is refused unless the operator says log anyway`() {
        LoggedQso.fromQso(complete().copy(closed = false), station, 7_074_000, Mode.FT8)
    }

    @Test
    fun `log anyway logs an incomplete contact`() {
        val q = LoggedQso.fromQso(
            complete().copy(closed = false, reportReceivedDb = null),
            station, 7_074_000, Mode.FT8, logAnyway = true
        )
        assertNull(q.rstRcvd)
    }

    // --- round trip ----------------------------------------------------------------

    @Test
    fun `write then read gives back exactly what went out`() {
        val qsos = listOf(
            LoggedQso.fromQso(complete(), station, 7_074_000, Mode.FT8),
            LoggedQso.fromQso(complete("K1ABC"), station, 7_047_500, Mode.FT4)
                .copy(sig = "POTA", sigInfo = "US-8088", mySig = "POTA", mySigInfo = "CA-0001",
                    myState = "MB", state = "MA", dxcc = 291, cqZone = 5, ituZone = 8,
                    continent = "NA", comment = "first P2P <with> brackets"),
            LoggedQso(call = "G4XYZ", timeOnMs = t0, mode = "SSB", band = "20m",
                extra = mapOf("APP_OTHERLOG_ID" to "42", "NAME" to "Ann"))
        )
        val back = Adif.read(Adif.write(qsos))
        assertEquals(emptyList<Adif.Rejected>(), back.rejected)
        assertEquals(qsos, back.qsos)
    }

    @Test
    fun `milliseconds are dropped, because ADIF keeps whole seconds`() {
        val q = LoggedQso(call = "K1ABC", timeOnMs = t0 + 999, mode = "FT8")
        assertEquals(t0, Adif.read(Adif.write(listOf(q))).qsos.single().timeOnMs)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `non-ASCII text is refused rather than written with an ambiguous length`() {
        Adif.write(listOf(LoggedQso(call = "K1ABC", timeOnMs = t0, mode = "FT8", comment = "ZÃ¼rich")))
    }

    // --- reading other programs' files ---------------------------------------------

    @Test
    fun `reads a headerless file with lower-case tags, types, HHMM times and a midnight crossing`() {
        val text = "<call:5>K1ABC<qso_date:8:D>20261014<time_on:4>2359" +
            "<time_off:4>0001<freq:9>14.074000<mode:3>FT8<eor>"
        val q = Adif.read(text).qsos.single()
        assertEquals("K1ABC", q.call)
        assertEquals("20m", q.band)
        assertEquals(14_074_000L, q.freqHz)
        assertEquals(2 * 60_000L, q.timeOffMs!! - q.timeOnMs)
    }

    @Test
    fun `a record missing a required field is reported, not dropped`() {
        val result = Adif.read("<CALL:5>K1ABC <MODE:3>FT8 <EOR>")
        assertEquals(0, result.qsos.size)
        assertEquals("no QSO_DATE", result.rejected.single().reason)
        assertEquals("K1ABC", result.rejected.single().fields["CALL"])
    }

    // --- band table ----------------------------------------------------------------

    @Test
    fun `a custom frequency off the dial table still has a band`() {
        for ((hz, band) in listOf(14_250_000L to "20m", 21_090_000L to "15m")) {
            assertNull("$hz", DialFrequencies.bandAt(hz))
            assertEquals("$hz", band, AdifBand.forHz(hz))
        }
    }

    @Test
    fun `every dial frequency we know falls on the band the frequency table names`() {
        for (d in DialFrequencies.ALL) {
            assertEquals("${d.hz}", d.band, AdifBand.forHz(d.hz))
        }
    }

    @Test
    fun `band edges are inclusive and gaps are null`() {
        assertEquals("40m", AdifBand.forHz(7_000_000))
        assertEquals("40m", AdifBand.forHz(7_300_000))
        assertNull(AdifBand.forHz(7_300_001))
        assertNull(AdifBand.forHz(12_000_000))
    }
}
