package com.ft8ok.qso

import com.ft8ok.model.DialFrequencies
import com.ft8ok.model.Mode
import com.ft8ok.model.SlotParity

/**
 * The gate every transmission passes through.
 *
 * Deliberately small, pure, and the **only** thing that decides whether the radio
 * may be keyed. Nothing here touches audio, PTT or the radio: it answers one
 * question — *may we start transmitting right now, and if not, why not* — so that
 * the answer can be tested exhaustively on the JVM rather than discovered on the
 * air. The code that actually keys the transmitter does nothing except obey it.
 *
 * It enforces, in order:
 *
 *  - **Arm.** TX is off at app start and after anything surprising (rule 3). An
 *    unarmed radio never transmits, whatever else is true.
 *  - **The frequency guard.** Only on a conventional FT8 dial frequency, from the
 *    verified table, unless deliberately overridden. The alternates are allowed:
 *    blocking 14.090 would stop the operator working a DXpedition.
 *  - **Slot parity.** We transmit in our own slots, not on top of the station we
 *    are working.
 *  - **The late-start window.** A fixed per-mode constant, no user setting.
 * Past it we do not transmit at all — and critically, we keep
 *    receiving. A refusal costs one transmission; keying late costs the
 *    transmission *and* the slot it was sent in, and usually the reply.
 */
class TxScheduler(
    private val mode: Mode = Mode.FT8
) {

    /** Why a transmission was allowed or refused. Shown to the operator, and logged. */
    enum class Verdict {
        /** Go. */
        TRANSMIT,

        /** TX is not armed. The default at every app start. */
        NOT_ARMED,

        /** Nothing to send. */
        NOTHING_TO_SEND,

        /** The radio is not on a conventional FT8 frequency, and no override is set. */
        OFF_FREQUENCY,

        /** We do not know what frequency the radio is on, so we cannot check. */
        FREQUENCY_UNKNOWN,

        /** This slot belongs to the other station. */
        WRONG_PARITY,

        /** Too early: the slot has not started yet. */
        TOO_EARLY,

        /**
         * Past the late-start limit. **Receive continues**; the queue carries to the
         * next slot of our parity.
         */
        TOO_LATE
    }

    data class Request(
        /** The message to send, or null if there is nothing queued. */
        val message: String?,
        /** True only when the operator has armed TX. */
        val armed: Boolean,
        /** The parity we transmit in. */
        val txParity: SlotParity,
        /** The radio's dial frequency, or null if the CAT link has not told us. */
        val dialHz: Long?,
        /**
         * Set when the operator has deliberately overridden the frequency guard.
         * An explicit act, never a default — the guard exists to prevent *accidental*
         * out-of-convention transmission, not to overrule a deliberate one.
         */
        val frequencyOverride: Boolean = false,
        /**
         * How long before the nominal start the transmit path may begin: opening the
         * audio, keying, and then holding silence so the signal itself starts on
         * time. Without it every delay in that path made us late. At most the
         * nominal start (the slot boundary); 0 starts the path at the nominal start.
         */
        val leadMs: Long = 0
    )

    data class Decision(
        val verdict: Verdict,
        val message: String? = null,
        /** How far into the slot this decision was made, for the log. */
        val positionInSlotMs: Long = 0
    ) {
        val mayTransmit: Boolean get() = verdict == Verdict.TRANSMIT
    }

    /**
     * May we start transmitting at [nowMs]?
     *
     * @param nowMs **corrected** time — the band's time, not the phone's. Using the
     *   phone's clock here would put us wherever its error happens to be, which was
     *   1.66 s on the test device.
     */
    fun decide(request: Request, nowMs: Long): Decision {
        val slotStart = mode.slotStartMs(nowMs)
        val position = mode.positionInSlotMs(nowMs)

        // Arm first, so that an unarmed radio cannot be talked into transmitting by
        // any combination of the other conditions.
        if (!request.armed) return Decision(Verdict.NOT_ARMED, positionInSlotMs = position)
        if (request.message.isNullOrBlank()) {
            return Decision(Verdict.NOTHING_TO_SEND, positionInSlotMs = position)
        }

        if (!request.frequencyOverride) {
            val dial = request.dialHz
                ?: return Decision(Verdict.FREQUENCY_UNKNOWN, positionInSlotMs = position)
            if (!DialFrequencies.isConventional(dial, mode.name)) {
                return Decision(Verdict.OFF_FREQUENCY, positionInSlotMs = position)
            }
        }

        if (mode.parityOf(slotStart) != request.txParity) {
            return Decision(Verdict.WRONG_PARITY, positionInSlotMs = position)
        }

        if (position < mode.nominalTxStartMs - request.leadMs.coerceIn(0, mode.nominalTxStartMs)) {
            return Decision(Verdict.TOO_EARLY, positionInSlotMs = position)
        }
        if (position > mode.latestTxStartMs) {
            return Decision(Verdict.TOO_LATE, positionInSlotMs = position)
        }

        return Decision(Verdict.TRANSMIT, request.message, position)
    }

    /**
     * Must a transmission in progress stop now?
     *
     * The hard stop is absolute: whatever is being sent, it ends by this point in
     * the slot so that nothing runs into the next one. A transmission begun at the
     * late-start limit is therefore cut short, which is accepted — the tail is lost
     * and the receiver may still decode it.
     */
    fun mustStop(nowMs: Long): Boolean =
        mode.positionInSlotMs(nowMs) >= mode.hardStopMs

    /** How long is left before the hard stop, from [nowMs]. Never negative. */
    fun remainingMs(nowMs: Long): Long =
        (mode.hardStopMs - mode.positionInSlotMs(nowMs)).coerceAtLeast(0)
}
