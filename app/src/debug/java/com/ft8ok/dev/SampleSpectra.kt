package com.ft8ok.dev

import com.ft8ok.dsp.Ft8Decoder
import com.ft8ok.dsp.Occupancy

/**
 * Real spectra from the air, so the strip can be judged against known data rather
 * than against something invented to flatter it.
 *
 * These are occupancy strips logged on **18.100 MHz** by an early test build,
 * transcribed from its text output. Using the text
 * rendering as the interchange format loses resolution — each character is one of
 * five levels — but it keeps the thing that matters: where the stations actually
 * were, in real slots, with real parity separation. A synthetic spectrum would have
 * told us the renderer draws bars, not whether the display is readable when 16
 * bands are busy.
 *
 * Dev-only scaffolding. It exists to make the strip checkable without a radio
 * attached, and goes when live data drives the real screen.
 */
object SampleSpectra {

    /** Slot 055345, odd parity: 19 decodes, the busiest in the run. */
    const val ODD_SLOT = "+:..   +::  .....::. .T   :.  :##::+##..:::  :+ ::##. :::++: .##"

    /** Slot 055400, even parity: 12 decodes. */
    const val EVEN_SLOT = " ..  .##+++ ..   .. ++ .. ##  .+:   T ..:.     ++##:+:.      .. "

    /** Levels the strip's characters stand for, in dB above the slot's noise floor. */
    private fun levelOf(c: Char): Float = when (c) {
        '#' -> 20f
        '+' -> 14f
        ':' -> 8f
        '.' -> 4f
        'T' -> 0f // the marker sat on a clear gap, so it carries no energy
        else -> 0f
    }

    /**
     * Expand a text strip back into a full-resolution spectrum and analyse it, so
     * the map carries real gaps and candidates rather than hand-written ones.
     */
    fun mapFrom(strip: String): Occupancy.Map {
        val binHz = Ft8Decoder.binHz()
        val bins = FloatArray(1025) { -100f }
        val span = (Occupancy.BAND_HIGH_HZ - Occupancy.BAND_LOW_HZ).toDouble()
        for ((col, c) in strip.withIndex()) {
            val db = levelOf(c)
            if (db <= 0f) continue
            val fromHz = Occupancy.BAND_LOW_HZ + span * col / strip.length
            val toHz = Occupancy.BAND_LOW_HZ + span * (col + 1) / strip.length
            val from = (fromHz / binHz).toInt()
            val to = (toHz / binHz).toInt()
            for (i in from..to) if (i in bins.indices) bins[i] = -100f + db
        }
        return Occupancy.analyse(Occupancy.Snapshot(binHz, bins, emptyList()))
    }
}
