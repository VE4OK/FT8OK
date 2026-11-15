package com.ft8ok.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rung 0 tests for the occupancy map.
 *
 * These exist because of a specific on-air failure: during live RX once
 * every even slot picked 2626 Hz, correctly identified as the widest gap and
 * useless as a transmit frequency, because the top of the passband is empty
 * precisely where operators do not go. The TX band cap is the fix, and these tests
 * are what stop it regressing silently.
 */
class OccupancyTest {

    private val binHz = Ft8Decoder.binHz()

    /** Noise at -40 dB between two audio frequencies, the filter's stopband elsewhere. */
    private fun passband(lowHz: Int, highHz: Int) = FloatArray(1025) { bin ->
        if (bin * binHz in lowHz.toDouble()..highHz.toDouble()) -40f else -100f
    }

    @Test
    fun `a narrow filter does not drag the floor onto its skirt`() {
        // FIL2 on 6 m: 550-2250 Hz. The floor read 29 dB low, and every
        // bin of a dead band read busy.
        val map = Occupancy.analyse(Occupancy.Snapshot(binHz, passband(550, 2250), emptyList()))
        assertEquals(-40.0, map.noiseFloorDb, 0.01)
        assertTrue("a dead band is not busy: ${map.occupied}", map.occupied.isEmpty())
    }

    @Test
    fun `the floor survives a 500 Hz filter`() {
        val levels = passband(1250, 1750)
        val low = (Occupancy.FLOOR_LOW_HZ / binHz).toInt()
        val high = (Occupancy.FLOOR_HIGH_HZ / binHz).toInt()
        assertEquals(-40f, Occupancy.floorDb(levels, low, high), 0.01f)
    }

    @Test
    fun `signals inside a narrow filter still stand above the floor`() {
        val levels = passband(550, 2250)
        for (bin in (1000 / binHz).toInt()..(1050 / binHz).toInt()) levels[bin] = -20f
        val map = Occupancy.analyse(Occupancy.Snapshot(binHz, levels, emptyList()))
        assertEquals(-40.0, map.noiseFloorDb, 0.01)
        assertEquals(1, map.occupied.size)
    }

    @Test
    fun `a filter wider than the range changes nothing`() {
        val low = (Occupancy.FLOOR_LOW_HZ / binHz).toInt()
        val high = (Occupancy.FLOOR_HIGH_HZ / binHz).toInt()
        val levels = spectrumWith(800, 1200, 1900)
        val plain = levels.copyOfRange(low, high + 1).sorted()
        assertEquals(plain[(plain.size * Occupancy.FLOOR_PERCENTILE).toInt()], Occupancy.floorDb(levels, low, high), 0f)
    }

    /** A flat noise floor with Gaussian-ish humps at the given audio frequencies. */
    private fun spectrumWith(vararg signalHz: Int, levelDb: Float = 25f): FloatArray {
        val bins = FloatArray(1025) { -100f }
        for (hz in signalHz) {
            val centre = (hz / binHz).toInt()
            val halfWidth = ((Occupancy.SIGNAL_WIDTH_HZ / 2) / binHz).toInt()
            for (i in (centre - halfWidth)..(centre + halfWidth)) {
                if (i in bins.indices) bins[i] = -100f + levelDb
            }
        }
        return bins
    }

    private fun analyse(vararg signalHz: Int) =
        Occupancy.analyse(Occupancy.Snapshot(binHz, spectrumWith(*signalHz), emptyList()))

    @Test
    fun `no candidate falls outside the transmit band`() {
        // The empty passband top is the whole reason for the cap: leave 1000-2000 Hz
        // packed so the widest *absolute* gap is above 2500, and check we refuse it.
        val busy = IntArray(21) { 1000 + it * 50 }.toList().toIntArray()
        val map = analyse(*busy)
        assertTrue("expected candidates", map.candidates.isNotEmpty())
        for (hz in map.candidates) {
            assertTrue(
                "candidate $hz Hz is outside ${Occupancy.TX_LOW_HZ}-${Occupancy.TX_HIGH_HZ}",
                hz >= Occupancy.TX_LOW_HZ && hz <= Occupancy.TX_HIGH_HZ
            )
        }
    }

    @Test
    fun `our whole signal fits inside the transmit band`() {
        // Starting inside the band is not enough - a 50 Hz signal centred at 2490
        // would hang over the top edge.
        val half = Occupancy.SIGNAL_WIDTH_HZ / 2
        for (hz in analyse(1500).candidates) {
            assertTrue("$hz - $half below TX band", hz - half >= Occupancy.TX_LOW_HZ)
            assertTrue("$hz + $half above TX band", hz + half <= Occupancy.TX_HIGH_HZ)
        }
    }

    @Test
    fun `energy outside the transmit band is still reported as occupied`() {
        // Monitor wide, transmit narrow: a station at 3050 Hz must appear on the
        // strip even though we will never choose to sit next to it.
        val map = analyse(3050)
        assertTrue(
            "3050 Hz signal missing from occupied bands: ${map.occupied}",
            map.occupied.any { 3050 in it.fromHz..it.toHz }
        )
        assertTrue("3050 Hz leaked into candidates", map.candidates.none { it > Occupancy.TX_HIGH_HZ })
    }

