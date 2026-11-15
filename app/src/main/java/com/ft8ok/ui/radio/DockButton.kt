package com.ft8ok.ui.radio

/**
 * What the button left of ⋯ does: whatever the CQ ⋯ panel last had selected.
 * Its label says which, so "what
 * will ARM send" is answered on the button itself.
 *
 * Pure, so the rules are tested on the JVM; the dock only draws the result.
 */
object DockButton {

    enum class Kind {
        /** Start or stop a CQ run, with the word if one is set: `CQ POTA`. */
        CQ,
        /** Queue a call to the station typed in CALL: `CALL W1AW`. */
        CALL,
        /** Queue the typed text for one transmission, or drop it again. */
        TEXT
    }

    /**
     * @param label what the button reads. [Kind.CQ]: the CQ word, or empty. [Kind.CALL]:
     *   the callsign, or empty while none is ready.
     * @param lit drawn as active: a CQ run going, the typed station already our
     *   partner, or text queued.
     */
    data class State(
        val kind: Kind,
        val label: String,
        val enabled: Boolean,
        val lit: Boolean,
        /** Contest mode is on: a second line, `Contest`, whatever the button does. */
        val contest: Boolean = false
    )

    fun resolve(
        tab: MoreTab,
        canTransmit: Boolean,
        cqWord: String,
        callingCq: Boolean,
        /** The typed callsign, when FT8 can call it; null otherwise. */
        readyCall: String?,
        partner: String?,
        textReady: Boolean,
        textQueued: Boolean,
        /** The contest's CQ word while contest mode is on; null when off. */
        contestCqWord: String? = null
    ): State = when (tab) {
        MoreTab.CQ -> State(Kind.CQ, contestCqWord ?: cqWord, enabled = canTransmit, lit = callingCq)
        MoreTab.CALL -> State(
            Kind.CALL,
            readyCall ?: "",
            enabled = canTransmit && readyCall != null,
            lit = readyCall != null && readyCall.equals(partner, ignoreCase = true)
        )
        // Queued text can always be dropped again, even if the typing has since changed.
        MoreTab.TEXT -> State(Kind.TEXT, "", enabled = canTransmit && (textReady || textQueued), lit = textQueued)
    }.copy(contest = contestCqWord != null)

    /** What a press does, given the state it was drawn in. */
    enum class Press { START_CQ, STOP_CQ, CALL, NOTHING, QUEUE_TEXT, DROP_TEXT }

    fun press(state: State): Press = when {
        !state.enabled -> Press.NOTHING
        state.kind == Kind.CQ -> if (state.lit) Press.STOP_CQ else Press.START_CQ
        // Already our partner: calling again would restart a contact in progress.
        // Halt is how one is stopped.
        state.kind == Kind.CALL -> if (state.lit) Press.NOTHING else Press.CALL
        else -> if (state.lit) Press.DROP_TEXT else Press.QUEUE_TEXT
    }
}
