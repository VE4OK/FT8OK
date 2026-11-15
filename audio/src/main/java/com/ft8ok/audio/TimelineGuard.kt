package com.ft8ok.audio

/**
 * Notices when captured audio stops matching the passage of time, and says by how
 * much.
 *
 * The capture timeline is anchored once, then advanced by counting samples - which
 * is exact only while no sample is ever lost. If the capture thread misses its turn
 * for longer than the input buffer holds, samples vanish, every later sample is
 * filed earlier than it happened, and each slot is cut from the wrong audio. Small
 * slips were being absorbed by the band clock (moving our TX timing with them); a
 * large one left nothing decodable, for good, until Stop/Start re-anchored it - which
 * is what happened on air. **The slip is inferred from that
 * cure, not observed directly**; this class is how the next one will be observed.
 *
 * The check: after a read that had to wait for audio (so we are caught up, not
 * draining a backlog), `monotonic time - duration of samples held` is constant
 * apart from scheduling jitter. Its median over recent reads is compared with the
 * median taken just after start; a difference beyond [thresholdMs] is samples lost
 * (positive) or gained. The caller moves the timeline by that much.
 *
 * Pure: numbers in, a correction out.
 */
class TimelineGuard(
    private val thresholdMs: Double = SLIP_THRESHOLD_MS,
    private val window: Int = WINDOW
) {

    private val recent = ArrayDeque<Double>()
    private var baseline: Double? = null

    /**
     * One read.
     *
     * @param monoMs a monotonic clock, ms.
     * @param heldMs total duration of the samples captured so far.
     * @param waited true when the read had to wait for audio, i.e. we are caught up.
     * @return how far the timeline has slipped, ms (positive: samples were lost, the
     *   audio since is later than counted), or 0 when within tolerance.
     */
    fun onRead(monoMs: Double, heldMs: Double, waited: Boolean): Double {
        if (!waited) return 0.0
        recent.addLast(monoMs - heldMs)
        while (recent.size > window) recent.removeFirst()
        if (recent.size < window) return 0.0
        val median = recent.sorted()[recent.size / 2]
        val base = baseline ?: run {
            baseline = median
            return 0.0
        }
        val slip = median - base
        if (kotlin.math.abs(slip) < thresholdMs) return 0.0
        // Corrected: measure afresh from here.
        baseline = base + slip
        recent.clear()
        return slip
    }

    fun reset() {
        recent.clear()
        baseline = null
    }

    companion object {
        /**
         * Chosen default: 40 ms. Above read-wakeup jitter, well below what moves a decode,
         * and small enough that slow drift between the codec's crystal and the phone's
         * clock is corrected in steps the band clock barely notices.
         */
        const val SLIP_THRESHOLD_MS = 40.0

        /** Reads in the median: one second at ten reads a second. */
        const val WINDOW = 10
    }
}
