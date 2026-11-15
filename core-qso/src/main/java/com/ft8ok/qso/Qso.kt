package com.ft8ok.qso

import com.ft8ok.model.ContestSetup

/**
 * One contact, as far as it has got.
 *
 * Deliberately a record of **what has actually been exchanged** rather than a
 * position in a script. An FT8 QSO does not reliably follow the textbook sequence:
 * messages are missed and repeated, both stations sometimes send the same step
 * twice, and either side may jump straight to RR73. Storing "we have their report,
 * they have ours, nobody has said 73 yet" survives all of that, where a step
 * counter has to guess what a repeat means.
 *
 * The one rule that matters for the log: a contact is only complete when **both
 * reports have been exchanged and the contact has been closed**.
 */
data class Qso(
    /** The other station. Never [com.ft8ok.model.Ft8Message.HASHED]. */
    val partner: String,

    /** Their grid, if they have sent one. Not every QSO carries it. */
    val partnerGrid: String? = null,

    /** The report we have sent them, in dB. Null until we send one. */
    val reportSentDb: Int? = null,

    /** The report they have sent us. Null until they do. */
    val reportReceivedDb: Int? = null,

    /** True once we have told them we have their report (the `R` form, or RRR/RR73). */
    val weAcknowledged: Boolean = false,

    /** True once they have told us they have ours. */
    val theyAcknowledged: Boolean = false,

    /** True once either side has sent a closing message (RR73 or 73). */
    val closed: Boolean = false,

    /**
     * True once **they** have sent RRR, RR73 or 73 to us: all that is left for us to
     * say is 73, never another RR73.
     */
    val theyClosed: Boolean = false,

    /** Corrected-time epoch of the first message of this contact: ADIF TIME_ON. */
    val startedAtMs: Long,

    /** Corrected-time epoch of the last message either way: ADIF TIME_OFF. */
    val lastActivityMs: Long,

    /** How many times we have sent without hearing anything back. */
    val unansweredTransmissions: Int = 0,

    /** True while the operator has halted this contact; the partner is kept. */
    val paused: Boolean = false,

    /**
     * True once a message carrying our report to them has actually been transmitted.
     * [reportSentDb] is set when the report is *queued*, a slot earlier, so it alone
     * cannot say whether they could have heard it. Set by the transmit path, not the
     * engine, which never knows what went out.
     */
    val ourReportAired: Boolean = false,

    /**
     * True once our RR73 or 73 has actually been transmitted. [closed] is set when it is
     * *queued*, so it cannot say whether it still has to go. Set by the transmit path.
     */
    val closeAired: Boolean = false,

    /**
     * The contest format this contact was last answered in, or null
     * for standard messages. Follows contest mode at every step;
     * kept so the log knows which contest fields apply.
     */
    val contest: ContestSetup? = null,

    /** Our contest exchange as sent (`2A EMA`, `FN25`): in a contest, what our report is elsewhere. */
    val exchangeSent: String? = null,

    /** Their contest exchange as received: in a contest, what their report is elsewhere. */
    val exchangeReceived: String? = null
) {

    /**
     * Have both stations exchanged reports?
     *
     * The precondition for logging. A contact where only one report got through is
     * not a contact, however friendly the 73 was. In a contest the exchange is the
     * report, and either counts.
     */
    val reportsExchanged: Boolean get() = sentSomething && receivedSomething

    /** Our report or exchange has been queued for them. */
    private val sentSomething: Boolean get() = reportSentDb != null || exchangeSent != null

    /** Their report or exchange has been heard. */
    private val receivedSomething: Boolean get() = reportReceivedDb != null || exchangeReceived != null

    /**
     * Is this contact complete enough to log automatically?
     *
     * Both reports, and a close. An incomplete contact is never logged
     * automatically; the operator can log it by hand. So this being false is not an error — it is the state in which the operator decides.
     */
    val loggable: Boolean get() = reportsExchanged && closed

    /**
     * Have both reports been on the air - theirs heard by us, ours transmitted or
     * acknowledged by them? What the operator may log by hand when no close came.
     * Stricter than
     * [reportsExchanged], which counts our report as soon as it is queued.
     */
    val reportsOnAir: Boolean
        get() = receivedSomething && sentSomething && (ourReportAired || theyAcknowledged)

    /** What the operator sees. Derived, never stored, so it cannot contradict the facts. */
    val stage: Stage
        get() = when {
            paused -> Stage.PAUSED
            loggable -> Stage.COMPLETE
            closed -> Stage.CLOSING
            reportsExchanged -> Stage.CONFIRMING
            sentSomething || receivedSomething -> Stage.REPORTING
            else -> Stage.CALLING
        }

    enum class Stage {
        /** Called them, or answered their CQ; no reports yet. */
        CALLING,

        /** A report has gone one way. */
        REPORTING,

        /** Both reports exchanged; waiting on the close. */
        CONFIRMING,

        /** A closing message has been sent or received. */
        CLOSING,

        /** Both reports and a close: ready to log. */
        COMPLETE,

        /** Halted by the operator. The partner is kept, TX is disarmed. */
        PAUSED
    }
}
