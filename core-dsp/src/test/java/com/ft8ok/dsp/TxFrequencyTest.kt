package com.ft8ok.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rung 0 tests for the ADR 0013 policy.
 *
 * The thing these guard is not an algorithm but a restraint: an early test build re-picked
 * every cycle and wandered 744 to 1728 Hz across consecutive same-parity slots,
 * each pick correct for its own slot while the partner listened where we were.
 * Most of these tests assert that nothing happens.
 */
class TxFrequencyTest {

    private val binHz = Ft8Decoder.binHz()

    /** A map with flat noise plus signals of a chosen strength at given frequencies. */
    private fun mapWith(vararg signalHz: Int, levelDb: Float = 25f): Occupancy.Map {
        val bins = FloatArray(1025) { -100f }
        for (hz in signalHz) {
            val centre = (hz / binHz).toInt()
            val half = ((Occupancy.SIGNAL_WIDTH_HZ / 2) / binHz).toInt()
            for (i in (centre - half)..(centre + half)) {
                if (i in bins.indices) bins[i] = -100f + levelDb
            }
        }
        return Occupancy.analyse(Occupancy.Snapshot(binHz, bins, emptyList()))
    }

    @Test
    fun `a held frequency survives a clear slot`() {
        val d = TxFrequency.decide(TxFrequency.Mode.FULL_AUTO, 1500, mapWith(900), inQso = false)
        assertEquals(1500, d.frequencyHz)
        assertEquals(TxFrequency.Reason.HELD, d.reason)
        assertFalse(d.moved)
    }

    @Test
    fun `nothing moves mid-QSO, however busy our spot has become`() {
        // The partner is listening where we were. This is the one case where being
        // right about the spectrum and acting on it would still be wrong.
        val d = TxFrequency.decide(TxFrequency.Mode.FULL_AUTO, 1500, mapWith(1500), inQso = true)
        assertEquals(1500, d.frequencyHz)
        assertEquals(TxFrequency.Reason.HELD_IN_QSO, d.reason)
    }

    @Test
    fun `no fresh listening slot means no move`() {
        // While CQing every cycle of our parity this is the normal state, and it is
        // the whole reason the policy holds rather than re-optimises.
        val d = TxFrequency.decide(TxFrequency.Mode.FULL_AUTO, 1500, null, inQso = false)
        assertEquals(1500, d.frequencyHz)
        assertEquals(TxFrequency.Reason.HELD_NO_FRESH_DATA, d.reason)
    }

    @Test
    fun `a strong signal on our spot moves us`() {
        val d = TxFrequency.decide(TxFrequency.Mode.FULL_AUTO, 1500, mapWith(1500), inQso = false)
        assertEquals(TxFrequency.Reason.MOVED_SPOT_BUSY, d.reason)
        assertTrue(d.moved)
        assertTrue("moved outside the TX band: ${d.frequencyHz}",
            d.frequencyHz!! in Occupancy.TX_LOW_HZ..Occupancy.TX_HIGH_HZ)
    }

    @Test
    fun `a signal just strong enough to mark a band occupied is not strong enough to move us`() {
        // The asymmetry at the heart of ADR 0013: 6 dB is enough to avoid a
        // frequency when choosing, and deliberately not enough to abandon one.
        val weak = mapWith(1500, levelDb = 8f)
        assertTrue(
            "8 dB should still count as occupied for picking",
            weak.occupied.any { 1500 in it.fromHz..it.toHz }
        )
        assertFalse("8 dB should not be enough to move", TxFrequency.isBusy(weak, 1500))

        val d = TxFrequency.decide(TxFrequency.Mode.FULL_AUTO, 1500, weak, inQso = false)
        assertEquals(TxFrequency.Reason.HELD, d.reason)
    }

    @Test
    fun `a blue-tier signal on our spot moves us since the threshold went to 9 dB`() {
        // Our own spot should be clear. At 12 dB a station
        // up to a -5 report could share it without moving us; at 9 dB, 10 dB moves.
        val d = TxFrequency.decide(TxFrequency.Mode.FULL_AUTO, 1500, mapWith(1500, levelDb = 10f), inQso = false)
        assertEquals(TxFrequency.Reason.MOVED_SPOT_BUSY, d.reason)
    }

