package com.ft8ok.geo

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Maidenhead locator maths for the map, at the two levels FT8 uses: the field
 * (`EN`, 20 x 10 degrees) and the four-character square (`FN25`, 2 x 1 degrees).
 *
 * The scheme: the first letter pair steps longitude from -180 in 20 degree fields
 * and latitude from -90 in 10 degree fields; the digit pair steps 2 degrees of
 * longitude and 1 of latitude inside the field.
 */
object Maidenhead {

    /** A rectangle in degrees: west and south inclusive, east and north exclusive. */
    data class Box(val west: Double, val south: Double, val east: Double, val north: Double) {
        val centreLon: Double get() = (west + east) / 2
        val centreLat: Double get() = (south + north) / 2
    }

    private val SQUARE = Regex("^[A-R]{2}[0-9]{2}$")

    /** The four-character square, upper-cased, or null if [grid] does not start with one. */
    fun square(grid: String?): String? {
        val g = grid?.trim()?.uppercase()?.take(4) ?: return null
        return g.takeIf { SQUARE.matches(it) && it != "RR73" }
    }

    /** The box a four-character square covers, or null if it is not one. */
    fun boxOf(grid: String?): Box? {
        val g = square(grid) ?: return null
        val west = (g[0] - 'A') * 20.0 - 180.0 + (g[2] - '0') * 2.0
        val south = (g[1] - 'A') * 10.0 - 90.0 + (g[3] - '0') * 1.0
        return Box(west, south, west + 2.0, south + 1.0)
    }

    /** The four-character square containing a point. */
    fun squareAt(lon: Double, lat: Double): String {
        val x = (lon + 180.0).coerceIn(0.0, 359.999_999)
        val y = (lat + 90.0).coerceIn(0.0, 179.999_999)
        val fieldLon = (x / 20).toInt()
        val fieldLat = (y / 10).toInt()
        val sqLon = ((x - fieldLon * 20) / 2).toInt()
        val sqLat = (y - fieldLat * 10).toInt()
        return "${'A' + fieldLon}${'A' + fieldLat}$sqLon$sqLat"
    }

    /** Great-circle distance in km between two square centres, or null. */
    fun distanceKm(from: String?, to: String?): Int? {
        val a = boxOf(from) ?: return null
        val b = boxOf(to) ?: return null
        val p1 = Math.toRadians(a.centreLat)
        val p2 = Math.toRadians(b.centreLat)
        val dp = p2 - p1
        val dl = Math.toRadians(b.centreLon - a.centreLon)
        val h = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return (2 * EARTH_RADIUS_KM * asin(sqrt(h))).roundToInt()
    }

    /** Initial great-circle bearing in whole degrees from one square centre to another. */
    fun bearingDeg(from: String?, to: String?): Int? {
        val a = boxOf(from) ?: return null
        val b = boxOf(to) ?: return null
        val p1 = Math.toRadians(a.centreLat)
        val p2 = Math.toRadians(b.centreLat)
        val dl = Math.toRadians(b.centreLon - a.centreLon)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return ((Math.toDegrees(atan2(y, x)) + 360) % 360).roundToInt() % 360
    }

    /** Mean Earth radius. */
    private const val EARTH_RADIUS_KM = 6371.0
}
