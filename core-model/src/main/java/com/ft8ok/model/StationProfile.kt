package com.ft8ok.model

/**
 * Who we are on the air, and the limits we transmit within.
 *
 * **The callsign is not a cosmetic setting.** It goes into every message we send and
 * into the other operator's log, so an empty or wrong one is a reason not to
 * transmit at all rather than something to paper over with a default. There is
 * deliberately no fallback value: a placeholder callsign would be somebody else's.
 *
 * Pure and validated here so the rules are testable, and so the TX path can ask one
 * question — [canTransmit] — instead of re-deriving them.
 */
data class StationProfile(
    /** As transmitted, e.g. `VE4OK`. Portable suffixes like `/P` are allowed. */
    val callsign: String = "",

    /** Four-character Maidenhead locator, e.g. `FN25`. Entered by hand, never from GPS. */
    val grid: String = "",

    /**
     * SWR at which a transmission is aborted.
     *
     * Adjustable, because 2.0 is a sensible default and not a law: *"up to 3.0 could
     * be acceptable in some odd cases"* — a compromise antenna
     * on a hilltop, or a band edge where the tuner cannot quite get there. Bounded
     * at both ends: below [MIN_SWR_ABORT] an ordinary antenna would trip it
     * constantly and the operator would learn to ignore it, and above
     * [MAX_SWR_ABORT] it stops protecting the radio.
     */
    val swrAbort: Double = DEFAULT_SWR_ABORT
) {

    /** The callsign as it will be sent: trimmed and upper case. */
    val normalisedCallsign: String get() = callsign.trim().uppercase()

    /** The grid as it will be sent. */
    val normalisedGrid: String get() = grid.trim().uppercase()

    /** The SWR abort, clamped into the range the radio and the operator can both live with. */
    val effectiveSwrAbort: Double get() = swrAbort.coerceIn(MIN_SWR_ABORT, MAX_SWR_ABORT)

    /** Is the callsign one the protocol could carry? */
    val hasValidCallsign: Boolean get() = CALLSIGN.matches(normalisedCallsign)

    /** Is the grid a four-character locator? */
    val hasValidGrid: Boolean get() = GRID.matches(normalisedGrid)

    /**
     * May this station transmit?
     *
     * Both a callsign and a grid, because the first message of a contact carries the
     * grid and a QSO cannot start without one.
     */
    val canTransmit: Boolean get() = hasValidCallsign && hasValidGrid

    /** Why not, in a form the screen can turn into words. Null when it can. */
    val blocker: Blocker?
        get() = when {
            normalisedCallsign.isEmpty() -> Blocker.NO_CALLSIGN
            !hasValidCallsign -> Blocker.BAD_CALLSIGN
            normalisedGrid.isEmpty() -> Blocker.NO_GRID
            !hasValidGrid -> Blocker.BAD_GRID
            else -> null
        }

    enum class Blocker { NO_CALLSIGN, BAD_CALLSIGN, NO_GRID, BAD_GRID }

    companion object {
        /**
         * Deliberately permissive. The authority on whether a callsign can be sent
         * is the encoder — the 77-bit protocol has its own rules, and this app does
         * not get to decide that a legitimate call is invalid. This rejects the
         * obviously wrong (empty, spaces, punctuation) and leaves the rest to
         * `Ft8Decoder.encode`, which returns null for anything it cannot carry.
         */
        private val CALLSIGN = Regex("^[A-Z0-9]{3,}(/[A-Z0-9]{1,3})?$")

        private val GRID = Regex("^[A-R]{2}[0-9]{2}$")

        /** 2.0 is the usual line, and what the radio's own meter calibration marks. */
        const val DEFAULT_SWR_ABORT = 2.0

        /** Below this an ordinary antenna trips constantly and the alarm stops meaning anything. */
        const val MIN_SWR_ABORT = 1.5

        /** Above this it is no longer protecting anything. */
        const val MAX_SWR_ABORT = 3.0
    }
}
