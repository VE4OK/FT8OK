package com.ft8ok.rx

import com.ft8ok.geo.Maidenhead

/**
 * The Map's data: stations grouped by the four-character square they sent.
 * Stations whose grid we have not heard are not on the
 * map - there is nowhere honest to put them. Pure, so it is tested on the JVM.
 */
object MapSquares {

    data class Square(
        val grid: String,
        /** Most recently heard first. */
        val stations: List<HeardStations.Station>
    ) {
        val lastHeardMs: Long get() = stations.first().lastHeardMs
        val anyCalling: Boolean get() = stations.any { it.calling }
    }

    fun build(stations: List<HeardStations.Station>): Map<String, Square> =
        stations
            .mapNotNull { s -> Maidenhead.square(s.grid)?.let { it to s } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (grid, list) -> Square(grid, list.sortedByDescending { it.lastHeardMs }) }
}
