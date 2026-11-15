package com.ft8ok.dsp

import kotlin.math.abs

/**
 * The TX-frequency policy of ADR 0013: two modes, pick and **hold**, move only on
 * measured evidence.
 *
 * The decision this encodes exists because FT8 is half-duplex. The spectrum we need
 * to know about is our own TX parity, and we are deaf in every slot we transmit in,
 * so a station CQing every cycle of its parity never hears that parity again. The
 * map for the only parity that matters is frozen at whatever it last heard — 30
 * seconds ago, or four minutes ago.
 *
 * Nor can a collision be inferred. A squatter sharing our parity and our frequency
 * is mutually deaf with us, and because most operators work split their answers
 * land on their own frequencies rather than ours, so nothing gives them away. A
 * co-parity collision on our own spot is **undetectable while we transmit**. This
 * class therefore does not try to detect one; it only reacts to spectrum we have
 * actually heard, in a slot of our own parity in which we were listening.
 *
 * Pure Kotlin on purpose (rung 0 of the test ladder). **It belongs in the TX
 * scheduler** once `core-qso` exists; it lives beside [Occupancy] for now because
 * it is nothing but pure functions over an [Occupancy.Map] and has to be testable
 * without a phone.
 */
object TxFrequency {

    /** How the TX frequency is chosen. Set in Setup, never on the main screen. */
    enum class Mode {
        /** The app picks and holds, moving only on evidence. The default. */
        FULL_AUTO,

        /** The user taps the strip; the app never adjusts it. */
        USER_SET
    }

    /**
     * How far either side of our centre frequency another signal counts as being on
     * top of us: half our own width, plus the guard. Energy in the guard band alone
     * does not mask us.
     */
    /**
     * How far below our own frequency another signal still counts as on top of us.
     *
     * Asymmetric, because **a frequency is the lowest tone and our signal extends
     * UPWARD from it**. Energy just below us is
     * only a problem within the guard band; energy anywhere up to 50 Hz above us is
     * inside our own signal.
     */
    const val COLLISION_BELOW_HZ = Occupancy.GUARD_HZ

    /** How far above our frequency counts as on top of us: our width plus the guard. */
    const val COLLISION_ABOVE_HZ = Occupancy.SIGNAL_WIDTH_HZ + Occupancy.GUARD_HZ

    /**
     * How strong an interferer has to be, in dB above that slot's noise floor,
     * before we give up a frequency we are already holding.
     *
     * **Deliberately higher than the 6 dB that marks a band occupied when
     * *choosing*,** because the costs are asymmetric in opposite directions. A false
     * "busy" while choosing costs nothing but a different choice. A false positive
     * while *abandoning* costs us the spot, and possibly the station halfway through
     * calling us.
     *
     * 9 dB on trial: at 12 a station up to
     * about a -5 dB report could share our spot without moving us, and our own
     * spot should be clear; moving is fine outside a contact, and Auto never
     * moves inside one. It was 12, the strip's amber tier; a move can now come from
     * a blue bar (6-12 dB). About report + 17 dB, so 9 dB is roughly a -8 report.
     */
    const val MOVE_THRESHOLD_DB = 9.0f

    /**
     * What counts as a **loud** neighbour for [COMFORTABLE_CLEARANCE_HZ]: the strip's
     * amber tier. Kept at 12 when the move threshold went to 9, so lowering
     * that did not also change which gaps Auto picks.
     */
    const val LOUD_DB = 12.0f

    /**
     * How far our centre should be from the nearest **loud** neighbour before a spot
     * counts as comfortable.
     *
     * The gap rule alone only guarantees the legal minimum: a usable gap is
     * [Occupancy.MIN_GAP_HZ] wide, which is our own 50 Hz plus a 5 Hz guard each
     * side. Centred in one of those, we sit 30 Hz from the edge of whatever is next
     * door — fine beside a weak station, uncomfortable beside one 20 dB over the
     * floor, which is what was seen on air: Auto picking an open spot right beside a
     * loud signal when better open areas existed.
     *
     * Measured to the nearest bin at or above [LOUD_DB], the amber tier on the strip,
     * so the operator can see what "loud" means here (it matched the move threshold
     * until that went to 9 dB). At 100 Hz our
     * edges clear a neighbour's by roughly 75 Hz.
     *
     * A chosen default, to be tuned on air.
     */
    const val COMFORTABLE_CLEARANCE_HZ = 100

    /** Why [decide] returned what it did — shown to the operator, and logged. */
    enum class Reason {
        /** No frequency held yet, so one was picked. */
        FIRST_PICK,

        /** Held: nothing has been heard that justifies moving. */
        HELD,

        /** Held because a QSO is in progress. Never move mid-QSO (ADR 0013). */
        HELD_IN_QSO,

        /** Held because the only map we have is stale — we have been transmitting. */
        HELD_NO_FRESH_DATA,

        /** Moved: a listening slot of our own parity showed our spot occupied. */
        MOVED_SPOT_BUSY,

