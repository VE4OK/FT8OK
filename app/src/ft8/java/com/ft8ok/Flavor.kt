package com.ft8ok

import com.ft8ok.model.Mode

/** What this build is. FT8OK. */
object Flavor {
    /** The one mode this app receives and transmits, fixed at build time. */
    val MODE: Mode = Mode.FT8

    /** The waveform, encoder and timing of [MODE] are built for transmitting. */
    const val TRANSMIT_BUILT = true
}
