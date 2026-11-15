package com.ft8ok.rx

/**
 * Who has been on the band recently: one entry per station heard sending on it in the
 * last window. A view of [HeardStations], so the Roster and the
 * Map agree. Pure, so it is tested on the JVM.
 */
object Roster {

    /**
     * COUNTRY is alphabetical by entity name, so on a North American band the DX -
     * everything that does not begin "United States" - gathers above the long US
     * block. State/province is still to come.
     */
    enum class Sort { COUNTRY, RECENT, SNR, GRID }

    fun select(
        heard: HeardStations,
        /** The band the radio is on: the Roster shows no other. */
        band: String?,
        nowMs: Long,
        windowMs: Long,
        sort: Sort = Sort.RECENT,
        /** The station's country, or null if unknown; unknowns sort last. */
        countryOf: (String) -> String? = { null }
    ): List<HeardStations.Station> {
        val entries = heard.heardSince(nowMs - windowMs, band)
        return when (sort) {
            Sort.COUNTRY -> entries
                .map { it to countryOf(it.call) }
                .sortedWith(compareBy<Pair<HeardStations.Station, String?>, String?>(nullsLast()) { it.second }.thenBy { it.first.call })
                .map { it.first }
            Sort.RECENT -> entries.sortedWith(compareByDescending<HeardStations.Station> { it.lastHeardMs }.thenBy { it.call })
            Sort.SNR -> entries.sortedWith(compareByDescending<HeardStations.Station, Int?>(nullsFirst()) { it.report }.thenBy { it.call })
            Sort.GRID -> entries.sortedWith(compareBy<HeardStations.Station, String?>(nullsLast()) { it.grid }.thenBy { it.call })
        }
    }

    /** The same, straight from rows: for tests, and anywhere without an index. */
    fun build(
        feed: List<RxSession.DecodeRow>,
        nowMs: Long,
        windowMs: Long,
        slotMs: Long,
        sort: Sort = Sort.RECENT,
        band: String? = null,
        countryOf: (String) -> String? = { null }
    ): List<HeardStations.Station> =
        select(HeardStations().apply { observe(feed, slotMs) }, band, nowMs, windowMs, sort, countryOf)
}
