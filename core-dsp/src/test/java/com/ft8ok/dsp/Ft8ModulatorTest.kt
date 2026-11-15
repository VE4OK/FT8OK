package com.ft8ok.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The transmitted waveform, checked on the JVM.
 *
 * This is the only code in the app whose output other people hear, so the tests ask
 * what a receiver would see: is each symbol on the frequency it should be, does the
 * signal stay inside its 50 Hz, and does it start and stop without a click.
 */
class Ft8ModulatorTest {

    private val rate = 12_000
    private val samplesPerSymbol = (rate * Ft8Modulator.SYMBOL_PERIOD_SEC).toInt()

    /**
     * Power at [hz] within one symbol, by correlation — a one-bin DFT, **Hann
     * windowed**.
     *
     * The window is not a detail. Without it the sidelobes of a strong in-band tone
     * swamp any measurement 100 Hz away: an unwindowed version of this helper
     * reported identical out-of-band energy for a properly shaped signal and a
     * deliberately hard-switched one, which is how it was caught — it was measuring
     * its own leakage rather than the signal.
     */
    private fun power(signal: FloatArray, symbol: Int, hz: Double): Double {
        val start = symbol * samplesPerSymbol
        // Skip the symbol's edges, where the smoothing is sliding between tones.
        val margin = samplesPerSymbol / 4
        val from = margin
        val until = samplesPerSymbol - margin
        val length = until - from

        var re = 0.0
        var im = 0.0
        for (i in from until until) {
            val window = 0.5 - 0.5 * cos(2 * PI * (i - from) / (length - 1))
            val sample = signal[start + i] * window
            val t = (start + i) / rate.toDouble()
            re += sample * cos(2 * PI * hz * t)
            im += sample * sin(2 * PI * hz * t)
        }
        return sqrt(re * re + im * im)
    }

    @Test
    fun `each symbol lands on its own tone`() {
        // A scripted sequence rather than a real message: the question here is
        // whether tone n comes out at base + n x 6.25 Hz, which is what the
        // receiver's demodulator looks for.
        val tones = byteArrayOf(0, 3, 7, 1, 5, 2, 6, 4)
        val base = 1000.0
        val signal = Ft8Modulator.synthesise(tones, base, rate)

        for ((index, tone) in tones.withIndex()) {
            val expected = base + tone * Ft8Modulator.TONE_SPACING_HZ
            val onTone = power(signal, index, expected)
            // Compare against a neighbouring tone slot: the right one must dominate.
            val wrongTone = base + ((tone + 4) % 8) * Ft8Modulator.TONE_SPACING_HZ
            val offTone = power(signal, index, wrongTone)
            assertTrue(
                "symbol $index (tone $tone): wanted $expected Hz to dominate, " +
                    "got $onTone there and $offTone at $wrongTone",
                onTone > offTone * 3
            )
        }
    }

    @Test
    fun `the signal is the length FT8 expects`() {
        val tones = ByteArray(Ft8Modulator.SYMBOL_COUNT)
        val signal = Ft8Modulator.synthesise(tones, 1000.0, rate)
        assertEquals(Ft8Modulator.SYMBOL_COUNT * samplesPerSymbol, signal.size)
        // 79 symbols x 0.16 s = 12.64 s, the number Mode.FT8 is built around.
        assertEquals(12.64, signal.size.toDouble() / rate, 0.001)
    }

    @Test
    fun `it fades in and out instead of clicking`() {
        // A keyed sine starts at full amplitude and splatters across the band. The
        // first and last samples must be near silent.
        val tones = ByteArray(Ft8Modulator.SYMBOL_COUNT) { 4 }
        val signal = Ft8Modulator.synthesise(tones, 1500.0, rate)

        assertTrue("starts abruptly: ${signal.first()}", abs(signal.first()) < 0.01)
        assertTrue("ends abruptly: ${signal.last()}", abs(signal.last()) < 0.01)

        // ...and reaches full amplitude in the middle, so the fade is a fade and not
        // a permanently quiet signal.
        val peak = signal.maxOf { abs(it) }
        assertTrue("never reaches amplitude, peak $peak", peak > 0.9)
    }

    @Test
    fun `amplitude is respected, because the radio's linear limit is not full scale`() {
        // Measured linear up to -11 dBFS on this rig. Driving harder
        // distorts rather than radiating more, and distortion is splatter.
        val tones = ByteArray(Ft8Modulator.SYMBOL_COUNT) { 2 }
        val quiet = Ft8Modulator.synthesise(tones, 1500.0, rate, amplitude = 0.28)
        assertTrue("peak ${quiet.maxOf { abs(it) }}", quiet.maxOf { abs(it) } <= 0.29)
        assertTrue(quiet.maxOf { abs(it) } > 0.25)
    }

