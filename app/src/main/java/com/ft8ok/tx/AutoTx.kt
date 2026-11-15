package com.ft8ok.tx

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ft8ok.dsp.Decode
import com.ft8ok.dsp.Ft8Decoder
import com.ft8ok.model.ContestSetup
import com.ft8ok.model.Ft8Message
import com.ft8ok.model.Mode
import com.ft8ok.model.SlotParity
import com.ft8ok.model.StationProfile
import com.ft8ok.qso.CallerQueue
import com.ft8ok.qso.Qso
import com.ft8ok.qso.QsoEngine
import com.ft8ok.qso.TxScheduler
import com.ft8ok.time.TxClock

/**
 * Transmitting without a finger on a button.
 *
 * Everything above this point has been a setup test: the operator held a control and
 * letting go stopped it. This is the first thing in the app that keys a transmitter
 * on its own, so it is built as a gate rather than as a feature.
 *
 * **Four independent things must all be true before a single transmission happens**,
 * and each is checked in a different place so no one mistake can satisfy them all:
 *
 *  1. The operator armed TX, deliberately, since the last time anything disarmed it.
 *     [armed] starts false at every app start and is never set by anything but
 *     [arm].
 *  2. The station profile is complete — we cannot send a message without a callsign,
 *     and there is no default callsign because a default would be someone else's.
 *  3. [TxScheduler] allows it: on frequency, in our own slot parity, inside the
 *     late-start window. That class is pure and tested exhaustively for refusals.
 *  4. The radio answers PTT, and its own watchdog will release even if we stop.
 *
 * **Halt is the fifth thing and it works in the other direction.** It aborts
 * immediately, disarms, and keeps the partner so nothing already exchanged is lost
 * (rule 3). Nothing re-arms automatically, ever: after a Halt, after a band change,
 * after backgrounding, the operator must arm again.
 */
