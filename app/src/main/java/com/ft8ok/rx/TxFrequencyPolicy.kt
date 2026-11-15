package com.ft8ok.rx

import com.ft8ok.dsp.Occupancy
import com.ft8ok.dsp.TxFrequency
import com.ft8ok.model.Mode
import com.ft8ok.model.SlotParity

/**
 * Which spectrum governs our next transmission, and therefore which frequency.
 *
 * Pulled out of [RxSession] and made pure because it had a bug that was invisible
 * from the inside and obvious on air: **the held frequency was being tested against
 * the wrong parity's map.**
 *
 * The parity we would transmit in is that of the *next* slot, so it changes at every
 * slot boundary. The frequency was only recomputed once a slot, at the final decode
 * pass, which happens 0.3 s before that boundary. For the whole of the following slot
 * the app was therefore holding a frequency chosen for one parity while displaying,
 * and checking it against, the other. A frequency sitting squarely on a loud station
 * in the lane on screen would never move, because [TxFrequency.isBusy] was asking a
 * spectrum in which that frequency was indeed clear (seen on air: a pick
 * at 697 Hz, 18 Hz from the edge of a signal 20 dB over the floor, while 2200-2500 Hz
 * stood empty).
 *
 * The rule, stated once so it cannot drift again: **the map that governs a
 * transmission is the most recent one heard in a slot of that transmission's own
 * parity.** FT8 alternates, so for the next slot that map is 30 s old, not 15 s.
 */
object TxFrequencyPolicy {

    /** One parity's most recent occupancy, with the slot it came from. */
    data class Reading(val map: Occupancy.Map?, val heardAtMs: Long)

    /**
     * The parity we would transmit in: the slot after the one in progress.
     *
     * With no QSO there is no partner dictating it, so the honest answer is
     * "whichever comes next", and it alternates exactly as our transmission would.
     * Once the QSO engine exists the partner decides it instead.
     */
    fun txParityAt(mode: Mode, nowMs: Long): SlotParity =
        mode.parityOf(mode.slotStartMs(nowMs) + mode.slotMs)

    /**
     * Decide the TX frequency for the moment [nowMs].
     *
     * @param readings the latest map per parity.
     * @param freshWithinMs how old a map may be and still drive a decision. Beyond
     *   this we hold rather than act on a stale picture (ADR 0013).
     */
    fun decide(
        mode: Mode,
        nowMs: Long,
        readings: Map<SlotParity, Reading>,
        heldHz: Int?,
        txMode: TxFrequency.Mode,
        inQso: Boolean = false,
        freshWithinMs: Long = DEFAULT_FRESH_WITHIN_MS,
        // The parity we will actually transmit in. A contact or a CQ run fixes it;
        // "the next slot" is only right when nothing has.
        txParity: SlotParity = txParityAt(mode, nowMs)
    ): TxFrequency.Decision {
        val reading = readings[txParity]
        val fresh = reading
            ?.takeIf { nowMs - it.heardAtMs <= freshWithinMs }
            ?.map

        return TxFrequency.decide(
            mode = txMode,
            heldHz = heldHz,
            freshMap = fresh,
            inQso = inQso
        )
    }

    /**
     * One pick per lane.
     *
     * EVEN and ODD are different crowds, so a spot can be clear in one lane and
     * taken in the other. Each lane keeps its own pick, judged only against its own
     * map, and the transmitter uses the pick for the lane it transmits in. It used to
     * be one frequency judged against each lane in turn, which picked from a gap in
     * one lane and then sat beside a station in the other.
     *
     * In a contact or CQ run only the lane we are committed to holds still
     * ([TxFrequency.Reason.HELD_IN_QSO]); the other lane keeps choosing, since nobody
     * is listening for us there. With [inContact] but no committed parity, both hold.
     */
    fun decideLanes(
        mode: Mode,
        nowMs: Long,
        readings: Map<SlotParity, Reading>,
        held: Map<SlotParity, Int?>,
        txMode: TxFrequency.Mode,
        inContact: Boolean,
        committedParity: SlotParity?,
        freshWithinMs: Long = DEFAULT_FRESH_WITHIN_MS
    ): Map<SlotParity, TxFrequency.Decision> = SlotParity.entries.associateWith { lane ->
        decide(
            mode = mode,
            nowMs = nowMs,
            readings = readings,
            heldHz = held[lane],
            txMode = txMode,
            inQso = inContact && (committedParity == null || committedParity == lane),
            freshWithinMs = freshWithinMs,
            txParity = lane
        )
    }

    /**
     * Two slots of the governing parity. Older than that and the picture is not
     * worth acting on — though holding what we already have still is.
     */
    /** FT8's value, for callers that give none; `RxSession` passes its mode's ([RxSession.freshWithinMs]). */
    const val DEFAULT_FRESH_WITHIN_MS = 2 * 2 * 15_000L
}
