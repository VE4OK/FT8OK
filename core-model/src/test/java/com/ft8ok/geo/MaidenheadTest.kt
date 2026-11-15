package com.ft8ok.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MaidenheadTest {

    @Test
    fun `a square's box follows the field and digit steps`() {
        // F = 6th field east of -180 (-80), N = 14th north of -90 (40); 2 and 5 step
        // 2 deg of longitude and 1 of latitude.
        assertEquals(Maidenhead.Box(-76.0, 45.0, -74.0, 46.0), Maidenhead.boxOf("FN25"))
        assertEquals(Maidenhead.Box(-180.0, -90.0, -178.0, -89.0), Maidenhead.boxOf("aa00"))
        assertEquals(Maidenhead.Box(178.0, 89.0, 180.0, 90.0), Maidenhead.boxOf("RR99"))
    }

    @Test
    fun `known places land in their squares`() {
        assertEquals("FN25", Maidenhead.squareAt(-75.70, 45.42)) // Ottawa
        assertEquals("FN42", Maidenhead.squareAt(-71.06, 42.36)) // Boston
        assertEquals("JN58", Maidenhead.squareAt(11.58, 48.14))  // Munich
        assertEquals("QF22", Maidenhead.squareAt(144.96, -37.81)) // Melbourne
    }

    @Test
    fun `only real four-character squares are accepted`() {
        assertEquals("FN25", Maidenhead.square("fn25"))
        assertEquals("FN25", Maidenhead.square("FN25ab"))
        assertNull("RR73 is the acknowledgement, not a grid", Maidenhead.square("RR73"))
        assertNull(Maidenhead.square("SN19"))
        assertNull(Maidenhead.square("E19"))
        assertNull(Maidenhead.square(null))
    }

    @Test
    fun `distance and bearing between square centres`() {
        // FN25 (-75, 45.5) to FN42 (-71, 42.5): roughly 460 km, south-east.
        val km = Maidenhead.distanceKm("FN25", "FN42")!!
        assertTrue("$km km", km in 400..520)
        val deg = Maidenhead.bearingDeg("FN25", "FN42")!!
        assertTrue("$deg deg", deg in 125..145)
        assertEquals(0, Maidenhead.distanceKm("FN25", "FN25"))
        assertNull(Maidenhead.distanceKm("FN25", "nope"))
    }
}