    @Test
    fun `energy in the guard band alone does not move us`() {
        // 90 Hz away (centre to centre) clears our signal and its 5 Hz guard: a close
        // neighbour rather than someone on top of us.
        val d = TxFrequency.decide(TxFrequency.Mode.FULL_AUTO, 1500, mapWith(1590), inQso = false)
        assertEquals(TxFrequency.Reason.HELD, d.reason)
    }

    @Test
    fun `a forced move prefers the nearest COMFORTABLE gap, not simply the nearest`() {
        // Busy on us at 1500. Above us sits a 130 Hz slot between two loud stations
        // (1500 and 1700); below, the middle of a 380 Hz clear area; far above, the
        // widest gap of all.
        //
        // This test used to assert the literal nearest, which was 1600 - squeezed
        // between two signals 25 dB over the floor with 75 Hz either side. Legal by
        // the gap rule and wrong by every other measure, and exactly what was
        // seen on air. Nearness still decides between comfortable gaps; it no longer
        // buys its way into a cramped one.
        val busy = IntArray(12) { 500 + it * 50 } + intArrayOf(1500, 1700)
        val map = mapWith(*busy)
        val d = TxFrequency.decide(TxFrequency.Mode.FULL_AUTO, 1500, map, inQso = false)

        assertEquals(TxFrequency.Reason.MOVED_SPOT_BUSY, d.reason)
        assertTrue("must land somewhere comfortable", TxFrequency.isComfortable(map, d.frequencyHz!!))

        val cramped = map.candidates.minByOrNull { kotlin.math.abs(it - 1500) }!!
        assertFalse("the literal nearest gap is the cramped one", TxFrequency.isComfortable(map, cramped))
        assertTrue("and it must not have been chosen", d.frequencyHz != cramped)

        // Still nearness-driven among the comfortable ones: not the widest gap,
        // which is much further away.
        val widest = map.candidates.first()
        assertTrue(
            "should not have jumped to the widest gap at $widest",
            kotlin.math.abs(d.frequencyHz!! - 1500) < kotlin.math.abs(widest - 1500)
        )
    }

    @Test
    fun `with no frequency held we pick the widest gap`() {
        val map = mapWith(1500)
        val d = TxFrequency.decide(TxFrequency.Mode.FULL_AUTO, null, map, inQso = false)
        assertEquals(TxFrequency.Reason.FIRST_PICK, d.reason)
        assertEquals(map.candidates.first(), d.frequencyHz)
    }

    @Test
    fun `with no frequency held and no data we pick nothing rather than guessing`() {
        val d = TxFrequency.decide(TxFrequency.Mode.FULL_AUTO, null, null, inQso = false)
        assertNull(d.frequencyHz)
    }

    @Test
    fun `User-set is never touched, even when our spot is plainly busy`() {
        val d = TxFrequency.decide(TxFrequency.Mode.USER_SET, 1500, mapWith(1500), inQso = false)
        assertEquals(1500, d.frequencyHz)
        assertEquals(TxFrequency.Reason.USER_SET, d.reason)
    }

    @Test
    fun `a tap snaps to the nearest candidate gap`() {
        val map = mapWith(1500)
        val snapped = TxFrequency.snap(1200, map)
        assertEquals(map.candidates.minByOrNull { kotlin.math.abs(it - 1200) }, snapped)
    }

    @Test
    fun `a tap with no candidates still lands inside the TX band`() {
        // A frequency is the signal's LOW EDGE, so the lowest we may
        // transmit on is the band edge itself, and the highest is 50 Hz below the
        // top - which is where our own signal would end.
        assertEquals(Occupancy.TX_LOW_HZ, TxFrequency.snap(150, null))
        assertEquals(Occupancy.TX_HIGH_HZ - Occupancy.SIGNAL_WIDTH_HZ, TxFrequency.snap(3050, null))
    }

    @Test
    fun `strip geometry round-trips`() {
        for (hz in intArrayOf(400, 1000, 1500, 2500)) {
            assertEquals(hz.toFloat(), Occupancy.hzAt(Occupancy.fractionOf(hz)).toFloat(), 2f)
        }
        // Clamped at the monitored band's edges, so a tap in the margin is sane.
        assertEquals(Occupancy.BAND_LOW_HZ, Occupancy.hzAt(-1f))
        assertEquals(Occupancy.BAND_HIGH_HZ, Occupancy.hzAt(2f))
    }

