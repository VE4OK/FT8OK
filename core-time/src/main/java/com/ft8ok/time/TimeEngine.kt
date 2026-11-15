package com.ft8ok.time

import com.ft8ok.model.Mode
import kotlin.math.abs
import kotlin.math.sign

/**
 * The app's idea of what time it is (ARCHITECTURE.md, ADR 0005).
 *
 * The phone's own clock is not good enough and cannot be trusted to stay put: the
 * test phone was **1.66 s fast with network time enabled**, which is most of FT8's
 * tolerance spent before anything else goes wrong. So the band decides, through
 * [BandConsensus], and this class owns the resulting correction.
 *
 * Two deliberate properties:
 *
 *  - **Corrections accumulate and are rate-limited.** The band reports a residual
 *    relative to our current alignment, never an absolute offset. The first
 *    estimate is applied whole, because a cold start can be seconds out and waiting
 *    would mean decoding nothing; afterwards corrections are capped per slot, so one
 *    bad slot cannot throw a working sync away.
 *  - **A system-clock step does not move us.** If Android's wall clock jumps (a
 *    network time update, the user editing it), the jump is detected against the
 *    monotonic clock and removed from the offset, so corrected time stays
 *    continuous. Our band-derived time is the trusted one.
 *
 * Both clocks are injected, so all of this is testable on the JVM against a
 * scripted clock. No Android types.
 *
 * **This is the listen clock only.** The separate TX clock of ADR 0005 — snapped to
 * the partner's measured offset during a QSO — is [TxClock].
 * The two must never be merged into one number.
 */