    /*
     * REMOVED: a test asserting the shaped signal splatters less than an
     * unshaped one. Three attempts all measured the measurement rather than the
     * signal - an unwindowed one-bin DFT reported shaped and hard-switched as
     * identical to six figures, and once windowed and widened to include the symbol
     * transitions it reported the SHAPED signal as worse. That may even be right at
     * these offsets: FT8 uses BT = 2.0, which is mild smoothing, and the comparison
     * signal is phase-continuous FSK at h = 1, which is itself compact. Rather than
     * tune a threshold until it agreed with me, the claim is withdrawn.
     *
     * What actually validates this class is a round trip: encode a message,
     * synthesise it, decode it back, and check the text survives. That needs the
     * native decoder, so it runs on the device rather than here.
     */

    /** Power at [hz] across the entire signal, Hann windowed. */
    private fun powerOverAll(signal: FloatArray, hz: Double): Double {
        var re = 0.0
        var im = 0.0
        for (i in signal.indices) {
            val window = 0.5 - 0.5 * cos(2 * PI * i / (signal.size - 1))
            val sample = signal[i] * window
            val t = i / rate.toDouble()
            re += sample * cos(2 * PI * hz * t)
            im += sample * sin(2 * PI * hz * t)
        }
        return sqrt(re * re + im * im)
    }

    /** The same symbols with no phase shaping: what we must not transmit. */
    private fun hardSwitched(tones: ByteArray, base: Double): FloatArray {
        val out = FloatArray(tones.size * samplesPerSymbol)
        var phase = 0.0
        for ((index, tone) in tones.withIndex()) {
            val hz = base + tone * Ft8Modulator.TONE_SPACING_HZ
            for (i in 0 until samplesPerSymbol) {
                out[index * samplesPerSymbol + i] = sin(phase).toFloat()
                phase = (phase + 2 * PI * hz / rate) % (2 * PI)
            }
        }
        return out
    }

    @Test
    fun `moving the base frequency moves the whole signal`() {
        val tones = byteArrayOf(0, 1, 2, 3)
        val low = Ft8Modulator.synthesise(tones, 500.0, rate)
        val high = Ft8Modulator.synthesise(tones, 2000.0, rate)

        assertTrue(power(low, 0, 500.0) > power(low, 0, 2000.0) * 3)
        assertTrue(power(high, 0, 2000.0) > power(high, 0, 500.0) * 3)
    }

    @Test
    fun `nonsense input is refused rather than transmitted`() {
        var refused = false
        try {
            Ft8Modulator.synthesise(ByteArray(0), 1000.0, rate)
        } catch (e: IllegalArgumentException) {
            refused = true
        }
        assertTrue("an empty tone array must not produce a transmission", refused)
    }

    @Test
    fun `it synthesises correctly at the rate the radio actually accepts`() {
        // The TX path runs at 48 kHz, not the decoder's 12 kHz: the IC-7300's codec
        // supports 32/44.1/48 kHz on OUTPUT. Building the
        // output at 12 kHz keyed the radio and produced no power, while AudioTrack
        // accepted every sample and reported success - so the rate is tested here.
        val txRate = 48_000
        val perSymbol = (txRate * Ft8Modulator.SYMBOL_PERIOD_SEC).toInt()
        val tones = byteArrayOf(0, 3, 7, 1, 5, 2, 6, 4)
        val signal = Ft8Modulator.synthesise(tones, 1000.0, txRate)

        assertEquals(tones.size * perSymbol, signal.size)
        assertEquals(
            "the same message must last the same time at any rate",
            tones.size * Ft8Modulator.SYMBOL_PERIOD_SEC,
            signal.size.toDouble() / txRate,
            0.001
        )

        // Tones must land in the right place at this rate too.
        fun powerAt(symbol: Int, hz: Double): Double {
            val start = symbol * perSymbol
            val margin = perSymbol / 4
            var re = 0.0
            var im = 0.0
            for (i in margin until perSymbol - margin) {
                val t = (start + i) / txRate.toDouble()
                re += signal[start + i] * cos(2 * PI * hz * t)
                im += signal[start + i] * sin(2 * PI * hz * t)
            }
            return sqrt(re * re + im * im)
        }
        for ((index, tone) in tones.withIndex()) {
            val onTone = powerAt(index, 1000.0 + tone * Ft8Modulator.TONE_SPACING_HZ)
            val offTone = powerAt(index, 1000.0 + ((tone + 4) % 8) * Ft8Modulator.TONE_SPACING_HZ)
            assertTrue("symbol $index at 48 kHz", onTone > offTone * 3)
        }

        // 79 symbols at 48 kHz is what a real transmission writes.
        val full = Ft8Modulator.synthesise(ByteArray(Ft8Modulator.SYMBOL_COUNT), 1500.0, txRate)
        assertEquals(79 * 7680, full.size)
    }

