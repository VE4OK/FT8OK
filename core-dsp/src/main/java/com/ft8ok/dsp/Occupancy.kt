package com.ft8ok.dsp

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The occupancy map behind the strip.
 *
 * The strip's job is **not** to look like a waterfall. It is to answer one
 * question: where can we transmit without sitting on top of someone? That makes
 * two things essential, and both are easy to get wrong:
 *
 *  1. **Raw energy counts, not just decoded signals.** A station too weak for us
 *     to decode still collides with our transmission, and is probably perfectly
 *     readable to the person they are working. Decoded frequencies alone would
 *     declare busy spectrum empty.
 *  2. **Parity matters.** FT8 stations alternate even and odd 15 s slots, so the
 *     spectrum relevant to *our* TX slot is what was heard in the previous slot
 *     of the **same parity** - 30 s ago, not 15 s ago. Averaging both parities
 *     together would blur the two populations into one and hide the gaps.
 *
 * Pure Kotlin with no Android types, so it can be unit-tested on the JVM against
 * synthetic spectra.
 */
object Occupancy {

    /**
     * The band we **monitor and display**. Deliberately wider than the band we
     * transmit in: a station outside the TX range still deserves to be seen, and
     * one just inside the edge can still be clipped by our guard band.
     */
    const val BAND_LOW_HZ = 0
    const val BAND_HIGH_HZ = 3100

    /**
     * Where the noise floor is measured, for the strip and for the reports we send.
     * Fixed and well inside the radio's
     * passband (an IC-7300 receives 3.55 kHz wide), and independent of the
     * displayed band: the display reaches 0 Hz, where the radio's filter has rolled
     * off, and those near-empty bins would drag the floor down and make every bar
     * and every report we send look stronger than it is. 2500 Hz wide, the same as
     * the bandwidth reports are quoted in.
     */
    const val FLOOR_LOW_HZ = 500
    const val FLOOR_HIGH_HZ = 3000

    /**
     * The band we transmit in **when the radio has not told us its own** — the
     * IC-7300's default SSB-D transmit passband, 300–2700 Hz.
     *
     * The real limit is the radio's: in USB-D
     * the IC-7300 passes only audio inside its SSB-D TBW setting to the transmitter
     * (MENU › SET › Tone Control/TBW › TX › SSB-D; read over CI-V as `1A 05 01 96`),
     * and outside it keys with no power - two `NO RF` transmissions at 2838 Hz on a
     * radio set to 300–2700. So the app reads that setting and uses it ([TxBand]);
     * these are only the fallback until it answers, or if its firmware cannot. The
     * radio's edges are 100/200/300/500 and 2500/2700/2800/2900 Hz.
     *
     * Earlier versions used 400–2500, then 300–3000. The reasoning below about the empty top of the passband still holds.
     *
     * This matters more than it looks, because the widest-gap heuristic has a
     * perverse incentive without it. On air every even slot picked
     * 2626 Hz: genuinely the widest gap, and empty **because operators avoid it**.
     * Above roughly 2500 Hz a signal starts falling outside other stations'
     * receive passbands, so the quietest spectrum is quiet precisely because it is
     * unusable. A picker optimising for width alone walks straight into it.
     */
    const val TX_LOW_HZ = 300
    const val TX_HIGH_HZ = 2700

    /**
     * The audio range our whole signal must stay inside when we transmit: the radio's
     * SSB-D transmit passband once it has told us, [DEFAULT] until then.
     */
    data class TxBand(
        val lowHz: Int,
        val highHz: Int,
        /**
         * One signal's width in this app's mode (`Mode.signalWidthHz`): ours, and every decoded station's, since a band carries one mode.
         * FT8 50, FT4 83.
         */
        val signalWidthHz: Int = SIGNAL_WIDTH_HZ
    ) {
        /** The lowest frequency we may transmit on: the signal starts at the edge. */
        val searchLowHz: Int get() = lowHz

        /** The highest: our signal must END at the edge, since a frequency is tone 0. */
        val searchHighHz: Int get() = highHz - signalWidthHz

        /** Narrower than this is not a usable gap: our width plus a guard each side. */
        val minGapHz: Int get() = signalWidthHz + 2 * GUARD_HZ

        /** Does a signal on [hz] fit wholly inside? */
        fun fits(hz: Int): Boolean = hz in searchLowHz..searchHighHz

        companion object {
            val DEFAULT = TxBand(TX_LOW_HZ, TX_HIGH_HZ)
        }
    }

    /** An FT8 signal is about 50 Hz wide. */
    const val SIGNAL_WIDTH_HZ = 50

    /**
     * Keep this much clear either side of a neighbour before calling a slot free.
     * 5 Hz (was 10): our lowest tone then sits
     * about 11 Hz above a neighbour's top tone, nearly two 6.25 Hz tone spacings. Loud
     * neighbours get more room anyway, because the measuring window smears each tone.
     */
    const val GUARD_HZ = 5