class AutoTx(
    val mode: Mode = Mode.FT8,
    private val scheduler: TxScheduler = TxScheduler(mode),
    /** Our start, moved to the partner's time during a contact (ADR 0005). */
    private val txClock: TxClock = TxClock(mode, Ft8Decoder.dtBiasSec(mode.name))
) {

    /**
     * Armed state. **False at every app start** (rule 3) and set only by [arm].
     *
     * Deliberately not persisted. An app that came back armed after a crash, or
     * after being swiped away, would key a transmitter the operator thought was off.
     */
    var armed by mutableStateOf(false)
        private set

    private var qsoState by mutableStateOf<Qso?>(null)

    /**
     * The contact in progress, or null.
     *
     * Every change goes through this one setter (about fifteen places assign it), so
     * a contact that leaves the slot unlogged - a tap on another row, giving up, a
     * band change, a CQ run dropping it - is always handed to [onContactLeft].
     */
    var qso: Qso?
        get() = qsoState
        private set(value) {
            val old = qsoState
            qsoState = value
            // Working them again by any path (a tap, the panel) ends their being dropped.
            if (value != null && (old == null || keyOf(value) != keyOf(old))) {
                dropped.remove(value.partner.uppercase())
                returning.remove(value.partner.uppercase())
            }
            if (old != null && (value == null || keyOf(value) != keyOf(old))) left(old)
        }

    /** What we will send in our next slot, or null if there is nothing to say. */
    var queued by mutableStateOf<String?>(null)
        private set

    /**
     * The operator's pick, or null while the automatic sequence chooses. **Sticky:** it goes out every slot until AUTO ([auto]), another
     * pick, or the contact ending. The partner's messages still update the contact -
     * reports, grid, timing, logging - but no longer replace it (on air with VA3XAB a picked RR73 was replaced by R-05 and R-09 within the slot).
     */
    var pinned by mutableStateOf<QsoEngine.Step?>(null)
        private set

    /** What the automatic sequence would send now; [auto] goes back to it. */
    private var autoQueued: String? = null

    /** Follow the automatic sequence from [reply]: the pick, if any, is dropped. */
    private fun follow(reply: String?) {
        pinned = null
        autoQueued = reply
        queued = reply
    }

    /** Why the last slot was not transmitted in. Shown so silence is explicable. */
    var lastVerdict by mutableStateOf<TxScheduler.Verdict?>(null)
        private set

    /** True while a transmission is actually in progress. */
    var transmitting by mutableStateOf(false)
        private set

    /** A station picked from one of its contest messages, and that message. */
    data class ContestPick(val call: String, val message: Ft8Message)

    /**
     * The last tap that picked a contest station: its CQ FD/RU/TEST/WW or one of its
     * exchanges. We answer only with standard messages, which a
     * station in a WSJT-X contest mode may not take, so Live warns while that station
     * is the partner ([contestPartner]). The tap still calls them, as WSJT-X does
     * after its own warning.
     */
    var contestPick by mutableStateOf<ContestPick?>(null)
        private set

    /**
     * [contestPick] while its station is the partner; null otherwise, and null when
     * the contact is in that same contest: then we send what they
     * expect.
     */
    val contestPartner: ContestPick?
        get() = contestPick?.takeIf { pick ->
            val q = qso
            q != null && q.partner.equals(pick.call, ignoreCase = true) && q.contest?.sameContestAs(pick.message) != true
        }

    /** Set when something disarmed us, so the screen can say why. */
    var disarmReason by mutableStateOf<String?>(null)
        private set

    /**
     * The slot parity we transmit in: the opposite of the one we hear the partner
     * transmit in. Null until there is a partner.
     *
     * **Latched when the partner is heard, never recomputed from the clock.** It
     * was previously supplied by the caller as "the parity of the next slot",
     * evaluated afresh inside the transmit decision - which runs ten times a second
     * *during* the slot we mean to transmit in, where "the next slot" is the wrong
     * one. The scheduler compares against the slot in progress, so the two were
     * always opposite and the verdict was WRONG_PARITY in every slot forever: armed
     * auto TX could not transmit at all, and said nothing about why. Owning the
     * value here is what stops a caller passing the wrong one again.
     */
    var txParity by mutableStateOf<SlotParity?>(null)
        private set

    /**
     * The parity we transmit in next: the contact's, or the CQ run's while calling
     * CQ with no contact - or, with neither, the one a queued text was given. Null
     * when nothing has fixed one.
     */
    val committedParity: SlotParity?
        get() = (if (qso == null && callingCq) cqParity else txParity)
            ?: textParity.takeIf { text != null }

    private var lastTransmittedSlot: Long? = null

    /**
     * The newest slot whose final decodes [onDecodes] has finished applying. [decide]
     * reads it on the TX ticker's thread; written last, so what it sees here means the
     * reply is in place too.
     */
    @Volatile
    private var lastDecodedSlotMs: Long? = null

    /**
     * When the signal itself should start in the slot beginning at [slotStartMs]:
     * the nominal start, moved by the partner's measured offset while there is a
     * contact ([TxClock]). The transmit path pads with silence to land on it.
     */
    fun txStartTargetMs(slotStartMs: Long): Long =
        slotStartMs + mode.nominalTxStartMs + txShiftMs()

    /**
     * True when the next slot is one we expect to transmit in and it starts within
     * [PREWARM_LEAD_MS]: armed, a parity fixed, something to send. A hint for
     * warming the audio output, not a decision - [decide] still makes that.
     */
    fun shouldPrewarm(nowMs: Long): Boolean {
        if (!armed || transmitting || nextMessage == null) return false
        val parity = committedParity ?: return false
        val next = mode.slotStartMs(nowMs) + mode.slotMs
        return mode.parityOf(next) == parity && next - nowMs <= PREWARM_LEAD_MS
    }

    /** The partner-time shift [txStartTargetMs] applies now, ms; positive is later. */
    fun txShiftMs(): Long = txClock.shiftMsFor(qso?.partner)

    /**
     * How far behind [txStartTargetMs] a late start may still go out whole: the
     * mode's cap counted from the band's time, not the partner's. A partner shift of +1 s leaves 0.5 s; a
     * negative one leaves more. Without this a +1 s partner and a 1.82 s late
     * start put us 2.8 s behind the band, outside WSJT-X's ±2.5 s search.
     */
    fun lateShiftAllowanceMs(): Long = (mode.maxLateShiftMs - txShiftMs()).coerceAtLeast(0)

    /**
     * True while we are calling CQ: the one state in which a station calling us is
     * answered automatically. Set only by [startCq].
     *
     * Calling CQ is the partner slot left empty for the app to fill: with no contact in progress our CQ is what goes out; the first station to
     * call us becomes the contact; when it ends, CQ carries on.
     */
    var callingCq by mutableStateOf(false)
        private set

    /** CQs sent since anyone last answered one. At [CQ_LIMIT], CQ stops and TX disarms. */
    var unansweredCqs by mutableIntStateOf(0)
        private set

    private var cqMessage: String? = null
    private var cqParity: SlotParity? = null

    /**
     * Stations calling us in a CQ run while we work someone else, first heard first.
     * Empty outside a CQ run: search and pounce answers nobody
     * the operator did not choose.
     */
    private val callers = CallerQueue(
        // Their slot comes round every two slots. Half a slot short of the second
        // one, so it is the decodes of that slot that drop them, whatever the jitter
        // in when a slot's decodes arrive.
        staleAfterMs = CALLER_MISSED_CYCLES * 2 * mode.slotMs - mode.slotMs / 2,
        // Fresh: heard in their latest slot, so half a slot short of the one before.
        freshWithinMs = 2 * mode.slotMs - mode.slotMs / 2
    )

    /** Who goes next in a CQ run, for the dock; null when nobody is waiting. */
    var nextCaller by mutableStateOf<String?>(null)
        private set

    /**
     * The contact a DXpedition message of ours closed, while its partner may still
     * show they missed it - by sending their R-report again, which is answered by
     * repeating the combined message while the new partner has not answered yet.
     */
    private var combinedTail: Qso? = null
    private var tailMissedClose = false
    /** The tail's latest R-report to us, kept while [tailMissedClose]. */
    private var tailRepeat: Returning? = null

    /**
     * Logged contacts already given their one extra RR73 outside the contact (by
     * [releaseTail], a late R-report, or the missed-reply limit at the close): never
     * twice, so a station repeating its R-report forever (EA3XAA) cannot loop.
     */
    private val tailsHandedOff = HashSet<Pair<String, Long>>()

    /**
     * Contacts logged in the last [DROPPED_KEPT_MS], by call. In a CQ run, an
     * R-report from one of them while it is not our partner asks for our RR73 again:
     * answered with RR73 (once, [tailsHandedOff]), never with a new contact.
     */
    private val recentlyLogged = HashMap<String, Qso>()

    /** Contacts whose ignored R-report is already in the event log, so it says so once. */
    private val ignoredRepeats = HashSet<Pair<String, Long>>()

    private fun dropTail() {
        combinedTail = null
        tailMissedClose = false
        tailRepeat = null
    }

    /**
     * Let the tail go while the run moves on. Kept aside in [dropped], so a later
     * R-report from them gets our RR73 again; one that has already shown it missed our
     * RR73 waits in [returning] at once, ahead of new callers. Once per logged contact,
     * so a station repeating its R-report forever (EA3XAA) cannot loop (HA5XA and ON7XA were forgotten). True if
     * they now wait in [returning].
     */
    private fun releaseTail(): Boolean {
        val tail = combinedTail
        val repeat = tailRepeat.takeIf { tailMissedClose }
        var waiting = false
        if (tail != null && callingCq && tailsHandedOff.add(keyOf(tail))) {
            val key = tail.partner.uppercase()
            dropped[key] = tail
            if (repeat != null) {
                returning[key] = repeat
                waiting = true
            }
        }
        dropTail()
        return waiting
    }

    /** Text the operator typed, queued for one transmission. */
    data class QueuedText(
        val text: String,
        /** Sent as free text, forced; false for a standard message typed as text. */
        val freeText: Boolean
    )

    /**
     * Text queued for our next slot, or null. It goes **once** per ARM, ahead of
     * whatever the contact or CQ run would send, and then the automatic sequence
     * carries on (exchanges automatic by default).
     *
     * Sent on its own - no contact, no CQ run - TX disarms and the text **stays
     * queued**, so ARM sends it again. Sent inside a contact or run
     * it is dropped, since left queued it would go out ahead of the exchange every
     * slot; it stays in the TEXT field for the button to queue again.
     */
    var text by mutableStateOf<QueuedText?>(null)
        private set

    /** The parity a text goes in when no contact or CQ run has fixed one. */
    private var textParity: SlotParity? = null

    /**
     * The queued text has gone out at least once. Such a text is dropped when a
     * contact or CQ run starts, so a repeat left waiting for ARM never jumps ahead of
     * a new exchange; a text not yet sent still goes first, as queued.
     */
    private var textSent = false

    private fun dropSentText() {
        if (textSent) cancelText()
    }

    /**
     * An ending reached when our transmission finished - the retry limit, our RR73s
     * or our CQs unanswered - held until the partner's slot after it has been
     * decoded. Acting at once ignored the very reply being waited for: VE2XB
     * gave up at 21:35:14 and their report came in the 21:35:15 slot.
     * Settled by [onDecodes] for that slot, or by [decide] in our next slot if its
     * decodes never come (receive audio lost).
     */
    private enum class Ending { GAVE_UP, CLOSED_UNANSWERED, CQ_UNANSWERED, GAVE_WAY, NO_PROGRESS }

    /**
     * Settings › CQ PREFERENCES: in a CQ run, move
     * on after this many missed replies in a row, or 0 for off (the rules as built
     * before it). Read at each of our transmissions. Set by the app from the settings.
     */
    var cqMissLimit: () -> Int = { DEFAULT_CQ_MISS_LIMIT }

    /**
     * Settings › CQ PREFERENCES: how many
     * CQs in a row may go unanswered before CQ stops and TX disarms, or 0 for never
     * (useful in a contest, set and forget). One of [CQ_STOP_CHOICES].
     * Read at each CQ. Set by the app from the settings.
     */
    var cqStopLimit: () -> Int = { CQ_LIMIT }

    /*
     * Missed replies in a CQ run. A miss is one of our messages to the partner after
     * which their next slot told us nothing new: silence, or the same message again
     * (EA3XAA sent their grid seven times and then R-17 six times, and
     * every repeat reset the silence count). "Nothing new" is [theirProgress] not
     * rising; any rise starts the count again.
     */
    private var missPartner: String? = null
    private var missLevel = 0
    private var missSends = 0

    private fun forgetMisses() {
        missPartner = null
        missLevel = 0
        missSends = 0
    }

    /** What the partner has told us so far: their report (or exchange), their R, their close. */
    private fun theirProgress(q: Qso): Int =
        (if (q.reportReceivedDb != null || q.exchangeReceived != null) 1 else 0) +
            (if (q.theyAcknowledged) 1 else 0) +
            (if (q.theyClosed) 1 else 0)

    /** Since our last counted message, has the partner told us something new (or gone)? */
    private fun progressedSinceCount(): Boolean {
        val q = qso ?: return true
        return !q.partner.equals(missPartner, ignoreCase = true) || theirProgress(q) > missLevel
    }

    /**
     * Count our message [sent], just transmitted in full, as a miss until the partner's
     * next slot shows otherwise. The ending is held, like every other, and settled when
     * that slot is decoded, so the slot after the last miss already goes to the next
     * caller or our CQ.
     */
    private fun countMiss(sent: Ft8Message?, limit: Int) {
        val q = qso ?: return
        if (q.paused || sent == null) return
        // A combined message's report half is the one addressed to the partner it leaves.
        val to = if (sent is Ft8Message.Dxpedition) sent.report.to else addresseeOf(sent)
        if (!to.equals(q.partner, ignoreCase = true)) return
        val level = theirProgress(q)
        if (!q.partner.equals(missPartner, ignoreCase = true) || level > missLevel) {
            missPartner = q.partner
            missLevel = level
            missSends = 0
        }
        missSends += 1
        when {
            missSends >= limit -> holdEnding(Ending.NO_PROGRESS)
            // After our RR73 nothing is expected back: silence ends
            // the contact, already logged, with no second RR73. Their R-report again is
            // a miss, answered with RR73 until the limit.
            q.loggable && sent is Ft8Message.Acknowledge && sent.closes -> holdEnding(Ending.CLOSED_UNANSWERED)
        }
    }

    /**
     * Contacts a CQ run dropped after [CQ_RUN_TRIES] unanswered messages, by call,
     * so a station heard again later - often with the R-report they could not get
     * through - carries on from what was exchanged instead of starting again.
     * Kept for
     * [DROPPED_KEPT_MS] from their last message; cleared with the run.
     */
    private val dropped = HashMap<String, Qso>()

    /** A dropped partner's latest message to us, heard while another contact ran. */
    private data class Returning(
        val message: Ft8Message,
        val reportDb: Int,
        val dtSeconds: Double,
        val heardIn: SlotParity,
        val heardMs: Long
    )

    /**
     * Dropped partners heard sending to us while we were busy, by call, first heard
     * first. They are worked once the contact in progress is over, ahead of new
     * callers. Forgotten when not heard for as
     * long as a waiting caller is ([returningStaleMs]), or when heard working others.
     */
    private val returning = LinkedHashMap<String, Returning>()
    private val returningStaleMs = CALLER_MISSED_CYCLES * 2 * mode.slotMs - mode.slotMs / 2

    /**
     * Carry on the dropped contact [earlier] from [message], their latest to us. True
     * if a contact is now in progress (false if it went nowhere, or completed with
     * nothing left to send).
     */
    private fun resumeDropped(
        engine: QsoEngine,
        sender: String,
        earlier: Qso,
        message: Ft8Message,
        report: Int,
        dtSeconds: Double,
        heardIn: SlotParity,
        nowMs: Long
    ): Boolean {
        val logged = isLogged(earlier)
        val decision = engine.onMessage(earlier.copy(unansweredTransmissions = 0), message, nowMs, report)
        if (decision.qso == null || decision.reason == QsoEngine.Reason.IGNORED) return false
        dropped.remove(sender.uppercase())
        returning.remove(sender.uppercase())
        unansweredCqs = 0
        answeredSinceSend = true
        txClock.observe(sender, dtSeconds)
        partnerReportDb = report
        qso = decision.qso
        follow(decision.reply)
        txParity = heardIn.other
        onEvent?.invoke(
            if (logged) "$sender missed our RR73: ${decision.reply ?: "nothing to send"}"
            else "$sender heard again after being dropped: ${decision.reply ?: "nothing to send"}"
        )
        if (decision.reason == QsoEngine.Reason.COMPLETE) {
            complete(decision.qso)
            if (decision.reply == null) contactOver()
        }
        return true
    }

    private fun isRogerReport(message: Ft8Message): Boolean =
        (message is Ft8Message.Report && message.roger) || (message is Ft8Message.Contest && message.roger)

    /** With no contact in progress: carry on with the first returning dropped partner. */
    private fun resumeReturning(engine: QsoEngine, nowMs: Long): Boolean {
        while (returning.isNotEmpty()) {
            val (key, r) = returning.entries.first()
            returning.remove(key)
            val earlier = dropped[key] ?: continue
            if (resumeDropped(engine, earlier.partner, earlier, r.message, r.reportDb, r.dtSeconds, r.heardIn, nowMs) &&
                qso != null
            ) return true
        }
        return false
    }

    private var pendingEnding: Ending? = null
    /** The slot whose transmission reached [pendingEnding]. */
    private var pendingAfterSlot: Long? = null
    /** The contact moved, or a caller answered our CQ, since our last transmission began. */
    private var answeredSinceSend = false

    private fun holdEnding(ending: Ending) {
        pendingEnding = ending
        pendingAfterSlot = lastTransmittedSlot
    }

    private fun dropPendingEnding() {
        pendingEnding = null
        pendingAfterSlot = null
    }

    private fun settle(ending: Ending) {
        dropPendingEnding()
        when (ending) {
            Ending.GAVE_UP -> {
                qso = null
                follow(null)
                disarm("No answer - gave up")
            }
            Ending.CLOSED_UNANSWERED -> {
                qso?.let { onEvent?.invoke("${it.partner} quiet after our close: contact over") }
                contactOver()
            }
            Ending.CQ_UNANSWERED -> {
                stopCq()
                disarm("No reply to CQ")
            }
            // The run carries on armed: the next decodes start a waiting caller, or
            // our CQ goes out. The contact is kept aside in case they answer late.
            Ending.GAVE_WAY -> {
                qso?.let {
                    dropped[it.partner.uppercase()] = it
                    onEvent?.invoke("${it.partner} not answering after $CQ_RUN_TRIES tries: dropped, run carries on")
                }
                clearPartner()
            }
            // CQ PREFERENCES: the run carries on, armed. A logged contact (their R-report
            // kept coming back to our RR73) is simply over; any other is kept aside, as
            // GAVE_WAY's are, for a late answer.
            Ending.NO_PROGRESS -> {
                val q = qso
                val misses = missSends
                if (q != null && q.loggable) {
                    // They have had their repeats already: no extra RR73 later.
                    tailsHandedOff.add(keyOf(q))
                    onEvent?.invoke("${q.partner}: $misses missed replies to our close: contact logged, run carries on")
                    contactOver()
                } else if (q != null) {
                    dropped[q.partner.uppercase()] = q
                    onEvent?.invoke("${q.partner}: $misses missed replies (silent or repeating): dropped, run carries on")
                    clearPartner()
                }
            }
        }
    }

    /**
     * What goes out in our next slot of our parity: queued text first, then the
     * contact's next message, or our CQ while calling CQ with no contact in progress.
     */
    val nextMessage: String?
        get() = text?.text ?: queued ?: cqMessage.takeIf { callingCq && qso == null }

    /** True when the message [decide] last handed out is text to send as free text. */
    var lastSentFreeText = false
        private set

    /**
     * Start calling CQ in [parity], with [word] after CQ if one is set (`CQ POTA`).
     * Like tapping a station, this picks what to send and does not arm (rule 3). Any
     * contact in progress is dropped; one that was complete has already been logged.
     */
    fun startCq(engine: QsoEngine, parity: SlotParity, word: String? = null) {
        dropSentText()
        clearPartner()
        callingCq = true
        cqMessage = engine.cq(word)
        cqParity = parity
        unansweredCqs = 0
    }

    /**
     * The CQ word changed in the panel. A run in progress sends the new CQ from its
     * next transmission; nothing else changes, and nothing starts.
     */
    fun changeCqWord(engine: QsoEngine, word: String?) {
        if (callingCq) cqMessage = engine.cq(word)
    }

    /**
     * Queue [queuedText] for one transmission. With no contact and no CQ run it goes
     * in [parity]; otherwise in theirs. Does not arm (rule 3).
     */
    fun queueText(queuedText: QueuedText, parity: SlotParity) {
        text = queuedText
        textParity = parity
        textSent = false
        onEvent?.invoke(
            "text queued: \"${queuedText.text}\" (${if (queuedText.freeText) "free text" else "standard"}), " +
                "parity ${committedParity?.name?.lowercase() ?: "none"}"
        )
    }

    /** The operator dropped the queued text before it went out. Logged, unlike [cancelText]. */
    fun dropText() {
        text?.let { onEvent?.invoke("text dropped: \"${it.text}\"") }
        cancelText()
    }

    /** Forget the queued text: sent, halted, or the band changed. */
    fun cancelText() {
        text = null
        textParity = null
        textSent = false
    }

    /**
     * True while the partner was called from the panel and has not been heard since,
     * so the screen can say so: a call nobody has heard must not
     * read like an answer.
     */
    var partnerUnheard by mutableStateOf(false)
        private set

    /**
     * Call [call] from the panel, heard or not. The same partner slot a tap fills, so
     * the exchange that follows is the automatic one. Transmits in [txIn]: the
     * opposite of where they were last heard, or our own side if never. Like a tap, it stops a CQ run and does not arm.
     */
    fun callByCallsign(engine: QsoEngine, call: String, nowMs: Long, txIn: SlotParity, heard: Boolean) {
        callStation(engine, call, nowMs, heardIn = txIn.other)
        partnerUnheard = !heard && qso != null
        onEvent?.invoke(
            "called \"$call\" from the panel (${if (heard) "heard" else "not heard"}): " +
                "next ${nextMessage ?: "nothing"}, parity ${committedParity?.name?.lowercase() ?: "none"}"
        )
    }

    /** Stop calling CQ. A contact already under way carries on. */
    fun stopCq() {
        callingCq = false
        cqMessage = null
        cqParity = null
        callers.clear()
        nextCaller = null
        dropTail()
        dropped.clear()
        returning.clear()
        tailsHandedOff.clear()
        ignoredRepeats.clear()
    }

    /**
     * Called once per contact, at the moment it is complete enough to log:
     * both reports exchanged, and the close has actually happened -
     * **their** RR73/73 heard, or **our** RR73/73 transmitted.
     *
     * Not simply "[Qso.loggable] became true". The engine marks a contact closed as
     * soon as it *queues* our RR73, a slot before it is sent; logging there would log
     * a contact that a Halt, a late start or a lost slot left unclosed on the air.
     */
    var onContactComplete: ((Qso) -> Unit)? = null

    /**
     * One line for the record when something happens that the operator may later
     * ask about - why TX disarmed above all. The app writes these to the system
     * log, which survives after the screen has moved on (on air the
     * reason for a disarm was gone from the screen before anyone could read it).
     */
    var onEvent: ((String) -> Unit)? = null

    /**
     * The message handed out by the last [decide] that allowed a transmission - while
     * [transmitting], what is on the air right now, which the screen shows.
     */
    var lastSent by mutableStateOf<String?>(null)
        private set

    /**
     * A contact with both reports exchanged left the partner slot without being
     * logged: the operator may still log it from the Log tab.
     */
    var onContactLeft: ((Qso) -> Unit)? = null

    /**
     * Partner and start time of the contacts already logged, automatically or by the
     * operator, newest last. More than one: a contact force-logged and dropped from a
     * CQ run can come back and complete minutes later, and must not be logged twice.
     */
    private val loggedKeys = ArrayDeque<Pair<String, Long>>()

    /** Has this contact been logged, by the app or by the operator? */
    fun isLogged(contact: Qso): Boolean = keyOf(contact) in loggedKeys

    /** The operator logged [contact] by hand: never log it again automatically. */
    fun markLogged(contact: Qso) {
        val key = keyOf(contact)
        if (key in loggedKeys) return
        recentlyLogged[contact.partner.uppercase()] = contact
        loggedKeys.addLast(key)
        while (loggedKeys.size > LOGGED_KEYS_KEPT) loggedKeys.removeFirst()
    }

    /** The operator undid a log entry made by hand. */
    fun unmarkLogged(contact: Qso) {
        loggedKeys.remove(keyOf(contact))
    }

    private fun complete(contact: Qso?) {
        if (contact == null || !contact.loggable || isLogged(contact)) return
        markLogged(contact)
        onContactComplete?.invoke(contact)
    }

    private fun left(contact: Qso) {
        if (contact.reportsOnAir && !isLogged(contact)) onContactLeft?.invoke(contact)
    }

    /**
     * Arm, if the station is in a state to transmit at all.
     *
     * @param refusal why the radio is not fit to transmit (TxGuard: not USB-D), shown as
     *   the reason it stayed off. Null when it is fit.
     * @return null on success, or why not.
     */
    fun arm(profile: StationProfile, refusal: String? = null): String? {
        if (!profile.canTransmit) return "Set your callsign and grid first."
        if (refusal != null) {
            disarmReason = refusal
            return refusal
        }
        armed = true
        disarmReason = null
        // Arming is the operator choosing to carry on: missed replies count afresh.
        forgetMisses()
        // Arming over "they are in a QSO" is the operator's call to go on calling:
        // the queued message goes out. If they are still busy, their next R-report
        // to the other station disarms again.
        // Logged like a disarm: "it did not send" cannot be answered from the log
        // without knowing when TX was armed (VA2XA).
        onEvent?.invoke("armed (partner ${qso?.partner ?: "none"}, next ${nextMessage ?: "nothing"}, parity ${committedParity?.name?.lowercase() ?: "none"})")
        return null
    }

    /**
     * Disarm, with a reason the operator can read. A transmission in progress stops
     * (see [mayContinueTransmitting]).
     *
     * **What was queued is kept**, so re-arming carries on where it left off without
     * hunting for the row to tap again. Nothing is sent while
     * disarmed. Halt and a frequency change clear it themselves.
     */
    fun disarm(reason: String) {
        if (armed) onEvent?.invoke("disarmed: $reason (partner ${qso?.partner ?: "none"}, next ${nextMessage ?: "nothing"})")
        if (armed) disarmReason = reason
        armed = false
        letCurrentFinish = false
    }

    /**
     * The operator pressed ARMED to disarm. Unlike every other disarm, a
     * transmission already on the air **finishes**; nothing new starts. Stopping a
     * transmission in its tracks is Halt's job.
     */
    fun disarmByOperator() {
        val onAir = transmitting
        disarm("Disarmed by operator")
        letCurrentFinish = onAir
    }

    /** True from [disarmByOperator] mid-transmission until that transmission ends. */
    @Volatile
    private var letCurrentFinish = false

    /**
     * May the transmission in progress carry on? Read by the transmit loop. False
     * the moment Halt or any disarm other than [disarmByOperator] happens.
     */
    val mayContinueTransmitting: Boolean get() = (armed || letCurrentFinish) && !restartRequested

    /**
     * Set when the operator picked a different message early enough in our own
     * transmission to start again with it: the one on the air stops, and [decide]
     * may start the new one in the same slot ([pickRetargets]).
     */
    @Volatile
    private var restartRequested = false

    /** A pick made during the transmission now ending, too late to restart it. */
    private var pickedSinceSend = false

    /**
     * Halt: stop now, disarm, keep the partner.
     *
     * The partner is kept paused rather than discarded, because discarding it would
     * also discard the reports already exchanged — and Halt is pressed when
     * something is wrong, which is exactly when losing a half-finished contact is
     * most annoying.
     */
    fun halt(engine: QsoEngine) {
        disarm("Halted")
        follow(null)
        cancelText()
        stopCq()
        qso = engine.halt(qso).qso
    }

    /** Resume a halted contact. Does NOT re-arm; that stays the operator's choice. */
    fun resume(engine: QsoEngine) {
        qso = engine.resume(qso).qso
    }

    /**
     * Begin working a station the operator picked, or that the app proposed.
     *
     * @param heardIn the parity of the slot the station was heard in. We answer in
     *   the opposite one - a station cannot be answered in the slot it is still
     *   transmitting in.
     */
    /**
     * The operator tapped a decode row.
     *
     * A message **addressed to us** is answered as the message it is - tapping
     * `VE4OK VE2XA -14` queues `VE2XA VE4OK R+10` and keeps their report, as
     * double-clicking does in WSJT-X. Tapping anything else (a CQ, a call to someone
     * else) starts a fresh call to the sender, as before.
     *
     * Before this, every tap started a fresh call: on air a tap on VE2XA's
     * report mid-contact threw the report away and queued our grid again.
     *
     * Tapping is not arming: a halted contact is resumed but TX stays off (rule 3).
     *
     * @param ourReportDb our measured report of the row, or null if the slot has not
     *   been measured yet - in which case we cannot answer a report without making
     *   one up, and fall back to a fresh call.
     */
    fun onRowTapped(
        engine: QsoEngine,
        text: String,
        ourReportDb: Int?,
        nowMs: Long,
        heardIn: SlotParity,
        /** The row's DT, so a contact started by a tap is timed to them from the start. */
        dtSeconds: Double? = null
    ) {
        applyTap(engine, text, ourReportDb, nowMs, heardIn, dtSeconds)
        // What the tap became, and in which parity: with the arm line, enough to say
        // from the log why a slot went by without a transmission.
        onEvent?.invoke(
            "tapped \"$text\" (heard ${heardIn.name.lowercase()}): next ${nextMessage ?: "nothing"}, " +
                "parity ${committedParity?.name?.lowercase() ?: "none"}"
        )
    }

    private fun applyTap(
        engine: QsoEngine,
        text: String,
        ourReportDb: Int?,
        nowMs: Long,
        heardIn: SlotParity,
        dtSeconds: Double?
    ) {
        val message = engine.focus(Ft8Message.parse(text))
        val sender = senderOf(message)
        if (sender == null || sender == Ft8Message.HASHED) return
        contestPick = if (Ft8Message.isContest(message)) ContestPick(sender, message) else null

        // A CQ run with a contact under way: tapping another station calling us makes
        // them next, ahead of the first heard,
        // and leaves the contact alone.
        val working = qso
        if (callingCq && working != null && !working.paused && ourReportDb != null &&
            !working.partner.equals(sender, ignoreCase = true) && callers.isCallTo(engine.myCall, message)
        ) {
            callers.heard(sender, message, ourReportDb, dtSeconds ?: 0.0, heardIn, nowMs)
            callers.moveToFront(sender)
            nextCaller = sender
            return
        }
        // A tapped row is a station heard, and a contact about to start.
        partnerUnheard = false
        forgetMisses()
        dropSentText()
        dropPendingEnding()
        dtSeconds?.let { txClock.observe(sender, it) }
        ourReportDb?.let { partnerReportDb = it }

        // Their RRR, RR73 or 73 to us is answered with our 73 - never with a fresh
        // call. Once our 73 has gone out the contact is cleared, so a station that
        // missed it and keeps sending RRR had nothing to continue, and the tap
        // queued our grid (on air, VA3XAA).
        if (message is Ft8Message.Acknowledge) {
            val current = qso?.takeIf { it.partner.equals(sender, ignoreCase = true) }
                ?.let { engine.resume(it).qso }
            // The report argument is not read for an acknowledgement.
            val decision = current?.let { engine.onMessage(it, message, nowMs, ourReportDb ?: 0) }
                ?.takeIf { it.reply != null }
                ?: engine.answerTail(message, nowMs)
            if (decision.reason != QsoEngine.Reason.IGNORED && decision.qso != null) {
                qso = decision.qso
                follow(decision.reply)
                txParity = heardIn.other
                if (decision.reason == QsoEngine.Reason.COMPLETE) complete(decision.qso)
                return
            }
        }

        if (ourReportDb != null && message !is Ft8Message.Cq) {
            // Only the contact with THIS station continues; tapping a different
            // station that is calling us switches to them.
            val current = qso?.takeIf { it.partner.equals(sender, ignoreCase = true) }
                ?.let { engine.resume(it).qso }
            val decision = engine.onMessage(current, message, nowMs, ourReportDb)
            // The partner's R-report to someone else. The tap means "keep going with
            // them", and the engine has no reply to give: applied, that wiped the queued
            // message and every later tap found nothing (VE3XA; 5B4XAA). With nothing queued it falls through to a fresh call.
            if (decision.reason == QsoEngine.Reason.PARTNER_MOVED_ON && (decision.qso != null || closeOwed)) {
                if (queued != null) {
                    qso = decision.qso ?: current
                    txParity = heardIn.other
                    return
                }
            } else if (decision.reason != QsoEngine.Reason.IGNORED && decision.qso != null) {
                // A contact started by a tap is an answer too: without this the CQ
                // count ran on across tapped contacts and could disarm mid-session.
                unansweredCqs = 0
                qso = decision.qso
                follow(decision.reply)
                txParity = heardIn.other
                if (decision.reason == QsoEngine.Reason.COMPLETE) complete(decision.qso)
                return
            }
        }
        callStation(engine, sender, nowMs, heardIn, message as? Ft8Message.Cq)
    }

    fun callStation(
        engine: QsoEngine,
        call: String,
        nowMs: Long,
        heardIn: SlotParity,
        cq: Ft8Message.Cq? = null
    ) {
        // Picking a station to call is search and pounce: CQ stops. Answering a
        // station that called us (onRowTapped) does not come through here.
        stopCq()
        // Set again by callByCallsign when the station has never been heard.
        partnerUnheard = false
        dropSentText()
        dropPendingEnding()
        val decision = engine.begin(call, nowMs, cq)
        qso = decision.qso
        follow(decision.reply)
        txParity = heardIn.other
    }

    /**
     * Our latest measured report of the partner, for the picker's report steps.
     * Refreshed whenever they are heard or tapped.
     */
    var partnerReportDb by mutableStateOf<Int?>(null)
        private set

    /**
     * The station and parity of the contact cleared last, so the picker still has
     * someone to address after our 73 has gone out - which is exactly when a station
     * that missed it keeps sending RRR.
     */
    var lastPartner by mutableStateOf<Pair<String, SlotParity>?>(null)
        private set

    /** Who the picker addresses: the contact in progress, or the one just ended. */
    val pickerPartner: String? get() = qso?.partner ?: lastPartner?.first

    /** Which step the queued message is, so the picker can show it selected. */
    val queuedStep: QsoEngine.Step?
        get() = pinned ?: when (queued?.let(Ft8Message::parse)) {
            is Ft8Message.Reply -> QsoEngine.Step.GRID
            is Ft8Message.Report -> QsoEngine.Step.REPORT
            is Ft8Message.Contest -> QsoEngine.Step.REPORT
            is Ft8Message.Acknowledge -> QsoEngine.Step.CLOSE
            else -> null
        }

    /**
     * The contact a pick applies to: the one in progress, or - after it ended - the
     * tail of the one just ended, which is when a partner that missed our close
     * keeps sending theirs. That tail counts as closed by them, so the close step
     * is our 73, not another RR73.
     */
    private fun pickBase(nowMs: Long): Qso? = qso ?: lastPartner?.let { (call, _) ->
        Qso(partner = call, startedAtMs = nowMs, lastActivityMs = nowMs, theyClosed = true)
    }

    /** The report a pick sends: our latest measurement of them, else the one we sent. */
    private fun pickReportDb(base: Qso): Int? = partnerReportDb ?: base.reportSentDb

    /**
     * What a step would send, as the picker labels it: `R-09` or `-09`, `RR73` or
     * `73`. Null for the grid (the label says GRID) or a report not yet measured.
     * [contest] is contest mode as set now, which the pick follows.
     */
    fun pickLabel(step: QsoEngine.Step, contest: ContestSetup? = null): String? {
        val base = pickBase(0L) ?: return null
        return when (step) {
            QsoEngine.Step.GRID -> null
            // In a contest the report step is the exchange.
            // Report+grid sends a report there, like no contest.
            QsoEngine.Step.REPORT -> contest?.takeIf { it.sendsExchange }?.let { c ->
                (if (base.exchangeReceived != null || base.reportReceivedDb != null) "R " else "") + c.exchange
            } ?: pickReportDb(base)?.let {
                (if (base.reportReceivedDb != null) "R" else "") + "%+03d".format(it)
            }
            QsoEngine.Step.CLOSE -> if (base.theyClosed) "73" else "RR73"
        }
    }

    /**
     * The operator picked which message goes next. It is
     * **pinned**: sent every slot until [auto], another pick, or the contact ends.
     * Does not arm (rule 3).
     *
     * Picked during our own transmission, early enough to start again within the
     * late-start window, it replaces the message on the air now; later than that, it
     * waits for our next slot ([pickRetargets]).
     *
     * @return false if there is no one to address, or a report step was picked
     *   before we have measured them.
     */
    fun choose(engine: QsoEngine, step: QsoEngine.Step, nowMs: Long): Boolean {
        val current = qso
        val base = pickBase(nowMs) ?: return false
        val decision = engine.choose(base, step, pickReportDb(base))
        if (decision.reply == null || decision.qso == null) return false
        if (current == null) txParity = lastPartner?.second
        qso = decision.qso
        pinned = step
        queued = decision.reply
        forgetMisses()
        onEvent?.invoke("picked: ${decision.reply}")
        pickRetargets(nowMs)
        return true
    }

    /**
     * AUTO: drop the pick and send what the automatic sequence would - its answer to
     * the partner's latest message. Like a pick, it can replace a transmission still
     * early enough to restart.
     */
    fun auto(nowMs: Long) {
        if (pinned == null) return
        pinned = null
        queued = autoQueued
        onEvent?.invoke("auto: ${queued ?: "nothing to send"}")
        pickRetargets(nowMs)
    }

    /** Re-render the pin against the contact as it now stands (their report, their close). */
    private fun repin(engine: QsoEngine) {
        val step = pinned ?: return
        val current = qso?.takeIf { !it.paused } ?: return
        val decision = engine.choose(current, step, pickReportDb(current))
        if (decision.reply == null || decision.qso == null) {
            pinned = null
            return
        }
        qso = decision.qso
        queued = decision.reply
    }

    /**
     * A pick or AUTO while we are transmitting something else. Up to
     * [RESTART_MARGIN_MS] before the late-start limit, the transmission stops and the
     * new message starts in the same slot - a late start like any other, cut at the
     * hard stop. After that it goes out in our next slot.
     */
    private fun pickRetargets(nowMs: Long) {
        if (!transmitting || queued == null || queued == lastSent) return
        if (mode.positionInSlotMs(nowMs) <= mode.latestTxStartMs - RESTART_MARGIN_MS) {
            restartRequested = true
            onEvent?.invoke("restarting this transmission with: $queued")
        } else {
            pickedSinceSend = true
        }
    }

    /** Give up on the current contact and clear the slot. */
    fun clearPartner() {
        dropPendingEnding()
        forgetMisses()
        qso?.let { q -> txParity?.let { lastPartner = q.partner to it } }
        txClock.clear()
        releaseTail()
        partnerUnheard = false
        qso = null
        follow(null)
        txParity = null
    }

    /** Is our RR73 or 73 queued for the partner and not yet on the air? */
    private val closeOwed: Boolean
        get() = qso?.closeAired == false &&
            (queued?.let(Ft8Message::parse) as? Ft8Message.Acknowledge)?.closes == true

    /**
     * The contact is over and nothing is left to send: our 73 went out, their 73
     * answered our RR73, our RR73s went unanswered, or they moved on after it was
     * complete. **Search and pounce disarms here; a CQ run stays armed**: answering the next caller is the point of CQ,
     * and anything else is the operator's next choice, as WSJT-X does it.
     */
    private fun contactOver() {
        clearPartner()
        if (!callingCq) disarm("Contact complete")
    }

    /**
     * Feed the decodes from a finished slot.
     *
     * Returns the message we intend to send next, which is also left in [queued].
     * Called for every slot whether armed or not — the QSO engine should follow the
     * conversation either way, so that arming mid-contact picks up where it is
     * rather than starting again.
     */
    fun onDecodes(
        engine: QsoEngine,
        decodes: List<Decode>,
        snrFor: (Decode) -> Int?,
        nowMs: Long,
        slotParity: SlotParity,
        /** The slot these decodes came from, so [decide] knows they are in. */
        slotStartMs: Long? = null
    ) {
        dropped.values.removeAll { nowMs - it.lastActivityMs > DROPPED_KEPT_MS }
        returning.values.removeAll { nowMs - it.heardMs > returningStaleMs }
        recentlyLogged.values.removeAll { nowMs - it.lastActivityMs > DROPPED_KEPT_MS }
        for (decode in decodes) {
            val message = engine.focus(Ft8Message.parse(decode.message))
            // Our partner transmitting in this parity, to anyone: they cannot hear us
            // in it, so ours is the other one. Before, only a message addressed to us
            // moved our parity, and VE3XAB's CQ on even left
            // us calling them on even, from their old odd-slot message.
            if (qso?.partner?.equals(senderOf(message), ignoreCase = true) == true) {
                txParity = slotParity.other
                partnerUnheard = false
            }
            // A decode too weak for the spectrum to measure still counts: skipped, it
            // was never heard (DL2XAA's R-16 and 73). It gets the floor
            // report, as a listed reply does.
            val report = snrFor(decode) ?: UNMEASURED_REPORT_DB
            val sender = senderOf(message)
            // The station our DXpedition message closed with: their R-report again
            // means our RR73 never reached them; anything else, that it did.
            // Their contact is logged and not the engine's any more, and they are not a
            // new caller either.
            if (combinedTail?.partner?.equals(sender, ignoreCase = true) == true) {
                if (message is Ft8Message.Report && message.roger &&
                    message.to.equals(engine.myCall, ignoreCase = true)
                ) {
                    tailMissedClose = true
                    tailRepeat = Returning(message, report, decode.dtSeconds, slotParity, nowMs)
                } else {
                    dropTail()
                }
                continue
            }
            if (callingCq && sender != null) {
                // A partner this run dropped, heard again sending to us: carry on from
                // what was exchanged - their late R-report gets our RR73, not a fresh
                // start. Free, at once; busy, they are next once the contact in
                // progress is over, ahead of any new caller (otherwise a returning
                // station's R-report can go unanswered for several slots).
                // A station logged a moment ago sending its R-report again (not our
                // partner, not kept aside): it missed our RR73. One more RR73, by the
                // same path as a dropped partner; after that, ignored. It must not
                // start a second contact answered "R-NN".
                if (isRogerReport(message) && !dropped.containsKey(sender.uppercase()) &&
                    qso?.partner?.equals(sender, ignoreCase = true) != true &&
                    addresseeOf(message)?.equals(engine.myCall, ignoreCase = true) == true
                ) {
                    val done = recentlyLogged[sender.uppercase()]
                    if (done != null) {
                        if (tailsHandedOff.add(keyOf(done))) {
                            dropped[sender.uppercase()] = done
                        } else {
                            if (ignoredRepeats.add(keyOf(done))) {
                                onEvent?.invoke("$sender R-report again after their extra RR73: ignored")
                            }
                            continue
                        }
                    }
                }
                var earlier = dropped[sender.uppercase()]
                // A logged contact kept aside by [releaseTail] wants only one thing from
                // us, an RR73 again, and only their R-report asks for it. Anything else
                // (their 73: they had it) ends the wait.
                if (earlier != null && isLogged(earlier) && !isRogerReport(message)) {
                    dropped.remove(sender.uppercase())
                    returning.remove(sender.uppercase())
                    earlier = null
                }
                if (earlier != null &&
                    addresseeOf(message)?.equals(engine.myCall, ignoreCase = true) == true
                ) {
                    if (qso == null) {
                        if (resumeDropped(engine, sender, earlier, message, report, decode.dtSeconds, slotParity, nowMs)) continue
                    } else {
                        returning[sender.uppercase()] = Returning(message, report, decode.dtSeconds, slotParity, nowMs)
                        continue
                    }
                }
                // Calling us while we work someone else, or before this run has a
                // contact: they wait their turn, first heard first.
                if (callers.isCallTo(engine.myCall, message) && qso?.partner?.equals(sender, ignoreCase = true) != true) {
                    // A dupe calling us is not queued or answered on its own; a tap still answers them.
                    if (isContestDupe(engine, sender)) continue
                    callers.heard(sender, message, report, decode.dtSeconds, slotParity, nowMs)
                    // Somebody answered our CQ: a held "no reply to CQ" is off. Not
                    // during a contact: a caller is no answer from the partner, and
                    // cancelling a held "two RR73s unanswered" sent W6XAA a third,
                    // combined (drop them after two RR73s).
                    if (qso == null) answeredSinceSend = true
                    continue
                }
                // A waiting station heard calling CQ or sending to someone else has
                // stopped waiting for us.
                if (message is Ft8Message.Cq || addresseeOf(message)?.let {
                        it != Ft8Message.HASHED && !it.equals(engine.myCall, ignoreCase = true)
                    } == true
                ) {
                    callers.take(sender)
                    returning.remove(sender.uppercase())
                }
            }
            // With no contact in progress the only thing the engine can do is start
            // one with whoever is calling us. That is automatic only while we are
            // calling CQ; otherwise the caller's row is
            // highlighted and a tap answers it. Before this, TX staying armed after a
            // contact meant any late caller was answered without the operator choosing.
            if (qso == null && !callingCq) continue
            val decision = engine.onMessage(qso, message, nowMs, report)
            // IGNORED means the message was not part of our contact — somebody
            // else's QSO, which is most of a busy slot. Applying it would overwrite
            // the reply queued moments earlier with the engine's null, and since a
            // slot carries twenty-odd decodes the reply would be destroyed before it
            // could ever be sent. Only a decision that actually moved the contact is
            // applied.
            //
            // PAUSED likewise: a halted contact answers every decode in the slot,
            // from anyone, with PAUSED. Applied, each one re-latched our parity and
            // overwrote the partner's report and timing with some other station's
            // (seen as "parity odd" when re-arming for VA3XAC).
            if (decision.reason == QsoEngine.Reason.IGNORED || decision.reason == QsoEngine.Reason.PAUSED) continue
            if (decision.reason == QsoEngine.Reason.PARTNER_MOVED_ON) {
                val partner = qso?.partner ?: continue
                val to = addresseeOf(message) ?: Ft8Message.HASHED
                when {
                    // Our RR73 or 73 has yet to go: it goes, once, whatever they do next.
                    // 5B4XAA went back to CQ while ours
                    // waited for our slot, and the contact ended with it unsent.
                    decision.qso == null && closeOwed ->
                        onEvent?.invoke("$partner moved on: sending our close first")
                    // Complete and logged already: moving on is what happens next.
                    decision.qso == null -> {
                        answeredSinceSend = true
                        contactOver()
                    }
                    // Mid-contact with someone else while answering our CQ: drop them
                    // and carry on calling CQ, armed.
                    // Kept aside like any dropped contact, so their R-report once they
                    // are done there is finished, not ignored (DL1XAA).
                    callingCq -> {
                        answeredSinceSend = true
                        qso?.let { dropped[it.partner.uppercase()] = it }
                        onEvent?.invoke("$partner is in a QSO with $to: dropped, CQ resumes")
                        clearPartner()
                    }
                    // Search and pounce: keep calling. Their
                    // R-report is near the end of that contact, and the disarm cost the
                    // very slot they could have heard us in. Not an answer to us, so the
                    // usual limits on calling still run.
                    else -> onEvent?.invoke("$partner is working $to: still calling")
                }
                continue
            }
            // Whatever it was, it moved the contact: a held ending is off.
            answeredSinceSend = true
            // Their timing, for ours (ADR 0005).
            decision.qso?.partner?.let { txClock.observe(it, decode.dtSeconds) }
            // And our freshest report of them, for the picker.
            if (decision.qso != null) partnerReportDb = report
            // Somebody answered: the CQ count starts again.
            if (qso == null) unansweredCqs = 0
            qso = decision.qso
            // The engine's answer is what AUTO sends; a pick stays on the air instead,
            // re-rendered against what they just told us (their report puts the R on
            // our report, their close turns RR73 into 73).
            autoQueued = decision.reply
            queued = decision.reply
            repin(engine)
            // We heard the partner in this slot, so ours is the other one. Re-latched
            // on every message of theirs rather than only at the start, so a partner
            // that moves parity mid-contact takes us with them.
            txParity = slotParity.other
            // They closed a contact that had both reports: it is complete, whether or
            // not we were armed to answer their 73.
            if (decision.reason == QsoEngine.Reason.COMPLETE) {
                complete(decision.qso)
                // The contact has ended, and with it the pick: what is left to say
                // is the engine's (our 73, or nothing).
                qso = decision.qso
                follow(decision.reply)
                // Their 73 after our RR73: nothing left to say, so the contact is
                // over. Left in place it would keep a CQ from resuming.
                if (decision.reply == null) contactOver()
            }
        }

        // The slot after the one whose transmission reached an ending is in: if it
        // brought no answer, the ending goes ahead now.
        val ending = pendingEnding
        val after = pendingAfterSlot
        if (ending != null && after != null && slotStartMs != null && slotStartMs > after) {
            // A missed reply is one that told us nothing new: being answered with a
            // repeat does not call it off.
            val goOn = if (ending == Ending.NO_PROGRESS) progressedSinceCount() else answeredSinceSend
            if (goOn) dropPendingEnding() else settle(ending)
        }

        // A CQ run: with no contact, work the first station waiting; with one, fold
        // the next caller's report into our RR73.
        // A dropped partner who came back while we were busy goes first, then new
        // callers. After either, a waiting caller can ride on our
        // RR73 to a returning partner.
        if (callingCq) {
            if (qso == null && !resumeReturning(engine, nowMs)) takeNextCaller(engine, nowMs)
            if (qso != null) combineIfWaiting(engine, nowMs)
            nextCaller = returning.keys.firstOrNull()?.let { r -> dropped[r]?.partner }
                ?: callers.next(nowMs, except = qso?.partner)?.call
        }

        // Last: the moment this is set, [decide] may send what is queued. Set first, it
        // sent VA3XAD a second R-04 with their RR73 already decoded.
        slotStartMs?.let { lastDecodedSlotMs = it }
    }


    /**
     * Has [call] already been worked in the contest we are in, on this band? Set by
     * the app from the logbook; asked only in contest mode.
     */
    var isDupe: (String) -> Boolean = { false }

    /** Dupe callers already reported in the event log, so a pileup does not flood it. */
    private val dupesReported = mutableSetOf<String>()

    /** A dupe in contest mode: not answered by the CQ run on its own. Logged once per call. */
    private fun isContestDupe(engine: QsoEngine, call: String): Boolean {
        if (engine.contest == null || !isDupe(call)) return false
        if (dupesReported.add(call.uppercase())) onEvent?.invoke("dupe $call calling: not answered (a tap answers)")
        return true
    }
    /** Start the contact with the first station waiting, as if they had just called. */
    private fun takeNextCaller(engine: QsoEngine, nowMs: Long) {
        val caller = callers.next(nowMs) ?: return
        callers.take(caller.call)
        // Logged since they queued (a contact completed by a tap meanwhile).
        if (isContestDupe(engine, caller.call)) return
        val decision = engine.onMessage(null, caller.message, nowMs, caller.reportDb)
        val started = decision.qso ?: return
        unansweredCqs = 0
        txClock.observe(caller.call, caller.dtSeconds)
        partnerReportDb = caller.reportDb
        // TIME_ON is when they first called, not when their turn came.
        qso = started.copy(startedAtMs = caller.firstHeardMs)
        follow(decision.reply)
        txParity = caller.heardIn.other
        onEvent?.invoke("next caller ${caller.call}: ${decision.reply}")
    }

    /**
     * Replace our RR73 to the partner with one message that also sends the next
     * caller their report: `A RR73; B <US> -14`. Only then - our
     * call goes as a hash in it - and only with a caller in the partner's parity,
     * since one transmitting in ours cannot hear us. Also repeats such a message when
     * the station it closed with sends their R-report again before the station it
     * opened with has answered.
     */
    private fun combineIfWaiting(engine: QsoEngine, nowMs: Long) {
        val current = qso ?: return
        if (current.paused || pinned != null || current.contest != null) return
        // A returning dropped partner is next and needs an RR73 of their own, which
        // cannot share a message with this one: ours goes out plain.
        if (returning.isNotEmpty()) return
        val owed = queued?.let(Ft8Message::parse) ?: return
        val text = when {
            owed is Ft8Message.Acknowledge && owed.kind == Ft8Message.Acknowledge.Kind.RR73 &&
                owed.to.equals(current.partner, ignoreCase = true) -> {
                // The partner has moved on to closing. The station before them, if
                // they showed they missed our RR73, is next after this contact,
                // so ours goes out plain.
                if (releaseTail()) return
                val next = callers.next(nowMs, heardIn = txParity?.other, except = current.partner) ?: return
                engine.dxpedition(current.partner, next.call, next.reportDb)
            }
            // They closed first (they answered our CQ with a report, so the RR73 was
            // theirs) and our 73 is all that is left: it goes as the RR73 half. A
            // WSJT-X station that has sent its own RR73 or 73 ignores a dual message
            // after it (m_sentFirst73 in mainwindow.cpp), so it is not logged twice.
            // (KC2XA then NA1XA went out one at a time once.)
            owed is Ft8Message.Acknowledge && owed.kind == Ft8Message.Acknowledge.Kind.SEVENTY_THREE &&
                current.theyClosed && owed.to.equals(current.partner, ignoreCase = true) -> {
                if (releaseTail()) return
                val next = callers.next(nowMs, heardIn = txParity?.other, except = current.partner) ?: return
                engine.dxpedition(current.partner, next.call, next.reportDb)
            }
            tailMissedClose && owed is Ft8Message.Report && !owed.roger &&
                owed.to.equals(current.partner, ignoreCase = true) ->
                combinedTail?.let { engine.dxpedition(it.partner, current.partner, current.reportSentDb ?: owed.reportDb) }
            else -> null
        } ?: return
        autoQueued = text
        queued = text
        onEvent?.invoke("combined: $text")
    }

    /**
     * Our DXpedition message went out in full. Its RR73 completes the contact it
     * closed - logged now, as for any RR73 of ours; a WSJT-X partner sends no 73
     * to it - and the station it sent a report to becomes the partner. A repeat for
     * a contact that missed our RR73 changes nothing but the queue.
     */
    private fun onDxpeditionSent(engine: QsoEngine, sent: Ft8Message.Dxpedition) {
        val current = qso ?: return
        if (current.partner.equals(sent.report.to, ignoreCase = true)) {
            // The repeat carried our RR73 to the tail again: nothing more is owed yet.
            tailMissedClose = false
            tailRepeat = null
            follow(engine.reportFor(current))
            return
        }
        if (!current.partner.equals(sent.closing.to, ignoreCase = true)) return
        val closing = current
        complete(closing)
        val caller = callers.take(sent.report.to)
        val opened = caller?.let { engine.startedByDxpedition(it, it.reportDb) } ?: Qso(
            partner = sent.report.to,
            reportSentDb = sent.report.reportDb,
            startedAtMs = closing.lastActivityMs,
            lastActivityMs = closing.lastActivityMs
        )
        val parity = caller?.heardIn?.other ?: txParity
        txParity?.let { lastPartner = closing.partner to it }
        combinedTail = closing
        tailMissedClose = false
        caller?.let {
            txClock.observe(it.call, it.dtSeconds)
            partnerReportDb = it.reportDb
        }
        qso = opened
        follow(engine.reportFor(opened))
        txParity = parity
        if (nextCaller.equals(opened.partner, ignoreCase = true)) nextCaller = null
        onEvent?.invoke("${closing.partner} closed by combined message; now working ${opened.partner}")
    }

    /**
     * Should we transmit right now, and with what?
     *
     * Pure decision, no side effects, so the caller can log it and so this can be
     * tested without an audio device. Returns null when nothing should happen.
     */
    fun decide(
        nowMs: Long,
        profile: StationProfile,
        dialHz: Long?,
        /** The dial is one the operator added, so the table check is waived. */
        customDial: Boolean = false
    ): String? {
        val slot = mode.slotStartMs(nowMs)

        // No partner, no parity, nothing to send. Stated here rather than left to
        // the scheduler so that the parity it is given is never a guess. While
        // calling CQ with no contact, the parity is the one CQ was started in.
        val parity = committedParity ?: run {
            lastVerdict = TxScheduler.Verdict.NOTHING_TO_SEND
            return null
        }

        // One transmission per slot. Without this, every audio chunk inside the
        // late-start window would start another one.
        if (lastTransmittedSlot == slot) return null
        if (transmitting) return null

        // We now start preparing at the slot boundary, half a second before the
        // signal is due - but the decodes of the slot that just ended (the
        // partner's) can arrive a little after the boundary, and what we send
        // depends on them. While receiving is running, wait for them, but only
        // briefly: they are either nearly here or never coming (audio lost), and
        // waiting longer only makes us late.
        val position = mode.positionInSlotMs(nowMs)
        val decoded = lastDecodedSlotMs
        val receiving = decoded != null && decoded >= slot - 3 * mode.slotMs
        if (receiving && decoded!! < slot - mode.slotMs && position < mode.decodeWaitMs) {
            return null
        }

        // An ending held for the partner's slot whose decodes never came: our next
        // slot is here, so go ahead with it rather than send another repeat.
        val ending = pendingEnding
        val after = pendingAfterSlot
        if (ending != null && after != null && slot > after + mode.slotMs) {
            settle(ending)
            return null
        }

        val decision = scheduler.decide(
            TxScheduler.Request(
                message = nextMessage,
                armed = armed && profile.canTransmit,
                txParity = parity,
                dialHz = dialHz,
                frequencyOverride = customDial,
                leadMs = mode.nominalTxStartMs
            ),
            nowMs
        )
        lastVerdict = decision.verdict
        if (!decision.mayTransmit) return null

        lastTransmittedSlot = slot
        lastSent = decision.message
        lastSentFreeText = text?.let { it.text == decision.message && it.freeText } == true
        return decision.message
    }

    /** Called when a transmission starts and finishes, for the UI and the engine. */
    fun onTransmitStarted() {
        transmitting = true
        pickedSinceSend = false
        answeredSinceSend = false
    }

    /**
     * @param transmitted keyed and ended without a fault (see [TxSession.startHoldTransmit]).
     */
    fun onTransmitFinished(engine: QsoEngine, transmitted: Boolean = true) {
        transmitting = false
        // Stopped to start again with the operator's new pick ([pickRetargets]). Not
        // a transmission that went unanswered, and not a close that went out: none of
        // the bookkeeping below applies. Freeing the slot lets [decide] start the new
        // message in it, still inside the late-start window.
        if (restartRequested) {
            restartRequested = false
            letCurrentFinish = false
            lastTransmittedSlot = null
            return
        }
        // Sent in full: keyed without a fault, and not cut short - Halt and every
        // other disarm except the operator's ARMED button stop it, and clear
        // mayContinueTransmitting before this runs.
        val clean = transmitted && mayContinueTransmitting
        letCurrentFinish = false
        // Queued text: not part of the contact, so it neither answers anything nor
        // counts as an unanswered try. Cut short, it stays queued. Sent in full inside
        // a contact or CQ run, it is done and the automatic sequence has the next slot.
        // Sent on its own, there is nothing left to say: TX disarms, as search and
        // pounce does after a contact, and the text stays queued so ARM sends it again.
        val sentText = text
        if (sentText != null && lastSent == sentText.text) {
            if (clean) {
                if (qso == null && !callingCq) {
                    textSent = true
                    disarm("Text sent")
                } else {
                    cancelText()
                }
            }
            return
        }
        // Our RR73/73 has gone out. A late start truncated at the hard stop still
        // counts: that is the designed behaviour. A close cut short by Halt or an SWR
        // abort is the operator's call ("Log anyway"), never the app's.
        val sent = lastSent?.let(Ft8Message::parse)
        // Then counted like any transmission, against the contact it leaves in [qso].
        if (clean && sent is Ft8Message.Dxpedition) onDxpeditionSent(engine, sent)
        // Our CQ went out and nobody has answered yet. Before CQ existed a
        // transmission always had a contact behind it, and the code below took a
        // missing one as "gave up".
        if (qso == null) {
            if (transmitted && callingCq && sent is Ft8Message.Cq) {
                unansweredCqs += 1
                // Not yet: a caller may answer this very CQ in the next slot.
                val stopAfter = cqStopLimit()
                if (stopAfter > 0 && unansweredCqs >= stopAfter) holdEnding(Ending.CQ_UNANSWERED)
            }
            return
        }
        // Our report has been on the air, so a contact that goes no further can still
        // be logged by hand. Keyed counts, cut short or not: FT8
        // decodes with symbols missing, so whether it got through is the operator's call.
        qso?.let { q ->
            val reportTo = when (sent) {
                is Ft8Message.Report -> sent.to
                // A contest exchange is the report there.
                is Ft8Message.Contest -> sent.to
                is Ft8Message.Dxpedition -> sent.report.to
                else -> null
            }
            if (transmitted && !q.ourReportAired && reportTo.equals(q.partner, ignoreCase = true)) {
                qso = q.copy(ourReportAired = true)
            }
        }
        if (clean && sent is Ft8Message.Acknowledge && sent.closes) {
            qso = qso?.copy(closeAired = true)
            complete(qso)
        }
        // Our 73 has gone out: that is the last word, and the contact is over. Without
        // this the repeat-if-unanswered rule below kept it queued and sent 73 again in
        // our next slot (on air, VE2XAA: the operator had to Halt). A 73
        // cut short by Halt keeps the partner, paused, for the operator to decide.
        if (clean && sent is Ft8Message.Acknowledge &&
            sent.kind == Ft8Message.Acknowledge.Kind.SEVENTY_THREE && !pickedSinceSend
        ) {
            contactOver()
            return
        }
        // Nothing came back in the slot we transmitted in - we were deaf in it. The
        // retry count only advances when a slot of our own parity passes with no
        // answer, which onDecodes resets the moment the partner is heard.
        // The message stays QUEUED so the next slot of our parity repeats it. A
        // transmission that was not decoded is the normal case on a real band, not a
        // failure: without the repeat, one missed message ends the contact and we go
        // silent while the other station waits. Found by the exchange harness, which
        // could not complete a contact whose first transmission was lost.
        //
        // It is cleared when the partner answers (onDecodes replaces it) or when we
        // give up below.
        val unanswered = engine.onUnanswered(qso)
        // CQ PREFERENCES: a CQ run moves on after this many missed
        // replies, silent or repeated. Not while the operator's pick is on the air:
        // that is a manual choice. Zero is off, and the rules below run as before it.
        val missLimit = if (callingCq && pinned == null) cqMissLimit().coerceIn(0, MAX_CQ_MISS_LIMIT) else 0
        // The last try has gone out. Whether it was answered is known only once
        // their next slot is decoded, so the ending waits for that ([pendingEnding]).
        when (unanswered.reason) {
            // Our RR73 went out the allowed number of times. The contact is complete
            // and logged; if they stay quiet it ends without calling it a failure.
            QsoEngine.Reason.CLOSED_UNANSWERED -> holdEnding(Ending.CLOSED_UNANSWERED)
            // The engine's own limit, in a run with CQ PREFERENCES on: the run carries
            // on rather than disarming.
            QsoEngine.Reason.GAVE_UP -> holdEnding(if (missLimit > 0) Ending.NO_PROGRESS else Ending.GAVE_UP)
            else -> {
                qso = unanswered.qso
                // A CQ run: a partner that has not answered two of our messages is
                // dropped, for the next caller or our CQ (two tries at most in a CQ
                // run). Heard again later, they are answered
                // ([dropped]). With CQ PREFERENCES on, [countMiss] does this job.
                val q = qso
                if (missLimit == 0 && callingCq && q != null && !q.loggable && q.unansweredTransmissions >= CQ_RUN_TRIES) {
                    holdEnding(Ending.GAVE_WAY)
                }
            }
        }
        if (missLimit > 0 && clean) countMiss(sent, missLimit)
    }

    /**
     * The radio moved. Disarm, and clear the partner slot and any CQ run.
     *
     * Rule 3 lists a band change as a disarming event, and it is the right call: the
     * operator changing frequency mid-session has stopped doing whatever they were
     * doing, and a queued message for the old band is not something to transmit on
     * the new one.
     *
     * **Armed or not**: a partner picked on 20 m and left in the
     * slot while disarmed would otherwise be called on 17 m by the next ARM.
     */
    fun onFrequencyChanged() {
        // An armed one is in the disarm line already.
        if (!armed) qso?.let { onEvent?.invoke("partner cleared: Frequency changed (partner ${it.partner})") }
        disarm("Frequency changed")
        clearPartner()
        stopCq()
        cancelText()
    }

    /** Who transmitted [message], or null for a message with no sender we can read. */
    private fun senderOf(message: Ft8Message): String? = when (message) {
        is Ft8Message.Cq -> message.caller
        is Ft8Message.Reply -> message.from
        is Ft8Message.Report -> message.from
        is Ft8Message.Acknowledge -> message.from
        is Ft8Message.Dxpedition -> message.from
        is Ft8Message.Contest -> message.from
        is Ft8Message.Unknown -> null
    }

    /** Who [message] is addressed to, or null for a CQ or anything unaddressed. */
    private fun addresseeOf(message: Ft8Message): String? = when (message) {
        is Ft8Message.Reply -> message.to
        is Ft8Message.Report -> message.to
        is Ft8Message.Acknowledge -> message.to
        is Ft8Message.Contest -> message.to
        else -> null
    }

    companion object {
        /**
         * A station waiting in a CQ run is dropped once it has been missed in this
         * many of the slots it calls in: two, so 60 s in FT8, counted on the slot we
         * expected to hear them in.
         */
        const val CALLER_MISSED_CYCLES = 2L

        /**
         * In a CQ run a partner gets this many unanswered messages before being
         * dropped: two. Search and pounce keeps the engine's own retry limits.
         */
        const val CQ_RUN_TRIES = 2

        /**
         * Settings › CQ PREFERENCES: move on after this many missed replies in a CQ
         * run: default 2, choices off and 1 to [MAX_CQ_MISS_LIMIT]. An unattended run,
         * replayed at 2, lost no contact and gave back about twelve of our slots.
         */
        const val DEFAULT_CQ_MISS_LIMIT = 2

        /** The highest choice offered. */
        const val MAX_CQ_MISS_LIMIT = 5

        /**
         * How long a dropped contact is kept for a late answer: five minutes.
         * A chosen default.
         */
        const val DROPPED_KEPT_MS = 5 * 60_000L

        /**
         * Our report of a decode the spectrum could not measure (signal at or below the
         * noise in its band): the bottom of the scale, -24 dB, as for a listed reply
         * (`LiveRxPipeline.LISTED_REPORT_DB`).
         */
        const val UNMEASURED_REPORT_DB = -24

        /** How many logged contacts [isLogged] remembers: far more than an hour of a CQ run. */
        const val LOGGED_KEYS_KEPT = 64

        /** What identifies one contact: the same partner and start time is the same contact. */
        fun keyOf(contact: Qso): Pair<String, Long> = contact.partner.uppercase() to contact.startedAtMs

        /** How long before our slot the audio output is started on silence. A chosen default. */
        const val PREWARM_LEAD_MS = 1_000L

        /**
         * How far before the late-start limit a pick can still restart our own
         * transmission: stopping, unkeying, re-opening the audio and keying again
         * take time, and a restart that lands past the limit sends nothing at all.
         * A chosen default, not measured; the log's timing line for the
         * restarted transmission shows what it actually cost.
         */
        const val RESTART_MARGIN_MS = 1_000L

        /**
         * CQs with no answer before CQ stops and TX disarms, by default: ten, about
         * five minutes of FT8 slots (2.5 of FT4). A chosen default - a CQ
         * left running on an unattended phone should not run forever. The operator
         * chooses from [CQ_STOP_CHOICES].
         */
        const val CQ_LIMIT = 10

        /** The Settings bar: 0 is Off (never stop). Chosen defaults. */
        val CQ_STOP_CHOICES = listOf(0, 5, 10, 20, 40)
    }
}
