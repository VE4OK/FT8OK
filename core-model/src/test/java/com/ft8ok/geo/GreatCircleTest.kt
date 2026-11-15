package com.ft8ok.geo

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GreatCircleTest {

    private fun between(from: String, to: String): DoubleArray {
        val a = Maidenhead.boxOf(from)!!
        val b = Maidenhead.boxOf(to)!!
        return GreatCircle.path(a.centreLon, a.centreLat, b.centreLon, b.centreLat)
    }

    /** Arc between two points, in degrees. */
    private fun arcDeg(lon1: Double, lat1: Double, lon2: Double, lat2: Double): Double {
        val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2)
        val h = sin((p2 - p1) / 2).let { it * it } +
            cos(p1) * cos(p2) * sin(Math.toRadians(lon2 - lon1) / 2).let { it * it }
        return Math.toDegrees(2 * asin(sqrt(h)))
    }

    private fun DoubleArray.lons() = (0 until size / 2).map { this[2 * it] }
    private fun DoubleArray.lats() = (0 until size / 2).map { this[2 * it + 1] }

    @Test
    fun `starts out on the panel's bearing`() {
        for (to in listOf("JO50", "PM95", "QF56", "FN42")) {
            val p = between("FN25", to)
            val p1 = Math.toRadians(p[1]); val p2 = Math.toRadians(p[3])
            val dl = Math.toRadians(p[2] - p[0])
            val first = Math.toDegrees(atan2(sin(dl) * cos(p2), cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)))
            val expected = Maidenhead.bearingDeg("FN25", to)!!.toDouble()
            assertEquals("FN25 to $to", 0.0, WorldWrap.normalize(first - expected), 1.5)
        }
    }

    @Test
    fun `is as long as the panel's distance, in steps of a degree at most`() {
        for (to in listOf("JO50", "PM95", "QF56", "FN42")) {
            val p = between("FN25", to)
            var total = 0.0
            for (i in 0 until p.size / 2 - 1) {
                val step = arcDeg(p[2 * i], p[2 * i + 1], p[2 * i + 2], p[2 * i + 3])
                assertTrue("FN25 to $to: step $step", step <= 1.0 + 1e-9)
                total += step
            }
            val km = total * Math.PI / 180 * 6371.0
            assertEquals("FN25 to $to", Maidenhead.distanceKm("FN25", to)!!.toDouble(), km, 2.0)
        }
    }

    @Test
    fun `to Europe it bows north`() {
        val p = between("FN25", "JO50")
        assertEquals(56.9, p.lats().max(), 0.3)
        // Ends exactly on the square centres.
        assertEquals(-75.0, p[0], 1e-12); assertEquals(45.5, p[1], 1e-12)
        assertEquals(11.0, p[p.size - 2], 1e-12); assertEquals(50.5, p[p.size - 1], 1e-12)
    }

    @Test
    fun `to Japan it goes west, far north, and past 180`() {
        val p = between("FN25", "PM95")
        val lons = p.lons()
        // West all the way, no jump: it ends at 139 E minus 360.
        assertTrue(lons.zipWithNext().all { (a, b) -> b < a && a - b < 5.0 })
        assertEquals(139.0 - 360.0, lons.last(), 1e-9)
        assertEquals(71.4, p.lats().max(), 0.3)
    }

    @Test
    fun `a short path is nearly straight`() {
        val p = between("FN25", "FN35")
        assertTrue(p.lats().all { it in 45.0..46.5 })
    }

    @Test
    fun `over the pole it does not jump at the far end`() {
        // 97 W to 83 E is exactly 180 apart: the short path is over the pole.
        val p = GreatCircle.path(-97.0, 49.5, 83.0, 30.5)
        assertTrue(p.lats().max() > 89.0)
        val lons = p.lons()
        // One 180 turn at the pole, and no other step anywhere near that.
        val big = lons.zipWithNext().count { (a, b) -> abs(b - a) > 10.0 }
        assertEquals(1, big)
        assertEquals(83.0, WorldWrap.normalize(lons.last()), 1e-9)
    }

    @Test
    fun `the same square, or opposite points, give just the ends`() {
        assertEquals(2, GreatCircle.path(-97.0, 49.5, -97.0, 49.5).size / 2)
        assertEquals(2, GreatCircle.path(0.0, 10.0, 180.0, -10.0).size / 2)
    }

    @Test
    fun `a path's own copies are found when the view is on the far end`() {
        // FN25 to Japan runs from 75 W to 221 W; a view over Japan needs it 360 east.
        assertEquals(listOf(360.0), WorldWrap.shifts(120.0, 160.0, -221.0, -75.0))
    }
}
