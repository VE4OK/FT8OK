package com.ft8ok.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.log10
import kotlin.math.pow

/**
 * The SNR estimator against spectra whose true SNR is known exactly.
 *
 * This is the ground truth: a synthetic spectrum built from a
 * chosen noise density and a chosen signal power has an SNR that follows from
 * arithmetic, not from another decoder's opinion of the same audio. If the
 * estimator returns something else, the estimator is wrong — there is nothing to
 * argue about.
 */
class SnrEstimatorTest {

    private val binHz = Ft8Decoder.binHz()          // 12000 / 2048 ≈ 5.86 Hz
    private val sliceSeconds = 15.0
    private val signalSeconds = 12.64               // FT8: 79 symbols x 1920 / 12000

    /**
     * A spectrum with flat noise at [noiseDbPerBin] and one FT8 signal occupying
     * 50 Hz from [signalStartHz], whose total power is [snrDb] relative to the
     * noise power in 2500 Hz — built from the definition, so the answer is known.
     */
    private fun spectrumWith(
        snrDb: Double,
        signalStartHz: Int = 1500,
        noiseDbPerBin: Double = -100.0,
        bins: Int = 1025
    ): FloatArray {
        val noisePerBin = 10.0.pow(noiseDbPerBin / 10.0)
        val n0 = noisePerBin / binHz                               // per Hz
        // Total signal power that yields this SNR against 2500 Hz of noise...
        val wanted = 10.0.pow(snrDb / 10.0) * n0 * SnrEstimator.REFERENCE_BANDWIDTH_HZ
        // ...diluted by the transmission occupying only part of the averaged slice,
        // which is what a real averaged spectrum would show.
        val averaged = wanted * (signalSeconds / sliceSeconds)

        val first = (signalStartHz / binHz).toInt()
        val last = ((signalStartHz + SnrEstimator.SIGNAL_BANDWIDTH_HZ) / binHz).toInt()
        val perSignalBin = averaged / (last - first + 1)

        return FloatArray(bins) { i ->
            val power = noisePerBin + if (i in first..last) perSignalBin else 0.0
            (10.0 * log10(power)).toFloat()
        }
    }

    private fun estimate(spectrum: FloatArray, startHz: Int = 1500): Double? =
        SnrEstimator.estimate(spectrum, binHz, startHz, sliceSeconds, signalSeconds)

    @Test
    fun `a signal built at a known SNR is measured at that SNR`() {
        for (truth in listOf(-20.0, -15.0, -10.0, -5.0, 0.0, 5.0, 10.0)) {
            val measured = estimate(spectrumWith(truth))
            assertNotNull("no estimate at $truth dB", measured)
            assertEquals("at $truth dB", truth, measured!!, 0.5)
        }
    }

    @Test
    fun `the answer does not depend on the absolute level of the audio`() {
        // Turning the radio's output up must not change the signal report. If it
        // did, every report we sent would depend on the AF gain knob.
        val quiet = estimate(spectrumWith(-10.0, noiseDbPerBin = -120.0))!!
        val loud = estimate(spectrumWith(-10.0, noiseDbPerBin = -60.0))!!
        assertEquals(quiet, loud, 0.2)
    }

    @Test
    fun `the answer does not depend on where in the band the signal sits`() {
        val low = estimate(spectrumWith(-5.0, signalStartHz = 400), 400)!!
        val mid = estimate(spectrumWith(-5.0, signalStartHz = 1500), 1500)!!
        val high = estimate(spectrumWith(-5.0, signalStartHz = 2400), 2400)!!
        assertEquals(low, mid, 0.3)
        assertEquals(mid, high, 0.3)
    }

    @Test
    fun `reports land in the range real FT8 reports occupy`() {
        // The old number was +11 to
        // +18.5 on ordinary signals, where real reports are mostly negative.
        val typical = estimate(spectrumWith(-12.0))!!
        assertTrue("a -12 dB signal must report negative, got $typical", typical < 0)
        assertTrue(typical > -24)
    }

    @Test
    fun `the 2500 Hz reference is what makes the number conventional`() {
        // Same signal, same noise density: the SNR is defined against 2500 Hz, so
        // halving the reference would add 3 dB. Pinned so nobody "simplifies" the
        // constant away later.
        assertEquals(2500.0, SnrEstimator.REFERENCE_BANDWIDTH_HZ, 0.0)
        val spectrum = spectrumWith(0.0)
        val n0 = SnrEstimator.noiseDensityPerHz(spectrum, binHz)!!
        val measured = SnrEstimator.estimate(spectrum, binHz, 1500, sliceSeconds, signalSeconds, n0)!!
        assertEquals(0.0, measured, 0.5)
    }

    @Test
    fun `the duty-cycle correction is applied, not left as a constant error`() {
        // Told the signal fills the whole slice when it really fills 12.64 s of it,
        // the estimate must shift by exactly that ratio - about 0.75 dB. This pins
        // that the correction exists and has the right sign.
        val spectrum = spectrumWith(0.0)
        val corrected = SnrEstimator.estimate(spectrum, binHz, 1500, sliceSeconds, signalSeconds)!!
        val uncorrected = SnrEstimator.estimate(spectrum, binHz, 1500, sliceSeconds, sliceSeconds)!!
        assertEquals(10.0 * log10(sliceSeconds / signalSeconds), corrected - uncorrected, 0.05)
        assertTrue("correcting for duty cycle must raise the estimate", corrected > uncorrected)
    }

    @Test
    fun `a signal at or below the noise in its own band has no estimate`() {
        // Better than a confident number from the logarithm of almost nothing.
        val flat = FloatArray(1025) { -100f }
        assertNull(estimate(flat))
    }

