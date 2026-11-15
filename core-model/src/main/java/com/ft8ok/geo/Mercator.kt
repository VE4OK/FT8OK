package com.ft8ok.geo

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.tan

/**
 * The Mercator northing, in "degrees" so that one unit is the width of one degree of
 * longitude: a map scaled by the same factor both ways then keeps every shape true.
 *
 * Chosen for the Map tab because it is conformal, so continents keep
 * their shape at every latitude, and because meridians and parallels stay straight, so
 * Maidenhead squares stay rectangles (taller toward the poles, as they truly are in
 * shape). The poles are at infinity, so latitude is clamped to [LIMIT].
 */
object Mercator {

    /** The usual web-map cut-off: the world then comes out square. */
    const val LIMIT = 85.0511

    fun y(latDeg: Double): Double {
        val lat = Math.toRadians(latDeg.coerceIn(-LIMIT, LIMIT))
        return Math.toDegrees(ln(tan(PI / 4 + lat / 2)))
    }

    fun lat(yDeg: Double): Double =
        Math.toDegrees(2 * atan(exp(Math.toRadians(yDeg))) - PI / 2)
}