class TimeEngine(
    private val mode: Mode,
    /** The host's wall clock, epoch ms. */
    private val wallClockMs: () -> Long,
    /** A monotonic clock, ms, unaffected by wall-clock changes. */
    private val monotonicMs: () -> Long,
    /**
     * Audio input latency, kept **separate from clock error on purpose**
     * (ARCHITECTURE.md). The two are physically different things and mixing them
     * produces a number that is wrong in both roles. Default 0: capture is anchored
     * with `AudioRecord.getTimestamp`, which already reports when a frame was
     * captured, and the residual measured on hardware was about 6 ms. Set it only
     * from a calibration that measured it.
     */
    private val inputLatencyMs: Long = 0,
    /**
     * The decoder's own DT bias, in seconds - `Ft8Decoder.DT_BIAS_SEC`.
     *
     * Passed in rather than imported, because `core-time` must not depend on a
     * particular decoder (ARCHITECTURE: core-* stays swappable). Zero means "a
     * correctly-timed station reads exactly the nominal start", which is true of no
     * decoder we have actually measured.
     */
    private val decoderDtBiasSec: Double = 0.0
) {

    enum class Sync {
        /** Never synchronised. The phone's own clock is all we have. */
        NONE,

        /**
         * The grid comes from fewer stations than [BandConsensus.MIN_STATIONS]. Good
         * enough to decode, but one station can be seconds out, so [GridSearch] keeps
         * running and a bigger group replaces it.
         */
        TENTATIVE,

        /** Synchronised, with a recent estimate from the band. */
        SYNCED,

        /**
         * Synchronised, but nothing fresh for a while — a quiet band, or we have
         * been transmitting. The offset is still used: a phone crystal drifts
         * milliseconds per hour, so an old sync is far better than none.
         */
        HOLDING
    }

    /**
     * The most a correction may move us after the first one, per estimate.
     *
     * Rate-limiting rather than jumping keeps one wrong slot from destroying a
     * working sync; genuine drift is far slower than this, so the cap never fights
     * reality. A chosen default, to be tuned on air.
     */
    val maxSlewMs: Long = 200

    /** How long an estimate stays "fresh" before the state becomes [Sync.HOLDING]. */
    val holdoverAfterMs: Long = 120_000

    /**
     * A wall-clock move this much larger than the monotonic clock's is a step, not
     * drift. Well above any plausible scheduling jitter between the two readings.
     */
    val stepDetectMs: Long = 1_000

    @Volatile
    var offsetMs: Long = 0
        private set

    @Volatile
    var sync: Sync = Sync.NONE
        private set

    /** The last estimate accepted from the band, for display. */
    @Volatile
    var lastEstimate: BandConsensus.Estimate? = null
        private set

    private var lastAcceptedMono: Long? = null
    private var tentativeStations = 0
    private var lastWall: Long = wallClockMs()
    private var lastMono: Long = monotonicMs()

    /** Our best idea of true time. Everything slot-aligned uses this, nothing else. */
    fun correctedNowMs(): Long = wallClockMs() + offsetMs

    /** How stale the sync is, on the monotonic clock so a clock step cannot age it. */
    fun syncAgeMs(): Long? = lastAcceptedMono?.let { monotonicMs() - it }

    /**
     * Watch for the wall clock being stepped underneath us. Cheap; call it often
     * (the capture loop does, once per audio chunk).
     *
     * @return the size of the step absorbed, or null if nothing happened.
     */
    @Synchronized
    fun tick(): Long? {
        val wall = wallClockMs()
        val mono = monotonicMs()
        val drift = (wall - lastWall) - (mono - lastMono)
        lastWall = wall
        lastMono = mono

        if (abs(drift) < stepDetectMs) return null

        // The wall clock moved by `drift` more than real time did. Our offset was
        // measured against the old wall clock, so remove the step from it and
        // corrected time does not flinch.
        offsetMs -= drift
        if (sync == Sync.SYNCED) sync = Sync.HOLDING
        return drift
    }

    /** What one slot's decodes did to the clock. */
    data class Outcome(
        val estimate: BandConsensus.Estimate?,
        /** How much of the correction was actually applied. */
        val appliedMs: Long,
        /** True when the correction was capped by [maxSlewMs]. */
        val rateLimited: Boolean,
        val offsetMsAfter: Long,
        val sync: Sync
    )

    /**
     * Offer one slot's DT measurements to the clock.
     *
     * Only slots we actually listened through may be offered. A slot we transmitted
     * in tells us nothing, and a stale one tells us something that was true a while
     * ago, which is worse than nothing for a loop that accumulates.
     */
    @Synchronized
    fun observe(samples: List<BandConsensus.DtSample>): Outcome {
        refreshSyncState()

        val estimate = BandConsensus.estimate(samples, mode, decoderDtBiasSec)
            ?: return Outcome(null, 0, false, offsetMs, sync)

        // The input latency is a separate physical term, applied here and nowhere
        // else. Audio read late makes signals look late, so the clock error is the
        // residual minus that latency.
        val wanted = estimate.correctionMs + inputLatencyMs

        val applied: Long
        val limited: Boolean
        if (needsGridSearch) {
            // Cold start: apply it whole. The phone can be seconds out, and slewing
            // in at 200 ms a slot would mean minutes of decoding nothing. A tentative
            // lock counts as cold: this is the first consensus it has had.
            applied = wanted
            limited = false
        } else {
            applied = if (abs(wanted) > maxSlewMs) maxSlewMs * wanted.sign else wanted
            limited = abs(wanted) > maxSlewMs
        }

        offsetMs += applied
        lastEstimate = estimate
        lastAcceptedMono = monotonicMs()
        sync = Sync.SYNCED
        tentativeStations = 0
        return Outcome(estimate, applied, limited, offsetMs, sync)
    }

    /** True until a consensus exists: [GridSearch] runs only while this holds. */
    val needsGridSearch: Boolean get() = sync == Sync.NONE || sync == Sync.TENTATIVE

    /** The slot boundary, host time, implied by a message [dtSeconds] into a window. */
    fun gridHostMs(windowStartHostMs: Long, dtSeconds: Double): Double =
        windowStartHostMs + (dtSeconds - decoderDtBiasSec) * 1000.0 - mode.nominalTxStartMs

    /**
     * Take the grid from [GridSearch], applied whole like a cold start.
     *
     * [BandConsensus.MIN_STATIONS] or more is a consensus. Fewer is [Sync.TENTATIVE],
     * and only a bigger group may replace it: two lone stations that disagree cannot be
     * told apart, so the first stands. The band gives the phase only, so the shift is
     * never over half a slot, and a clock wrong by whole slots stays wrong by them.
     *
     * @return the correction applied, or null if nothing changed.
     */
    @Synchronized
    fun acceptGrid(gridHostMs: Double, stations: Int): Long? {
        if (!needsGridSearch) return null
        val consensus = stations >= BandConsensus.MIN_STATIONS
        if (!consensus && sync == Sync.TENTATIVE && stations <= tentativeStations) return null

        val error = GridSearch.wrapToHalfSlot(gridHostMs + offsetMs, mode.slotMs)
        val applied = Math.round(-error) + inputLatencyMs
        offsetMs += applied
        lastAcceptedMono = monotonicMs()
        sync = if (consensus) Sync.SYNCED else Sync.TENTATIVE
        tentativeStations = if (consensus) 0 else stations
        return applied
    }

    private fun refreshSyncState() {
        val age = syncAgeMs() ?: return
        if (sync == Sync.SYNCED && age > holdoverAfterMs) sync = Sync.HOLDING
    }

    /** Recompute the freshness of the sync without offering anything new. */
    @Synchronized
    fun refresh(): Sync {
        refreshSyncState()
        return sync
    }
}
