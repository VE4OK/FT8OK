package com.ft8ok.geo

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BasemapTest {

    /** A file holding one square ring, 10 x 10 degrees around the origin. */
    private fun oneSquare(): ByteArray {
        val points = listOf(-5 to -5, 5 to -5, 5 to 5, -5 to 5)
        val b = ByteBuffer.allocate(12 + 4 + points.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        b.put("FTBM".toByteArray()).put(1).put(0).putShort(100).putInt(1).putInt(points.size)
        for ((lon, lat) in points) b.putShort((lon * 100).toShort()).putShort((lat * 100).toShort())
        return b.array()
    }

    @Test
    fun `decodes a ring and tests points against it`() {
        val map = Basemap.decode(oneSquare())
        assertEquals(100, map.perDegree)
        assertEquals(1, map.rings.size)
        assertEquals(4, map.pointCount)
        assertTrue(map.contains(0.0, 0.0))
        assertFalse(map.contains(6.0, 0.0))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `refuses a file that is not a basemap`() {
        Basemap.decode(ByteArray(20))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `refuses a truncated file`() {
        Basemap.decode(oneSquare().copyOf(20))
    }

    /**
     * The real assets, converted from Natural Earth. If these points come out wrong
     * the converter swapped axes, flipped a sign or lost rings - which would draw a
     * plausible-looking map that is simply wrong.
     */
    @Test
    fun `the shipped outlines put land and sea where they are`() {
        for (name in listOf("countries-50m.bin", "countries-110m.bin")) {
            val file = File("../app/src/main/assets/basemap/$name")
            val map = Basemap.decode(file.readBytes())
            assertTrue("$name: rings", map.rings.size > 100)
            assertTrue("$name: Ottawa", map.contains(-75.70, 45.42))
            assertTrue("$name: Madrid", map.contains(-3.70, 40.42))
            assertTrue("$name: Alice Springs", map.contains(133.88, -23.70))
            assertFalse("$name: Gulf of Guinea", map.contains(0.0, 0.0))
            assertFalse("$name: mid-Atlantic", map.contains(-35.0, 35.0))
            assertFalse("$name: mid-Pacific", map.contains(-150.0, 0.0))
        }
    }

    /**
     * The Map draws the world a second time beside itself, 360 degrees over, so the
     * view wraps across the antimeridian. That only joins up if no ring crosses it by
     * jumping the width of the world: such a segment would draw a streak across the
     * map. A segment along the bottom edge (Antarctica's, at the pole) is off the
     * Mercator map and allowed.
     */
    @Test
    fun `no shipped ring jumps across the antimeridian`() {
        for (name in listOf("countries-50m.bin", "countries-110m.bin", "lakes-50m.bin", "admin1-50m.bin")) {
            val map = Basemap.decode(File("../app/src/main/assets/basemap/$name").readBytes())
            // The state and province file is polylines; the rest are closed rings.
            val closed = !name.startsWith("admin1")
            val per = map.perDegree.toDouble()
            val jumps = ArrayList<String>()
            for ((r, ring) in map.rings.withIndex()) {
                val n = ring.size / 2
                for (i in 0 until if (closed) n else n - 1) {
                    val j = (i + 1) % n
                    val lon1 = ring[2 * i] / per; val lat1 = ring[2 * i + 1] / per
                    val lon2 = ring[2 * j] / per; val lat2 = ring[2 * j + 1] / per
                    assertTrue("$name ring $r: longitude $lon1 outside the world", lon1 in -180.0..180.0)
                    val offMap = minOf(abs(lat1), abs(lat2)) > Mercator.LIMIT
                    if (abs(lon2 - lon1) > 180.0 && !offMap) jumps += "ring $r ($lon1,$lat1)->($lon2,$lat2)"
                }
            }
            assertTrue("$name: ${jumps.size} jumps, e.g. ${jumps.take(5)}", jumps.isEmpty())
            if (name.startsWith("countries")) {
                // Land runs up to the line from both sides (Chukotka, Fiji), cut there
                // rather than missing, so the check above has something to see.
                val lons = map.rings.flatMap { ring -> (0 until ring.size / 2).map { ring[2 * it] / per } }
                assertTrue("$name: nothing at 180E", lons.any { it >= 179.99 })
                assertTrue("$name: nothing at 180W", lons.any { it <= -179.99 })
            }
        }
    }
}
