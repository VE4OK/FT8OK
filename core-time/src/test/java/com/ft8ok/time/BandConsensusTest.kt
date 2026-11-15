package com.ft8ok.time

import com.ft8ok.model.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BandConsensusTest {

    private fun samples(vararg dt: Double, weight: Double = 1.0) =
        dt.map { BandConsensus.DtSample(it, weight) }

    @Test
    fun `a slot with too few stations has no verdict`() {
        assertNull(BandConsensus.estimate(samples(0.5, 0.5), Mode.FT8))
    }

    @Test
    fun `a well-synchronised band asks for no correction`() {
        // Everyone near the nominal 0.5 s start: our clock is right.
        val e = BandConsensus.estimate(samples(0.4, 0.5, 0.5, 0.6, 0.5), Mode.FT8)
        assertNotNull(e)
        assertEquals(0.5, e!!.medianDtSec, 1e-9)
        assertEquals(0.0, e.residualSec, 1e-9)
        assertEquals(0L, e.correctionMs)
    }

    @Test
    fun `signals arriving late mean our clock is fast, and the correction is negative`() {
        // The phone measured 1.66 s fast once: every station then appears
        // 1.66 s late, at DT 2.16, and the clock must go BACK.
        val e = BandConsensus.estimate(samples(2.1, 2.16, 2.16, 2.2, 2.16), Mode.FT8)!!
        assertEquals(1.66, e.residualSec, 1e-9)
        assertEquals(-1660L, e.correctionMs)
    }

    @Test
    fun `one badly synchronised station does not move the consensus`() {
        val honest = samples(0.48, 0.50, 0.52, 0.49)
        val withRogue = honest + BandConsensus.DtSample(4.0, 1.0)
        val a = BandConsensus.estimate(honest, Mode.FT8)!!
        val b = BandConsensus.estimate(withRogue, Mode.FT8)!!
        assertEquals(a.medianDtSec, b.medianDtSec, 1e-9)
        assertEquals(1, b.stationsRejected)
        assertEquals(4, b.stationsUsed)
    }

    @Test
    fun `confidence weighting favours the firmly decoded stations`() {
        // Three weak stations clustered late, two strong ones at the right place.
        // The weighted median must side with the weight, not the head count.
        val mixed = listOf(
            BandConsensus.DtSample(0.50, 100.0),
            BandConsensus.DtSample(0.50, 100.0),
            BandConsensus.DtSample(0.62, 1.0),
            BandConsensus.DtSample(0.64, 1.0),
            BandConsensus.DtSample(0.66, 1.0)
        )
        val e = BandConsensus.estimate(mixed, Mode.FT8)!!
        assertEquals(0.50, e.medianDtSec, 1e-9)
    }

    @Test
    fun `a slot that is all outliers produces no verdict rather than a bad one`() {
        // Two tight clusters a long way apart and nothing in between: there is no
        // consensus here, and inventing one would poison the loop.
        val split = samples(0.5, 0.5, 6.0, 6.0)
        val e = BandConsensus.estimate(split, Mode.FT8)
        if (e != null) assertTrue("must not land between the clusters", e.medianDtSec < 1.0)
    }

    @Test
    fun `the correction is expressed against the mode's own nominal start`() {
        // Same measured DT, different mode: FT4's nominal start is its own constant,
        // so nothing here may assume FT8's (ADR 0006).
        val dts = samples(1.5, 1.5, 1.5)
        val ft8 = BandConsensus.estimate(dts, Mode.FT8)!!
        val ft4 = BandConsensus.estimate(dts, Mode.FT4)!!
        assertEquals(1.5 - Mode.FT8.nominalTxStartMs / 1000.0, ft8.residualSec, 1e-9)
        assertEquals(1.5 - Mode.FT4.nominalTxStartMs / 1000.0, ft4.residualSec, 1e-9)
    }

    @Test
    fun `zero-weight measurements are ignored, not counted towards the gate`() {
        val e = BandConsensus.estimate(
            listOf(
                BandConsensus.DtSample(0.5, 1.0),
                BandConsensus.DtSample(0.5, 1.0),
                BandConsensus.DtSample(0.5, 0.0)
            ),
            Mode.FT8
        )
        assertNull(e)
    }

    @Test
    fun `the decoder's own DT bias is accounted for, not folded into the protocol`() {
        // ft8_lib reports DT about 0.15 s above the true offset, so a
        // correctly-timed station reads 0.65 on it rather than the protocol's 0.50.
        // Treating 0.50 as correct drives the whole slot grid 0.15 s late.
        val dts = samples(0.65, 0.65, 0.65, 0.65, 0.65)

        val naive = BandConsensus.estimate(dts, Mode.FT8)!!
        assertEquals("without the bias it thinks we are 150 ms out", -150L, naive.correctionMs)

        val corrected = BandConsensus.estimate(dts, Mode.FT8, decoderDtBiasSec = 0.15)!!
        assertEquals("with it, the band is telling us we are right", 0L, corrected.correctionMs)
    }

    @Test
    fun `the bias shifts the whole answer and nothing else`() {
        val dts = samples(1.0, 1.0, 1.0)
        val without = BandConsensus.estimate(dts, Mode.FT8)!!
        val with = BandConsensus.estimate(dts, Mode.FT8, decoderDtBiasSec = 0.15)!!
        assertEquals(without.medianDtSec, with.medianDtSec, 1e-9)
        // The bias makes the correction SMALLER: the band is 150 ms less wrong than
        // the naive reading thinks. Sign matters - getting it backwards would double
        // the error instead of removing it.
        assertEquals(-150L, without.correctionMs - with.correctionMs)
        assertTrue(kotlin.math.abs(with.correctionMs) < kotlin.math.abs(without.correctionMs))
    }
}
