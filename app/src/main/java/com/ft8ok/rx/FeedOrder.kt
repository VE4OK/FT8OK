package com.ft8ok.rx

/**
 * Where new rows go in the decode feed: in slot order, oldest first.
 *
 * Our own transmission is added the moment it starts, half a second into its slot,
 * and a decode pass for the slot before can still be running then on a slow phone.
 * Appending blindly put those late decodes below our TX row, out of time order, and
 * our transmissions gathered at the bottom of the feed instead of sitting between
 * the slots they belong between.
 *
 * The order is by slot, and within a slot our transmission comes first - it began
 * before anything in that slot could have been decoded. Rows of the same slot keep
 * the order they arrived in, so a row already on screen never jumps past another.
 */
object FeedOrder {

    fun insert(feed: List<RxSession.DecodeRow>, rows: List<RxSession.DecodeRow>, limit: Int): List<RxSession.DecodeRow> {
        if (rows.isEmpty()) return feed
        val out = feed.toMutableList()
        for (row in rows) {
            // After the last row that sorts at or before this one.
            var at = out.size
            while (at > 0 && after(out[at - 1], row)) at--
            out.add(at, row)
        }
        return out.takeLast(limit)
    }

    /** Does [a] belong after [b]? */
    private fun after(a: RxSession.DecodeRow, b: RxSession.DecodeRow): Boolean =
        a.slotStartMs > b.slotStartMs || (a.slotStartMs == b.slotStartMs && !a.tx && b.tx)
}
