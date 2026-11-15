package com.ft8ok.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * Turns FT8 tones into the audio we transmit.
 *
 * **Ported from `ft8_lib`'s `demo/gen_ft8.c` (`gfsk_pulse`, `synth_gfsk`), MIT**,
 * not invented — see `THIRD_PARTY_LICENSES.md`. It lives in Kotlin rather than
 * behind the JNI so the waveform can be checked on the JVM against a scripted tone
 * sequence, which matters more here than anywhere else in the app: this is the only
 * code whose output other people hear, and a mistake in it is radiated.
 *
 * **Why the shaping is not optional.** FT8 is 8-FSK, and simply switching between
 * tones would splatter energy far outside the 50 Hz the signal is supposed to
 * occupy, landing on whoever is either side of us. The Gaussian phase shaping keeps
 * the transmission inside its own slot. The amplitude ramps at each end do the same
 * job in time rather than frequency — keying a full-amplitude sine on and off
 * produces a click across the whole band.
 */
object Ft8Modulator {

    /** FT8 sends 79 symbols. */
    const val SYMBOL_COUNT = 79

    /** Symbol period: 1920 samples at 12 kHz. 79 x 0.16 s = 12.64 s. */
    const val SYMBOL_PERIOD_SEC = 0.16

    /** Tone spacing, which is also the symbol rate: 1 / 0.16. */
    const val TONE_SPACING_HZ = 6.25

    /**
     * Smoothing filter bandwidth factor. 2.0 for FT8, 1.0 for FT4 — `gen_ft8.c`.
     * A different value is a different signal, not a tuning preference.
     */
    const val SYMBOL_BT = 2.0

    /** `pi * sqrt(2 / log(2))`, from the same source. */
    private const val GFSK_CONST_K = 5.336446

    /**
     * Fraction of a symbol over which the transmission fades in and out.
     * `n_spsym / 8` in the original.
     */
    private const val RAMP_FRACTION = 8

    /**
     * Everything that makes one protocol's waveform differ from another's.
     * The tone spacing is always 1 / [symbolPeriodSec].
     */
    data class Shape(
        val symbolPeriodSec: Double,
        val symbolBt: Double,
        /**
         * Fade in and out over the whole first and last symbol, as WSJT-X's FT4 does
         * (`gen_ft4wave`: `(1-cos)/2` and `(1+cos)/2`, the "ramp" symbols). Otherwise
         * over [RAMP_FRACTION] of a symbol, as `ft8_lib` does for FT8.
         */
        val wholeSymbolRamps: Boolean
    )

    /** FT8: 0.16 s symbols, BT 2.0, 1/8-symbol ramps. */
    val FT8 = Shape(SYMBOL_PERIOD_SEC, SYMBOL_BT, wholeSymbolRamps = false)

    /**
     * FT4, verified from WSJT-X (`ft4_params.f90`, `gen_ft4wave.f90`): 576 samples at
     * 12 kHz = 0.048 s, so 20.83 Hz tone spacing; BT 1.0; the encoder's first and
     * last tone are the ramp symbols, faded over the whole symbol.
     */
    val FT4 = Shape(0.048, 1.0, wholeSymbolRamps = true)

    /** The shape for a mode by its on-air name (`Mode.name`): FT4's, else FT8's. */
    fun shapeFor(modeName: String): Shape = if (modeName == "FT4") FT4 else FT8

