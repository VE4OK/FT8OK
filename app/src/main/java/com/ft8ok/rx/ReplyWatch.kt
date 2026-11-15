package com.ft8ok.rx

import com.ft8ok.dsp.Decode
import com.ft8ok.dsp.ReplyMatch
import com.ft8ok.model.Ft8Message
import com.ft8ok.model.Mode
import com.ft8ok.model.SlotParity
import com.ft8ok.qso.HeardGrids
import kotlin.math.abs

/**
 * Decides which slots to test against the list of a partner's possible replies, and
 * where, and which results count as heard. Pure, so it is tested on
 * the JVM. Used on the decode thread only.
 *
 * Every slot the partner should be transmitting in is tested, heard or not, so the log
 * shows both what the list adds and how it behaves on what we know the answer to. Only
 * [accept] lets a result reach the feed and the QSO engine.
 */
class ReplyWatch(private val mode: Mode) {

    /** Who to look for: the partner in the slot, and what we are. */
    data class Target(val partner: String, val myCall: String, val armed: Boolean)

    /** What the ordinary decoders made of the partner in this slot. */
    enum class Heard { TO_US, TO_OTHER, NOT_HEARD }

    /** One test to run. */
    data class Plan(
        val partner: String,
        val myCall: String,
        val decoyCall: String,
        val armed: Boolean,
        val heard: Heard,
        val frequencyHz: Int,
        val dtSeconds: Double,
        /** The partner's grid if they have sent one lately: their grid messages join the list. */
        val partnerGrid: String? = null
    )

    private data class Seen(val frequencyHz: Int, val dtSeconds: Double, val parity: SlotParity, val slotMs: Long)

    private val seen = HashMap<String, Seen>()
    private val grids = HeardGrids()
    private var newestSlotMs = 0L

    /** Remember where everyone in a finished slot was, and the grids they sent. */
    fun observe(decodes: List<Decode>, parity: SlotParity, slotMs: Long) {
        newestSlotMs = slotMs
        for (d in decodes) {
            grids.observe(Ft8Message.parse(d.message), slotMs)
            val sender = HeardStations.senderOf(d.message) ?: continue
            seen[sender.uppercase()] = Seen(d.frequencyHz, d.dtSeconds, parity, slotMs)
        }
        seen.values.removeAll { slotMs - it.slotMs > FORGET_AFTER_MS }
    }

    /**
     * The test for this finished slot, or null if there is none to run. Call after
     * [observe], so a partner heard in this slot is placed where they were just now.
     */
    fun plan(target: Target?, decodes: List<Decode>, parity: SlotParity, ownTransmission: Boolean): Plan? {
        if (target == null || ownTransmission) return null
        val partner = target.partner.uppercase()
        val myCall = target.myCall.uppercase()
        if (!isPlain(partner) || !isPlain(myCall)) return null
        val where = seen[partner] ?: return null
        if (where.parity != parity) return null

        val fromPartner = decodes.filter { HeardStations.senderOf(it.message).equals(partner, ignoreCase = true) }
        val heard = when {
            fromPartner.isEmpty() -> Heard.NOT_HEARD
            fromPartner.any { it.message.substringBefore(' ').equals(myCall, ignoreCase = true) } -> Heard.TO_US
            else -> Heard.TO_OTHER
        }
        return Plan(
            partner, myCall,
            if (partner == DECOY_CALL || myCall == DECOY_CALL) OTHER_DECOY_CALL else DECOY_CALL,
            target.armed, heard, where.frequencyHz, where.dtSeconds, grids.gridOf(partner, newestSlotMs)
        )
    }

    /**
     * The reply [match] found, as a decode for the feed and the QSO engine, or null if it
     * does not count. It counts only if all hold:
     *  - the ordinary decoders did not hear the partner in this slot at all (heard, their
     *    own traffic to someone else matches the list too well, 4zv step 1);
     *  - at most [ACT_MAX_HARD] of the 174 bits disagree, and more than that for the decoy;
     *  - no message to us was decoded within [OTHER_CALLER_HZ] of the spot: another
     *    station's reply to us there matched as the partner at 40 on the bench;
     *  - it is a message from the partner to us.
     * [heardSoFar] is everything decoded in the slot.
     */
    fun accept(plan: Plan, match: ReplyMatch, heardSoFar: List<Decode>): Decode? {
        if (plan.heard != Heard.NOT_HEARD) return null
        if (match.hardErrors > ACT_MAX_HARD || match.decoyHardErrors <= ACT_MAX_HARD) return null
        val words = match.message.split(' ')
        if (words.size < 3 || !words[0].equals(plan.myCall, true) || !words[1].equals(plan.partner, true)) return null
        val otherCaller = heardSoFar.any {
            it.message.substringBefore(' ').equals(plan.myCall, ignoreCase = true) &&
                abs(it.frequencyHz - plan.frequencyHz) <= OTHER_CALLER_HZ
        }
        if (otherCaller) return null
        return Decode(
            score = 0, dtSeconds = plan.dtSeconds, frequencyHz = plan.frequencyHz,
            message = match.message, fromReplyList = true
        )
    }

    /** Only plain calls pack as the list's messages. WSJT-X skips the others too. */
    private fun isPlain(call: String) = call.length >= 3 && call.all { it.isLetterOrDigit() }

    companion object {
        /**
         * The most of a reply's 174 bits that may disagree for it to count as heard.
         * Set from the bench, where the closest false match was 40.
         */
        const val ACT_MAX_HARD = 35

        /**
         * A message to us decoded this close to the partner's spot stops the list
         * acting: the list looks 15 Hz either way, and a signal is 50 Hz wide. A chosen default.
         */
        const val OTHER_CALLER_HZ = 60

        /**
         * Tried in our place on every test. Nobody sends to it, so a match on it says how
         * much a match on our own call is worth in that slot.
         */
        private const val DECOY_CALL = "W9XYZ"
        private const val OTHER_DECOY_CALL = "K1ABC"

        /** A station not heard for this long may be anywhere. A chosen default. */
        private const val FORGET_AFTER_MS = 300_000L
    }
}
