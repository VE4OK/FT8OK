package com.ft8ok.qso

import com.ft8ok.model.Ft8Message

/**
 * The grid each station has recently sent, from everything decoded - not just what
 * was said to us.
 *
 * A contact does not always carry the partner's grid: when we answer a CQ, the
 * grid was in the CQ, and the exchange that follows is reports only. The log should
 * still have it, so every decoded CQ (`CQ K1ABC FN42`) and every
 * grid-bearing call to anyone (`W3XAA K1ABC FN42`) is remembered against its sender.
 *
 * Only what the station itself sent counts: in `W3XAA K1ABC FN42` the grid is
 * K1ABC's, never W3XAA's. Entries older than [maxAgeMs] are not used.
 */
class HeardGrids(private val maxAgeMs: Long = DEFAULT_MAX_AGE_MS) {

    private class Heard(val grid: String, val atMs: Long)

    private val heard = HashMap<String, Heard>()

    fun observe(message: Ft8Message, atMs: Long) {
        val (call, grid) = when (message) {
            is Ft8Message.Cq -> message.caller to message.grid
            is Ft8Message.Reply -> message.from to message.grid
            is Ft8Message.Contest -> message.from to message.grid
            else -> return
        }
        if (grid == null || call == Ft8Message.HASHED) return
        heard[call.uppercase()] = Heard(grid.uppercase(), atMs)
        // Keep the map to the window: a long session hears thousands of stations.
        if (heard.size > PRUNE_ABOVE) heard.values.removeAll { atMs - it.atMs > maxAgeMs }
    }

    /** The grid [call] sent most recently, if within [maxAgeMs] of [nowMs]. */
    fun gridOf(call: String, nowMs: Long): String? =
        heard[call.uppercase()]?.takeIf { nowMs - it.atMs <= maxAgeMs }?.grid

    companion object {
        /**
         * Chosen default: one hour. A station's grid does not change within a session
         * except for a portable operator moving, which takes longer than this.
         */
        const val DEFAULT_MAX_AGE_MS = 60 * 60_000L

        private const val PRUNE_ABOVE = 5_000
    }
}