    @Test
    fun `columns agree with the text strip about what is busy`() {
        // The lanes and the log must never disagree; they share one computation.
        val map = mapWith(1500)
        val width = 64
        val levels = Occupancy.columns(map, width)
        val text = Occupancy.render(map, width)
        for (col in 0 until width) {
            val busyByLevel = levels[col] >= 6f
            val busyByText = text[col] in charArrayOf(':', '+', '#')
            assertEquals("column $col", busyByLevel, busyByText)
        }
    }

    // --- Comfortable clearance -------------------------------

    @Test
    fun `a move prefers a comfortable gap over a nearer cramped one`() {
        // Two loud stations 120 Hz apart leave a legal but cramped slot between
        // them, and our spot has just been taken. Nearest-of-any-width would dive
        // straight into that slot; it must take the open water further out instead.
        val map = mapWith(1500, 1200, 1320, levelDb = 25f)
        val d = TxFrequency.decide(TxFrequency.Mode.FULL_AUTO, 1500, map, inQso = false)

        assertEquals(TxFrequency.Reason.MOVED_SPOT_BUSY, d.reason)
        assertTrue(
            "moved to ${d.frequencyHz}, which is only " +
                "${TxFrequency.clearanceHz(map, d.frequencyHz!!)} Hz from something loud",
            TxFrequency.isComfortable(map, d.frequencyHz!!)
        )
    }

    @Test
    fun `clearance is measured to loud energy, not to anything at all`() {
        // A weak neighbour is not a reason to move further away: we would be giving
        // up a good spot to a station we are probably stronger than.
        val quiet = mapWith(1200, levelDb = 8f)
        assertTrue(TxFrequency.isComfortable(quiet, 1260))

        val loud = mapWith(1200, levelDb = 25f)
        assertFalse(TxFrequency.isComfortable(loud, 1260))
    }

    @Test
    fun `an empty band leaves every candidate comfortable`() {
        val empty = mapWith()
        assertTrue(TxFrequency.isComfortable(empty, 1500))
        assertEquals(Int.MAX_VALUE, TxFrequency.clearanceHz(empty, 1500))
    }

    @Test
    fun `a crowded band still transmits rather than refusing`() {
        // Loud signals every 150 Hz across the whole TX band: nothing is comfortable
        // anywhere. Holding out for a good spot would mean never transmitting, so
        // the nearest usable gap is still taken.
        val crowded = mapWith(*(400..2500 step 150).toList().toIntArray(), levelDb = 25f)
        val d = TxFrequency.decide(TxFrequency.Mode.FULL_AUTO, 1450, crowded, inQso = false)
        assertTrue(
            "must still produce a frequency on a crowded band, got ${d.reason}",
            d.frequencyHz != null
        )
    }

    @Test
    fun `the first pick also prefers comfort over raw width`() {
        // The widest gap is beside a loud station; a slightly narrower one is clear.
        // With nothing held, the clear one wins.
        val map = mapWith(1000, 1060, levelDb = 25f)
        val d = TxFrequency.decide(TxFrequency.Mode.FULL_AUTO, null, map, inQso = false)
        assertEquals(TxFrequency.Reason.FIRST_PICK, d.reason)
        assertTrue(TxFrequency.isComfortable(map, d.frequencyHz!!))
    }

    @Test
    fun `comfort never overrides the rule that we do not move at all`() {
        // An uncomfortable spot is not a reason to move. Only a BUSY spot is.
        // Otherwise the policy would drift every time a loud station appeared
        // nearby, which is the re-picking ADR 0013 exists to prevent.
        val map = mapWith(1400, levelDb = 25f)
        val d = TxFrequency.decide(TxFrequency.Mode.FULL_AUTO, 1460, map, inQso = false)
        assertEquals(TxFrequency.Reason.HELD, d.reason)
        assertEquals(1460, d.frequencyHz)
        assertFalse(TxFrequency.isComfortable(map, 1460))
    }

