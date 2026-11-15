package com.ft8ok.time

import com.ft8ok.model.Mode
import kotlin.math.abs

/**
 * What the band says the time is (ADR 0005).
 *
 * Every decoded message carries a DT: how far into the slot that station's
 * transmission appeared to start. With slot-aligned capture, a single DT is the sum
 * of **our** clock error, **their** clock error, and a few ms of audio latency.
 * Their errors are independent of each other and ours is common to all of them, so
 * the median across a slot's stations largely cancels theirs and leaves ours. That
 * is the whole idea: sync to the band, never to a server — the app has no network
 * permission and may be on a hillside with no signal.
 *
 * Pure and stateless: feed it one slot's measurements, it returns what that slot
 * implies. Deciding whether to act on it belongs to [TimeEngine].
 */
object BandConsensus {

    /**
     * How many stations a slot needs before its verdict is worth anything.
     *
     * The median of two stations is their mean, which is not a consensus — one
     * badly-synchronised station would drag it by half its error. A chosen default; on air
     * a normal band gave 10 to 19 decodes a slot, so this is not a demanding gate.
     */
    const val MIN_STATIONS = 3

    /**
     * How far from the median a measurement must be before it is thrown away.
     *
     * Stations genuinely running a second or more out exist and decode fine; they
     * must not be allowed to pull the consensus. Generous enough to keep ordinary
     * spread, tight enough to drop the real outliers. A chosen default.
     */
    const val OUTLIER_SEC = 0.5

    /**
     * One station's contribution.
     *
     * @param weight confidence in this measurement, higher being better. The
     *   decoder's sync-correlation score is what we have; **it is not an SNR**
     * and nothing here treats it as one. It is used only to rank
     *   one measurement against another within the same slot, which is a question
     *   of how firmly each was decoded, so a relative score answers it honestly.
     */
    data class DtSample(val dtSeconds: Double, val weight: Double)

    /**
     * @param medianDtSec the agreed DT after outliers were dropped.
     * @param residualSec how far that is from where a correct clock would put it.
     * @param correctionMs how much further to correct, signed. **A delta, not an
     *   absolute offset** — the band reports where we are *relative to our current
     *   alignment*, so corrections accumulate. Treating one as absolute is what made
     *   a second sync undo the first once: once converged the band says
     *   "+20 ms more", and storing +20 ms as the offset threw away the -1660 ms that
     *   had got us there.
     */
    data class Estimate(
        val medianDtSec: Double,
        val residualSec: Double,
        val correctionMs: Long,
        val stationsUsed: Int,
        val stationsRejected: Int
    )

    /**
     * What one slot's decodes imply about our clock, or null if this slot cannot
     * support a verdict.
     */
    fun estimate(
        samples: List<DtSample>,
        mode: Mode,
        /**
         * How much higher the decoder reports DT than the true offset.
         *
         * Injected rather than assumed zero, and kept separate from
         * [Mode.nominalTxStartMs] because they are different things: the nominal
         * start is the protocol's, the bias is this particular decoder's
         * (`Ft8Decoder.DT_BIAS_SEC`). Folding them together would mean a decoder
         * change silently moved the clock.
         */
        decoderDtBiasSec: Double = 0.0
    ): Estimate? {
        val usable = samples.filter { it.weight > 0.0 && it.dtSeconds.isFinite() }
        if (usable.size < MIN_STATIONS) return null

        val rough = weightedMedian(usable) ?: return null
        val kept = usable.filter { abs(it.dtSeconds - rough) <= OUTLIER_SEC }
        if (kept.size < MIN_STATIONS) return null

        val median = weightedMedian(kept) ?: return null
        // What a correctly-timed station would read on THIS decoder.
        val expected = mode.nominalTxStartMs / 1000.0 + decoderDtBiasSec
        val residualSec = median - expected
        return Estimate(
            medianDtSec = median,
            residualSec = residualSec,
            // Negative: if signals arrive late, our clock is early and must go back.
            correctionMs = Math.round(-residualSec * 1000.0),
            stationsUsed = kept.size,
            stationsRejected = usable.size - kept.size
        )
    }

    /**
     * The weighted median: the value at which half the total weight lies either
     * side.
     *
     * A median rather than a mean because one station a second out should move the
     * answer by nothing at all, not by its error divided by the station count.
     */
    private fun weightedMedian(samples: List<DtSample>): Double? {
        if (samples.isEmpty()) return null
        val sorted = samples.sortedBy { it.dtSeconds }
        val total = sorted.sumOf { it.weight }
        if (total <= 0.0) return null

        var running = 0.0
        for (s in sorted) {
            running += s.weight
            if (running >= total / 2.0) return s.dtSeconds
        }
        return sorted.last().dtSeconds
    }
}
