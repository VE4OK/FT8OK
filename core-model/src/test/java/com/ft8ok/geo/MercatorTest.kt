package com.ft8ok.geo

import org.junit.Assert.assertEquals
import org.junit.Test

class MercatorTest {

    @Test
    fun `the equator is zero and the projection is symmetric`() {
        assertEquals(0.0, Mercator.y(0.0), 1e-12)
        assertEquals(-Mercator.y(49.5), Mercator.y(-49.5), 1e-12)
    }

    @Test
    fun `latitude round-trips`() {
        for (lat in listOf(-80.0, -33.9, 0.0, 12.5, 49.5, 70.0, 85.0)) {
            assertEquals(lat, Mercator.lat(Mercator.y(lat)), 1e-9)
        }
    }

    @Test
    fun `local north-south scale is 1 over cos latitude`() {
        // That is what keeps shapes true: east-west is stretched by the same factor.
        for (lat in listOf(0.0, 30.0, 49.5, 60.0)) {
            val d = 1e-4
            val scale = (Mercator.y(lat + d) - Mercator.y(lat - d)) / (2 * d)
            assertEquals(1 / Math.cos(Math.toRadians(lat)), scale, 1e-6)
        }
    }

    @Test
    fun `the cut-off makes the world square`() {
        assertEquals(180.0, Mercator.y(Mercator.LIMIT), 0.01)
        assertEquals(Mercator.y(Mercator.LIMIT), Mercator.y(90.0), 0.0)
    }
}
