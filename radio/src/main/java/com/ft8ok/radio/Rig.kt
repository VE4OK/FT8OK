package com.ft8ok.radio

import com.ft8ok.model.DialFrequencies

/**
 * What the radio is doing, as far as we have been told.
 *
 * Every field is nullable and means "not known yet" when null, never a default. A
 * frequency of 0 or a mode of "USB" invented before the radio has said anything
 * would be a lie that the frequency guard would later act on.
 *
 * @param dialHz the carrier frequency the radio displays.
 * @param band the conventional band label, from [DialFrequencies], or null when the
 *   radio is not sitting on a listed frequency. Null is a real answer: the app does
 *   not own band-edge data and will not guess one.
 */
data class RigState(
    val dialHz: Long? = null,
    val mode: String? = null,
    val filter: String? = null,
    /**
     * The radio's data mode (USB-D), asked for separately: the transceive mode
     * broadcast carries only mode and filter. Null until the radio has answered, and
     * again after any mode change until it answers once more.
     */
    val dataMode: Boolean? = null,
    /**
     * The radio's SSB-D transmit passband in Hz (`1A 05 01 96`):
     * the audio range that reaches the transmitter in USB-D. Null until it answers.
     */
    val txPassbandHz: IntRange? = null,
    /** Epoch ms of the last frame we understood, for staleness. */
    val lastHeardMs: Long? = null
) {
    val band: String? get() = dialHz?.let { DialFrequencies.bandAt(it) }

    /** Is the radio parked on a conventional FT8 frequency? */
    val onFt8Frequency: Boolean get() = dialHz?.let { DialFrequencies.isConventional(it, "FT8") } == true

    /** USB with data on, as FT8 needs. False while either is unknown. */
    val inFt8Mode: Boolean get() = mode == "USB" && dataMode == true

    /** What the radio display would say: USB-D, USB, LSB ... or null if unknown. */
    val modeLabel: String? get() = mode?.let { if (dataMode == true) "$it-D" else it }
}

/**
 * The CAT link to a radio.
 *
 * **The radio pushes, we listen** (ARCHITECTURE.md, proven on hardware). The IC-7300
 * broadcasts every frequency and mode change unsolicited, so a `Rig` subscribes
 * rather than polls: updates are instant, there is no polling interval to tune, and
 * the bus stays quiet. An implementation may still ask explicitly for the things the
 * radio does not volunteer, but it must never depend on asking.
 *
 * **Nothing on this interface keys or moves the radio.** PTT and setting the dial and
 * mode live on the concrete link (`Ic7300Rig.ptt`, `tune`, `setFt8Mode`), reached only
 * by the code that also knows how to stop.
 */
interface Rig {

    /** The latest known state. Updated from any inbound frame. */
    val state: RigState

    /** True while the link is open and frames are arriving. */
    val isConnected: Boolean

    /** Open the link. Returns null on success, or a reason the operator can act on. */
    fun connect(): String?

    /** Close the link and release the port. Safe when not connected. */
    fun disconnect()
}

/**
 * A rig that is not there.
 *
 * Returned when no CAT device is attached, so the rest of the app has something to
 * talk to and the UI shows "no radio" rather than branching on null everywhere.
 */
object NoRig : Rig {
    override val state = RigState()
    override val isConnected = false
    override fun connect(): String? = "No CAT device attached."
    override fun disconnect() {}
}
