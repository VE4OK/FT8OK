package com.ft8ok.dsp

import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The signal report we send: how strong the other station is, in dB.
 *
 * **This is the number that goes in their log.** In FT8 the report is the SNR you
 * measured of them — `K1ABC W3XAA -12` means "I hear you at −12 dB" — so a wrong
 * estimate corrupts a stranger's logbook as well as ours. That is why this exists
 * at all, and why [Decode.score] must never be used in its place.
 *
 * **The definition is WSJT-X's, quoted from its own user guide**
 * (`doc/user_guide/en/make-qso.adoc`):
 *
 * > *Signal reports* are specified as signal-to-noise ratio (S/N or SNR) in dB,
 * > using a standard reference noise bandwidth of 2500 Hz.
 *
 * So the quantity is **signal power divided by the noise power that would be found
 * in 2500 Hz**, which is a convention and not a physical constant: it is why
 * narrowing the receiver's filter makes the number look better without anything
 * actually improving. Everything here follows from that one sentence.
 *
 * Pure Kotlin over a power spectrum, so it is testable against synthetic spectra
 * whose true SNR is known exactly — far better ground truth than another decoder's
 * opinion of the same audio.
 */
object SnrEstimator {

    /** The conventional reference bandwidth. Not physical; see the class note. */
    const val REFERENCE_BANDWIDTH_HZ = 2500.0

    /**
     * How wide an FT8 signal is: 8 tones spaced 6.25 Hz apart.
     *
     * The same 50 Hz [Occupancy] uses, for the same reason, but note the two place
     * it differently — see [signalStartHz] on [estimate].
     */
    const val SIGNAL_BANDWIDTH_HZ = 50.0

    /**
     * Estimate the SNR of one decoded signal.
     *
     * @param spectrumDb average power per bin in dB, from DC upward, as
     *   [Ft8Decoder.spectrum] produces.
     * @param binHz width of one bin.
     * @param signalStartHz the frequency the decoder reported for this message.
     *   **Treated as the LOWEST TONE, not the centre** — `ft8_lib` derives it from
     *   the candidate's lowest bin, and WSJT-X's waterfall marker likewise sits at
     *   the low edge of a signal. So the signal occupies
     *   `[signalStartHz, signalStartHz + 50)`. Note [Occupancy] instead treats a
     *   decoded frequency as a centre and spans ±25 Hz; one of the two is wrong by
     *   half a signal width, and it is worth settling. It matters less there, where the question is only
     *   "is this region busy".
     * @param sliceSeconds how much audio the spectrum was averaged over.
     * @param signalSeconds how long the transmission itself lasts — 12.64 s for
     *   FT8. The spectrum averages over the whole slice, so a signal present for
     *   only part of it is diluted, and the dilution is corrected here rather than
     *   left to show up as a constant error of about −0.7 dB.
     * @return SNR in dB, or null if the spectrum cannot support an estimate.
     */
    fun estimate(
        spectrumDb: FloatArray,
        binHz: Double,
        signalStartHz: Int,
        sliceSeconds: Double,
        signalSeconds: Double,
        noiseDensityPerHz: Double? = null,
        /** The mode's signal width (`Mode.signalWidthHz`): 50 Hz for FT8, 83 Hz for FT4. */
        signalWidthHz: Double = SIGNAL_BANDWIDTH_HZ
    ): Double? {
        if (spectrumDb.isEmpty() || binHz <= 0.0) return null
        if (sliceSeconds <= 0.0 || signalSeconds <= 0.0) return null

        val n0 = noiseDensityPerHz ?: noiseDensityPerHz(spectrumDb, binHz) ?: return null
        if (n0 <= 0.0) return null

        val firstBin = (signalStartHz / binHz).toInt()
        val lastBin = ((signalStartHz + signalWidthHz) / binHz).toInt()
        if (firstBin < 0 || lastBin >= spectrumDb.size || lastBin <= firstBin) return null

        // Total power in the signal's band: signal plus the noise that shares it.
        var inBand = 0.0
        for (i in firstBin..lastBin) inBand += dbToPower(spectrumDb[i])

        val bandHz = (lastBin - firstBin + 1) * binHz
        val signalPower = inBand - n0 * bandHz
        // Buried at or below the noise in its own band: no usable estimate rather
        // than a confident negative number from the logarithm of something tiny.
        if (signalPower <= 0.0) return null

        // The spectrum averaged the transmission over the whole slice. Undo it.
        val dutyCorrected = signalPower * (sliceSeconds / signalSeconds)

        return 10.0 * log10(dutyCorrected / (n0 * REFERENCE_BANDWIDTH_HZ))
    }

