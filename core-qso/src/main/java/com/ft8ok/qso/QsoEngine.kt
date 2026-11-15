package com.ft8ok.qso

import com.ft8ok.model.ContestSetup
import com.ft8ok.model.Ft8Message

/**
 * What to say next (docs/ARCHITECTURE.md).
 *
 * **One primitive: the partner slot.** There is no "S&P mode" versus "CQ mode" —
 * Search & Pounce means the operator fills the partner slot, calling CQ means the
 * app proposes into the same slot. Both arrive here as the same thing, which is why
 * this class has no notion of which it is.
 *
 * Pure: messages and a clock in, a decision out. It transmits nothing, keys nothing
 * and knows nothing about time slots — the TX scheduler decides *whether* and
 * *when* a reply may be sent, including the late-start rule (`Mode.mayStartTxAt`).
 * Keeping those apart means the whole conversation can be tested in milliseconds,
 * and the one component able to key a transmitter stays small enough to audit.
 */
class QsoEngine(
    /** Our callsign, as sent. */
    val myCall: String,
    /** Our grid, four characters, as sent in a reply to a CQ. */
    private val myGrid: String,
    /** Give up calling a station that has never answered after this many transmissions. */
    val callRetryLimit: Int = DEFAULT_CALL_RETRY_LIMIT,
    /** Give up on a contact already under way after this many unanswered transmissions. */
    val retryLimit: Int = DEFAULT_RETRY_LIMIT,
    /** Stop repeating our RR73 after this many go unanswered: the contact is already complete. */
    val closeRepeatLimit: Int = DEFAULT_CLOSE_REPEAT_LIMIT,
    /**
     * Contest mode, or null when it is off. Every step follows it,
     * a contact in progress included, so switching it mid-contact changes the next
     * message (a contact stuck in the wrong format could not be
     * finished). [Qso.contest] records the format a contact was last answered in.
     */
    val contest: ContestSetup? = null
) {

    /**
     * The contest whose exchange replaces the report, or null: off, or a Report+grid
     * contest, whose contacts follow the standard sequence. Every
     * message the engine builds reads this; the CQ word, combined messages and the
     * contest recorded on the contact read [contest].
     */
    private val exchangeContest: ContestSetup? get() = contest?.takeIf { it.sendsExchange }

    /** What the engine decided, and why. The reason is shown and logged. */
    data class Decision(
        val qso: Qso?,
        /** The message to transmit, or null to stay silent. */
        val reply: String?,
        val reason: Reason
    )

    enum class Reason {
        /** We answered a CQ. */
        ANSWERED_CQ,

        /** They called us; we replied with their report. */
        ANSWERED_CALL,

        /** We sent our report, or repeated it. */
        SENT_REPORT,

        /** We acknowledged their report and sent ours. */
        SENT_ROGER_REPORT,

        /** We acknowledged and closed. */
        SENT_CLOSE,

        /** They closed and the contact is complete. Nothing more to send. */
        COMPLETE,

        /** Nothing to do: the message was not for us, or not part of this contact. */
        IGNORED,

        /** They are not answering and we have reached the retry limit. */
        GAVE_UP,

        /**
         * Our RR73 has gone unanswered [closeRepeatLimit] times. Not a failure: both
         * reports were exchanged and the contact is logged. Usually it means they
         * heard our RR73 and simply did not send a 73.
         */
        CLOSED_UNANSWERED,


        /**
         * Our partner sent an R-report to someone else - they are part-way through a
         * contact with that station, so calling them now only adds QRM. An incomplete
         * contact is returned unchanged, for the caller to stop sending to.
         * A complete contact ends as soon as the partner is heard sending anything to
         * anyone else, or calling CQ.
         */
        PARTNER_MOVED_ON,

        /** The operator has halted this contact. */
        PAUSED,

        /** The operator picked which message of the contact to send next ([choose]). */
        CHOSEN
    }

    /**
     * The messages of a contact, in order, for the operator to pick from when the
     * automatic sequence is wrong for what is on the air.
     *
     * Three, not five. Which form of a report or a close
     * is right follows from the contact, as the WSJT-X guide's standard exchange has
     * it: the R means "I have your report", so [REPORT] carries it exactly when we
     * have theirs; RR73 acknowledges their R-report and 73 answers their close, so
     * [CLOSE] is 73 exactly when they have closed.
     */
    enum class Step { GRID, REPORT, CLOSE }

    /**
     * Start a contact with a station, whether the operator tapped them or the app
     * proposed them. [heardAtMs] becomes the contact's TIME_ON.
     *
     * @param theirCq their CQ, when we are answering one, so their grid is kept.
     */
    fun begin(partner: String, heardAtMs: Long, theirCq: Ft8Message.Cq? = null): Decision {
        if (partner == Ft8Message.HASHED || partner.isBlank()) {
            // A hashed callsign is a station we cannot name, so it is not one we can
            // call. Refusing here keeps every path above from having to remember.
            return Decision(null, null, Reason.IGNORED)
        }
        val qso = Qso(
            partner = partner,
            partnerGrid = theirCq?.grid,
            startedAtMs = heardAtMs,
            lastActivityMs = heardAtMs,
            unansweredTransmissions = 1,
            contest = contest
        )
        // In a contest the caller opens with the exchange, skipping the grid message
        // (WSJT-X: "start QSO with Tx2"). In the grid contests that is the same text.
        // [Qso.exchangeSent] waits for their answer, as a report does: until then they
        // have answered nothing, and the shorter calling patience applies.
        val reply = exchangeContest?.let { exchangeMessage(partner, it, roger = false) } ?: "$partner $myCall $myGrid"
        return Decision(qso, reply, Reason.ANSWERED_CQ)
    }

    /**
     * Feed one decoded message.
     *
     * @param qso the contact in progress, or null if there is none.
     * @param ourReportDb what we measure *them* at — the number that goes in their
     *   log. Taken as a parameter rather than stored, because it is re-measured
     *   every time we hear them and the freshest value is the honest one.
     * @return what to do. [Decision.reply] is a suggestion: the TX scheduler may
     *   still refuse it for being too late in the slot, and refusing costs nothing
     *   but the transmission.
     */
    fun onMessage(
        qso: Qso?,
        message: Ft8Message,
        nowMs: Long,
        ourReportDb: Int
    ): Decision {
        if (message is Ft8Message.Dxpedition) {
            val part = focus(message)
            val decision = onMessage(qso, part, nowMs, ourReportDb)
            // Its RR73 to us ends the contact with nothing more to say: WSJT-X answers
            // a dual message's RR73 by logging and calling CQ, never with 73
            // (mainwindow.cpp, "dual Fox style message"). Our 73
            // would only cost the sender a decode while it works the next station.
            return if (part === message.closing && decision.reason == Reason.COMPLETE) {
                decision.copy(reply = null)
            } else {
                decision
            }
        }
        if (qso?.paused == true) return Decision(qso, null, Reason.PAUSED)

        // Someone calling us when we have no contact in progress starts one. This is
        // the "they answered our CQ" path.
        if (qso == null) return onUnsolicited(message, nowMs, ourReportDb)

        if (!addressesUs(message, qso.partner)) {
            // A complete contact is over once the partner talks to anyone else.
            if (qso.loggable && movedOnFrom(message, qso.partner)) {
                return Decision(null, null, Reason.PARTNER_MOVED_ON)
            }
            // An incomplete one stops only when the partner sends an R-report to
            // another station: they are mid-contact with them. A CQ, a grid, a plain report or an
            // RR73/73 to someone else does not stop us - they may well answer us next,
            // and an earlier rule that paused on all of those refused to call a
            // station that was calling CQ. Say why; the caller stops sending (search and pounce disarms with the message kept, a CQ run drops them).
            // The contact itself is returned unchanged and live, not paused, so that
            // if they answer us next it carries on from what they say.
            if (!qso.loggable && rogerToSomeoneElse(message, qso.partner)) {
                return Decision(qso, null, Reason.PARTNER_MOVED_ON)
            }
            return Decision(qso, null, Reason.IGNORED)
        }

        val heard = qso.copy(lastActivityMs = nowMs, unansweredTransmissions = 0, contest = contest)

        exchangeContest?.let { c -> contestStep(heard, c, message, ourReportDb)?.let { return it } }

        return when (message) {
            is Ft8Message.Reply -> {
                // They answered with their grid: send their report.
                // A call with no grid keeps any grid already known for them.
                val next = heard.copy(partnerGrid = message.grid ?: heard.partnerGrid, reportSentDb = ourReportDb)
                Decision(next, report(qso.partner, ourReportDb, roger = false), Reason.SENT_REPORT)
            }

            is Ft8Message.Report -> {
                val next = heard.copy(
                    reportReceivedDb = message.reportDb,
                    theyAcknowledged = heard.theyAcknowledged || message.roger,
                    // Their R-form tells us they have our report, which only makes
                    // sense if we sent one; believe the air over our own bookkeeping,
                    // since a repeat we never heard is likelier than them inventing it.
                    reportSentDb = heard.reportSentDb ?: if (message.roger) ourReportDb else null
                )
                if (message.roger) {
                    // They have ours and sent theirs: acknowledge and close.
                    Decision(
                        next.copy(weAcknowledged = true, closed = true),
                        "${qso.partner} $myCall RR73",
                        Reason.SENT_CLOSE
                    )
                } else {
                    // We have theirs; send ours with the R, which says both.
                    Decision(
                        next.copy(weAcknowledged = true, reportSentDb = ourReportDb),
                        report(qso.partner, ourReportDb, roger = true),
                        Reason.SENT_ROGER_REPORT
                    )
                }
            }

            is Ft8Message.Acknowledge -> {
                val next = heard.copy(
                    theyAcknowledged = true,
                    closed = heard.closed || message.closes,
                    theyClosed = true
                )
                if (message.closes) {
                    // They said goodbye. Say it back only if we have not already
                    // closed, so a QSO cannot ping-pong 73s forever.
                    val reply = if (heard.closed) null else "${qso.partner} $myCall 73"
                    Decision(next.copy(closed = true), reply, Reason.COMPLETE)
                } else {
                    // RRR: they have acknowledged, so all that is left is the goodbye.
                    // 73, not RR73, whatever roger message was sent, and the shorter message keeps
                    // room for multi-QSO-per-slot schemes later. Complete on receipt,
                    // like their RR73.
                    //
                    // Answered with 73 even when we have already closed: a repeated
                    // RRR means our close never reached them. Answering it with
                    // nothing wiped a 73 that was queued and not yet sent (on air
                    // The screen said 73 was next, and it never went).
                    // This cannot ping-pong: once our 73 is on the air the contact
                    // is cleared, and a later RRR finds no contact to answer.
                    Decision(
                        next.copy(weAcknowledged = true, closed = true),
                        "${qso.partner} $myCall 73",
                        Reason.COMPLETE
                    )
                }
            }

            is Ft8Message.Cq -> Decision(heard, null, Reason.IGNORED)
            is Ft8Message.Unknown -> Decision(heard, null, Reason.IGNORED)
            // A standard contact sends only standard messages, so a contest exchange is
            // not answered. A contest contact took it above ([contestStep]).
            is Ft8Message.Contest -> Decision(qso, null, Reason.IGNORED)
            // Not reached: [focus] has already split it at the top.
            is Ft8Message.Dxpedition -> Decision(heard, null, Reason.IGNORED)
        }
    }

    /**
     * A contest contact's answer to [message] from its partner,
     * following WSJT-X's auto-sequence (`processMessage`). Null
     * where the standard sequence applies unchanged: their RRR/RR73/73, and their
     * report with the R (both answered with RR73, as WSJT-X does in its contest modes).
     *
     * - Their exchange, or their grid in a grid contest: we have theirs, so R and ours.
     * - Their exchange with the R: they have ours too, so RR73.
     * - A plain report or a grid where no exchange was expected (a station not in the
     *   contest): sent our exchange, with the R once we hold something of theirs.
     *   WSJT-X's Field Day mode sends the exchange without the R after a report; with
     *   their report in hand the R is true, and it lets the contact finish.
     * - An exchange in another contest's format is not answered.
     */
    private fun contestStep(heard: Qso, c: ContestSetup, message: Ft8Message, ourReportDb: Int): Decision? {
        val p = heard.partner
        return when (message) {
            is Ft8Message.Contest -> {
                if (!c.matches(message)) return Decision(heard, null, Reason.IGNORED)
                val got = heard.copy(
                    exchangeReceived = message.exchange,
                    partnerGrid = message.grid ?: heard.partnerGrid
                )
                if (message.roger) {
                    // They have ours: believe the air, as for an R-report.
                    Decision(
                        got.copy(
                            exchangeSent = got.exchangeSent ?: c.exchange,
                            theyAcknowledged = true,
                            weAcknowledged = true,
                            closed = true
                        ),
                        "$p $myCall RR73",
                        Reason.SENT_CLOSE
                    )
                } else {
                    Decision(
                        got.copy(exchangeSent = c.exchange, weAcknowledged = true),
                        exchangeMessage(p, c, roger = true),
                        Reason.SENT_ROGER_REPORT
                    )
                }
            }

            is Ft8Message.Reply -> {
                // In a grid contest their grid is their exchange; elsewhere it is a
                // station calling us the ordinary way, and gets our exchange.
                val theirs = message.grid?.takeIf { c.kind == ContestSetup.Kind.GRID }
                val got = heard.copy(
                    partnerGrid = message.grid ?: heard.partnerGrid,
                    exchangeReceived = theirs ?: heard.exchangeReceived
                )
                val roger = got.exchangeReceived != null || got.reportReceivedDb != null
                Decision(
                    got.copy(exchangeSent = c.exchange, weAcknowledged = got.weAcknowledged || roger),
                    exchangeMessage(p, c, roger),
                    if (roger) Reason.SENT_ROGER_REPORT else Reason.SENT_REPORT
                )
            }

            is Ft8Message.Report -> {
                if (message.roger) {
                    // Answered by the standard sequence with RR73. Their R says they
                    // have what we sent, which in a contest was the exchange.
                    return Decision(
                        heard.copy(
                            reportReceivedDb = message.reportDb,
                            exchangeSent = heard.exchangeSent ?: c.exchange,
                            theyAcknowledged = true,
                            weAcknowledged = true,
                            closed = true
                        ),
                        "$p $myCall RR73",
                        Reason.SENT_CLOSE
                    )
                }
                Decision(
                    heard.copy(reportReceivedDb = message.reportDb, exchangeSent = c.exchange, weAcknowledged = true),
                    exchangeMessage(p, c, roger = true),
                    Reason.SENT_ROGER_REPORT
                )
            }

            else -> null
        }
    }

    /** Our contest exchange to [to]: `W9XYZ VE4OK 2A EMA`, or with the R, `W9XYZ VE4OK R 2A EMA`. */
    private fun exchangeMessage(to: String, c: ContestSetup, roger: Boolean): String =
        "$to $myCall ${if (roger) "R " else ""}${c.exchange}"

    /**
     * Nothing in progress and a message arrives. Only one shape matters: somebody
     * calling us.
     */
    private fun onUnsolicited(message: Ft8Message, nowMs: Long, ourReportDb: Int): Decision {
        val to = addressee(message) ?: return Decision(null, null, Reason.IGNORED)
        if (!to.equals(myCall, ignoreCase = true)) return Decision(null, null, Reason.IGNORED)

        val from = sender(message) ?: return Decision(null, null, Reason.IGNORED)
        if (from == Ft8Message.HASHED) return Decision(null, null, Reason.IGNORED)

        val started = Qso(
            partner = from,
            startedAtMs = nowMs,
            lastActivityMs = nowMs,
            contest = contest
        )

        // In a contest, whatever they called with - an exchange, a grid, a report - is
        // answered as the contest sequence answers it mid-contact (WSJT-X answers a
        // caller with its exchange, R'd once it has theirs). An R-form from a station
        // we have no contact with is answered as a fresh exchange: there is nothing of
        // ours on record for them to have acknowledged.
        exchangeContest?.let { c ->
            val asCall = when {
                message is Ft8Message.Contest && message.roger -> message.copy(roger = false)
                message is Ft8Message.Report && message.roger -> message.copy(roger = false)
                else -> message
            }
            if (asCall !is Ft8Message.Reply && asCall !is Ft8Message.Report && asCall !is Ft8Message.Contest) {
                return Decision(null, null, Reason.IGNORED)
            }
            val decision = contestStep(started, c, asCall, ourReportDb) ?: return Decision(null, null, Reason.IGNORED)
            if (decision.qso == null || decision.reply == null) return Decision(null, null, Reason.IGNORED)
            return decision.copy(reason = Reason.ANSWERED_CALL)
        }

        return when (message) {
            is Ft8Message.Reply -> Decision(
                started.copy(partnerGrid = message.grid, reportSentDb = ourReportDb),
                report(from, ourReportDb, roger = false),
                Reason.ANSWERED_CALL
            )

            is Ft8Message.Report -> Decision(
                started.copy(
                    reportReceivedDb = message.reportDb,
                    reportSentDb = ourReportDb,
                    weAcknowledged = true,
                    theyAcknowledged = message.roger
                ),
                report(from, ourReportDb, roger = true),
                Reason.SENT_ROGER_REPORT
            )

            // A bare acknowledgement with no contact in progress is the tail of
            // something we did not hear the start of. There is nothing to log and
            // nothing useful to say.
            else -> Decision(null, null, Reason.IGNORED)
        }
    }

    /**
     * Called when a transmission went out and nothing came back.
     *
     * Returns a decision whose [Decision.qso] is null once the retry limit is
     * reached — the station goes on the failed-call list and the slot frees up for
     * someone who is answering.
     */
    fun onUnanswered(qso: Qso?): Decision {
        if (qso == null) return Decision(null, null, Reason.IGNORED)
        if (qso.paused) return Decision(qso, null, Reason.PAUSED)

        val tries = qso.unansweredTransmissions + 1
        // A contact that is already complete (both reports, and our RR73 has gone out)
        // needs no patience: repeat the close a little in case they missed it, then
        // stop. Its partner repeating their R-report resets the count, and the engine
        // answers that with RR73 again, so a lost RR73 is still recovered.
        if (qso.loggable && tries >= closeRepeatLimit) return Decision(null, null, Reason.CLOSED_UNANSWERED)
        // A station that has never answered gets less patience than one we are
        // already exchanging reports with.
        val limit = if (qso.stage == Qso.Stage.CALLING) callRetryLimit else retryLimit
        if (tries > limit) return Decision(null, null, Reason.GAVE_UP)
        return Decision(qso.copy(unansweredTransmissions = tries), null, Reason.IGNORED)
    }

    /**
     * Halt: the contact is kept and paused, never discarded.
     *
     * Rule 3 — Halt aborts the transmission, releases PTT and disarms; the partner
     * stays on screen so the operator can resume or log anyway. Losing the partner
     * would also lose the reports already exchanged.
     */
    fun halt(qso: Qso?): Decision {
        if (qso == null) return Decision(null, null, Reason.IGNORED)
        return Decision(qso.copy(paused = true), null, Reason.PAUSED)
    }

    /** Resume a halted contact. The next received message drives it on as before. */
    fun resume(qso: Qso?): Decision {
        if (qso == null) return Decision(null, null, Reason.IGNORED)
        return Decision(qso.copy(paused = false, unansweredTransmissions = 0), null, Reason.IGNORED)
    }

    /**
     * The message that calls CQ, with [word] after CQ when there is one: `CQ POTA
     * VE4OK FN25`. Still a standard message, so a station answering
     * it replies as to any CQ and the exchange is unchanged. The word must be one FT8
     * carries ([com.ft8ok.model.TxText.isCqWord]); the panel offers nothing else.
     */
    fun cq(word: String? = null): String {
        // In contest mode the contest's word, whatever the panel holds.
        val w = contest?.cqWord ?: word
        return if (w.isNullOrEmpty()) "CQ $myCall $myGrid" else "CQ $w $myCall $myGrid"
    }

    /**
     * The operator tapped an RRR, RR73 or 73 addressed to us, with no contact in
     * progress with that station - normally because our 73 went out and they did not
     * decode it, so they keep repeating. Answer with our 73.
     *
     * The contact this is the tail of was logged when it completed. The one returned
     * here carries no reports, so it is not loggable and cannot log a second time.
     * Before this, the tap fell through to a fresh call and queued our grid (on air
     * VA3XAA).
     */
    fun answerTail(message: Ft8Message.Acknowledge, nowMs: Long): Decision {
        if (!message.to.equals(myCall, ignoreCase = true) || message.from == Ft8Message.HASHED) {
            return Decision(null, null, Reason.IGNORED)
        }
        val qso = Qso(
            partner = message.from,
            startedAtMs = nowMs,
            lastActivityMs = nowMs,
            weAcknowledged = true,
            theyAcknowledged = true,
            closed = true,
            theyClosed = true
        )
        return Decision(qso, "${message.from} $myCall 73", Reason.CHOSEN)
    }

    /**
     * The operator picked which message to send next ([Step]). The contact's
     * bookkeeping follows the pick, so the automatic sequence carries on from there
     * when the partner answers. Picking also un-pauses a halted contact, since it is
     * a clear choice to go on with it; it does not arm (rule 3).
     *
     * @param ourReportDb our report of them; a report step without one is refused
     *   rather than made up.
     */
    fun choose(qso: Qso, step: Step, ourReportDb: Int?): Decision {
        val live = qso.copy(paused = false, unansweredTransmissions = 0, contest = contest)
        val p = qso.partner
        val c = exchangeContest
        return when (step) {
            Step.GRID -> Decision(live, "$p $myCall $myGrid", Reason.CHOSEN)
            // In a contest the report step is the exchange, with the R once we hold
            // theirs. It needs no measurement of them.
            Step.REPORT -> if (c != null) {
                val roger = qso.exchangeReceived != null || qso.reportReceivedDb != null
                Decision(
                    live.copy(exchangeSent = c.exchange, weAcknowledged = live.weAcknowledged || roger),
                    exchangeMessage(p, c, roger),
                    Reason.CHOSEN
                )
            } else ourReportDb?.let {
                // R exactly when we have their report: claiming it otherwise is false.
                val roger = qso.reportReceivedDb != null
                Decision(
                    live.copy(reportSentDb = it, weAcknowledged = live.weAcknowledged || roger),
                    report(p, it, roger),
                    Reason.CHOSEN
                )
            } ?: Decision(qso, null, Reason.IGNORED)
            Step.CLOSE -> Decision(
                live.copy(weAcknowledged = true, closed = true), "$p $myCall ${closeWord(qso)}", Reason.CHOSEN
            )
        }
    }

    /**
     * One transmission closing the contact with [closeTo] and opening the one with
     * [reportTo]: `K1ABC RR73; W9XYZ <VE4OK> -14`. Our call goes
     * only as a hash, so this is used only when it saves a slot. Null when either
     * call cannot go in one; send the ordinary messages then.
     */
    fun dxpedition(closeTo: String, reportTo: String, reportDb: Int): String? =
        // Never in contest mode: a contest exchange cannot ride in one.
        if (contest != null) null else Ft8Message.dxpeditionText(closeTo, reportTo, myCall, reportDb)

    /**
     * The contact with [caller] once our DXpedition message carrying their report has
     * gone out: our report sent (as carried, even), theirs and their grid if they
     * sent them, started when they were first heard calling.
     */
    fun startedByDxpedition(caller: CallerQueue.Caller, sentDb: Int): Qso {
        val theirs = caller.message as? Ft8Message.Report
        return Qso(
            partner = caller.call,
            partnerGrid = (caller.message as? Ft8Message.Reply)?.grid,
            reportSentDb = Ft8Message.dxpeditionReport(sentDb),
            reportReceivedDb = theirs?.reportDb,
            startedAtMs = caller.firstHeardMs,
            lastActivityMs = caller.lastHeardMs
        )
    }

    /**
     * What to repeat to a contact opened by a DXpedition message if they do not
     * answer it: the same report as an ordinary message, so a repeat carries our
     * call in full.
     */
    fun reportFor(qso: Qso): String? =
        qso.reportSentDb?.let { report(qso.partner, it, roger = qso.reportReceivedDb != null) }

    /** What [Step.CLOSE] sends: 73 once they have closed, RR73 before. */
    fun closeWord(qso: Qso): String = if (qso.theyClosed) "73" else "RR73"

    private fun report(to: String, db: Int, roger: Boolean): String {
        val prefix = if (roger) "R" else ""
        return "$to $myCall $prefix${"%+03d".format(db)}"
    }

    /**
     * A DXpedition message ([Ft8Message.Dxpedition]) as the one message it is for us:
     * its half addressed to us, or else its report half - the sender's contact in
     * progress with someone else, which is what the moved-on rules read. Any other
     * message is returned as it is.
     */
    fun focus(message: Ft8Message): Ft8Message =
        if (message is Ft8Message.Dxpedition) message.partFor(myCall) ?: message.report else message

    private fun addressesUs(message: Ft8Message, partner: String): Boolean {
        val to = addressee(message) ?: return false
        val from = sender(message) ?: return false
        // Must be from our partner AND to us. A message from them to someone else
        // means they have moved on, and one to us from someone else is a different
        // contact entirely.
        return from.equals(partner, ignoreCase = true) && to.equals(myCall, ignoreCase = true)
    }

    /**
     * Is [message] our [partner] talking to somebody else, or calling CQ? A hashed
     * addressee does not count: it could be us.
     */
    private fun movedOnFrom(message: Ft8Message, partner: String): Boolean {
        if (sender(message)?.equals(partner, ignoreCase = true) != true) return false
        if (message is Ft8Message.Cq) return true
        val to = addressee(message) ?: return false
        return to != Ft8Message.HASHED && !to.equals(myCall, ignoreCase = true)
    }

    /** Is [message] our [partner] sending an R-report to a station that is not us? */
    private fun rogerToSomeoneElse(message: Ft8Message, partner: String): Boolean =
        ((message is Ft8Message.Report && message.roger) ||
            (message is Ft8Message.Contest && message.roger)) && movedOnFrom(message, partner)

    private fun addressee(message: Ft8Message): String? = when (message) {
        is Ft8Message.Reply -> message.to
        is Ft8Message.Report -> message.to
        is Ft8Message.Acknowledge -> message.to
        is Ft8Message.Contest -> message.to
        else -> null
    }

    private fun sender(message: Ft8Message): String? = when (message) {
        is Ft8Message.Reply -> message.from
        is Ft8Message.Report -> message.from
        is Ft8Message.Acknowledge -> message.from
        is Ft8Message.Cq -> message.caller
        is Ft8Message.Contest -> message.from
        else -> null
    }

    companion object {
        /**
         * How many unanswered transmissions before giving up on a contact already
         * under way (we have heard from them at least once).
         *
         * A chosen default. Each one costs a 15 s slot of our own parity, so patience is
         * expensive: six tries is three minutes (one of our slots every 30 s) spent on
         * someone who may not be able to hear us at all.
         */
        const val DEFAULT_RETRY_LIMIT = 6

        /**
         * How many times to call a station that never answers: three, about 1.5
         * minutes: fewer tries, then disarm.
         */
        const val DEFAULT_CALL_RETRY_LIMIT = 3

        /**
         * How many RR73s to send when the partner goes quiet after our close: two.
         * At most two before the automatic sequence stops.
         */
        const val DEFAULT_CLOSE_REPEAT_LIMIT = 2
    }
}
