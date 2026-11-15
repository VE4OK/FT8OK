package com.ft8ok.log

/**
 * ADIF's Band enumeration: which band a frequency is on.
 *
 * Transcribed from the ADIF 3.1.6 specification's Band table
 * (https://adif.org/316/ADIF_316.htm), verified - not from memory
 * Both edges are inclusive, as the spec lists them.
 *
 * Needed because POTA requires `BAND` on every QSO and the
 * operator may TX off the dial-frequency table with the guard overridden, so the
 * band cannot come from [com.ft8ok.model.DialFrequencies] alone. The whole table,
 * not just the IC-7300's bands: the dial table itself reaches 23cm.
 */
object AdifBand {

    private class Range(val name: String, val lowHz: Long, val highHz: Long)

    private val TABLE = listOf(
        Range("2190m", 135_700, 137_800),
        Range("630m", 472_000, 479_000),
        Range("560m", 501_000, 504_000),
        Range("160m", 1_800_000, 2_000_000),
        Range("80m", 3_500_000, 4_000_000),
        Range("60m", 5_060_000, 5_450_000),
        Range("40m", 7_000_000, 7_300_000),
        Range("30m", 10_100_000, 10_150_000),
        Range("20m", 14_000_000, 14_350_000),
        Range("17m", 18_068_000, 18_168_000),
        Range("15m", 21_000_000, 21_450_000),
        Range("12m", 24_890_000, 24_990_000),
        Range("10m", 28_000_000, 29_700_000),
        Range("8m", 40_000_000, 45_000_000),
        Range("6m", 50_000_000, 54_000_000),
        Range("5m", 54_000_001, 69_900_000),
        Range("4m", 70_000_000, 71_000_000),
        Range("2m", 144_000_000, 148_000_000),
        Range("1.25m", 222_000_000, 225_000_000),
        Range("70cm", 420_000_000, 450_000_000),
        Range("33cm", 902_000_000, 928_000_000),
        Range("23cm", 1_240_000_000, 1_300_000_000),
        Range("13cm", 2_300_000_000, 2_450_000_000),
        Range("9cm", 3_300_000_000, 3_500_000_000),
        Range("6cm", 5_650_000_000, 5_925_000_000),
        Range("3cm", 10_000_000_000, 10_500_000_000),
        Range("1.25cm", 24_000_000_000, 24_250_000_000),
        Range("6mm", 47_000_000_000, 47_200_000_000),
        Range("4mm", 75_500_000_000, 81_000_000_000),
        Range("2.5mm", 119_980_000_000, 123_000_000_000),
        Range("2mm", 134_000_000_000, 149_000_000_000),
        Range("1mm", 241_000_000_000, 250_000_000_000),
        Range("submm", 300_000_000_000, 7_500_000_000_000)
    )

    /** The ADIF band name for [hz], or null if it is on no amateur band in the table. */
    fun forHz(hz: Long): String? = TABLE.firstOrNull { hz in it.lowHz..it.highHz }?.name
}