    /**
     * How many seconds of a transmission lie inside a slice that starts at the slot
     * boundary: the `signalSeconds` to give [estimate].
     *
     * An early decode pass hands over 11.8 s of a message that ends about 13.1 s
     * into the slot, and a late station's tail can miss even the final pass. Taking
     * the whole transmission length there would read about 0.5 dB low at 11.8 s.
     *
     * @param startSeconds where the transmission starts in the slice: the decoder's
     *   DT less its bias ([Ft8Decoder.DT_BIAS_SEC]). May be negative for an early station.
     * @return the overlap, or the whole transmission when it cannot be worked out.
     */
    fun signalSecondsInSlice(sliceSeconds: Double, startSeconds: Double, signalSeconds: Double): Double {
        val overlap = minOf(sliceSeconds, startSeconds + signalSeconds) - maxOf(0.0, startSeconds)
        // No overlap at all would mean the decode came from nothing; do not divide by it.
        return if (overlap > 0.0) overlap else signalSeconds
    }

    /**
     * Noise power per Hz: [Occupancy.floorDb], the same floor the strip shows. A mean
     * would be dragged upward by a handful of strong signals, and above about 75% band
     * occupancy the signals still become the floor.
     *
     * Restricted to the band the radio actually passes: bins outside the receiver's
     * passband hold almost nothing and would drag the floor down, making every
     * signal look stronger than it is. WSJT-X has the same sensitivity — its
     * developers note the waterfall passband must match the receiver's or the
     * regions outside it unrealistically lower the noise measurement.
     */
    fun noiseDensityPerHz(
        spectrumDb: FloatArray,
        binHz: Double,
        lowHz: Int = Occupancy.FLOOR_LOW_HZ,
        highHz: Int = Occupancy.FLOOR_HIGH_HZ
    ): Double? {
        if (spectrumDb.isEmpty() || binHz <= 0.0) return null
        val lowBin = (lowHz / binHz).toInt().coerceIn(0, spectrumDb.size - 1)
        val highBin = (highHz / binHz).toInt().coerceIn(0, spectrumDb.size - 1)
        if (highBin <= lowBin) return null

        val perBin = dbToPower(Occupancy.floorDb(spectrumDb, lowBin, highBin))
        return if (perBin <= 0.0) null else perBin / binHz
    }

    /**
     * The lowest report WSJT-X will send for FT8, and therefore the lowest other
     * stations expect to receive.
     *
     * From its own decoder, `lib/ft8/ft8b.f90`: `if(xsnr .lt. -25.0) xsnr=-25.0`.
     * The same routine treats a weak-sync decode below −25 as a likely false decode
     * and bails out, so −25 is both a floor and a sanity line.
     */
    const val MIN_REPORT_DB = -25

    /**
     * The highest report the message field can carry.
     *
     * From `ft8_lib`'s packer: a report is stored as `irpt = 35 + dd` and unpacked
     * with two-digit formatting, so `dd` above 99 would not survive the round trip.
     * The floor the *encoding* allows is −30, because `irpt` 1 to 4 are reserved for
     * the blank, RRR, RR73 and 73 messages — but [MIN_REPORT_DB] is stricter and is
     * what we send.
     */
    const val MAX_REPORT_DB = 99

    /**
     * Turn a measurement into the number we would put on the air.
     *
     * Deliberately separate from [estimate]: the **measurement stays honest** and
     * only the *report* is constrained. Keeping them apart means a reading of −31 dB
     * is still visible as what it was when working out whether the estimator is
     * behaving, while what reaches the other operator's log is a value their
     * software expects. Our estimator returned −27 on air within minutes of being
     * switched on, so this is not hypothetical.
     */
    fun toReport(snrDb: Double): Int =
        snrDb.roundToInt().coerceIn(MIN_REPORT_DB, MAX_REPORT_DB)

    private fun dbToPower(db: Float): Double = 10.0.pow(db / 10.0)
}
