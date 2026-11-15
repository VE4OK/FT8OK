package com.ft8ok.geo

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The short great-circle path between two points, for the Map's line to the partner:
 * the same path [Maidenhead.distanceKm] and [Maidenhead.bearingDeg]
 * measure, so the line points the way the panel's bearing says.
 */
object GreatCircle {

    /**
     * Points along the path, interleaved longitude, latitude, in degrees, no more than
     * [stepDeg] of arc apart, both ends included. Longitudes are unwrapped from the
     * start, so a path across the antimeridian runs past 180 rather than jumping: it
     * ends at the partner's longitude plus or minus 360.
     *
     * For two points exactly opposite each other every great circle is as short, so
     * there is no one path: only the two ends are returned.
     */
    fun path(lon1: Double, lat1: Double, lon2: Double, lat2: Double, stepDeg: Double = 1.0): DoubleArray {
        val p1 = Math.toRadians(lat1); val l1 = Math.toRadians(lon1)
        val p2 = Math.toRadians(lat2); val l2 = Math.toRadians(lon2)
        // Both ends as unit vectors; the path is the arc between them.
        val ax = cos(p1) * cos(l1); val ay = cos(p1) * sin(l1); val az = sin(p1)
        val bx = cos(p2) * cos(l2); val by = cos(p2) * sin(l2); val bz = sin(p2)
        val h = sin((p2 - p1) / 2).let { it * it } + cos(p1) * cos(p2) * sin((l2 - l1) / 2).let { it * it }
        val d = 2 * asin(sqrt(h.coerceIn(0.0, 1.0)))
        val end = lon1 + WorldWrap.normalize(lon2 - lon1)
        if (sin(d) < 1e-6) return doubleArrayOf(lon1, lat1, end, lat2)

        val n = ceil(Math.toDegrees(d) / stepDeg).toInt().coerceAtLeast(1)
        val out = DoubleArray(2 * (n + 1))
        var prev = lon1
        for (i in 0..n) {
            val f = i.toDouble() / n
            val a = sin((1 - f) * d) / sin(d)
            val b = sin(f * d) / sin(d)
            val x = a * ax + b * bx; val y = a * ay + b * by; val z = a * az + b * bz
            val lon = Math.toDegrees(atan2(y, x))
            // Each step stays next to the one before, never the other side of the world.
            prev += WorldWrap.normalize(lon - prev)
            out[2 * i] = prev
            out[2 * i + 1] = Math.toDegrees(atan2(z, sqrt(x * x + y * y)))
        }
        // The ends exactly, not rounded through the vectors. The far end is taken next
        // to the last step, not from [end]: over the pole the two can differ by 360.
        out[0] = lon1; out[1] = lat1
        out[2 * n] = prev + WorldWrap.normalize(lon2 - prev); out[2 * n + 1] = lat2
        return out
    }
}
