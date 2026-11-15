package com.ft8ok.audio

/**
 * How much more silence to write before the signal, so the signal's first sample
 * leaves the phone at a target moment.
 *
 * The padding used to be sized once, up front, from the output delay measured on
 * the *previous* transmission. A fresh output track does not start in the same time
 * twice: on air (17 m, three transmissions in a row) it took 371, 99 and
 * 410 ms, so each pad was wrong by the change - +177, -269 and +314 ms against the
 * target, which a KiwiSDR saw as DT +0.04, -0.68, +0.52 on the same message.
 *
 * So the silence is written a little at a time and the question asked again after
 * each piece. Once the track reports a timestamp - frame F played at time T - the
 * answer is exact whatever the start-up delay was. Until then the old estimate
 * stands in, and it only decides how much silence to queue *so far*.
 *
 * Pure: numbers in, a frame count out. Nanoseconds on the `System.nanoTime` base,
 * which is the base `AudioTrack.getTimestamp` reports in.
 */
object StartPlanner {

    /**
     * Frames of silence still to write before the signal, or 0 when the signal is
     * due now or already late. Lateness is dealt with by [lateSkipFrames].
     *
     * @param writtenFrames frames written to the track so far, all silence.
     * @param stampFrame a frame the track has reported playing, or null before it has.
     * @param stampNanos when [stampFrame] played.
     * @param writeStartNanos when the first frame was written, for the estimate.
     * @param assumedLatencyNanos the output delay to assume until a timestamp arrives.
     */
    fun silenceFramesLeft(
        targetNanos: Long,
        sampleRate: Int,
        writtenFrames: Long,
        stampFrame: Long?,
        stampNanos: Long?,
        writeStartNanos: Long,
        assumedLatencyNanos: Long
    ): Long = (targetFrame(targetNanos, sampleRate, stampFrame, stampNanos, writeStartNanos, assumedLatencyNanos) - writtenFrames)
        .coerceAtLeast(0)

    /**
     * How many of the signal's first frames to leave out so that it is never later
     * than [maxShiftFrames] behind its target, or 0 when it is on time or late by
     * less than that.
     *
     * A late start used to send the whole message late. Receivers search for a
     * signal only so far from the nominal start - WSJT-X ±2.5 s (`sync8.f90`) - and
     * one 2.7 s late went unanswered once. WSJT-X itself skips everything
     * missed and stays on time, which loses symbols weak signals need. Measured on
     * the host decoder (`ft8_lib`, 60 trials a point), sending the start late up to
     * 1.5 s and skipping only what is later than that decoded best at every lateness
     * and level: at -17 dB and 3 s late, 55/60, against 13 skipping it all and 0
     * shifting it all.
     *
     * Same arguments as [silenceFramesLeft], called once the silence is written:
     * [writtenFrames] is then where the signal's first frame lands.
     */
    fun lateSkipFrames(
        targetNanos: Long,
        sampleRate: Int,
        writtenFrames: Long,
        stampFrame: Long?,
        stampNanos: Long?,
        writeStartNanos: Long,
        assumedLatencyNanos: Long,
        maxShiftFrames: Long
    ): Long {
        val late = writtenFrames - targetFrame(targetNanos, sampleRate, stampFrame, stampNanos, writeStartNanos, assumedLatencyNanos)
        return (late - maxShiftFrames.coerceAtLeast(0)).coerceAtLeast(0)
    }

    /**
     * Frames written but not yet played at [nowNanos]: what a warm stream already
     * holds when a transmission takes it. None of it can be taken back, so a target
     * nearer than this is missed by the difference whatever the planner does.
     */
    fun queuedFrames(
        writtenFrames: Long,
        stampFrame: Long,
        stampNanos: Long,
        nowNanos: Long,
        sampleRate: Int
    ): Long = (writtenFrames - (stampFrame + (nowNanos - stampNanos) * sampleRate / 1_000_000_000L))
        .coerceAtLeast(0)

    /** The frame that will be playing at the target moment. */
    private fun targetFrame(
        targetNanos: Long,
        sampleRate: Int,
        stampFrame: Long?,
        stampNanos: Long?,
        writeStartNanos: Long,
        assumedLatencyNanos: Long
    ): Long = if (stampFrame != null && stampNanos != null) {
        stampFrame + (targetNanos - stampNanos) * sampleRate / 1_000_000_000L
    } else {
        (targetNanos - writeStartNanos - assumedLatencyNanos) * sampleRate / 1_000_000_000L
    }
}
