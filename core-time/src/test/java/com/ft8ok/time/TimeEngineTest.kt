package com.ft8ok.time

import com.ft8ok.model.Mode
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The band-consensus loop against a scripted clock — the whole reason [TimeEngine]
 * takes its clocks as functions. None of this needs a phone, a radio or a band.
 */
class TimeEngineTest {

    /** A clock the test drives by hand. Wall and monotonic move independently, which
     *  is the only way to express a system clock being stepped. */
    private class FakeClock(var wall: Long = 1_760_000_000_000L, var mono: Long = 0L) {
        fun advance(ms: Long) { wall += ms; mono += ms }
    }

    private fun engine(clock: FakeClock, inputLatencyMs: Long = 0) =
        TimeEngine(Mode.FT8, { clock.wall }, { clock.mono }, inputLatencyMs)

    private fun band(dt: Double, n: Int = 5) =
        List(n) { BandConsensus.DtSample(dt, 1.0) }

    @Test
    fun `before anything is heard the clock is the phone's own`() {
        val clock = FakeClock()
        val engine = engine(clock)
        assertEquals(TimeEngine.Sync.NONE, engine.sync)
        assertEquals(clock.wall, engine.correctedNowMs())
        assertNull(engine.syncAgeMs())
    }

    // --- the grid search ----------------------------------------

    @Test
    fun `a search with one station gives a tentative lock, and keeps searching`() {
        val clock = FakeClock()
        val engine = engine(clock)
        // The band's boundary sits 5 s past a boundary of the phone's own grid.
        val grid = Mode.FT8.slotStartMs(clock.wall) + 5_000.0
        assertEquals(-5_000L, engine.acceptGrid(grid, 1))
        assertEquals(TimeEngine.Sync.TENTATIVE, engine.sync)
        assertTrue(engine.needsGridSearch)
        assertEquals(0L, Mode.FT8.positionInSlotMs(grid.toLong() + engine.offsetMs))
    }

    @Test
    fun `three stations in a search are a consensus, and the search stops`() {
        val clock = FakeClock()
        val engine = engine(clock)
        engine.acceptGrid(Mode.FT8.slotStartMs(clock.wall) + 5_000.0, 3)
        assertEquals(TimeEngine.Sync.SYNCED, engine.sync)
        assertTrue(!engine.needsGridSearch)
    }

    @Test
    fun `only a bigger group replaces a tentative lock`() {
        val clock = FakeClock()
        val engine = engine(clock)
        val base = Mode.FT8.slotStartMs(clock.wall).toDouble()
        engine.acceptGrid(base + 5_000.0, 1)
        val first = engine.offsetMs
        assertNull("another lone station cannot be told from the first", engine.acceptGrid(base + 2_000.0, 1))
        assertEquals(first, engine.offsetMs)
        assertNotNull(engine.acceptGrid(base + 2_000.0, 2))
        assertEquals(0L, Mode.FT8.positionInSlotMs((base + 2_000.0).toLong() + engine.offsetMs))
    }

    @Test
    fun `a search never moves the clock by more than half a slot`() {
        val clock = FakeClock()
        val engine = engine(clock)
        val base = Mode.FT8.slotStartMs(clock.wall).toDouble()
        assertEquals(4_000L, engine.acceptGrid(base + 11_000.0, 1))
    }

    @Test
    fun `a search cannot touch a clock that has a consensus`() {
        val clock = FakeClock()
        val engine = engine(clock)
        engine.observe(band(0.5))
        assertNull(engine.acceptGrid(clock.wall + 5_000.0, 9))
        assertEquals(0L, engine.offsetMs)
    }

    @Test
    fun `a slot's consensus takes over from a tentative lock, whole`() {
        val clock = FakeClock()
        val engine = engine(clock)
        engine.acceptGrid(Mode.FT8.slotStartMs(clock.wall) + 5_000.0, 1)
        val outcome = engine.observe(band(1.3))   // that lone station was 0.8 s out
        assertEquals(-800L, outcome.appliedMs)
        assertEquals(TimeEngine.Sync.SYNCED, engine.sync)
    }

    @Test
    fun `a clock step leaves a tentative lock tentative`() {
        val clock = FakeClock()
        val engine = engine(clock)
        engine.acceptGrid(Mode.FT8.slotStartMs(clock.wall) + 5_000.0, 1)
        clock.wall += 60_000
        engine.tick()
        assertEquals(TimeEngine.Sync.TENTATIVE, engine.sync)
    }

    @Test
    fun `the grid implied by a decode allows for the nominal start and the bias`() {
        val clock = FakeClock()
        val engine = TimeEngine(Mode.FT8, { clock.wall }, { clock.mono }, decoderDtBiasSec = 0.15)
        assertEquals(10_000.0, engine.gridHostMs(10_000L, 0.65), 0.001)
    }

    @Test
    fun `the first estimate is applied whole, because a cold start can be seconds out`() {
        val clock = FakeClock()
        val engine = engine(clock)
        val outcome = engine.observe(band(2.16))   // 1.66 s fast, as measured on air
        assertEquals(-1660L, outcome.appliedMs)
        assertEquals(-1660L, engine.offsetMs)
        assertTrue(!outcome.rateLimited)
        assertEquals(TimeEngine.Sync.SYNCED, engine.sync)
        assertEquals(clock.wall - 1660, engine.correctedNowMs())
    }

