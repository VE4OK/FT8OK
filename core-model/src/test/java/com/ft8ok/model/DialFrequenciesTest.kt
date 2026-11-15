package com.ft8ok.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The table against the things `docs/reference/FREQUENCIES.md` says to get right.
 *
 * These are not tests of arithmetic; they are tests that the transcription is
 * faithful and that the traps named in that file are actually avoided. A wrong
 * number here puts a transmission on the wrong frequency.
 */
class DialFrequenciesTest {

    @Test
    fun `the frequencies an operator will actually use`() {
        assertEquals(14_074_000L, DialFrequencies.at(14_074_000, "FT8")!!.hz)
        assertEquals("20m", DialFrequencies.bandAt(14_074_000))
        assertEquals("17m", DialFrequencies.bandAt(18_100_000))
        assertEquals("40m", DialFrequencies.bandAt(7_074_000))
    }

    @Test
    fun `40m FT4 is not a whole kHz, which is why the table is in Hz`() {
        val entry = DialFrequencies.at(7_047_500, "FT4")
        assertEquals(7_047_500L, entry!!.hz)
        // 7.047 or 7.048 would both be wrong, and both look plausible.
        assertNull(DialFrequencies.at(7_047_000, "FT4"))
        assertNull(DialFrequencies.at(7_048_000, "FT4"))
    }

    @Test
    fun `there is no FT4 allocation on 160m, and a picker must not offer one`() {
        assertTrue(DialFrequencies.ALL.none { it.mode == "FT4" && it.band == "160m" })
        assertTrue(DialFrequencies.ALL.any { it.mode == "FT8" && it.band == "160m" })
    }

    @Test
    fun `the guard allows DXpedition alternates, not just the preferred frequencies`() {
        // Blocking 14.090 would stop the operator working a DXpedition. This is the
        // single most likely way to get the guard wrong.
        assertTrue(DialFrequencies.isConventional(14_090_000, "FT8"))
        assertFalse(DialFrequencies.at(14_090_000, "FT8")!!.preferred)
        assertTrue(DialFrequencies.isConventional(7_056_000, "FT8"))
        assertTrue(DialFrequencies.isConventional(18_095_000, "FT8"))
    }

    @Test
    fun `presets offer only the preferred frequencies`() {
        val presets = DialFrequencies.presetsFor("FT8")
        assertTrue(presets.all { it.preferred })
        assertTrue(presets.any { it.hz == 14_074_000L })
        assertFalse(presets.any { it.hz == 14_090_000L })
    }

    @Test
    fun `60m is shared between the modes, at the same frequency`() {
        assertEquals(5_357_000L, DialFrequencies.at(5_357_000, "FT8")!!.hz)
        assertEquals(5_357_000L, DialFrequencies.at(5_357_000, "FT4")!!.hz)
    }

    @Test
    fun `region-specific entries are kept out of the default presets`() {
        // 4 m is R1 and 1.25 m is R2; everything else is ALL.
        val defaults = DialFrequencies.presetsFor("FT8", Region.ALL)
        assertFalse(defaults.any { it.band == "4m" })
        assertFalse(defaults.any { it.band == "1.25m" })
        assertTrue(DialFrequencies.presetsFor("FT8", Region.R1).any { it.band == "4m" })
    }

    @Test
    fun `being somewhere else entirely is not a conventional frequency`() {
        // The middle of the 20 m phone band, and the WSPR frequency.
        assertFalse(DialFrequencies.isConventional(14_250_000))
        assertFalse(DialFrequencies.isConventional(14_095_600))
        assertNull(DialFrequencies.bandAt(14_250_000))
    }

    @Test
    fun `a few Hz off the dial still counts as being on it`() {
        // Tuned by hand, sitting 30 Hz low. Still 20 m FT8.
        assertEquals("20m", DialFrequencies.bandAt(14_073_970))
        assertTrue(DialFrequencies.isConventional(14_074_030, "FT8"))
    }

    @Test
    fun `the tolerance can never make two entries both match`() {
        // The closest pair in the table is 80 m FT4 3.575 and FT8 3.573, 2 kHz
        // apart. If the tolerance ever grew past half of that, "which frequency am
        // I on" would stop having one answer.
        val sorted = DialFrequencies.ALL.map { it.hz }.distinct().sorted()
        val closest = sorted.zipWithNext { a, b -> b - a }.minOrNull()!!
        assertTrue(
            "closest pair is $closest Hz apart, tolerance is ${DialFrequencies.MATCH_TOLERANCE_HZ}",
            DialFrequencies.MATCH_TOLERANCE_HZ * 2 < closest
        )
    }

    @Test
    fun `every entry is plausible as an integer Hz dial frequency`() {
        for (f in DialFrequencies.ALL) {
            assertTrue("${f.band} ${f.mode}: ${f.hz} Hz is not in the HF-to-microwave range", f.hz in 1_000_000..2_000_000_000)
            assertTrue("${f.band} ${f.mode}: band label is empty", f.band.isNotEmpty())
            assertTrue("${f.mode} is not a mode we know", f.mode in setOf("FT8", "FT4"))
        }
    }
}