        /** Our spot is busy, but nowhere better was free. Staying put beats silence. */
        HELD_NOWHERE_BETTER,

        /**
         * Our spot is busy and no gap exists anywhere, so we went to the quietest spot
         * in the TX band: Auto always moves to
         * the quietest, with no margin. Setting the frequency by hand is how to stay.
         */
        MOVED_TO_QUIETEST,

        /** The user chose this frequency, so it is not ours to change. */
        USER_SET
    }

    data class Decision(val frequencyHz: Int?, val reason: Reason) {
        val moved: Boolean get() = reason == Reason.FIRST_PICK ||
            reason == Reason.MOVED_SPOT_BUSY || reason == Reason.MOVED_TO_QUIETEST
    }

    /**
     * Decide what to transmit on.
     *
     * @param heldHz what we are transmitting on now, or null before the first pick.
     * @param freshMap occupancy from a slot of **our own TX parity in which we were
     *   listening, not transmitting**. Null when we have no such slot — which is the
     *   normal case while CQing every cycle, and the reason a stale map must never
     *   reach this parameter. Passing an old map here would reintroduce exactly the
     *   blindness the policy exists to handle.
     * @param inQso true while a QSO is in progress; the partner is listening where
     *   we were, so we do not move.
     */
    fun decide(
        mode: Mode,
        heldHz: Int?,
        freshMap: Occupancy.Map?,
        inQso: Boolean
    ): Decision {
        if (mode == Mode.USER_SET) return Decision(heldHz, Reason.USER_SET)

        if (heldHz == null) {
            // Widest first, but a comfortable gap ahead of a merely wider one. With no
            // gap anywhere, the quietest spot: it used to be nothing, and the
            // transmitter then fell back to 1500 Hz, usually the busiest part.
            val pick = freshMap?.let { map ->
                map.candidates.firstOrNull { isComfortable(map, it) }
                    ?: map.candidates.firstOrNull()
                    ?: quietestHz(map)
            }
            return Decision(pick, Reason.FIRST_PICK)
        }
        if (inQso) return Decision(heldHz, Reason.HELD_IN_QSO)
        if (freshMap == null) return Decision(heldHz, Reason.HELD_NO_FRESH_DATA)
        // A held spot outside the TX band - the radio's passband narrowed - is
        // given up as if busy: the radio would key with no power there.
        if (freshMap.txBand.fits(heldHz) && !isBusy(freshMap, heldHz)) return Decision(heldHz, Reason.HELD)

        // Our spot has gone. Prefer the nearest usable gap over the widest one: a
        // small move is less disruptive to anyone who was about to answer us, and
        // the band has already told us this neighbourhood is where the activity is.
        //
        // But "nearest" is qualified by [COMFORTABLE_CLEARANCE_HZ]. Nearest-of-any-width would squeeze us into a bare 70 Hz slot
        // beside a station 20 dB over the floor while a wide clear area sat a little
        // further off — legal by the gap rule and wrong by every other measure. So:
        // the nearest **comfortable** gap, and only if there is none, the nearest
        // gap at all. Falling back rather than refusing, because a cramped spot
        // still beats not transmitting.
        val comfortable = freshMap.candidates.filter { isComfortable(freshMap, it) }
        val better = comfortable.minByOrNull { abs(it - heldHz) }
            ?: freshMap.candidates.minByOrNull { abs(it - heldHz) }
        if (better != null) return Decision(better, Reason.MOVED_SPOT_BUSY)

        // No gap anywhere: the quietest spot, with no margin.
        val quietest = quietestHz(freshMap)
        return if (quietest != null && quietest != heldHz) {
            Decision(quietest, Reason.MOVED_TO_QUIETEST)
        } else {
            Decision(heldHz, Reason.HELD_NOWHERE_BETTER)
        }
    }

    /**
     * The loudest level, in dB above the floor, anywhere our signal would sit if we
     * transmitted on [hz]: the same stretch [isBusy] looks at. Null with no spectrum.
     */
    fun peakDbAt(map: Occupancy.Map, hz: Int): Float? {
        if (map.aboveFloorDb.isEmpty()) return null
        val (from, to) = collisionBins(map, hz) ?: return null
        var peak = Float.NEGATIVE_INFINITY
        for (i in from..to) if (map.aboveFloorDb[i] > peak) peak = map.aboveFloorDb[i]
        return peak
    }

