package com.ft8ok.rx

import com.ft8ok.model.Ft8Message
import com.ft8ok.model.SlotParity

/**
 * The feed as the screen lays it out: a header for each 15 s slot, then its rows.
 * The header carries the slot's UTC time and parity, so the rows
 * no longer repeat them and the message gets the room.
 *
 * Within a slot, decodes that mention our call go last, so on a busy band a later
 * pass cannot push them up and away from the thumb. Each group
 * keeps its arrival order, and our own transmission stays first.
 *
 * Pure, so the grouping is tested on the JVM. The feed is already in slot order
 * ([FeedOrder]) and stays that way; this only inserts the headers and moves our
 * mentions to the end of their slot for display.
 */
object FeedSections {

    sealed interface Item {
        /** A stable key for the list, so rows keep their place as the feed grows. */
        val key: String
    }

    data class Header(
        val slotStartMs: Long,
        val parity: SlotParity,
        /** Decodes in the slot, not counting our own transmissions. */
        val decodes: Int,
        /** We transmitted in this slot. */
        val ourTx: Boolean,
        /** The band the slot was heard on. */
        val band: String?
    ) : Item {
        override val key: String get() = "h$slotStartMs"
    }

    /**
     * [index] is the row's place in its slot in arrival order, not on screen: rows
     * of a slot only ever arrive at its end, so the key stays with the same decode
     * when a later pass slots rows in above a mention.
     */
    data class Line(val row: RxSession.DecodeRow, val index: Int) : Item {
        override val key: String get() = "r${row.slotStartMs}-$index"
    }

    /**
     * The radio changed band before this slot: a line across the feed, so history
     * from the old band reads as such. Not before the first slot.
     */
    data class BandChange(val band: String?, val slotStartMs: Long) : Item {
        override val key: String get() = "b$slotStartMs"
    }

    /**
     * [myCall]: decodes mentioning it go to the end of their slot; blank moves nothing.
     *
     * [show] is the filter chip: rows it refuses are left out, and
     * so is a slot with no row left. A shown slot's header still counts every decode
     * in it, so a filtered slot says how much is hidden. A band line goes before the
     * first shown slot of a new band.
     */
    fun build(
        feed: List<RxSession.DecodeRow>,
        myCall: String = "",
        show: (RxSession.DecodeRow) -> Boolean = { true }
    ): List<Item> {
        val items = ArrayList<Item>(feed.size + feed.size / 8 + 1)
        var previousBand: String? = null
        var anyShown = false
        var i = 0
        while (i < feed.size) {
            val slot = feed[i].slotStartMs
            var end = i
            while (end < feed.size && feed[end].slotStartMs == slot) end++
            val rows = feed.subList(i, end)
            val shown = BooleanArray(rows.size) { n -> show(rows[n]) }
            if (shown.none { it }) {
                i = end
                continue
            }
            val band = feed[i].band
            if (anyShown && band != previousBand) items += BandChange(band, slot)
            previousBand = band
            anyShown = true
            items += Header(
                slotStartMs = slot,
                parity = feed[i].parity,
                decodes = rows.count { !it.tx },
                ourTx = rows.any { it.tx },
                band = band
            )
            // Two passes rather than a sort: a sort would be stable too, but this
            // says what it does. Same test as the red highlight, so they agree.
            val mentionsUs = BooleanArray(rows.size) { n ->
                !rows[n].tx && Ft8Message.mentions(rows[n].decode.message, myCall)
            }
            rows.forEachIndexed { n, row -> if (shown[n] && !mentionsUs[n]) items += Line(row, n) }
            rows.forEachIndexed { n, row -> if (shown[n] && mentionsUs[n]) items += Line(row, n) }
            i = end
        }
        return items
    }
}
