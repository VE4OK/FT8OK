package com.ft8ok.time

import com.ft8ok.model.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The pooled vote. The replay tests use what the decoder found in
 * two real IC-7300 recordings, exported by
 * a host bench, so this port is held to the bench's answers.
 */
class GridSearchTest {

    private val mode = Mode.FT8
    private val start = 1_794_000_000_000L

    private class Vote(val windowMs: Long, val gridMs: Double, val hz: Int, val hash: String)

    private fun votes(name: String): List<Vote> =
        javaClass.getResourceAsStream("/$name")!!.bufferedReader().readLines()
            .filter { !it.startsWith("#") && it.isNotBlank() }
            .map { line ->
                val p = line.split(',')
                Vote(p[0].toLong(), p[1].toDouble(), p[2].toInt(), p[3])
            }

    private fun distance(a: Double, b: Double) = abs(GridSearch.wrapToHalfSlot(a - b, mode.slotMs))

    /** Start listening at each slot and search once a slot, as the app does. */
    private fun replay(file: String, bandGridMs: Double) {
        val all = votes(file)
        val endMs = all.maxOf { it.windowMs } + mode.slotMs
        var from = 0L
        var starts = 0
        while (from + 2 * mode.slotMs <= endMs) {
            val search = GridSearch(mode)
            var nowMs = from + 2 * mode.slotMs
            val reach = mode.slotMs + (mode.gridSearchWindows - 1) * mode.gridSearchStepMs
            val heard = all.filter { it.windowMs >= maxOf(from, nowMs - reach) && it.windowMs + mode.slotMs <= nowMs }
            for (v in heard) search.add(v.hash, v.hz, start + v.gridMs)
            val lock = search.lock(start + nowMs)
            assertNotNull("nothing heard from a start at $from ms", lock)
            assertTrue(
                "a start at $from ms put the grid ${distance(lock!!.gridHostMs, start + bandGridMs)} ms out",
                distance(lock.gridHostMs, start + bandGridMs) < 150
            )
            starts++
            from += mode.slotMs
        }
        assertTrue("too few starts: $starts", starts >= 10)
    }

    @Test
    fun `finds the grid from every start on a real busy band`() = replay("grid-votes-20m.csv", 5_390.0)

    @Test
    fun `finds the grid from every start on real 10 m at dusk`() = replay("grid-votes-10m.csv", 1_430.0)

    @Test
    fun `hearing nothing gives no answer`() {
        assertNull(GridSearch(mode).lock(start))
    }

    @Test
    fun `a transmission found in two windows counts once`() {
        val search = GridSearch(mode)
        search.add("CQ K1ABC FN42", 1000, start + 0.0)
        search.add("CQ K1ABC FN42", 1000, start + 40.0)
        assertEquals(1, search.lock(start + 1000)!!.transmissions)
    }

    @Test
    fun `one station repeating is one station, however often it calls`() {
        val search = GridSearch(mode)
        for (k in 0..2) search.add("CQ K1ABC FN42 $k", 1000, start + k * 30_000.0)
        val lock = search.lock(start + 60_000)!!
        assertEquals(1, lock.stations)
        assertEquals(3, lock.transmissions)
    }

    @Test
    fun `stations on either parity agree on the same grid`() {
        val search = GridSearch(mode)
        search.add("A", 800, start + 2_000.0)
        search.add("B", 1200, start + 2_000.0 + mode.slotMs)
        search.add("C", 1600, start + 2_000.0 + 2 * mode.slotMs)
        assertEquals(3, search.lock(start + 40_000)!!.stations)
    }

    @Test
    fun `the group agrees across the slot boundary`() {
        val search = GridSearch(mode)
        search.add("A", 800, start + 14_900.0)
        search.add("B", 1200, start + 15_100.0)
        assertEquals(2, search.lock(start + 20_000)!!.stations)
    }

    @Test
    fun `two stations outvote one far off the grid`() {
        val search = GridSearch(mode)
        search.add("off", 500, start + 5_000.0)
        search.add("A", 800, start + 0.0)
        search.add("B", 1200, start + 100.0)
        val lock = search.lock(start + 20_000)!!
        assertEquals(2, lock.stations)
        assertTrue(distance(lock.gridHostMs, start.toDouble()) < 150)
    }

    @Test
    fun `what was heard too long ago no longer counts`() {
        val search = GridSearch(mode)
        search.add("old", 500, start.toDouble())
        assertNull(search.lock(start + mode.gridHistoryMs + 1))
    }

    @Test
    fun `reset forgets everything`() {
        val search = GridSearch(mode)
        search.add("A", 800, start.toDouble())
        search.reset()
        assertNull(search.lock(start + 1000))
    }
}
