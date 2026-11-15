package com.ft8ok.time

import com.ft8ok.model.Mode
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * The TX clock of ADR 0005: during a contact, transmit on the partner's time rather
 * than the band's.
 *
 * The listen clock ([TimeEngine]) puts the band's median station at the nominal
 * start. A partner whose signal arrives `e` late is most often a station whose own
 * clock is `e` late - and then their receive window is `e` late too, so a signal
 * sent on the band's time reaches them `e` early. Sending ours `e` late centres it
 * in their window instead. **Inference, not measurement:** a partner whose lateness
 * is their own TX audio delay rather than their clock is better served by the band's
 * time, and nothing on the air tells the two apart. [MAX_SHIFT_MS] bounds what a
 * wrong guess can cost; FT8 decoders tolerate far more than that.
 *
 * Separate from the listen clock on purpose (ARCHITECTURE: two clocks, never
 * merged). It shifts only where *we* start transmitting; what we hear is unchanged.
 * There is nothing to slew back: between contacts the shift is simply zero.
 *
 * Pure: DT readings in, a shift out. Synchronized because the top bar reads the shift
 * on the main thread while the TX ticker and the decode path use it.
 */
class TxClock(
    private val mode: Mode,
    /** The decoder's DT bias, as for [TimeEngine]: `Ft8Decoder.DT_BIAS_SEC`. */
    private val decoderDtBiasSec: Double = 0.0,
    private val maxShiftMs: Long = MAX_SHIFT_MS,
    private val keep: Int = KEEP
) {

    private var partner: String? = null
    private val recent = ArrayDeque<Long>()

    /**
     * One decode of [call], at [dtSeconds] as the decoder reported it. A different
     * call from last time starts afresh: offsets are per station.
     */
    @Synchronized
    fun observe(call: String, dtSeconds: Double) {
        if (!call.equals(partner, ignoreCase = true)) {
            clear()
            partner = call
        }
        val residualMs = ((dtSeconds - (mode.nominalTxStartMs / 1000.0 + decoderDtBiasSec)) * 1000).roundToLong()
        // Beyond this it is a mis-measurement or a station far enough off that
        // following it would be worse than not.
        if (abs(residualMs) > IGNORE_ABOVE_MS) return
        recent.addLast(residualMs)
        while (recent.size > keep) recent.removeFirst()
    }

    @Synchronized
    fun clear() {
        partner = null
        recent.clear()
    }

    /** How far to move our start for [call], ms; positive is later. Zero for anyone else. */
    @Synchronized
    fun shiftMsFor(call: String?): Long {
        if (call == null || !call.equals(partner, ignoreCase = true) || recent.isEmpty()) return 0
        val sorted = recent.sorted()
        val median = if (sorted.size % 2 == 1) sorted[sorted.size / 2]
        else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2
        return median.coerceIn(-maxShiftMs, maxShiftMs)
    }

    companion object {
        /**
         * Chosen default: one second either way. A late shift of 1 s still ends an FT8
         * transmission by 14.14 s, inside the hard stop.
         */
        const val MAX_SHIFT_MS = 1_000L

        /** Readings further off than this are ignored. A chosen default. */
        const val IGNORE_ABOVE_MS = 2_500L

        /** How many recent readings the median is taken over. */
        const val KEEP = 3
    }
}