    @Test
    fun `a signal splits the band into gaps either side of it`() {
        val map = analyse(2200)
        assertEquals("expected a gap below and above 2200 Hz", 2, map.gaps.size)
        assertTrue(map.gaps.all { it.widthHz >= Occupancy.MIN_GAP_HZ })
        // 2200 is nearer the top of 300-3000, so the lower gap is the wider one and
        // must be offered first.
        assertTrue("widest gap not first", map.candidates.first() < 2200)
    }

    @Test
    fun `a decoded signal marks its frequency busy even with no measured energy`() {
        // A message that finished early averages down below the energy threshold but
        // still owned that frequency for the slot.
        val snapshot = Occupancy.Snapshot(binHz, FloatArray(1025) { -100f }, listOf(1200))
        val map = Occupancy.analyse(snapshot)
        assertTrue(
            "decoded 1200 Hz not marked occupied: ${map.occupied}",
            map.occupied.any { 1200 in it.fromHz..it.toHz }
        )
    }

    @Test
    fun `a crowded transmit band yields no candidates`() {
        // 1000-2400 Hz solid: 52% of the monitored band, which is the realistic busy
        // case and well inside what the percentile floor can measure.
        val busy = IntArray(29) { 1000 + it * 50 }
        val map = analyse(*busy)
        assertTrue(
            "expected no gap wide enough: ${map.gaps}",
            map.candidates.none { it in 1000..2400 }
        )
    }

    @Test
    fun `the floor estimate goes blind once the band is more than three-quarters full`() {
        // A documented limitation, not a bug to be silently tolerated: ANY order
        // statistic at fraction p fails above (1 - p) occupancy, because the signals
        // become the floor. At FLOOR_PERCENTILE = 0.25 that limit is 75% of the range
        // the floor is measured over (500-3000 Hz). 400-2550 Hz busy is 82% of it.
        // So "the whole TX band is busy" is a state this detector cannot report from
        // energy alone (a sliding local floor would fix it).
        //
        // This test asserts the blind spot deliberately. If it starts failing,
        // something improved and the open item can be closed.
        val busy = IntArray(43) { 400 + it * 50 }
        val map = analyse(*busy)
        assertTrue(
            "band is now detected as full - good news, update this known limit",
            map.occupied.isEmpty()
        )
    }

    @Test
    fun `the strip spans the monitored band and the window marks the transmit band`() {
        val width = 64
        val map = analyse(3050)
        assertEquals(width, Occupancy.render(map, width).length)
        val window = Occupancy.txWindow(width)
        assertEquals(width, window.length)
        // The top of the monitored band is outside the TX window, so the last column
        // must be blank - that difference is the thing the operator needs to see.
        assertEquals(' ', window.last())
        assertTrue(window.contains('['))
        assertTrue(window.contains(']'))
    }

    // ---- FT4's 83 Hz signal ----

    private val ft4Band = Occupancy.TxBand.DEFAULT.copy(signalWidthHz = 83)

    @Test
    fun `ft4 needs a wider gap than ft8`() {
        // Decoded stations at 1000 and 1080 Hz: FT8 signals end at 1050, leaving a
        // 30 Hz hole that is no gap for either; at 1000 and 1150 FT8 sees a gap
        // (1055..1145, 90 Hz >= 60) that FT4 does not (its 1000 signal ends at 1083,
        // leaving 1088..1145, 57 Hz < 93).
        val levels = FloatArray(1025) { -100f }
        val ft8 = Occupancy.analyse(Occupancy.Snapshot(binHz, levels, listOf(1000, 1150)))
        val ft4 = Occupancy.analyse(Occupancy.Snapshot(binHz, levels, listOf(1000, 1150)), txBand = ft4Band)
        assertTrue("FT8 gap between: ${ft8.gaps}", ft8.gaps.any { it.fromHz > 1000 && it.toHz < 1150 })
        assertTrue("FT4 gap between: ${ft4.gaps}", ft4.gaps.none { it.fromHz > 1000 && it.toHz < 1150 })
    }

    @Test
    fun `an ft4 pick leaves its whole 83 Hz inside the transmit band and centred in its gap`() {
        val map = Occupancy.analyse(Occupancy.Snapshot(binHz, FloatArray(1025) { -100f }, listOf(1500)), txBand = ft4Band)
        assertEquals(ft4Band.highHz - 83, ft4Band.searchHighHz)
        for (hz in map.candidates) assertTrue("$hz + 83 past ${ft4Band.highHz}", hz + 83 <= ft4Band.highHz)
        for (gap in map.gaps) {
            val pick = gap.fromHz + (gap.widthHz - 83) / 2
            assertTrue("$pick not offered: ${map.candidates}", pick in map.candidates)
        }
    }
}
