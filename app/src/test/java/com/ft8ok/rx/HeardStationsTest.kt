package com.ft8ok.rx

import com.ft8ok.dsp.Decode
import com.ft8ok.model.SlotParity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HeardStationsTest {

    private val slot = 15_000L
    private val t0 = 1_760_000_010_000L / 30_000 * 30_000

    private fun row(slotIndex: Int, message: String, snrDb: Double? = -10.0) = RxSession.DecodeRow(
        slotStartMs = t0 + slotIndex * slot,
        parity = if (slotIndex % 2 == 0) SlotParity.EVEN else SlotParity.ODD,
        decode = Decode(score = 20, dtSeconds = 0.1, frequencyHz = 1500, message = message),
        snrDb = snrDb
    )

    @Test
    fun `contest stations are heard, with a grid when their exchange has one`() {
        // Field Day and the rest in Roster and Map.
        val heard = HeardStations()
        heard.observe(
            listOf(
                row(0, "K1ABC W9XYZ 6A WI"),
                row(1, "TU; KA0DEF K1ABC R 569 MA"),
                row(2, "<G4ABC/P> <PA9XYZ> R 570007 JO22DB"),
                row(3, "<...> <...> 590003 IO91NP"),
                row(4, "W9XYZ N1ABC R FN42")
            ),
            slot
        )
        val byCall = heard.heardSince(0, null).associateBy { it.call }
        assertEquals(setOf("W9XYZ", "K1ABC", "PA9XYZ", "N1ABC"), byCall.keys)
        assertNull(byCall.getValue("W9XYZ").grid)
        assertEquals("JO22", byCall.getValue("PA9XYZ").grid)
        assertEquals("FN42", byCall.getValue("N1ABC").grid)
        assertEquals("W9XYZ", HeardStations.senderOf("K1ABC W9XYZ 6A WI"))
    }

    @Test
    fun `a station keeps its grid and moves forward in time, never back`() {
        val heard = HeardStations()
        heard.observe(listOf(row(0, "CQ K1ABC FN42")), slot)
        heard.observe(listOf(row(4, "W9XYZ K1ABC -12")), slot)
        heard.observe(listOf(row(2, "CQ K1ABC FN42")), slot) // late, older
        val k = heard.heardSince(0, null).single()
        assertEquals("FN42", k.grid)
        assertEquals(t0 + 5 * slot, k.lastHeardMs)
        assertEquals("W9XYZ K1ABC -12", k.row.decode.message)
    }

    @Test
    fun `an early pass replaces the old message at once and keeps the last report until the final one`() {
        // VE3XAB: odd answering N9XAA, then CQ on even. Until the slot's
        // final pass the Roster still handed over the odd-slot message.
        val heard = HeardStations()
        heard.observe(listOf(row(1, "N9XAA VE3XAB FN03", snrDb = -6.0)), slot)
        heard.observe(listOf(row(4, "CQ VE3XAB FN03", snrDb = null)), slot)
        val x = heard.heardSince(0, null).single()
        assertEquals("CQ VE3XAB FN03", x.row.decode.message)
        assertEquals(SlotParity.EVEN, x.row.parity)
        assertTrue(x.calling)
        assertEquals("last measured, until the final pass", com.ft8ok.dsp.SnrEstimator.toReport(-6.0), x.report)
    }

    @Test
    fun `the sender of a message`() {
        assertEquals("VE3XAB", HeardStations.senderOf("CQ VE3XAB FN03"))
        assertEquals("VE3XAB", HeardStations.senderOf("N9XAA VE3XAB FN03"))
        assertEquals("K1ABC", HeardStations.senderOf("VE4OK K1ABC RR73"))
        assertNull(HeardStations.senderOf("TNX 73 GL"))
    }

    @Test
    fun `the oldest-heard stations are dropped past the cap`() {
        val heard = HeardStations(cap = 2)
        heard.observe(listOf(row(0, "CQ AA1A FN42"), row(1, "CQ BB2B FN42"), row(2, "CQ CC3C FN42")), slot)
        heard.observe(listOf(row(3, "CQ AA1A FN42")), slot)
        heard.observe(listOf(row(4, "CQ DD4D FN42")), slot)
        assertEquals(setOf("AA1A", "DD4D"), heard.heardSince(0, null).map { it.call }.toSet())
    }

    @Test
    fun `squares group stations by the grid they sent, newest first`() {
        val heard = HeardStations()
        heard.observe(
            listOf(
                row(0, "CQ K1ABC FN42"),
                row(1, "W1AW W1XYZ FN42"),
                row(2, "CQ VE4AA FN25"),
                row(3, "K1ABC N0GRID -05")
            ),
            slot
        )
        val squares = MapSquares.build(heard.heardSince(0, null))
        assertEquals(setOf("FN42", "FN25"), squares.keys)
        assertEquals(listOf("W1XYZ", "K1ABC"), squares.getValue("FN42").stations.map { it.call })
        assertTrue(squares.getValue("FN42").anyCalling)
        assertNull("a station that never sent a grid is not on the map", squares.values.flatMap { it.stations }.find { it.call == "N0GRID" })
    }

    @Test
    fun `a station is kept per band, and each band sees only its own`() {
        // After a move to 17 m, a 20 m entry must not be offered.
        val heard = HeardStations()
        heard.observe(listOf(row(0, "CQ K1ABC FN42").copy(band = "20m")), slot)
        heard.observe(listOf(row(4, "CQ K1ABC FN42").copy(band = "17m")), slot)
        heard.observe(listOf(row(5, "CQ W9XYZ EN52").copy(band = "17m")), slot)
        assertEquals(listOf("K1ABC"), heard.heardSince(0, "20m").map { it.call })
        assertEquals(setOf("K1ABC", "W9XYZ"), heard.heardSince(0, "17m").map { it.call }.toSet())
        assertEquals("17m", heard.heardSince(0, "17m").first { it.call == "K1ABC" }.row.band)
        assertTrue("unknown band is its own", heard.heardSince(0, null).isEmpty())
    }

    @Test
    fun `stations heard in a gap that ended on another band become of unknown band`() {
        // The radio was lost in slot 2 and came back on 17 m.
        val heard = HeardStations()
        heard.observe(listOf(row(0, "CQ K1ABC FN42").copy(band = "20m")), slot)
        heard.observe(listOf(row(3, "CQ W9XYZ EN52").copy(band = "20m")), slot)
        heard.unknownBandSince(t0 + 2 * slot + 5_000)
        assertEquals(listOf("K1ABC"), heard.heardSince(0, "20m").map { it.call })
        val unknown = heard.heardSince(0, null)
        assertEquals(listOf("W9XYZ"), unknown.map { it.call })
        assertNull(unknown.first().row.band)
    }
}