    @Test
    fun `nonsense inputs are refused rather than guessed at`() {
        assertNull(SnrEstimator.estimate(FloatArray(0), binHz, 1500, 15.0, 12.64))
        assertNull(SnrEstimator.estimate(spectrumWith(0.0), 0.0, 1500, 15.0, 12.64))
        assertNull(SnrEstimator.estimate(spectrumWith(0.0), binHz, 1500, 0.0, 12.64))
        // Off the end of the spectrum.
        assertNull(SnrEstimator.estimate(spectrumWith(0.0), binHz, 99_000, 15.0, 12.64))
    }

    @Test
    fun `a crowded band raises the measured floor, which lowers every report`() {
        // Honest behaviour rather than a bug: with the band full the noise estimate
        // includes signal energy, so reports read low. The same order-statistic
        // blind spot as a full band in Occupancy, asserted here so it is a known property.
        val clean = spectrumWith(0.0)
        val crowded = clean.copyOf()
        // Fill more than three quarters of the range the floor is measured over.
        val from = (Occupancy.FLOOR_LOW_HZ / binHz).toInt()
        val to = ((Occupancy.FLOOR_LOW_HZ + 0.8 * (Occupancy.FLOOR_HIGH_HZ - Occupancy.FLOOR_LOW_HZ)) / binHz).toInt()
        for (i in from..to) crowded[i] = (crowded[i] + 20f)

        val a = estimate(clean)!!
        val b = estimate(crowded)
        if (b != null) {
            assertTrue("a crowded band should not read higher than a clean one", b <= a + 0.5)
        }
    }

    @Test
    fun `a narrow receive filter does not inflate the report`() {
        // The same signal must read the same through a wide or a narrow filter. Before the
        // floor ignored cut bins, cutting the bins outside 550-2250 Hz added about 29 dB.
        val wide = spectrumWith(0.0)
        val narrow = wide.copyOf()
        for (i in narrow.indices) {
            if (i * binHz !in 550.0..2250.0) narrow[i] = narrow[i] - 60f
        }
        assertEquals(estimate(wide)!!, estimate(narrow)!!, 0.5)
    }

    // --- the number that actually goes on the air --------------------------------

    @Test
    fun `a report is clamped to what other stations expect to receive`() {
        // -25 is WSJT-X's own floor for FT8, from lib/ft8/ft8b.f90:
        //     if(xsnr .lt. -25.0) xsnr=-25.0
        // Ours already returned -27 on air, so this clamp is not hypothetical.
        assertEquals(-25, SnrEstimator.toReport(-27.4))
        assertEquals(-25, SnrEstimator.toReport(-99.0))
        assertEquals(-25, SnrEstimator.toReport(-25.0))
        assertEquals(-24, SnrEstimator.toReport(-24.4))
    }

    @Test
    fun `an ordinary report passes through unchanged`() {
        assertEquals(-12, SnrEstimator.toReport(-12.0))
        assertEquals(-12, SnrEstimator.toReport(-11.6))
        assertEquals(0, SnrEstimator.toReport(0.2))
        assertEquals(7, SnrEstimator.toReport(6.5))
    }

    @Test
    fun `a report cannot exceed what the message field can carry`() {
        // ft8_lib packs a report as irpt = 35 + dd and unpacks it with two-digit
        // formatting, so anything above 99 would not survive the round trip.
        assertEquals(99, SnrEstimator.toReport(150.0))
        assertEquals(99, SnrEstimator.toReport(99.4))
    }

    @Test
    fun `the measurement itself is never clamped`() {
        // The estimate stays honest; only the report is constrained. Conflating the
        // two would hide exactly the evidence needed to tell whether the estimator
        // is working.
        val buried = estimate(spectrumWith(-40.0))
        if (buried != null) {
            assertTrue("a -40 dB measurement must survive as itself", buried < SnrEstimator.MIN_REPORT_DB)
        }
    }

    @Test
    fun `an early pass holds only the part of the transmission sent so far`() {
        // 11.8 s of audio, a station starting on time at 0.5 s: 11.3 s of 12.64.
        assertEquals(11.3, SnrEstimator.signalSecondsInSlice(11.8, 0.5, signalSeconds), 1e-9)
        // The whole transmission once the slice holds it.
        assertEquals(signalSeconds, SnrEstimator.signalSecondsInSlice(14.7, 0.5, signalSeconds), 1e-9)
        // A late station's tail is past even the final pass.
        assertEquals(12.2, SnrEstimator.signalSecondsInSlice(14.7, 2.5, signalSeconds), 1e-9)
        // An early one started before the slice did.
        assertEquals(12.34, SnrEstimator.signalSecondsInSlice(14.7, -0.3, signalSeconds), 1e-9)
    }

    @Test
    fun `a partial transmission measured with its own length reads the same as the whole`() {
        // The spectrum of 11.8 s holding 11.3 s of signal: the same power, a different
        // duty cycle. Corrected with the overlap, the estimate does not move.
        val whole = estimate(spectrumWith(-10.0))!!
        val earlySlice = 11.8
        val present = SnrEstimator.signalSecondsInSlice(earlySlice, 0.5, signalSeconds)
        // Rescale the signal bins as an 11.8 s average of 11.3 s of signal would hold.
        val spectrum = spectrumWith(-10.0, noiseDbPerBin = -100.0)
        val noisePerBin = 10.0.pow(-100.0 / 10.0)
        val scale = (present / earlySlice) / (signalSeconds / sliceSeconds)
        val partial = FloatArray(spectrum.size) { i ->
            val p = 10.0.pow(spectrum[i] / 10.0)
            val signal = p - noisePerBin
            (10.0 * log10(noisePerBin + signal * scale)).toFloat()
        }
        val early = SnrEstimator.estimate(partial, binHz, 1500, earlySlice, present)!!
        assertEquals(whole, early, 0.05)
    }
}