    @Test
    fun `a chosen frequency leaves our whole signal inside the gap`() {
        // A frequency is the LOW EDGE, so a candidate must leave room
        // for our 50 Hz above it. Returning the gap's centre - which is what this
        // used to do - would push the top half of our signal into the station that
        // bounds the gap above.
        val map = mapWith(1000, 1400, levelDb = 25f)
        for (hz in map.candidates) {
            val gap = map.gaps.firstOrNull { hz >= it.fromHz && hz <= it.toHz }
            assertTrue("candidate $hz is not inside any gap", gap != null)
            assertTrue(
                "our signal would run from $hz to ${hz + Occupancy.SIGNAL_WIDTH_HZ}, " +
                    "past the gap ending at ${gap!!.toHz}",
                hz + Occupancy.SIGNAL_WIDTH_HZ <= gap.toHz
            )
        }
    }

    @Test
    fun `energy just above our frequency is a collision, just below is not`() {
        // Asymmetric because our signal extends upward from the frequency we name.
        // A station 30 Hz ABOVE us is underneath our own transmission; one 30 Hz
        // BELOW us is clear of it.
        // Two stations the SAME distance either side of our frequency - mapWith
        // centres each one - and only the upper one collides with us.
        val above = mapWith(1540, levelDb = 25f)
        val below = mapWith(1460, levelDb = 25f)
        assertTrue("a station 40 Hz above our low edge sits under our signal", TxFrequency.isBusy(above, 1500))
        assertFalse("one the same distance below does not", TxFrequency.isBusy(below, 1500))
    }

    // --- The radio's SSB-D transmit passband ------------

    private fun mapIn(band: Occupancy.TxBand, vararg signalHz: Int): Occupancy.Map {
        val bins = FloatArray(1025) { -100f }
        for (hz in signalHz) {
            val centre = (hz / binHz).toInt()
            val half = ((Occupancy.SIGNAL_WIDTH_HZ / 2) / binHz).toInt()
            for (i in (centre - half)..(centre + half)) if (i in bins.indices) bins[i] = -75f
        }
        return Occupancy.analyse(Occupancy.Snapshot(binHz, bins, emptyList()), txBand = band)
    }

    @Test
    fun `no candidate lets our signal run past the radio's passband`() {
        // 300-2700: two NO RF transmissions at 2838 Hz on air.
        val band = Occupancy.TxBand(300, 2700)
        val map = mapIn(band, 1000, 1500)
        assertTrue(map.candidates.isNotEmpty())
        for (hz in map.candidates) assertTrue("$hz runs past 2700", hz + Occupancy.SIGNAL_WIDTH_HZ <= 2700)
        assertTrue(map.candidates.all { it >= 300 })
    }

    @Test
    fun `a held pick outside a narrowed passband is given up`() {
        // Held at 2600 on a radio now set to 2500: clear spectrum, but no power there.
        val band = Occupancy.TxBand(300, 2500)
        val d = TxFrequency.decide(TxFrequency.Mode.FULL_AUTO, 2600, mapIn(band, 1000), inQso = false)
        assertEquals(TxFrequency.Reason.MOVED_SPOT_BUSY, d.reason)
        assertTrue("moved to ${d.frequencyHz}", band.fits(d.frequencyHz!!))
    }

    @Test
    fun `a tap with no candidates is clamped into the radio's passband`() {
        val band = Occupancy.TxBand(500, 2900)
        assertEquals(500, TxFrequency.snap(200, null, band))
        assertEquals(2850, TxFrequency.snap(3050, null, band))
    }

    @Test
    fun `the quietest spot stays inside the radio's passband`() {
        val band = Occupancy.TxBand(300, 2500)
        val levels = FloatArray(((Occupancy.BAND_HIGH_HZ - Occupancy.BAND_LOW_HZ) / binHz).toInt() + 1) { i ->
            if (Occupancy.BAND_LOW_HZ + i * binHz > 2500) 0f else 20f
        }
        val map = Occupancy.Map(levels, binHz, -100.0, emptyList(), emptyList(), emptyList(), band)
        val hz = TxFrequency.quietestHz(map)!!
        assertTrue("$hz is outside 300-2500", band.fits(hz))
    }

    // --- A band with no gap left: the quietest spot -----

