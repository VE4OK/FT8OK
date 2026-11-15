package com.ft8ok.rx

/**
 * Which band each slot was heard on.
 *
 * Decodes carry the band so the Roster and the Map show only the band the radio is
 * on, and a band's stations come back when the operator returns to it. A slot the
 * band changed **during** belongs to neither: its audio is part one band, part the
 * other, and its decodes arrive after the change, so they would put old-band
 * stations in the new band's Roster. Such a slot is dropped.
 *
 * **Losing the radio is not a change of band**. While the CAT
 * link is down, slots keep the last band read, and a reconnect on that band changes
 * nothing: no dimming, no dropped slot. A reconnect on another band is a change made
 * somewhere in the gap, so [onBand] hands back the start of the gap and the session
 * marks what it heard since then as of unknown band. Slots before the **first**
 * reading are of unknown band too: the app cannot know the frequency without CAT.
 *
 * Times are the corrected clock the slots are cut on. A change is stamped when the
 * app learns of it, up to about a second after the radio moved (a tune is read back
 * after its acknowledgements): near a slot boundary that can mark the wrong one of
 * the two slots, which costs a slot of decodes or lets a second of the old band's
 * audio through - neither enough to decode a station from it.
 *
 * Pure, so it is tested on the JVM.
 */
class BandTimeline {

    /** [first]: the first reading ever, which ends an unknown, not a band. */
    private data class Change(val atMs: Long, val band: String?, val first: Boolean = false)

    private val changes = ArrayDeque<Change>()

    /** The band now; null while the radio's frequency is unknown. */
    var current: String? = null
        private set

    /** The last band read, kept through a disconnect; null before the first reading. */
    var known: String? = null
        private set

    /** When the radio was lost, while it is; null with the radio linked. */
    private var lostAtMs: Long? = null

    /**
     * The radio is now on [band] (null: unknown), as of [atMs]. Repeats are ignored.
     *
     * @return the start of a gap that ended on another band: what was heard from
     *   then on is of unknown band. Null otherwise.
     */
    fun onBand(band: String?, atMs: Long): Long? {
        if (band == current) return null
        current = band
        if (band == null) {
            if (known != null && lostAtMs == null) lostAtMs = atMs
            return null
        }
        val lost = lostAtMs
        lostAtMs = null
        val before = known
        known = band
        when {
            band == before -> return null
            before == null -> add(Change(atMs, band, first = true))
            else -> {
                if (lost != null) add(Change(lost, null))
                add(Change(atMs, band))
            }
        }
        return if (before != null) lost else null
    }

    private fun add(change: Change) {
        changes.addLast(change)
        while (changes.size > KEEP) changes.removeFirst()
    }

    /**
     * True if the band changed while the slot starting at [slotStartMs] was being
     * heard. The first reading is not a change: that slot stays of unknown band.
     */
    fun straddles(slotStartMs: Long, slotMs: Long): Boolean =
        changes.any { !it.first && it.atMs > slotStartMs && it.atMs < slotStartMs + slotMs }

    /**
     * The band in force when the slot starting at [slotStartMs] began; null before
     * the first reading, when it was unknown.
     */
    fun bandAt(slotStartMs: Long): String? =
        changes.lastOrNull { it.atMs <= slotStartMs }?.band

    private companion object {
        /** Decodes arrive within a slot or two of their own; a few changes is plenty. */
        const val KEEP = 16
    }
}