    /**
     * Synthesise the audio for one transmission.
     *
     * @param tones the 79 symbols from the encoder, each 0..7.
     * @param baseHz the audio frequency of tone 0 — **the signal's low edge**, the
     *   same convention the decoder reports and the occupancy map uses.
     * The signal occupies `[baseHz, baseHz + 50)`.
     * @param sampleRate samples per second of the output.
     * @param amplitude peak amplitude, 0..1. Kept well below full scale by callers:
     *   the measured linear limit on this radio is −11 dBFS, and
     *   driving harder distorts rather than radiates more.
     * @return mono samples, `tones.size * symbolPeriod * sampleRate` of them.
     */
    fun synthesise(
        tones: ByteArray,
        baseHz: Double,
        sampleRate: Int,
        amplitude: Double = 1.0,
        symbolPeriodSec: Double = SYMBOL_PERIOD_SEC,
        symbolBt: Double = SYMBOL_BT,
        wholeSymbolRamps: Boolean = false
    ): FloatArray {
        require(tones.isNotEmpty()) { "no tones to send" }
        require(sampleRate > 0) { "sample rate must be positive" }

        val samplesPerSymbol = (0.5 + sampleRate * symbolPeriodSec).toInt()
        val total = tones.size * samplesPerSymbol

        // Instantaneous phase step per sample, built up as a smoothed frequency
        // curve. Extended by one symbol at each end so the first and last symbols
        // have something to be smoothed against.
        val dphi = DoubleArray(total + 2 * samplesPerSymbol) {
            2.0 * PI * baseHz / sampleRate
        }
        val dphiPeak = 2.0 * PI / samplesPerSymbol

        val pulse = gfskPulse(samplesPerSymbol, symbolBt)

        for (i in tones.indices) {
            val start = i * samplesPerSymbol
            val tone = tones[i].toInt()
            for (j in 0 until 3 * samplesPerSymbol) {
                dphi[j + start] += dphiPeak * tone * pulse[j]
            }
        }

        // Dummy symbols either side, repeating the first and last tone, so the
        // transmission does not begin or end with a frequency step.
        val first = tones.first().toInt()
        val last = tones.last().toInt()
        for (j in 0 until 2 * samplesPerSymbol) {
            dphi[j] += dphiPeak * pulse[j + samplesPerSymbol] * first
            dphi[j + tones.size * samplesPerSymbol] += dphiPeak * pulse[j] * last
        }

        val signal = FloatArray(total)
        var phi = 0.0
        for (k in 0 until total) {
            signal[k] = (sin(phi) * amplitude).toFloat()
            phi = (phi + dphi[k + samplesPerSymbol]) % (2.0 * PI)
        }

        // Raised-cosine fade at each end. Without it, keying a sine on and off
        // radiates a click across the band. FT4's covers the whole ramp symbol.
        val ramp = if (wholeSymbolRamps) samplesPerSymbol else samplesPerSymbol / RAMP_FRACTION
        for (i in 0 until ramp) {
            val envelope = (1.0 - cos(2.0 * PI * i / (2.0 * ramp))) / 2.0
            signal[i] = (signal[i] * envelope).toFloat()
            signal[total - 1 - i] = (signal[total - 1 - i] * envelope).toFloat()
        }

        return signal
    }

    /** [synthesise] with a protocol's [shape]. */
    fun synthesise(tones: ByteArray, baseHz: Double, sampleRate: Int, shape: Shape, amplitude: Double = 1.0): FloatArray =
        synthesise(tones, baseHz, sampleRate, amplitude, shape.symbolPeriodSec, shape.symbolBt, shape.wholeSymbolRamps)

    /**
     * The Gaussian smoothing pulse, truncated at three symbol lengths.
     *
     * Theoretically infinite; three symbols is where `ft8_lib` cuts it and is what
     * the receiving end expects.
     */
    private fun gfskPulse(samplesPerSymbol: Int, symbolBt: Double): DoubleArray =
        DoubleArray(3 * samplesPerSymbol) { i ->
            val t = i / samplesPerSymbol.toDouble() - 1.5
            val a1 = GFSK_CONST_K * symbolBt * (t + 0.5)
            val a2 = GFSK_CONST_K * symbolBt * (t - 0.5)
            (erf(a1) - erf(a2)) / 2.0
        }

    /**
     * The error function, which Kotlin's standard library does not provide.
     *
     * Abramowitz and Stegun 7.1.26: maximum absolute error 1.5e-7, which is far
     * below anything that could shape a 50 Hz signal differently.
     */
    private fun erf(x: Double): Double {
        val sign = if (x < 0) -1.0 else 1.0
        val z = kotlin.math.abs(x)
        val t = 1.0 / (1.0 + 0.3275911 * z)
        val y = 1.0 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t + 0.254829592) * t * exp(-z * z)
        return sign * y
    }
}
