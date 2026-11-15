package com.ft8ok.model

/**
 * IARU region. Matters for exactly two entries in the table, and it is a Station
 * setting — the app has no location permission and must never infer it.
 */
enum class Region { ALL, R1, R2, R3 }

/**
 * One conventional dial frequency.
 *
 * @param hz the **USB dial frequency** — the carrier the radio displays in USB or
 *   DATA-USB — as an integer. Never a floating-point MHz value: 40 m FT4 is
 *   7.0475 MHz, which is not a whole kHz and does not survive being eyeballed.
 * @param preferred the source's own flag separating the normal calling frequency
 *   from alternates. Presets offer only preferred ones; the TX guard must allow
 *   them all, because the alternates are the DXpedition convention and blocking
 *   14.090 would stop the operator working a DXpedition.
 */
data class DialFrequency(
    val band: String,
    val hz: Long,
    val mode: String,
    val region: Region,
    val preferred: Boolean
)

/**
 * The single table of conventional FT8/FT4 dial frequencies, from docs/reference/FREQUENCIES.md.
 *
 * **Verbatim from `docs/reference/FREQUENCIES.md`**, which took them from WSJT-X's
 * own `models/FrequencyList.cpp` rather than from a web article or from memory. Do
 * not retype these numbers anywhere else, and do not add one without a source.
 *
 * Band labels are derived from the frequency, as that file says; the frequencies
 * themselves are the source's.
 *
 * Two things this table is **not**. It is not a statement of legality — a frequency
 * being conventional for FT8 does not make it legal for a given licence, region or
 * power, and the guard built on it exists to prevent *accidental* out-of-convention
 * transmission, not to certify anything. And it is not meant to be immutable: these
 * are conventions WSJT-X's own guide says the user may edit, so Setup will make the
 * table editable and the guard's allow-list derives from whatever it holds.
 */
object DialFrequencies {

    /** Every entry, both modes. */
    val ALL: List<DialFrequency> = listOf(
        // --- FT8 -------------------------------------------------------------
        DialFrequency("160m", 1_840_000, "FT8", Region.ALL, true),
        DialFrequency("80m", 3_567_000, "FT8", Region.ALL, false),
        DialFrequency("80m", 3_573_000, "FT8", Region.ALL, true),
        DialFrequency("60m", 5_357_000, "FT8", Region.ALL, true),
        DialFrequency("40m", 7_056_000, "FT8", Region.ALL, false),
        DialFrequency("40m", 7_074_000, "FT8", Region.ALL, true),
        DialFrequency("30m", 10_131_000, "FT8", Region.ALL, false),
        DialFrequency("30m", 10_136_000, "FT8", Region.ALL, true),
        DialFrequency("20m", 14_074_000, "FT8", Region.ALL, true),
        DialFrequency("20m", 14_090_000, "FT8", Region.ALL, false),
        DialFrequency("17m", 18_095_000, "FT8", Region.ALL, false),
        DialFrequency("17m", 18_100_000, "FT8", Region.ALL, true),
        DialFrequency("15m", 21_074_000, "FT8", Region.ALL, true),
        DialFrequency("15m", 21_091_000, "FT8", Region.ALL, false),
        DialFrequency("12m", 24_911_000, "FT8", Region.ALL, false),
        DialFrequency("12m", 24_915_000, "FT8", Region.ALL, true),
        DialFrequency("10m", 28_074_000, "FT8", Region.ALL, true),
        DialFrequency("10m", 28_091_000, "FT8", Region.ALL, false),
        DialFrequency("6m", 50_313_000, "FT8", Region.ALL, true),
        DialFrequency("6m", 50_323_000, "FT8", Region.ALL, false),
        DialFrequency("4m", 70_154_000, "FT8", Region.R1, true),
        DialFrequency("2m", 144_174_000, "FT8", Region.ALL, true),
        DialFrequency("1.25m", 222_174_000, "FT8", Region.R2, true),
        DialFrequency("70cm", 432_174_000, "FT8", Region.ALL, true),
        DialFrequency("23cm", 1_296_174_000, "FT8", Region.ALL, true),

        // --- FT4. Deferred from v1 (ADR 0011), defined now so it is a switch-on.
        // Note there is deliberately NO 160 m entry: the source has none, and a
        // band picker must not offer one.
        DialFrequency("80m", 3_575_000, "FT4", Region.ALL, true),
        DialFrequency("80m", 3_568_000, "FT4", Region.R3, false),
        DialFrequency("60m", 5_357_000, "FT4", Region.ALL, true),
        // Not a whole kHz. This is why the table is in Hz.
        DialFrequency("40m", 7_047_500, "FT4", Region.ALL, true),
        DialFrequency("30m", 10_140_000, "FT4", Region.ALL, true),
        DialFrequency("20m", 14_080_000, "FT4", Region.ALL, true),
        DialFrequency("17m", 18_104_000, "FT4", Region.ALL, true),
        DialFrequency("15m", 21_140_000, "FT4", Region.ALL, true),
        DialFrequency("12m", 24_919_000, "FT4", Region.ALL, true),
        DialFrequency("10m", 28_180_000, "FT4", Region.ALL, true),
        DialFrequency("6m", 50_318_000, "FT4", Region.ALL, true),
        DialFrequency("2m", 144_170_000, "FT4", Region.ALL, true)
    )

    /**
     * How far from a listed dial frequency still counts as being on it.
     *
     * The radio reports its dial to the Hz, and an operator who has tuned by hand
     * can sit a few Hz off without meaning to be anywhere else. Narrow enough that
     * no two table entries can ever both match: the closest pair, 80 m FT4's
     * 3.575 and FT8's 3.573, are 2 kHz apart.
     */
    const val MATCH_TOLERANCE_HZ = 200L

    /** The entry the radio is sitting on, or null if it is not on a listed one. */
    fun at(hz: Long, mode: String? = null): DialFrequency? =
        ALL.filter { mode == null || it.mode == mode }
            .minByOrNull { kotlin.math.abs(it.hz - hz) }
            ?.takeIf { kotlin.math.abs(it.hz - hz) <= MATCH_TOLERANCE_HZ }

    /**
     * A band label for [hz], from the table alone.
     *
     * Deliberately **not** a band-plan lookup. This answers "which conventional
     * frequency is the radio on", not "which amateur band is this" — the band
     * edges are regulatory data we have not sourced, and inventing them would be
     * guessing. Off a listed frequency, the
     * honest answer is null, and the UI shows the dial frequency alone.
     */
    fun bandAt(hz: Long): String? = at(hz)?.band

    /** Is [hz] a frequency the app may transmit on? Alternates included. */
    fun isConventional(hz: Long, mode: String? = null): Boolean = at(hz, mode) != null

    /** The preferred frequencies for a mode, for band presets. */
    fun presetsFor(mode: String, region: Region = Region.ALL): List<DialFrequency> =
        ALL.filter { it.mode == mode && it.preferred && (it.region == Region.ALL || it.region == region) }
}
