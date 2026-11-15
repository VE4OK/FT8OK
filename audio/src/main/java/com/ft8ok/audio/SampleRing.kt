package com.ft8ok.audio

/**
 * A continuous stream of audio with an absolute sample index and a known epoch, so
 * any 15 s slot can be cut out of it to the sample.
 *
 * **Why this exists instead of just clearing a buffer each slot.** The obvious
 * implementation starts a fresh buffer at every slot boundary and appends whatever
 * arrives. But audio arrives in chunks, so the buffer's first sample is wherever the
 * chunk after the boundary happened to land — alignment quantised to the chunk size.
 * At 100 ms chunks that injects up to 100 ms of error straight into DT, which is the
 * input to the band-consensus clock estimator (ADR 0005). DT would then be measuring
 * our own buffering rather than anyone's clock, and the measurement would look
 * perfectly healthy while being wrong.
 *
 * So: one continuous stream, an absolute sample count that never resets, the epoch
 * of sample zero established once from the audio timestamp, and slots sliced out by
 * index. Alignment is then independent of chunk size and scheduling jitter.
 *
 * Plain Kotlin, no Android types — the alignment arithmetic is testable on the JVM.
 *
 * **Threading:** one writer (the capture thread) and one reader (whoever decodes).
 * [written] is volatile, so a reader sees a consistent count, and the ring is sized
 * for several slots so the writer is nowhere near overwriting what a reader is
 * holding. [sliceByEpoch] refuses rather than returning torn audio if that is ever
 * untrue.
 */
class SampleRing(
    /** Samples per second of what is stored — after any decimation. */
    val sampleRate: Int,
    /** How much history to keep. Several slots, so a late decode still finds its audio. */
    val seconds: Int
) {

    private val buffer = FloatArray(sampleRate * seconds)

    /** Absolute count of samples ever written. Never resets. */
    @Volatile
    var written: Long = 0L
        private set

    /** Epoch ms of sample zero, or NaN until the stream has been anchored. */
    @Volatile
    var epochOfSampleZero: Double = Double.NaN
        private set

    val isAnchored: Boolean get() = !epochOfSampleZero.isNaN()

    /** The span the ring can hold, in ms. */
    val capacityMs: Long get() = seconds * 1000L

    /**
     * Fix the stream's position in time. Call once, as early as possible.
     *
     * @param epochMs epoch time of the sample at absolute index [atSampleIndex].
     */
    fun anchor(epochMs: Double, atSampleIndex: Long = 0L) {
        epochOfSampleZero = epochMs - atSampleIndex * 1000.0 / sampleRate
    }

    /**
     * Move the timeline by [ms]: every sample held is now [ms] later than it was.
     * For [TimelineGuard], when samples were lost and those since are later than
     * their count says.
     */
    fun shift(ms: Double) {
        if (isAnchored) epochOfSampleZero += ms
    }

    /** Forget everything, including the anchor. */
    fun reset() {
        written = 0L
        epochOfSampleZero = Double.NaN
    }

    /** Append [count] samples from [samples]. */
    fun append(samples: FloatArray, count: Int = samples.size) {
        var w = written
        for (i in 0 until count) {
            buffer[(w % buffer.size).toInt()] = samples[i]
            w++
        }
        written = w
    }

    /** Append one sample. */
    fun append(sample: Float) {
        buffer[(written % buffer.size).toInt()] = sample
        written += 1
    }

    /** Epoch ms of the newest sample held, or null before the stream is anchored. */
    fun newestEpochMs(): Double? =
        if (!isAnchored) null else epochOfSampleZero + written * 1000.0 / sampleRate

    /**
     * Cut out the audio beginning at [startEpochMs].
     *
     * Returns **as much as exists**, which is the normal case: decode passes fire
     * before the slot has finished, so a pass at 11.8 s gets 11.8 s of audio. Returns
     * null when the request cannot be served honestly — before the stream is
     * anchored, before the audio starts, when nothing of it has arrived yet, or when
     * the start has already been overwritten.
     *
     * @param startEpochMs the slot boundary, **in the same timebase the ring was
     *   anchored in** — the host's own clock. A caller holding a band-corrected time
     *   must convert first. Mixing the two is a silent and nasty bug: once
     *   the boundary was computed in corrected time and used directly to index audio
     *   anchored in system time, so a -1660 ms correction changed only *when* the
     *   decode fired, never *where* the slice began. DT never moved, and the log
     *   reported the correction as applied.
     */
    fun sliceByEpoch(startEpochMs: Long, durationMs: Long): FloatArray? {
        if (!isAnchored) return null

        val first = ((startEpochMs - epochOfSampleZero) * sampleRate / 1000.0).toLong()
        if (first < 0) return null

        val wanted = (durationMs * sampleRate / 1000L).toInt()
        val available = written - first
        if (available <= 0) return null
        // Has the start already been overwritten? Then any slice would begin
        // mid-stream with no way to tell, so refuse.
        if (available > buffer.size) return null

        val take = minOf(wanted.toLong(), available).toInt()
        val out = FloatArray(take)
        for (i in 0 until take) {
            out[i] = buffer[((first + i) % buffer.size).toInt()]
        }
        return out
    }
}
