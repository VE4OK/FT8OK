package com.ft8ok.rx

import com.ft8ok.dsp.Decode
import com.ft8ok.model.SlotParity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RosterTest {

    private val slot = 15_000L
    private val t0 = 1_760_000_010_000L / 30_000 * 30_000

    private fun row(slotIndex: Int, message: String, snrDb: Double? = -10.0, tx: Boolean = false) =
        RxSession.DecodeRow(
            slotStartMs = t0 + slotIndex * slot,
            parity = if (slotIndex % 2 == 0) SlotParity.EVEN else SlotParity.ODD,
            decode = Decode(score = 20, dtSeconds = 0.1, frequencyHz = 1500, message = message),
            snrDb = snrDb,
            tx = tx
        )

    private fun nowAfter(slotIndex: Int) = t0 + (slotIndex + 1) * slot

    @Test
    fun `one entry per station, from its latest decode, keeping a grid heard earlier`() {
        val feed = listOf(
            row(0, "CQ K1ABC FN42"),
            row(2, "W9XYZ K1ABC -12")
        )
        val entries = Roster.build(feed, nowAfter(2), 90_000, slot)
        assertEquals(1, entries.size)
        assertEquals("K1ABC", entries[0].call)
        assertEquals("FN42", entries[0].grid)
        assertTrue("last message was a report, not a CQ", !entries[0].calling)
        assertEquals("W9XYZ K1ABC -12", entries[0].row.decode.message)
    }

    @Test
    fun `stations outside the window, our own transmissions and hashed calls are left out`() {
        val feed = listOf(
            row(0, "CQ OLD1 EN10"),
            row(10, "CQ NEW1 EN20"),
            row(10, "NEW1 VE4OK FN25", tx = true),
            row(10, "VE4OK <...> RR73")
        )
        val entries = Roster.build(feed, nowAfter(10), 30_000, slot)
        assertEquals(listOf("NEW1"), entries.map { it.call })
    }

    @Test
    fun `sorted by recent, by SNR strongest first, or by grid`() {
        val feed = listOf(
            row(0, "CQ AA1A FN42", snrDb = -20.0),
            row(1, "CQ BB2B EM12", snrDb = 0.0),
            row(2, "W1XX CC3C -05", snrDb = -10.0)
        )
        val now = nowAfter(2)
        assertEquals(listOf("CC3C", "BB2B", "AA1A"), Roster.build(feed, now, 90_000, slot, Roster.Sort.RECENT).map { it.call })
        assertEquals("BB2B", Roster.build(feed, now, 90_000, slot, Roster.Sort.SNR).first().call)
        val byGrid = Roster.build(feed, now, 90_000, slot, Roster.Sort.GRID)
        assertEquals(listOf("BB2B", "AA1A", "CC3C"), byGrid.map { it.call })
        assertNull("no grid sorts last", byGrid.last().grid)
    }

    @Test
    fun `sorted by country, the DX lands above the United States block`() {
        val countries = mapOf("K1ABC" to "United States", "W2DEF" to "United States",
            "EA8ZZZ" to "Canary Islands", "VE5NNN" to "Canada", "JA1XYZ" to "Japan")
        val feed = listOf(
            row(0, "CQ K1ABC FN42"), row(0, "CQ EA8ZZZ IL18"), row(1, "CQ W2DEF FN20"),
            row(1, "CQ VE5NNN DO62"), row(2, "CQ JA1XYZ PM95"), row(2, "CQ ZZ0ZZ AA00")
        )
        val sorted = Roster.build(feed, nowAfter(2), 90_000, slot, Roster.Sort.COUNTRY) { countries[it] }
        assertEquals(listOf("VE5NNN", "EA8ZZZ", "JA1XYZ", "K1ABC", "W2DEF", "ZZ0ZZ"), sorted.map { it.call })
    }
}
