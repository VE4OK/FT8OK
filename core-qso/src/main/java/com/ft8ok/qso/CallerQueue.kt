package com.ft8ok.qso

import com.ft8ok.model.Ft8Message
import com.ft8ok.model.SlotParity

/**
 * Stations calling us during a CQ run while we work someone else.
 *
 * **Fresh callers first, then first heard first.** A caller heard within
 * [freshWithinMs] (their latest slot) goes ahead of one heard earlier; among each
 * group, first heard goes first. Freshness matters because a caller heard once a
 * cycle earlier, answered ahead of one calling every slot, often never comes back.
 * A tap puts a station first,
 * fresh or not. Only a CQ run fills it: in search and pounce nobody is answered
 * without the operator choosing them.
 *
 * A station is dropped when it is taken, when it is heard sending to someone else or
 * calling CQ, or when it has not been heard for [staleAfterMs] - a caller that has
 * stopped calling is not waiting any more.
 */
class CallerQueue(
    private val staleAfterMs: Long,
    /** Heard this recently counts as fresh. The default makes every caller fresh. */
    private val freshWithinMs: Long = Long.MAX_VALUE
) {

    data class Caller(
        val call: String,
        /** The contact's TIME_ON if it is worked. */
        val firstHeardMs: Long,
        val lastHeardMs: Long,
        /** Their latest message to us: a grid, or a report. */
        val message: Ft8Message,
        /** Our latest report of them, dB. */
        val reportDb: Int,
        val dtSeconds: Double,
        /** The parity they transmit in; we answer in the other. */
        val heardIn: SlotParity
    )

    private val waiting = mutableListOf<Caller>()

    val callers: List<Caller> get() = waiting.toList()

    /**
     * Is [message] a station calling us that we could start a contact with: their
     * grid or a plain report, to [myCall], from a call we can name? Not an R-report:
     * that is a station already in a contact with us - usually one just closed that
     * missed our RR73 - and queueing it would work them twice.
     */
    fun isCallTo(myCall: String, message: Ft8Message): Boolean {
        val (to, from) = when (message) {
            is Ft8Message.Reply -> message.to to message.from
            is Ft8Message.Report -> if (message.roger) return false else message.to to message.from
            // A contest exchange to us, answering our contest CQ. Its
            // R-form is a contact already under way, like an R-report.
            is Ft8Message.Contest -> if (message.roger) return false else message.to to message.from
            else -> return false
        }
        return to.equals(myCall, ignoreCase = true) && from != Ft8Message.HASHED
    }

    /** [call] was heard calling us. A station already waiting keeps its place. */
    fun heard(
        call: String,
        message: Ft8Message,
        reportDb: Int,
        dtSeconds: Double,
        heardIn: SlotParity,
        nowMs: Long
    ) {
        val i = indexOf(call)
        if (i < 0) {
            waiting += Caller(call, nowMs, nowMs, message, reportDb, dtSeconds, heardIn)
        } else {
            waiting[i] = waiting[i].copy(
                lastHeardMs = nowMs, message = message, reportDb = reportDb, dtSeconds = dtSeconds, heardIn = heardIn
            )
        }
    }

    /** The operator chose [call] to go next. False if they are not waiting. */
    fun moveToFront(call: String): Boolean {
        val i = indexOf(call)
        if (i < 0) return false
        waiting.add(0, waiting.removeAt(i))
        chosen = waiting[0].call
        return true
    }

    /** The station the operator tapped: first, fresh or not, until taken. */
    private var chosen: String? = null

    /**
     * Who goes next, without taking them, among those still calling and - when
     * [heardIn] is given - transmitting in that parity: the one the operator tapped,
     * else the first heard of the fresh callers, else the first heard of the rest.
     * Stale callers are dropped.
     */
    fun next(nowMs: Long, heardIn: SlotParity? = null, except: String? = null): Caller? {
        waiting.removeAll { nowMs - it.lastHeardMs > staleAfterMs }
        val eligible = waiting.filter {
            (heardIn == null || it.heardIn == heardIn) && !it.call.equals(except, ignoreCase = true)
        }
        return eligible.firstOrNull { it.call.equals(chosen, ignoreCase = true) }
            ?: eligible.firstOrNull { nowMs - it.lastHeardMs <= freshWithinMs }
            ?: eligible.firstOrNull()
    }

    /** Take [call] off the queue, returning them; null if they were not on it. */
    fun take(call: String): Caller? {
        if (call.equals(chosen, ignoreCase = true)) chosen = null
        val i = indexOf(call)
        return if (i < 0) null else waiting.removeAt(i)
    }

    fun clear() {
        waiting.clear()
        chosen = null
    }

    private fun indexOf(call: String) = waiting.indexOfFirst { it.call.equals(call, ignoreCase = true) }
}