    @Test
    fun `corrections accumulate - the bug that made a second sync undo the first`() {
        val clock = FakeClock()
        val engine = engine(clock)
        engine.observe(band(2.16))                 // -1660 ms
        assertEquals(-1660L, engine.offsetMs)

        // Now converged, the band reports a small residual. Storing it as the offset
        // would throw away the 1660 ms that got us here.
        clock.advance(15_000)
        engine.observe(band(0.52))                 // -20 ms more
        assertEquals(-1680L, engine.offsetMs)
    }

    @Test
    fun `after acquisition one bad slot cannot throw the sync away`() {
        val clock = FakeClock()
        val engine = engine(clock)
        engine.observe(band(0.5))                  // acquire, no correction needed
        assertEquals(0L, engine.offsetMs)

        clock.advance(15_000)
        val outcome = engine.observe(band(3.5))    // a slot claiming we are 3 s out
        assertTrue(outcome.rateLimited)
        assertEquals(-engine.maxSlewMs, outcome.appliedMs)
        assertEquals(-engine.maxSlewMs, engine.offsetMs)
    }

    @Test
    fun `real drift still gets there, a slot at a time, and then stays put`() {
        val clock = FakeClock()
        val engine = engine(clock)
        engine.observe(band(0.5))                  // acquire on a band we agree with
        assertEquals(0L, engine.offsetMs)

        // Now the phone's clock is genuinely 500 ms fast. Closed loop: what the band
        // appears to say depends on the correction we have already applied, which is
        // the only honest way to test a controller - fed a fixed DT it would correct
        // forever and the test would pass for the wrong reason.
        val trueErrorMs = 500L
        fun observedDt() = Mode.FT8.nominalTxStartMs / 1000.0 + (trueErrorMs + engine.offsetMs) / 1000.0

        var steps = 0
        while (engine.offsetMs != -trueErrorMs && steps < 20) {
            clock.advance(15_000)
            val outcome = engine.observe(band(observedDt()))
            assertTrue("no step may exceed the slew cap", abs(outcome.appliedMs) <= engine.maxSlewMs)
            steps++
        }
        assertEquals(-500L, engine.offsetMs)
        assertTrue("500 ms at 200 ms a slot should take about three slots, took $steps", steps <= 5)

        // Converged: the band now agrees, and nothing further moves. A controller
        // that keeps correcting at equilibrium would hunt around the right answer.
        clock.advance(15_000)
        val settled = engine.observe(band(observedDt()))
        assertEquals(0L, settled.appliedMs)
        assertEquals(-500L, engine.offsetMs)
    }

    @Test
    fun `a slot with too few stations changes nothing`() {
        val clock = FakeClock()
        val engine = engine(clock)
        engine.observe(band(2.16))
        val before = engine.offsetMs
        clock.advance(15_000)
        val outcome = engine.observe(band(0.9, n = 2))
        assertNull(outcome.estimate)
        assertEquals(0L, outcome.appliedMs)
        assertEquals(before, engine.offsetMs)
    }

    @Test
    fun `a system clock step does not move corrected time`() {
        val clock = FakeClock()
        val engine = engine(clock)
        engine.observe(band(2.16))
        val correctedBefore = engine.correctedNowMs()

        // Android decides to step the wall clock forward 5 s. Real time did not move.
        clock.wall += 5_000
        val step = engine.tick()
        assertEquals(5_000L, step)
        assertEquals(correctedBefore, engine.correctedNowMs())
        // Our own sync is intact but now worth re-checking against the band.
        assertEquals(TimeEngine.Sync.HOLDING, engine.sync)
    }

    @Test
    fun `ordinary ticking is not mistaken for a step`() {
        val clock = FakeClock()
        val engine = engine(clock)
        engine.observe(band(0.5))
        repeat(50) {
            clock.advance(100)
            assertNull(engine.tick())
        }
        assertEquals(TimeEngine.Sync.SYNCED, engine.refresh())
    }

    @Test
    fun `sync goes to holding when the band falls quiet, and keeps the offset`() {
        val clock = FakeClock()
        val engine = engine(clock)
        engine.observe(band(2.16))
        assertEquals(TimeEngine.Sync.SYNCED, engine.sync)

        clock.advance(engine.holdoverAfterMs + 1)
        assertEquals(TimeEngine.Sync.HOLDING, engine.refresh())
        // Holding is not losing: a crystal drifts milliseconds per hour, so the old
        // offset is still far better than the phone's raw clock.
        assertEquals(-1660L, engine.offsetMs)
        assertEquals(clock.wall - 1660, engine.correctedNowMs())
    }

    @Test
    fun `sync age is measured on the monotonic clock, so a clock step cannot age it`() {
        val clock = FakeClock()
        val engine = engine(clock)
        engine.observe(band(0.5))
        clock.mono += 30_000
        clock.wall += 30_000
        assertEquals(30_000L, engine.syncAgeMs())

        clock.wall += 3_600_000          // an hour-long wall-clock step
        engine.tick()
        assertEquals(30_000L, engine.syncAgeMs())
    }

    @Test
    fun `input latency is a separate term, applied once and only where it belongs`() {
        val clock = FakeClock()
        val plain = engine(clock)
        val delayed = engine(FakeClock(), inputLatencyMs = 6)
        val a = plain.observe(band(0.5))
        val b = delayed.observe(band(0.5))
        assertNotNull(a.estimate)
        // Same band, same measurement: the only difference is the calibrated term.
        assertEquals(0L, a.appliedMs)
        assertEquals(6L, b.appliedMs)
    }
}
