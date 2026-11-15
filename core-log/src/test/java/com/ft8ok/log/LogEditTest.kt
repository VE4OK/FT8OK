package com.ft8ok.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LogEditTest {

    /** 2026-10-15 14:32:17 UTC. */
    private val t0 = 1_792_074_737_000L

    private val q = LoggedQso(
        call = "VE2XAA", timeOnMs = t0, timeOffMs = t0 + 90_000, freqHz = 14_074_000, band = "20m",
        mode = "FT8", grid = "FN35", rstSent = "-05", rstRcvd = "+18"
    )

    private fun ok(form: LogEdit.Form) = (LogEdit.apply(q, form) as LogEdit.Outcome.Ok).qso
    private fun problems(form: LogEdit.Form) = (LogEdit.apply(q, form) as LogEdit.Outcome.Invalid).problems

    @Test
    fun `an untouched form saves the record unchanged, seconds and all`() {
        val form = LogEdit.formOf(q)
        assertEquals("2026-10-15 14:32", form.timeOn)
        assertEquals("14.074", form.freqMhz)
        assertEquals(q, ok(form))
    }

    @Test
    fun `fields are normalised as the app writes them`() {
        val edited = ok(LogEdit.formOf(q).copy(call = " ve2xaa/p ", grid = "fn35ab", rstSent = "-5", rstRcvd = "3"))
        assertEquals("VE2XAA/P", edited.call)
        assertEquals("FN35ab", edited.grid)
        assertEquals("-05", edited.rstSent)
        assertEquals("+03", edited.rstRcvd)
    }

    @Test
    fun `a new time moves the whole contact`() {
        val edited = ok(LogEdit.formOf(q).copy(timeOn = "2026-10-15 14:40"))
        assertEquals(1_792_075_200_000L, edited.timeOnMs)
        assertEquals("TIME_OFF keeps its distance", edited.timeOnMs + 90_000, edited.timeOffMs)
    }

    @Test
    fun `a new frequency brings its band`() {
        val edited = ok(LogEdit.formOf(q).copy(freqMhz = "7.074"))
        assertEquals(7_074_000L, edited.freqHz)
        assertEquals("40m", edited.band)
    }

    @Test
    fun `bad input is refused field by field, never saved`() {
        val p = problems(
            LogEdit.formOf(q).copy(
                call = "VEABC", timeOn = "15/10 14:32", freqMhz = "14.5x", grid = "ZZ99",
                rstSent = "minus twelve", comment = "Zürich"
            )
        )
        assertEquals(LogEdit.Problem.BAD_CALL, p[LogEdit.Field.CALL])
        assertEquals(LogEdit.Problem.BAD_TIME, p[LogEdit.Field.TIME_ON])
        assertEquals(LogEdit.Problem.BAD_FREQ, p[LogEdit.Field.FREQ])
        assertEquals(LogEdit.Problem.BAD_GRID, p[LogEdit.Field.GRID])
        assertEquals(LogEdit.Problem.BAD_REPORT, p[LogEdit.Field.RST_SENT])
        assertEquals(LogEdit.Problem.NOT_ASCII, p[LogEdit.Field.COMMENT])
    }

    @Test
    fun `a frequency outside every amateur band is refused`() {
        assertEquals(LogEdit.Problem.BAD_FREQ, problems(LogEdit.formOf(q).copy(freqMhz = "15.0"))[LogEdit.Field.FREQ])
    }

    @Test
    fun `an imported SSB contact keeps what the form would not accept`() {
        val ssb = q.copy(mode = "SSB", rstSent = "59", rstRcvd = "57", freqHz = 14_250_123)
        val edited = (LogEdit.apply(ssb, LogEdit.formOf(ssb).copy(comment = "nice chat")) as LogEdit.Outcome.Ok).qso
        assertEquals(ssb.copy(comment = "nice chat"), edited)
        assertTrue(LogEdit.formOf(ssb).freqMhz == "14.250123")
    }

    @Test
    fun `emptied fields are cleared`() {
        val edited = ok(LogEdit.formOf(q).copy(grid = "", comment = "", rstRcvd = ""))
        assertEquals(null, edited.grid)
        assertEquals(null, edited.rstRcvd)
    }
}