    /**
     * A map with no gap anywhere: [loudDb] across the whole spectrum except a quieter
     * patch of [quietDb] starting at [quietHz]. Built directly, because a band this
     * full blinds the percentile floor and [Occupancy.analyse] would report it empty
     * - which is exactly when ranking spots against each other has
     * to work.
     */
    private fun fullMap(quietHz: Int, quietDb: Float = 4f, loudDb: Float = 20f): Occupancy.Map {
        val levels = FloatArray(((Occupancy.BAND_HIGH_HZ - Occupancy.BAND_LOW_HZ) / binHz).toInt() + 1) { loudDb }
        val from = ((quietHz - Occupancy.BAND_LOW_HZ) / binHz).toInt()
        val to = ((quietHz + 120 - Occupancy.BAND_LOW_HZ) / binHz).toInt()
        for (i in from..to) levels[i] = quietDb
        return Occupancy.Map(
            aboveFloorDb = levels,
            binHz = binHz,
            noiseFloorDb = -100.0,
            occupied = listOf(Occupancy.Band(Occupancy.BAND_LOW_HZ, Occupancy.BAND_HIGH_HZ)),
            gaps = emptyList(),
            candidates = emptyList()
        )
    }

    @Test
    fun `with no gap anywhere the first pick is the quietest spot, not nothing`() {
        // It used to return nothing, and the transmitter fell back to 1500 Hz.
        val d = TxFrequency.decide(TxFrequency.Mode.FULL_AUTO, null, fullMap(2200), inQso = false)
        assertEquals(TxFrequency.Reason.FIRST_PICK, d.reason)
        val hz = d.frequencyHz!!
        assertTrue("picked $hz, not in the quiet patch at 2200-2320", hz in 2200..2270)
    }

    @Test
    fun `a busy spot with no gap anywhere moves to the quietest spot`() {
        val d = TxFrequency.decide(TxFrequency.Mode.FULL_AUTO, 1000, fullMap(2200), inQso = false)
        assertEquals(TxFrequency.Reason.MOVED_TO_QUIETEST, d.reason)
        assertTrue(d.moved)
        assertTrue("moved to ${d.frequencyHz}", d.frequencyHz!! in 2200..2270)
    }

    @Test
    fun `the quietest spot is any quieter spot, with no margin`() {
        // Auto always moves to the quietest. Only 2 dB quieter, still a move.
        val d = TxFrequency.decide(
            TxFrequency.Mode.FULL_AUTO, 1000, fullMap(2200, quietDb = 18f, loudDb = 20f), inQso = false
        )
        assertEquals(TxFrequency.Reason.MOVED_TO_QUIETEST, d.reason)
    }

    @Test
    fun `already on the quietest spot we stay`() {
        val map = fullMap(2200)
        val quietest = TxFrequency.quietestHz(map)!!
        val d = TxFrequency.decide(TxFrequency.Mode.FULL_AUTO, quietest, map.copy(
            // Make our own spot count as busy, so the move logic runs at all.
            aboveFloorDb = map.aboveFloorDb.map { it + 10f }.toFloatArray()
        ), inQso = false)
        assertEquals(TxFrequency.Reason.HELD_NOWHERE_BETTER, d.reason)
        assertEquals(quietest, d.frequencyHz)
    }

    @Test
    fun `the quietest spot keeps our whole signal inside the TX band`() {
        // Quiet patches at both edges of the spectrum, outside what we may use.
        val levels = FloatArray(((Occupancy.BAND_HIGH_HZ - Occupancy.BAND_LOW_HZ) / binHz).toInt() + 1) { i ->
            val hz = Occupancy.BAND_LOW_HZ + i * binHz
            if (hz < Occupancy.TX_LOW_HZ || hz > Occupancy.TX_HIGH_HZ) 0f else 20f
        }
        val map = Occupancy.Map(levels, binHz, -100.0, emptyList(), emptyList(), emptyList())
        val hz = TxFrequency.quietestHz(map)!!
        assertTrue("$hz below the TX band", hz >= Occupancy.TX_LOW_HZ)
        assertTrue("$hz runs past the TX band", hz + Occupancy.SIGNAL_WIDTH_HZ <= Occupancy.TX_HIGH_HZ)
    }
}
