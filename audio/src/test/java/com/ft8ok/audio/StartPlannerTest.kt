package com.ft8ok.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class StartPlannerTest {

    private val rate = 48_000
    private val ms = 1_000_000L

    @Test
    fun `before a timestamp, the assumed delay decides`() {
        // Target 500 ms after the first write, 100 ms assumed delay: 400 ms of silence.
        val left = StartPlanner.silenceFramesLeft(
            targetNanos = 500 * ms, sampleRate = rate, writtenFrames = 0,
            stampFrame = null, stampNanos = null,
            writeStartNanos = 0, assumedLatencyNanos = 100 * ms
        )
        assertEquals(400L * rate / 1000, left)
    }

    @Test
    fun `a timestamp overrides the assumption, whatever the start-up delay was`() {
        // The 17 m case: assumed ~100 ms, the track actually took 371 ms to start.
        // Frame 0 played at 371 ms; target 500 ms; so frame 129 ms worth is the start.
        val left = StartPlanner.silenceFramesLeft(
            targetNanos = 500 * ms, sampleRate = rate, writtenFrames = 4_800,
            stampFrame = 960, stampNanos = 391 * ms,
            writeStartNanos = 0, assumedLatencyNanos = 100 * ms
        )
        val targetFrame = 129L * rate / 1000
        assertEquals(targetFrame - 4_800, left)
    }

    @Test
    fun `queued frames are what is written beyond the frame playing now`() {
        // Frame 48 000 played at 1 000 ms; at 1 050 ms frame 50 400 is playing.
        val queued = StartPlanner.queuedFrames(
            writtenFrames = 60_000, stampFrame = 48_000, stampNanos = 1_000 * ms,
            nowNanos = 1_050 * ms, sampleRate = rate
        )
        assertEquals(9_600L, queued)
        // A starved track has nothing queued, not a negative amount.
        assertEquals(0L, StartPlanner.queuedFrames(48_000, 48_000, 1_000 * ms, 1_050 * ms, rate))
    }

    @Test
    fun `a slow start lands on the same moment as a quick one`() {
        fun signalLeavesAtMs(startupMs: Long): Long {
            // Frame 0 leaves at startupMs; we ask once 2 400 frames are queued.
            val written = 2_400L
            val left = StartPlanner.silenceFramesLeft(
                targetNanos = 600 * ms, sampleRate = rate, writtenFrames = written,
                stampFrame = 0, stampNanos = startupMs * ms,
                writeStartNanos = 0, assumedLatencyNanos = 100 * ms
            )
            return startupMs + (written + left) * 1000 / rate
        }
        assertEquals(600L, signalLeavesAtMs(99))
        assertEquals(600L, signalLeavesAtMs(410))
    }

    @Test
    fun `late means start now, never a negative pad`() {
        val left = StartPlanner.silenceFramesLeft(
            targetNanos = 500 * ms, sampleRate = rate, writtenFrames = 30_000,
            stampFrame = 0, stampNanos = 0,
            writeStartNanos = 0, assumedLatencyNanos = 100 * ms
        )
        assertEquals(0L, left)
    }

    private fun skipMs(lateMs: Long, capMs: Long): Long {
        // The track stamped frame 0 at time 0, so frame N plays at N/rate; the
        // signal lands at frame 'written', [lateMs] after the 500 ms target.
        val written = (500 + lateMs) * rate / 1000
        val frames = StartPlanner.lateSkipFrames(
            targetNanos = 500 * ms, sampleRate = rate, writtenFrames = written,
            stampFrame = 0, stampNanos = 0,
            writeStartNanos = 0, assumedLatencyNanos = 100 * ms,
            maxShiftFrames = capMs * rate / 1000
        )
        return frames * 1000 / rate
    }

    @Test
    fun `on time or a little late, nothing is skipped`() {
        assertEquals(0L, skipMs(lateMs = -200, capMs = 1_500))
        assertEquals(0L, skipMs(lateMs = 0, capMs = 1_500))
        assertEquals("late, but the whole message still goes", 0L, skipMs(lateMs = 1_500, capMs = 1_500))
    }

    @Test
    fun `later than the cap, only the excess is skipped, so the signal sits at the cap`() {
        // One call to F5XAA left the phone 2.47 s late and was sent whole.
        assertEquals(970L, skipMs(lateMs = 2_470, capMs = 1_500))
        assertEquals(3_000L, skipMs(lateMs = 4_500, capMs = 1_500))
    }

    @Test
    fun `a cap of zero is WSJT-X's own late start, everything missed is skipped`() {
        assertEquals(2_470L, skipMs(lateMs = 2_470, capMs = 0))
    }
}
