package com.ft8ok.logbook

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ft8ok.qso.Qso
import com.ft8ok.tx.AutoTx

/**
 * Contacts that got as far as both reports and then left the partner slot without
 * being logged - the RR73 never came, the operator tapped another row, a CQ run
 * dropped them. The Log tab offers each one for logging by hand.
 *
 * Without this, a tap on another row lost the contact: only the partner slot held it.
 *
 * In memory only, like the decode history: kept [KEEP_MS] from the contact's last
 * message and gone when the app closes. Never written anywhere; logging one is what
 * stores it.
 */
class NotLogged(private val keepMs: Long = KEEP_MS) {

    /**
     * One contact, with what was known when it left: the dial frequency then (a band
     * change ends the contact, and the radio is already on the new band by the time
     * anyone looks), and their grid from the exchange or from what they were heard
     * sending. [current] marks the contact still in the partner slot.
     */
    data class Entry(val qso: Qso, val dialHz: Long?, val grid: String?, val current: Boolean = false)

    /** Newest first. */
    var entries by mutableStateOf<List<Entry>>(emptyList())
        private set

    /** Keep [entry], replacing an earlier copy of the same contact. */
    fun add(entry: Entry, nowMs: Long) {
        val key = AutoTx.keyOf(entry.qso)
        entries = (listOf(entry) + entries.filter { AutoTx.keyOf(it.qso) != key })
            .filter { nowMs - it.qso.lastActivityMs <= keepMs }
            .take(MAX_ENTRIES)
    }

    /** The contact was logged, by hand or because it came back and completed. */
    fun remove(contact: Qso) {
        val key = AutoTx.keyOf(contact)
        entries = entries.filter { AutoTx.keyOf(it.qso) != key }
    }

    /** Drop what has aged out; called once a second with the rest of the screen. */
    fun prune(nowMs: Long) {
        if (entries.any { nowMs - it.qso.lastActivityMs > keepMs }) {
            entries = entries.filter { nowMs - it.qso.lastActivityMs <= keepMs }
        }
    }

    companion object {
        /** One hour from the contact's last message. A chosen default. */
        const val KEEP_MS = 60 * 60_000L

        /** A long CQ run's worth; older ones go first. A chosen default. */
        const val MAX_ENTRIES = 20
    }
}
