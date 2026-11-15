package com.ft8ok.logbook

import com.ft8ok.qso.Qso
import org.junit.Assert.assertEquals
import org.junit.Test

class NotLoggedTest {

    private val t0 = 1_760_000_000_000L

    private fun entry(call: String, startedAtMs: Long = t0, lastMs: Long = t0) = NotLogged.Entry(
        Qso(partner = call, reportSentDb = -10, reportReceivedDb = -12, startedAtMs = startedAtMs, lastActivityMs = lastMs),
        dialHz = 14_074_000, grid = null
    )

    private fun calls(list: NotLogged) = list.entries.map { it.qso.partner }

    @Test
    fun `newest first, and the same contact is kept once`() {
        val list = NotLogged()
        list.add(entry("K1ABC"), t0)
        list.add(entry("W9XYZ"), t0)
        list.add(entry("K1ABC", lastMs = t0 + 30_000), t0 + 30_000)
        assertEquals(listOf("K1ABC", "W9XYZ"), calls(list))
        assertEquals(t0 + 30_000, list.entries.first().qso.lastActivityMs)
    }

    @Test
    fun `a later contact with the same station is a different contact`() {
        val list = NotLogged()
        list.add(entry("K1ABC"), t0)
        list.add(entry("K1ABC", startedAtMs = t0 + 600_000, lastMs = t0 + 600_000), t0 + 600_000)
        assertEquals(2, list.entries.size)
    }

    @Test
    fun `logged ones go, and old ones age out`() {
        val list = NotLogged()
        list.add(entry("K1ABC"), t0)
        list.add(entry("W9XYZ", lastMs = t0 + 1_000), t0 + 1_000)
        list.remove(entry("W9XYZ", lastMs = t0 + 1_000).qso)
        assertEquals(listOf("K1ABC"), calls(list))

        list.prune(t0 + NotLogged.KEEP_MS)
        assertEquals("kept for the whole hour", listOf("K1ABC"), calls(list))
        list.prune(t0 + NotLogged.KEEP_MS + 1)
        assertEquals(emptyList<String>(), calls(list))
    }

    @Test
    fun `the list is capped`() {
        val list = NotLogged()
        repeat(NotLogged.MAX_ENTRIES + 5) { list.add(entry("K${it}AB", startedAtMs = t0 + it), t0) }
        assertEquals(NotLogged.MAX_ENTRIES, list.entries.size)
        assertEquals("the oldest went first", "K${NotLogged.MAX_ENTRIES + 4}AB", list.entries.first().qso.partner)
    }
}