    /** Narrower than this is not a usable gap. */
    const val MIN_GAP_HZ = SIGNAL_WIDTH_HZ + 2 * GUARD_HZ

    /**
     * Fraction of in-band bins assumed to be noise when estimating the floor. See
     * the reasoning in [analyse] — this is what sets how crowded a band can get
     * before the detector goes blind.
     */
    const val FLOOR_PERCENTILE = 0.25

    /**
     * How the floor finds the receiver's passband: a bin counts only
     * if it is within [PASSBAND_DEPTH_DB] of this percentile of the range. Bins the
     * filter has cut sit 50 dB or more down, while noise inside it does not.
     *
     * Measured on four IC-7300 recordings and on narrower filters faked from them.
     * 0.75 failed at 500 Hz, where most of the range is outside the
     * filter. Weakness: signals over 30 dB above the noise across a tenth of the range
     * would hide the noise and raise the floor. Not seen in those recordings.
     */
    const val PASSBAND_TOP_PERCENTILE = 0.90
    const val PASSBAND_DEPTH_DB = 30.0

    /**
     * The noise floor of [levelsDb] between two bins, inclusive: [FLOOR_PERCENTILE] of
     * the bins inside the passband. Measured from the audio because CI-V cannot give
     * the edges: `1A 03` is a width, and the passband's centre moved from about
     * 1400 Hz to 1750 Hz between recordings. The strip and the reports both use this.
     */
    fun floorDb(levelsDb: FloatArray, lowBin: Int, highBin: Int): Float {
        val range = levelsDb.copyOfRange(lowBin, highBin + 1).sorted()
        val top = range[(range.size * PASSBAND_TOP_PERCENTILE).toInt().coerceIn(0, range.size - 1)]
        val passband = range.filter { it >= top - PASSBAND_DEPTH_DB }
        return passband[(passband.size * FLOOR_PERCENTILE).toInt().coerceIn(0, passband.size - 1)]
    }

    /*
     * Our own signal must fit entirely inside the TX band, not merely start in it
     * ([TxBand.searchLowHz], [TxBand.searchHighHz]).
     *
     * **A frequency is the LOWEST TONE, not the centre**. `ft8_lib` searches candidates from the lowest tone upward -
     * `decode.c` line 207 bounds the loop by `freq_offset + num_tones - 1` - so the
     * frequency it reports is tone 0 and the signal occupies `[f, f + 50)`. Our
     * decodes also matched the reference corpus to within 2 Hz on the same audio,
     * so WSJT-X uses the same convention and an operator comparing the two sees the
     * same numbers. Everything in this file now follows it.
     *
     * So our signal must START no lower than the band edge and END no higher.
     */

    /**
     * A snapshot of one parity's spectrum.
     *
     * @param binHz width of each bin
     * @param levelsDb mean power per bin, starting at DC
     * @param decodedHz audio frequencies of messages decoded in this slot
     */
    data class Snapshot(
        val binHz: Double,
        val levelsDb: FloatArray,
        val decodedHz: List<Int>
    )

    data class Band(val fromHz: Int, val toHz: Int) {
        val widthHz: Int get() = toHz - fromHz
        val centreHz: Int get() = (fromHz + toHz) / 2
    }

    data class Map(
        /** Per-bin level relative to the slot's noise floor, in dB. */
        val aboveFloorDb: FloatArray,
        val binHz: Double,
        val noiseFloorDb: Double,
        val occupied: List<Band>,
        val gaps: List<Band>,
        /** Best gaps, widest first, as TX frequency candidates. */
        val candidates: List<Int>,
        /** The TX band the gaps and candidates were cut to. */
        val txBand: TxBand = TxBand.DEFAULT
    )

