package com.ft8ok.rig

import com.ft8ok.model.DialFrequencies
import com.ft8ok.model.Mode
import com.ft8ok.radio.RigState

/**
 * Rule 3's frequency guard, plus the mode: transmit only with the
 * radio confirmed in USB-D on a frequency the operator can pick - the FT8 table or
 * one they added themselves.
 *
 * Found on 80 m: the radio was in LSB, and nothing in the TX path checked
 * frequency or mode, so arming would have put mirrored tones out below the dial.
 * Pure, so it is tested on the JVM.
 */
object TxGuard {

    enum class Refusal { NO_RADIO, NO_FREQUENCY, OFF_LIST, NOT_USB, DATA_OFF, DATA_UNKNOWN }

    /**
     * Is [hz] a frequency the operator may transmit on? [mode]'s table (FT8OK and
     * FT4OK each check their own) or a custom dial.
     */
    fun allowedDial(hz: Long, customHz: Collection<Long>, mode: Mode = Mode.FT8): Boolean =
        DialFrequencies.isConventional(hz, mode.name) ||
            customHz.any { kotlin.math.abs(it - hz) <= DialFrequencies.MATCH_TOLERANCE_HZ }

    /** Why not to transmit now, or null when it is fine. */
    fun check(connected: Boolean, state: RigState, customHz: Collection<Long>, mode: Mode = Mode.FT8): Refusal? {
        if (!connected) return Refusal.NO_RADIO
        val dial = state.dialHz ?: return Refusal.NO_FREQUENCY
        if (!allowedDial(dial, customHz, mode)) return Refusal.OFF_LIST
        if (state.mode != "USB") return Refusal.NOT_USB
        return when (state.dataMode) {
            true -> null
            false -> Refusal.DATA_OFF
            null -> Refusal.DATA_UNKNOWN
        }
    }
}
