package com.ft8ok

import com.ft8ok.model.Mode

/** What this build is. FT4OK. */
object Flavor {
    /** The one mode this app receives and transmits, fixed at build time. */
    val MODE: Mode = Mode.FT4

    /**
     * Switched on once the hold-to-TX test was decoded by remote stations. False makes
     * [com.ft8ok.tx.TxSession] refuse every keying but the Settings hold-to-TX test.
     */
    const val TRANSMIT_BUILT = true
}
