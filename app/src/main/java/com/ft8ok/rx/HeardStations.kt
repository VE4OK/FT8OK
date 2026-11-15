package com.ft8ok.rx

import com.ft8ok.dsp.SnrEstimator
import com.ft8ok.model.Ft8Message

/**
 * Every station heard this session, one record per station and band: its latest
 * decode, its latest grid, our report of it. What the Roster and the Map read.
 *
 * Kept apart from the feed because the feed is capped ([RxSession.FEED_LIMIT]) and
 * the Map's longest window is the whole session; one record per station is small
 * even after hours on a busy band. Pure, so it is tested on the JVM.
 */
class HeardStations(private val cap: Int = DEFAULT_CAP) {

    data class Station(
        val call: String,
        /** Their latest grid this session, from a CQ or a reply. */
        val grid: String?,
        /** Our report of their latest decode, or null until that slot was measured. */
        val report: Int?,
        /** End of the slot they were last heard in. */
        val lastHeardMs: Long,
        /** Their last message was a CQ: they are looking for a contact. */
        val calling: Boolean,
        /** Their latest decode, so a tap works the same as tapping it on Live. */
        val row: RxSession.DecodeRow
    )

    private val byCall = LinkedHashMap<String, Station>()

    val size: Int get() = byCall.size

    /** Take in rows, oldest first. Our own transmissions and hashed calls are skipped. */
    fun observe(rows: List<RxSession.DecodeRow>, slotMs: Long) {
        for (row in rows) {
            if (row.tx) continue
            val message = Ft8Message.parse(row.decode.message)
            val (call, grid) = when (message) {
                is Ft8Message.Cq -> message.caller to message.grid
                is Ft8Message.Reply -> message.from to message.grid
                is Ft8Message.Report -> message.from to null
                is Ft8Message.Acknowledge -> message.from to null
                is Ft8Message.Dxpedition -> message.from to null
                // Contest stations are heard like any other.
                is Ft8Message.Contest -> message.from to message.grid
                is Ft8Message.Unknown -> continue
            }
            if (call == Ft8Message.HASHED) continue
            // Per band: a station worked on 20 m and heard again on 17 m is two entries,
            // and each band's Roster shows its own.
            val key = "${row.band}/${call.uppercase()}"
            val heardMs = row.slotStartMs + slotMs
            val earlier = byCall[key]
            // Out-of-order input never moves a station back in time.
            if (earlier != null && earlier.lastHeardMs > heardMs) continue
            // Re-inserted so iteration order stays oldest-heard first, which is what
            // the cap trims from.
            byCall.remove(key)
            byCall[key] = Station(
                call = call,
                // A grid once heard is kept when the latest message carries none.
                grid = grid?.uppercase() ?: earlier?.grid,
                // A row whose report could not be measured keeps the last one that was.
                report = row.snrDb?.let(SnrEstimator::toReport) ?: earlier?.report,
                lastHeardMs = heardMs,
                calling = message is Ft8Message.Cq,
                row = row
            )
        }
        if (byCall.size > cap) {
            val drop = byCall.keys.take(byCall.size - cap)
            drop.forEach(byCall::remove)
        }
    }

    /**
     * Stations last heard after [sinceMs] become of unknown band: the radio was lost
     * then and came back on another band, so they may be either's.
     */
    fun unknownBandSince(sinceMs: Long) {
        val moved = byCall.entries.filter { it.value.lastHeardMs > sinceMs && it.value.row.band != null }
        for ((key, station) in moved) {
            byCall.remove(key)
            val unknownKey = "null/${station.call.uppercase()}"
            val other = byCall[unknownKey]
            if (other == null || other.lastHeardMs <= station.lastHeardMs) {
                byCall.remove(unknownKey)
                byCall[unknownKey] = station.copy(row = station.row.copy(band = null))
            }
        }
    }

    /** Stations heard on [band] at or after [sinceMs], in no particular order. */
    fun heardSince(sinceMs: Long, band: String?): List<Station> =
        byCall.values.filter { it.lastHeardMs >= sinceMs && it.row.band == band }

    companion object {
        /** Far more stations than a session hears; only there so memory has a ceiling. */
        const val DEFAULT_CAP = 20_000

        /** The callsign that sent [text], or null for a hashed or unreadable sender. */
        fun senderOf(text: String): String? = when (val message = Ft8Message.parse(text)) {
            is Ft8Message.Cq -> message.caller
            is Ft8Message.Reply -> message.from
            is Ft8Message.Report -> message.from
            is Ft8Message.Acknowledge -> message.from
            is Ft8Message.Dxpedition -> message.from
            is Ft8Message.Contest -> message.from
            is Ft8Message.Unknown -> null
        }?.takeIf { it != Ft8Message.HASHED }
    }
}