    /**
     * Build the map for one parity.
     *
     * @param occupiedThresholdDb how far above the noise floor counts as occupied.
     *   6 dB is deliberately sensitive: the cost of calling a free slot busy is
     *   choosing a different frequency, while the cost of calling a busy slot free
     *   is transmitting on top of someone.
     * @param txBand where we may transmit: the radio's SSB-D passband when known.
     */
    fun analyse(
        snapshot: Snapshot,
        occupiedThresholdDb: Double = 6.0,
        txBand: TxBand = TxBand.DEFAULT
    ): Map {
        val binHz = snapshot.binHz
        val levels = snapshot.levelsDb
        val lowBin = (BAND_LOW_HZ / binHz).toInt().coerceIn(0, levels.size - 1)
        val highBin = (BAND_HIGH_HZ / binHz).toInt().coerceIn(0, levels.size - 1)
        if (highBin <= lowBin) {
            return Map(FloatArray(0), binHz, 0.0, emptyList(), emptyList(), emptyList(), txBand)
        }

        // Noise floor as a low percentile of the in-band bins, not the mean: a
        // handful of strong signals drags a mean upward and the floor would then sit
        // above the weak signals we most want to notice.
        //
        // The percentile rather than the median because any order statistic at
        // fraction p stops working once more than (1 - p) of the band is occupied -
        // the signals *become* the floor and the detector goes blind exactly when the
        // band is busiest and we most need it. A median tolerates 50% occupancy; the
        // 25th percentile tolerates 75%. The cost is near zero, because averaging
        // many overlapping FFTs over a 13.5 s slot leaves each noise bin with little
        // spread, so the 25th percentile of noise sits a fraction of a dB below its
        // median.
        //
        // Any percentile latches onto the filter's skirt once enough of the range is
        // outside the passband: FIL2 on 6 m put the floor 29 dB low and every bar
        // high on a dead band. [floorDb] drops those bins first. The
        // ceiling below a full TX band is still a known limit.
        //
        // Measured over [FLOOR_LOW_HZ, FLOOR_HIGH_HZ] only, not the displayed band:
        // the display now reaches down into the radio's filter roll-off.
        val inBand = levels.copyOfRange(lowBin, highBin + 1)
        val floorLow = (FLOOR_LOW_HZ / binHz).toInt().coerceIn(0, levels.size - 1)
        val floorHigh = (FLOOR_HIGH_HZ / binHz).toInt().coerceIn(floorLow, levels.size - 1)
        val noiseFloor = floorDb(levels, floorLow, floorHigh).toDouble()

        val aboveFloor = FloatArray(inBand.size) { (inBand[it] - noiseFloor).toFloat() }

        // Mark bins busy either from energy or from a decoded message. Decodes are
        // included explicitly because a signal can finish before the slice ends and
        // average down below the threshold while still having owned that frequency.
        val busy = BooleanArray(inBand.size)
        for (i in aboveFloor.indices) {
            if (aboveFloor[i] >= occupiedThresholdDb) busy[i] = true
        }
        for (hz in snapshot.decodedHz) {
            // The decode frequency is the signal's low edge, so it occupies
            // [hz, hz + 50). This used to centre on hz, which marked 25 Hz of empty
            // spectrum below the signal busy and left the top half of the signal
            // unmarked.
            val from = (hz / binHz).toInt() - lowBin
            val to = ((hz + txBand.signalWidthHz) / binHz).toInt() - lowBin
            for (i in max(0, from)..minOf(busy.size - 1, to)) busy[i] = true
        }

        fun hzOf(index: Int) = ((index + lowBin) * binHz).roundToInt()

        val occupied = mutableListOf<Band>()
        var run = -1
        for (i in busy.indices) {
            if (busy[i] && run < 0) run = i
            if (!busy[i] && run >= 0) {
                occupied += Band(hzOf(run), hzOf(i))
                run = -1
            }
        }
        if (run >= 0) occupied += Band(hzOf(run), hzOf(busy.size))

        // Gaps are the complement of the occupied bands, clipped to the TX band and
        // with the guard applied. Occupancy is detected across the whole monitored
        // band, but only the TX band is offered as somewhere to transmit.
        val gaps = mutableListOf<Band>()
        val txSearchLow = txBand.searchLowHz
        val txSearchHigh = txBand.searchHighHz
        var cursor = txSearchLow
        for (band in occupied.sortedBy { it.fromHz }) {
            if (band.toHz < txSearchLow) continue
            if (band.fromHz > txSearchHigh) break
            val gapEnd = minOf(band.fromHz - GUARD_HZ, txSearchHigh)
            if (gapEnd - cursor >= txBand.minGapHz) gaps += Band(cursor, gapEnd)
            cursor = max(cursor, band.toHz + GUARD_HZ)
        }
        if (txSearchHigh - cursor >= txBand.minGapHz) gaps += Band(cursor, txSearchHigh)

        // Widest first: a wide gap tolerates our own drift and anyone arriving
        // nearby mid-QSO. Width alone is the right rule *given* that the TX band is
        // already restricted to where operating actually happens - it was only
        // pathological when the search ran out to the unusable top of the passband.
        // A candidate is a frequency we could transmit ON, which is our signal's low
        // edge - so it is not the gap's centre but the point that leaves our 50 Hz
        // sitting centred in the gap. Returning the centre would push the top half
        // of our signal into whatever bounds the gap above.
        val candidates = gaps
            .sortedByDescending { it.widthHz }
            .map { it.fromHz + (it.widthHz - txBand.signalWidthHz) / 2 }

        return Map(
            aboveFloorDb = aboveFloor,
            binHz = binHz,
            noiseFloorDb = noiseFloor,
            occupied = occupied,
            gaps = gaps,
            candidates = candidates,
            txBand = txBand
        )
    }

