package com.ft8ok.time

import com.ft8ok.model.Mode
import kotlin.math.abs

/**
 * Where the band's slot grid is, from what was heard and nothing else.
 *
 * The normal path can only correct a clock that is already close, because a slot cut
 * in the wrong place decodes nothing. So while the clock is unknown the audio is
 * decoded at several offsets, and each message found says where one station puts the
 * grid. This pools those over a few slots: one vote per station, grouped by where
 * they say the grid is, and the biggest group wins. A station off the grid is
 * outvoted rather than taken for it, and no decodes means no answer.
 *
 * Every time here is host time, the timebase the audio is anchored in. Only the phase
 * within a slot means anything: the band cannot say which slot it is, or the time of
 * day. Pure, and safe to call from the decode thread while capture restarts.
 */
class GridSearch(private val mode: Mode) {

    /**
     * @param gridHostMs a slot boundary, host time.
     * @param stations stations that agree on it, each counted once.
     * @param transmissions the transmissions behind them.
     */
    data class Lock(val gridHostMs: Double, val stations: Int, val transmissions: Int)

    private class Heard(val key: String, val frequencyHz: Int, val gridHostMs: Double)

    private val heard = ArrayList<Heard>()

    /**
     * One decoded message. Overlapping windows find the same transmission again, and
     * that counts once: it is the same evidence.
     *
     * @param key the message, to tell transmissions apart.
     * @param gridHostMs the slot boundary this transmission implies.
     */
    @Synchronized
    fun add(key: String, frequencyHz: Int, gridHostMs: Double) {
        val again = heard.any { it.key == key && abs(it.gridHostMs - gridHostMs) < SAME_TRANSMISSION_MS }
        if (!again) heard += Heard(key, frequencyHz, gridHostMs)
    }

    /** The biggest group heard over the last [Mode.gridHistoryMs], or null if nothing was. */
    @Synchronized
    fun lock(nowHostMs: Long): Lock? {
        heard.removeAll { nowHostMs - it.gridHostMs > mode.gridHistoryMs }

        var best: Lock? = null
        for (anchor in heard) {
            val group = heard.filter { abs(phaseDifferenceMs(it.gridHostMs, anchor.gridHostMs)) <= GROUP_MS }
            val stations = stationsIn(group)
            val leader = best
            if (leader == null || stations > leader.stations ||
                (stations == leader.stations && group.size > leader.transmissions)
            ) {
                val offsets = group.map { phaseDifferenceMs(it.gridHostMs, anchor.gridHostMs) }.sorted()
                best = Lock(anchor.gridHostMs + offsets[offsets.size / 2], stations, group.size)
            }
        }
        return best
    }

    /** Forget everything: capture restarted, and what was heard may be from another time base. */
    @Synchronized
    fun reset() = heard.clear()

    /** A station keeps its frequency, so transmissions close in frequency are one vote. */
    private fun stationsIn(group: List<Heard>): Int {
        val seen = ArrayList<Int>()
        for (h in group) {
            if (seen.none { abs(it - h.frequencyHz) < SAME_STATION_HZ }) seen += h.frequencyHz
        }
        return seen.size
    }

    private fun phaseDifferenceMs(a: Double, b: Double): Double =
        wrapToHalfSlot(a - b, mode.slotMs)

    companion object {
        /** Stations this close agree: the same limit as [BandConsensus.OUTLIER_SEC]. */
        private const val GROUP_MS = BandConsensus.OUTLIER_SEC * 1000.0

        /** Twice [GROUP_MS]: one transmission, found in two overlapping windows. */
        private const val SAME_TRANSMISSION_MS = 2 * GROUP_MS

        /** Inside FT8's 50 Hz signal width, as the measurements on the bench used. */
        private const val SAME_STATION_HZ = 12

        /** [ms] as the nearest distance round a slot, within half a slot either way. */
        fun wrapToHalfSlot(ms: Double, slotMs: Long): Double {
            val wrapped = ms.mod(slotMs.toDouble())
            return if (wrapped > slotMs / 2.0) wrapped - slotMs else wrapped
        }
    }
}
