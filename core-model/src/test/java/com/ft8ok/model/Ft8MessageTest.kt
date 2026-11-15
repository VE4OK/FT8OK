package com.ft8ok.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parsing, against messages actually decoded off the air by this app.
 *
 * Every string below was copied from the decode feed during live testing on 20 m
 * and 17 m, rather than invented, so the parser is exercised against what the band
 * really sends — including the awkward ones.
 */
class Ft8MessageTest {

    @Test
    fun `a plain CQ`() {
        val m = Ft8Message.parse("CQ W3XAA EM13") as Ft8Message.Cq
        assertEquals("W3XAA", m.caller)
        assertEquals("EM13", m.grid)
        assertEquals(null, m.directedTo)
    }

    @Test
    fun `a directed CQ keeps the qualifier and still names the caller`() {
        // Getting this wrong would make us answer "DX" instead of the station.
        val dx = Ft8Message.parse("CQ DX NO5Q EL15") as Ft8Message.Cq
        assertEquals("NO5Q", dx.caller)
        assertEquals("EL15", dx.grid)
        assertEquals("DX", dx.directedTo)

        val qrp = Ft8Message.parse("CQ QRP KM6XAA EM75") as Ft8Message.Cq
        assertEquals("KM6XAA", qrp.caller)
        assertEquals("QRP", qrp.directedTo)

        // A numeric directed CQ, seen on air: "CQ 250 N5XAA EM12".
        val zone = Ft8Message.parse("CQ 250 N5XAA EM12") as Ft8Message.Cq
        assertEquals("N5XAA", zone.caller)
        assertEquals("250", zone.directedTo)
    }

    @Test
    fun `a reply carries a grid, and is not a report`() {
        val m = Ft8Message.parse("W3XAA K1ABC FN42") as Ft8Message.Reply
        assertEquals("W3XAA", m.to)
        assertEquals("K1ABC", m.from)
        assertEquals("FN42", m.grid)
    }

    @Test
    fun `reports, with and without the R`() {
        val plain = Ft8Message.parse("K1ABC W3XAA -12") as Ft8Message.Report
        assertEquals(-12, plain.reportDb)
        assertTrue(!plain.roger)

        val roger = Ft8Message.parse("W3XAA K1ABC R-09") as Ft8Message.Report
        assertEquals(-9, roger.reportDb)
        assertTrue(roger.roger)

        val positive = Ft8Message.parse("K4XA K9XAA +29") as Ft8Message.Report
        assertEquals(29, positive.reportDb)

        // Single-digit reports occur: "+00", "R+03" are two digits, but "+0" exists.
        val zero = Ft8Message.parse("N2XAA AI9Q +00") as Ft8Message.Report
        assertEquals(0, zero.reportDb)
    }

    @Test
    fun `acknowledgements, and which of them actually close a contact`() {
        val rrr = Ft8Message.parse("W5XAA KR4XAA RRR") as Ft8Message.Acknowledge
        assertEquals(Ft8Message.Acknowledge.Kind.RRR, rrr.kind)
        assertTrue("RRR acknowledges but does not say goodbye", !rrr.closes)

        val rr73 = Ft8Message.parse("W1XAA W7Q RR73") as Ft8Message.Acknowledge
        assertEquals(Ft8Message.Acknowledge.Kind.RR73, rr73.kind)
        assertTrue(rr73.closes)

        val seventyThree = Ft8Message.parse("AB7Q JM1XAA 73") as Ft8Message.Acknowledge
        assertEquals(Ft8Message.Acknowledge.Kind.SEVENTY_THREE, seventyThree.kind)
        assertTrue(seventyThree.closes)
    }

    @Test
    fun `a hashed callsign is kept as written, not treated as a station we can answer`() {
        // Seen constantly on air. The decoder knows a callsign is there but has not
        // heard the message that defines it. Answering "<...>" would transmit to a
        // station we cannot name.
        val m = Ft8Message.parse("<...> NF3Q FN20") as Ft8Message.Reply
        assertEquals(Ft8Message.HASHED, m.to)
        assertEquals("NF3Q", m.from)

        val other = Ft8Message.parse("8N7I60 <...> 73") as Ft8Message.Acknowledge
        assertEquals(Ft8Message.HASHED, other.from)
    }