    /**
     * The quietest place in the TX band to put our signal, for a band with no gap
     * left.
     *
     * Every possible frequency is scored by the loudest thing under our signal and
     * its guard ([peakDbAt]), lowest wins; a tie goes to the lower mean level, then
     * to the lower frequency. Energy only, not decodes: a decoded station shows in
     * the energy at about its report plus 17 dB (checked against a screenshot), so sitting on a weak station scores better than sitting on a
     * loud one, which is the point. Ranking spots against each other also keeps
     * working when the band is so full that the floor itself is wrong.
     */
    fun quietestHz(map: Occupancy.Map): Int? {
        if (map.aboveFloorDb.isEmpty()) return null
        var best: Int? = null
        var bestPeak = Float.POSITIVE_INFINITY
        var bestMean = Double.POSITIVE_INFINITY
        var hz = map.txBand.searchLowHz
        while (hz <= map.txBand.searchHighHz) {
            val bins = collisionBins(map, hz)
            if (bins != null) {
                var peak = Float.NEGATIVE_INFINITY
                var sum = 0.0
                for (i in bins.first..bins.second) {
                    val db = map.aboveFloorDb[i]
                    if (db > peak) peak = db
                    sum += db
                }
                val mean = sum / (bins.second - bins.first + 1)
                if (peak < bestPeak || (peak == bestPeak && mean < bestMean)) {
                    best = hz
                    bestPeak = peak
                    bestMean = mean
                }
            }
            hz += QUIETEST_STEP_HZ
        }
        return best
    }

    /** How finely [quietestHz] steps through the band: about one spectrum bin. */
    private const val QUIETEST_STEP_HZ = 5

    /** The map's bins our signal on [hz] would cover, guard included; null if none. */
    private fun collisionBins(map: Occupancy.Map, hz: Int): Pair<Int, Int>? {
        val from = ((hz - COLLISION_BELOW_HZ - Occupancy.BAND_LOW_HZ) / map.binHz).toInt()
            .coerceAtLeast(0)
        // Our width in the map's mode (FT4 83 Hz): COLLISION_ABOVE_HZ for FT8.
        val above = map.txBand.signalWidthHz + Occupancy.GUARD_HZ
        val to = ((hz + above - Occupancy.BAND_LOW_HZ) / map.binHz).toInt()
            .coerceAtMost(map.aboveFloorDb.size - 1)
        return if (to >= from) from to to else null
    }

    /**
     * Is [hz] masked, by the stricter standard of [MOVE_THRESHOLD_DB]?
     *
     * Note this asks about *energy*, not about whether the frequency falls outside a
     * gap. A gap is drawn with the sensitive 6 dB threshold used for picking, so
     * "not in a gap" and "too busy to stay" are deliberately different questions.
     */
    fun isBusy(map: Occupancy.Map, hz: Int): Boolean {
        val peak = peakDbAt(map, hz) ?: return false
        return peak >= MOVE_THRESHOLD_DB
    }

    /**
     * Is [hz] far enough from the nearest loud neighbour to be worth choosing?
     *
     * Note what this does **not** ask. It does not ask whether [hz] is in a gap —
     * the candidate list has already settled that, using the sensitive 6 dB
     * threshold. It asks the separate question of how close the nearest *loud*
     * thing is, so a gap beside a weak station stays perfectly choosable while one
     * beside a loud station does not. A band with no loud signals at all leaves
     * every candidate comfortable, which is the right answer.
     */
    fun isComfortable(map: Occupancy.Map, hz: Int): Boolean =
        clearanceHz(map, hz) >= COMFORTABLE_CLEARANCE_HZ

    /**
     * Distance in Hz from [hz] to the nearest energy at or above
     * [LOUD_DB], or [Int.MAX_VALUE] if the band holds nothing that loud.
     */
    fun clearanceHz(map: Occupancy.Map, hz: Int): Int {
        if (map.aboveFloorDb.isEmpty()) return Int.MAX_VALUE
        // From our signal's centre, as [COMFORTABLE_CLEARANCE_HZ] is defined. [hz]
        // is the low edge; measuring from it let a loud station above us sit 25 Hz
        // closer than intended.
        val centreHz = hz + map.txBand.signalWidthHz / 2
        var nearest = Int.MAX_VALUE
        for (i in map.aboveFloorDb.indices) {
            if (map.aboveFloorDb[i] < LOUD_DB) continue
            val binHz = (Occupancy.BAND_LOW_HZ + i * map.binHz).toInt()
            val distance = abs(binHz - centreHz)
            if (distance < nearest) nearest = distance
        }
        return nearest
    }

    /**
     * Turn a tap into a frequency the user can actually have meant.
     *
     * At roughly 330 px for 2700 Hz a fingertip covers about 300 Hz, so a literal
     * tap-to-Hz reading would be noise. The user is choosing a *gap* whether the UI
     * admits it or not, so snap to the nearest candidate and let the strip show
     * where it landed.
     *
     * Falls back to the raw frequency, clamped into the TX band, when the map offers
     * no candidate at all — better to transmit where the user pointed than to ignore
     * the tap.
     */
    fun snap(tappedHz: Int, map: Occupancy.Map?, txBand: Occupancy.TxBand = map?.txBand ?: Occupancy.TxBand.DEFAULT): Int {
        val candidates = map?.candidates
        if (candidates.isNullOrEmpty()) {
            return tappedHz.coerceIn(
                txBand.searchLowHz,
                txBand.searchHighHz
            )
        }
        return candidates.minByOrNull { abs(it - tappedHz) }!!
    }
}
