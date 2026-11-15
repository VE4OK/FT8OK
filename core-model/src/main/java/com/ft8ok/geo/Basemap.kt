package com.ft8ok.geo

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The offline world outline the Map draws: Natural Earth
 * Admin-0 country polygons, public domain, converted to this binary format by a build script.
 * The file format is described there.
 *
 * Each ring is its points interleaved as longitude, latitude, in units of
 * 1/[perDegree] degree. Rings are outer boundaries and holes alike, as the source
 * has them; fill them even-odd.
 */
class Basemap(val perDegree: Int, val rings: List<ShortArray>) {

    val pointCount: Int get() = rings.sumOf { it.size / 2 }

    /**
     * Is the point on land? Even-odd over every ring, the same rule the map fills
     * with, so this is also how the tests check the asset is drawn the right way up.
     */
    fun contains(lon: Double, lat: Double): Boolean {
        val x = lon * perDegree
        val y = lat * perDegree
        var inside = false
        for (ring in rings) {
            val n = ring.size / 2
            var j = n - 1
            for (i in 0 until n) {
                val xi = ring[2 * i].toDouble(); val yi = ring[2 * i + 1].toDouble()
                val xj = ring[2 * j].toDouble(); val yj = ring[2 * j + 1].toDouble()
                if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside
                j = i
            }
        }
        return inside
    }

    companion object {
        private const val VERSION = 1

        /** Parse the asset. Throws [IllegalArgumentException] on anything malformed. */
        fun decode(bytes: ByteArray): Basemap {
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            require(bytes.size >= 12) { "basemap too short" }
            val magic = String(bytes, 0, 4, Charsets.US_ASCII)
            require(magic == "FTBM") { "not a basemap (magic $magic)" }
            b.position(4)
            val version = b.get().toInt()
            require(version == VERSION) { "basemap version $version, expected $VERSION" }
            b.get()
            val perDegree = b.short.toInt() and 0xFFFF
            require(perDegree > 0) { "basemap scale 0" }
            val ringCount = b.int
            require(ringCount >= 0) { "negative ring count" }
            val rings = ArrayList<ShortArray>(ringCount)
            repeat(ringCount) {
                val n = b.int
                // Two points is a line: the state and province file is polylines.
                require(n >= 2 && b.remaining() >= n * 4) { "truncated basemap" }
                val ring = ShortArray(n * 2)
                b.asShortBuffer().get(ring)
                b.position(b.position() + n * 4)
                rings += ring
            }
            return Basemap(perDegree, rings)
        }
    }
}