    @Test
    fun `a grid is never mistaken for a report, nor the other way round`() {
        // The one confusion that would put wrong numbers in someone's log.
        assertTrue(Ft8Message.parse("W3XAA K1ABC EM13") is Ft8Message.Reply)
        assertTrue(Ft8Message.parse("W3XAA K1ABC -13") is Ft8Message.Report)
        // RR73 looks like neither, and must not parse as a grid despite the digits.
        assertTrue(Ft8Message.parse("W3XAA K1ABC RR73") is Ft8Message.Acknowledge)
    }

    @Test
    fun `anything we do not model parses as unknown rather than failing`() {
        assertTrue(Ft8Message.parse("") is Ft8Message.Unknown)
        assertTrue(Ft8Message.parse("TNX FER QSO") is Ft8Message.Unknown)
        assertTrue(Ft8Message.parse("K1ABC") is Ft8Message.Unknown)
        // A contest exchange is modelled since 4zn (shown and heard, never answered).
        assertTrue(Ft8Message.parse("K1ABC W3XAA 579 MA") is Ft8Message.Contest)
    }

    @Test
    fun `the raw text always survives, whatever was understood`() {
        // The feed shows exactly what was received; parsing must not rewrite it.
        for (text in listOf("CQ W3XAA EM13", "W3XAA K1ABC R-09", "nonsense here")) {
            assertEquals(text, Ft8Message.parse(text).raw)
        }
    }

    // DXpedition (0.1) lines below are in the decoder's own output format, from the
    // host test of ft8ok_decoder.c, not copied off the air.

    @Test
    fun `a DXpedition message is two messages from one sender`() {
        val m = Ft8Message.parse("K1ABC RR73; W9XYZ <KH1/KH7Z> -12") as Ft8Message.Dxpedition
        assertEquals("KH1/KH7Z", m.from)
        assertEquals(Ft8Message.Acknowledge("K1ABC", "KH1/KH7Z", Ft8Message.Acknowledge.Kind.RR73, m.raw), m.closing)
        assertEquals(Ft8Message.Report("W9XYZ", "KH1/KH7Z", -12, roger = false, raw = m.raw), m.report)
        assertEquals(m.closing, m.partFor("k1abc"))
        assertEquals(m.report, m.partFor("W9XYZ"))
        assertEquals(null, m.partFor("VE4OK"))
        assertEquals(4, (Ft8Message.parse("VE1AB RR73; VE2CD <W1AW> +04") as Ft8Message.Dxpedition).report.reportDb)
    }

    @Test
    fun `a DXpedition sender not yet heard in full stays hashed`() {
        val m = Ft8Message.parse("K1ABC RR73; W9XYZ <...> -12") as Ft8Message.Dxpedition
        assertEquals(Ft8Message.HASHED, m.from)
    }

    @Test
    fun `a DXpedition lookalike that is not one parses as unknown`() {
        for (text in listOf(
            "K1ABC RR73; W9XYZ KH7Z -12",   // sender not in brackets
            "K1ABC RR73; W9XYZ <KH7Z> R-12", // no R-form in this message
            "K1ABC RR73; W9XYZ <KH7Z> EN37", // a grid is the 0.6 contest type, not this
            "K1ABC RR73 W9XYZ <KH7Z> -12"    // no semicolon
        )) {
            assertTrue(text, Ft8Message.parse(text) is Ft8Message.Unknown)
        }
    }

    @Test
    fun `mentions finds a callsign as a whole word only`() {
        assertTrue(Ft8Message.mentions("VE4OK K1ABC FN42", "VE4OK"))
        assertTrue(Ft8Message.mentions("K1ABC <VE4OK> RR73", "ve4ok"))
        assertTrue(Ft8Message.mentions("CQ VE4OK FN25", "VE4OK"))
        assertFalse(Ft8Message.mentions("VE4OKA K1ABC FN42", "VE4OK"))
        assertFalse(Ft8Message.mentions("XVE4OK K1ABC FN42", "VE4OK"))
        assertFalse(Ft8Message.mentions("CQ VE4OK FN25", ""))
    }