    // ---- FT4, against WSJT-X's ft4_params / gen_ft4wave ----

    private val ft4Symbol = 576 // samples at 12 kHz

    /**
     * Power at [hz] over FT4 symbols [first] until [last], Hann windowed. Several
     * symbols, because one 48 ms symbol cannot resolve tones 20.8 Hz apart.
     */
    private fun ft4Power(signal: FloatArray, first: Int, last: Int, hz: Double): Double {
        val from = first * ft4Symbol
        val length = (last - first) * ft4Symbol
        var re = 0.0
        var im = 0.0
        for (i in 0 until length) {
            val window = 0.5 - 0.5 * cos(2 * PI * i / (length - 1))
            val t = (from + i) / rate.toDouble()
            re += signal[from + i] * window * cos(2 * PI * hz * t)
            im += signal[from + i] * window * sin(2 * PI * hz * t)
        }
        return sqrt(re * re + im * im)
    }

    @Test
    fun `ft4 is 105 symbols of 576 samples, 5_04 s`() {
        val signal = Ft8Modulator.synthesise(ByteArray(105), 1000.0, rate, Ft8Modulator.FT4)
        assertEquals(105 * ft4Symbol, signal.size)
        assertEquals(5.04, signal.size.toDouble() / rate, 0.0001)
        // At the TX rate, 48 kHz: WSJT-X's own nwave, (103 + 2) x 4 x 576.
        assertEquals(105 * 4 * 576, Ft8Modulator.synthesise(ByteArray(105), 1000.0, 48_000, Ft8Modulator.FT4).size)
    }

    @Test
    fun `ft4 tones are 20_83 Hz apart`() {
        // Four tones, each held for a few symbols so the BT 1.0 smoothing settles.
        val tones = ByteArray(105) { i -> if (i == 0 || i == 104) 0 else ((i / 8) % 4).toByte() }
        val base = 1200.0
        val signal = Ft8Modulator.synthesise(tones, base, rate, Ft8Modulator.FT4)
        val spacing = 12_000.0 / 576
        for (run in 1..4) { // symbols 8-15 hold tone 1, 16-23 tone 2, 24-31 tone 3, 32-39 tone 0
            val first = run * 8 + 1
            val tone = tones[first].toInt()
            val on = ft4Power(signal, first, first + 6, base + tone * spacing)
            for (other in 0 until 4) {
                if (other == tone) continue
                val off = ft4Power(signal, first, first + 6, base + other * spacing)
                assertTrue("run $run tone $tone: $on on, $off at tone $other", on > off * 10)
            }
        }
    }

    @Test
    fun `ft4 fades over the whole first and last symbol, as gen_ft4wave does`() {
        val signal = Ft8Modulator.synthesise(ByteArray(105), 1000.0, rate, Ft8Modulator.FT4, amplitude = 1.0)
        fun rms(from: Int, n: Int) = sqrt((from until from + n).sumOf { signal[it].toDouble() * signal[it] } / n)
        val full = rms(50 * ft4Symbol, ft4Symbol)
        // (1-cos)/2 over the ramp symbol: about 0.61 of full RMS across it (sqrt(3/8)),
        // and near zero at its start. FT8's 1/8-symbol ramp would give about 0.97.
        assertEquals(sqrt(3.0 / 8.0), rms(0, ft4Symbol) / full, 0.03)
        assertEquals(sqrt(3.0 / 8.0), rms(104 * ft4Symbol, ft4Symbol) / full, 0.03)
        assertTrue(abs(signal.first()) < 0.01 && abs(signal.last()) < 0.01)
        // A quarter into the ramp the envelope is (1-cos(pi/4))/2 = 0.146.
        assertTrue(rms(ft4Symbol / 4 - 24, 48) / full < 0.25)
    }

    @Test
    fun `the ft8 shape is what synthesise always did`() {
        val tones = ByteArray(79) { (it % 8).toByte() }
        val before = Ft8Modulator.synthesise(tones, 1500.0, rate)
        val shaped = Ft8Modulator.synthesise(tones, 1500.0, rate, Ft8Modulator.FT8)
        assertTrue(before.contentEquals(shaped))
        assertEquals(Ft8Modulator.FT8, Ft8Modulator.shapeFor("FT8"))
        assertEquals(Ft8Modulator.FT4, Ft8Modulator.shapeFor("FT4"))
    }
}
