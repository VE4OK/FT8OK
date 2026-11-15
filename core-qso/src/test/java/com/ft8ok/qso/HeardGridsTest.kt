package com.ft8ok.qso

import com.ft8ok.model.Ft8Message
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HeardGridsTest {

    private val t0 = 1_760_000_000_000L

    private fun HeardGrids.heard(text: String, atMs: Long = t0) = observe(Ft8Message.parse(text), atMs)

    @Test
    fun `a CQ gives the caller's grid`() {
        val grids = HeardGrids()
        grids.heard("CQ VE2XAA FN35")
        assertEquals("FN35", grids.gridOf("VE2XAA", t0 + 60_000))
    }

    @Test
    fun `a call to someone else gives the sender's grid, never the addressee's`() {
        val grids = HeardGrids()
        grids.heard("W3XAA K1ABC FN42")
        assertEquals("FN42", grids.gridOf("K1ABC", t0))
        assertNull(grids.gridOf("W3XAA", t0))
    }

    @Test
    fun `reports and acknowledgements carry no grid`() {
        val grids = HeardGrids()
        grids.heard("W3XAA K1ABC -12")
        grids.heard("W3XAA K1ABC RR73")
        assertNull(grids.gridOf("K1ABC", t0))
    }

    @Test
    fun `the newest grid wins, and an old one is not used`() {
        val grids = HeardGrids(maxAgeMs = 10 * 60_000)
        grids.heard("CQ K1ABC FN42")
        grids.heard("CQ K1ABC FN43", t0 + 60_000)
        assertEquals("FN43", grids.gridOf("K1ABC", t0 + 120_000))
        assertNull(grids.gridOf("K1ABC", t0 + 60_000 + 10 * 60_000 + 1))
    }
}