    @Test
    fun `a call with no grid is a reply without one, and two words of text are not`() {
        val call = Ft8Message.parse("VE4OK AD9XA") as Ft8Message.Reply
        assertEquals("VE4OK", call.to)
        assertEquals("AD9XA", call.from)
        assertEquals(null, call.grid)
        assertTrue(Ft8Message.parse("<...> AD9XA") is Ft8Message.Reply)
        assertTrue(Ft8Message.parse("TNX 73") is Ft8Message.Unknown)
        assertTrue(Ft8Message.parse("DE AD9XA") is Ft8Message.Unknown)
        assertTrue(Ft8Message.parse("CQ AD9XA") is Ft8Message.Cq)
    }

    @Test
    fun `plain standard calls are the ones a DXpedition message can carry`() {
        for (call in listOf("K1ABC", "W1AW", "VE4OK", "K9A", "9A1AA", "VA7XY", "K12AB")) {
            assertTrue(call, Ft8Message.isPlainStandardCall(call))
        }
        for (call in listOf("K1ABC/P", "KH1/KH7Z", "VE4OK/R", "K1", "ABCDEF", "W1ABCD", "<...>", "CQ")) {
            assertFalse(call, Ft8Message.isPlainStandardCall(call))
        }
    }

    @Test
    fun `a DXpedition message carries even reports, as WSJT-X packs them`() {
        assertEquals(-14, Ft8Message.dxpeditionReport(-13))
        assertEquals(-12, Ft8Message.dxpeditionReport(-12))
        assertEquals(2, Ft8Message.dxpeditionReport(3))
        assertEquals(-30, Ft8Message.dxpeditionReport(-31))
        assertEquals(-30, Ft8Message.dxpeditionReport(-40))
        assertEquals(32, Ft8Message.dxpeditionReport(40))
        assertEquals("K1ABC RR73; W9XYZ <VE4OK> -14", Ft8Message.dxpeditionText("K1ABC", "W9XYZ", "VE4OK", -13))
        assertEquals(null, Ft8Message.dxpeditionText("K1ABC/P", "W9XYZ", "VE4OK", -13))
        assertEquals(null, Ft8Message.dxpeditionText("K1ABC", "K1ABC", "VE4OK", -13))
        // What we build is what we parse back.
        val parsed = Ft8Message.parse("K1ABC RR73; W9XYZ <VE4OK> +02") as Ft8Message.Dxpedition
        assertEquals("VE4OK", parsed.from)
        assertEquals(2, parsed.report.reportDb)
    }

    @Test
    fun `a resolved hash is the call itself, an unresolved one stays hashed`() {
        // The brackets only say how the call travelled.
        val report = Ft8Message.parse("VE4OK <VE3XAA/QRP> -12") as Ft8Message.Report
        assertEquals("VE4OK", report.to)
        assertEquals("VE3XAA/QRP", report.from)
        assertEquals(-12, report.reportDb)

        val close = Ft8Message.parse("<VE4OK> VE3XAA/QRP RR73") as Ft8Message.Acknowledge
        assertEquals("VE4OK", close.to)
        assertEquals("VE3XAA/QRP", close.from)

        val call = Ft8Message.parse("<VE4OK> VE3XAA/QRP") as Ft8Message.Reply
        assertEquals("VE4OK", call.to)
        assertEquals("VE3XAA/QRP", call.from)
        assertNull(call.grid)

        val first = Ft8Message.parse("<VE3XAA/QRP> VE4OK FN25") as Ft8Message.Reply
        assertEquals("VE3XAA/QRP", first.to)

        assertEquals(Ft8Message.HASHED, (Ft8Message.parse("VE4OK <...> -12") as Ft8Message.Report).from)
        assertEquals(Ft8Message.HASHED, (Ft8Message.parse("<...> VE3XAA/QRP RR73") as Ft8Message.Acknowledge).to)
    }

    // --- Contest exchanges, WSJT-X's lib/77bit/messages.txt -------

    private fun contest(text: String) = Ft8Message.parse(text) as Ft8Message.Contest

