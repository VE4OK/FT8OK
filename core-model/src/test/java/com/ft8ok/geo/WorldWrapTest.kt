package com.ft8ok.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorldWrapTest {

    @Test
    fun `longitudes come back into the world`() {
        assertEquals(-97.0, WorldWrap.normalize(-97.0), 1e-12)
        assertEquals(139.0, WorldWrap.normalize(-221.0), 1e-12)
        assertEquals(-170.0, WorldWrap.normalize(190.0), 1e-12)
        assertEquals(-180.0, WorldWrap.normalize(180.0), 1e-12)
        assertEquals(10.0, WorldWrap.normalize(730.0), 1e-12)
    }

    @Test
    fun `a view inside the world needs only the world`() {
        assertEquals(listOf(0.0), WorldWrap.copies(-110.0, -84.0))
    }

    @Test
    fun `World centred on FN25 adds the copy to the west`() {
        // 360 degrees about 97W: Asia and the Pacific show west of Alaska.
        assertEquals(listOf(-360.0, 0.0), WorldWrap.copies(-277.0, 83.0))
    }

    @Test
    fun `a view across 180 east adds the copy to the east`() {
        assertEquals(listOf(0.0, 360.0), WorldWrap.copies(170.0, 190.0))
    }

    @Test
    fun `the widest view never needs more than two copies`() {
        assertEquals(listOf(0.0), WorldWrap.copies(-180.0, 180.0))
        var west = -540.0
        while (west <= 180.0) {
            val n = WorldWrap.copies(west, west + 360.0).size
            assertTrue("west $west: $n copies", n in 1..2)
            west += 7.3
        }
    }
}
