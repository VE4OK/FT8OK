package com.ft8ok.geo

import kotlin.math.ceil
import kotlin.math.floor

/**
 * The Map wraps east-west: the world is drawn again beside itself,
 * 360 degrees over, wherever the view runs past the antimeridian. The widest zoom
 * shows exactly 360 degrees, so the view never needs more than two copies.
 */
object WorldWrap {

    /** A longitude brought into [-180, 180). */
    fun normalize(lon: Double): Double {
        val l = (lon + 180.0) % 360.0
        return (if (l < 0) l + 360.0 else l) - 180.0
    }

    /**
     * The shifts, in degrees of longitude (multiples of 360), at which a copy of the
     * world overlaps a view running from [west] to [east], unwrapped.
     */
    fun copies(west: Double, east: Double): List<Double> = shifts(west, east, -180.0, 180.0)

    /**
     * The shifts (multiples of 360) at which something spanning [from] to [to] in
     * longitude, unwrapped, overlaps a view from [west] to [east]. A great-circle
     * path can run past 180 (FN25 to Japan ends at 221 W), so it needs its own.
     */
    fun shifts(west: Double, east: Double, from: Double, to: Double): List<Double> {
        // Strictly overlapping: a copy that only touches the view's edge is left out.
        val first = floor((west - to) / 360.0).toInt() + 1
        val last = ceil((east - from) / 360.0).toInt() - 1
        return (first..last).map { it * 360.0 }
    }
}