    @Test
    fun `Field Day exchanges name both calls`() {
        val first = contest("K1ABC W9XYZ 6A WI")
        assertEquals("K1ABC", first.to)
        assertEquals("W9XYZ", first.from)
        assertEquals(Ft8Message.Contest.Kind.FIELD_DAY, first.kind)
        assertFalse(first.roger)
        assertEquals("6A WI", first.exchange)
        assertNull(first.grid)

        val roger = contest("W9XYZ K1ABC R 16A EMA")
        assertTrue(roger.roger)
        assertEquals("16A EMA", roger.exchange)
        assertEquals("K1ABC", roger.from)
    }

    @Test
    fun `RTTY Roundup exchanges, with TU and a serial`() {
        val state = contest("K1ABC W9XYZ 579 WI")
        assertEquals(Ft8Message.Contest.Kind.RTTY, state.kind)
        assertEquals("579 WI", state.exchange)

        val thanks = contest("TU; KA0DEF K1ABC R 569 MA")
        assertTrue(thanks.thanks)
        assertTrue(thanks.roger)
        assertEquals("KA0DEF", thanks.to)
        assertEquals("K1ABC", thanks.from)

        assertEquals("529 0013", contest("KA1ABC G3AAA 529 0013").exchange)
        assertEquals("599 X42", contest("KA1ABC G3AAA 599 X42").exchange)
    }

    @Test
    fun `EU VHF exchanges carry a 6-character grid, the sender's 4 kept`() {
        val m = contest("<G4ABC/P> <PA9XYZ> R 570007 JO22DB")
        assertEquals(Ft8Message.Contest.Kind.EU_VHF, m.kind)
        assertEquals("G4ABC/P", m.to)
        assertEquals("PA9XYZ", m.from)
        assertEquals("JO22", m.grid)
        assertEquals("570007 JO22DB", m.exchange)

        assertEquals(Ft8Message.HASHED, contest("<...> <...> 590003 IO91NP").from)
    }

    @Test
    fun `the grid contests' R plus grid is a contest exchange, the plain grid still a reply`() {
        val roger = contest("W9XYZ K1ABC/R R FN42")
        assertEquals(Ft8Message.Contest.Kind.GRID, roger.kind)
        assertEquals("K1ABC/R", roger.from)
        assertEquals("FN42", roger.grid)

        assertTrue(Ft8Message.parse("K1ABC/R W9XYZ EN37") is Ft8Message.Reply)
    }

    @Test
    fun `contest CQs are told apart, other CQs are not`() {
        for (word in listOf("FD", "RU", "TEST", "WW")) {
            assertTrue(word, Ft8Message.isContest(Ft8Message.parse("CQ $word K1ABC FN42")))
        }
        assertFalse(Ft8Message.isContest(Ft8Message.parse("CQ POTA K1ABC FN42")))
        assertFalse(Ft8Message.isContest(Ft8Message.parse("CQ K1ABC FN42")))
        assertTrue(Ft8Message.isContest(Ft8Message.parse("K1ABC W9XYZ 6A WI")))
    }

    @Test
    fun `free text, WSPR and the standard messages are not contest exchanges`() {
        for (text in listOf("TNX BOB 73 GL", "TNX FER QSO", "K1ABC FN42 37", "PJ4/K1ABC 37", "<PJ4/K1ABC> FK52UD")) {
            assertFalse(text, Ft8Message.parse(text) is Ft8Message.Contest)
        }
        // Words that are not calls in the call fields: free text, not an exchange.
        assertTrue(Ft8Message.parse("HI HI 579 MA") is Ft8Message.Unknown)
        assertTrue(Ft8Message.parse("W9XYZ K1ABC R-09") is Ft8Message.Report)
        assertTrue(Ft8Message.parse("W9XYZ K1ABC RR73") is Ft8Message.Acknowledge)
        // An exchange with a word too many or too few is not one.
        assertTrue(Ft8Message.parse("K1ABC W9XYZ 6A") is Ft8Message.Unknown)
        assertTrue(Ft8Message.parse("K1ABC W9XYZ 6A WI X") is Ft8Message.Unknown)
    }
}