    /**
     * Render the map as a fixed-width text strip, for logs.
     *
     * The real UI draws two thin lanes; this exists to
     * prove the *data* is right before any pixels are spent on it.
     */
    fun render(map: Map, width: Int = 64, txHz: Int? = null): String {
        if (map.aboveFloorDb.isEmpty()) return "(no spectrum)"
        val levels = columns(map, width)
        val span = (BAND_HIGH_HZ - BAND_LOW_HZ).toDouble()
        val sb = StringBuilder(width)
        for (col in 0 until width) {
            val fromHz = BAND_LOW_HZ + (span * col / width)
            val toHz = BAND_LOW_HZ + (span * (col + 1) / width)
            val inThisColumn = txHz != null && txHz >= fromHz && txHz < toHz
            sb.append(
                when {
                    inThisColumn -> 'T'
                    levels[col] >= 18f -> '#'
                    levels[col] >= 12f -> '+'
                    levels[col] >= 6f -> ':'
                    levels[col] >= 3f -> '.'
                    else -> ' '
                }
            )
        }
        return sb.toString()
    }

    /**
     * Peak level above the floor for each of `width` equal columns spanning the
     * monitored band — the numbers behind both the text strip and the real Compose
     * lanes, so the two can never disagree about what is busy.
     *
     * **Peak, not mean.** A 50 Hz signal inside a column tens of Hz wide must still
     * show as occupied; averaging it with the quiet bins either side is how a real
     * station turns into a faint smudge.
     */
    fun columns(map: Map, width: Int): FloatArray {
        val out = FloatArray(width) { 0f }
        if (map.aboveFloorDb.isEmpty() || width <= 0) return out
        val span = (BAND_HIGH_HZ - BAND_LOW_HZ).toDouble()
        for (col in 0 until width) {
            val fromHz = BAND_LOW_HZ + (span * col / width)
            val toHz = BAND_LOW_HZ + (span * (col + 1) / width)
            var peak = -200.0f
            var i = ((fromHz - BAND_LOW_HZ) / map.binHz).toInt()
            val end = ((toHz - BAND_LOW_HZ) / map.binHz).toInt()
            while (i <= end && i < map.aboveFloorDb.size) {
                if (i >= 0 && map.aboveFloorDb[i] > peak) peak = map.aboveFloorDb[i]
                i++
            }
            out[col] = peak
        }
        return out
    }

    /** Fraction 0..1 across the monitored band, for placing a marker on the strip. */
    fun fractionOf(hz: Int): Float =
        ((hz - BAND_LOW_HZ).toFloat() / (BAND_HIGH_HZ - BAND_LOW_HZ)).coerceIn(0f, 1f)

    /** The inverse of [fractionOf], for turning a tap position into a frequency. */
    fun hzAt(fraction: Float): Int =
        (BAND_LOW_HZ + fraction.coerceIn(0f, 1f) * (BAND_HIGH_HZ - BAND_LOW_HZ)).roundToInt()

    /**
     * Marks the TX-usable window beneath the strip, so it is obvious that the
     * monitored band is wider than the band we will transmit in. Without this the
     * picker looks broken when it ignores a large clear area above 2500 Hz.
     */
    fun txWindow(width: Int = 64): String {
        val span = (BAND_HIGH_HZ - BAND_LOW_HZ).toDouble()
        val sb = StringBuilder()
        for (col in 0 until width) {
            val hz = BAND_LOW_HZ + span * (col + 0.5) / width
            sb.append(
                when {
                    hz < TX_LOW_HZ || hz > TX_HIGH_HZ -> ' '
                    else -> '='
                }
            )
        }
        // Bracket the ends so the limits are legible rather than inferred.
        val lowCol = ((TX_LOW_HZ - BAND_LOW_HZ) / span * width).toInt().coerceIn(0, width - 1)
        val highCol = ((TX_HIGH_HZ - BAND_LOW_HZ) / span * width).toInt().coerceIn(0, width - 1)
        sb[lowCol] = '['
        sb[highCol] = ']'
        return sb.toString()
    }

    /** Axis labels matching [render]'s width, so a log line is readable. */
    fun axis(width: Int = 64): String {
        val sb = StringBuilder(" ".repeat(width))
        val span = (BAND_HIGH_HZ - BAND_LOW_HZ).toDouble()
        for (hz in intArrayOf(500, 1000, 1500, 2000, 2500)) {
            val col = ((hz - BAND_LOW_HZ) / span * width).toInt()
            val label = (hz / 100).toString()
            if (col >= 0 && col + label.length <= width) {
                sb.replace(col, col + label.length, label)
            }
        }
        return sb.toString()
    }
}
