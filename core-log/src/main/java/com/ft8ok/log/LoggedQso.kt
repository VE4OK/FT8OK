package com.ft8ok.log

import com.ft8ok.model.Mode
import com.ft8ok.model.StationProfile
import com.ft8ok.qso.Qso
import java.util.Locale

/**
 * One contact as it is kept in the log: the shape of an ADIF record.
 *
 * Fields are the ADIF fields this app reads and writes, by their ADIF meaning.
 * Anything else an imported file carries is kept verbatim in [extra] and written
 * back out, because a log is precious (rule 8) and an import must not quietly lose
 * what another program recorded.
 *
 * MODE and SUBMODE are stored as ADIF has them, not as a [Mode], so a log imported
 * from elsewhere (SSB, CW, JS8) round-trips intact.
 */
data class LoggedQso(
    /** The other station: ADIF `CALL`. */
    val call: String,

    /** UTC epoch ms of the first message: `QSO_DATE` + `TIME_ON`. ADIF keeps whole seconds. */
    val timeOnMs: Long,

    /** UTC epoch ms of the close: `QSO_DATE_OFF` + `TIME_OFF`. Null if an import had none. */
    val timeOffMs: Long? = null,

    /**
     * `FREQ`, in Hz. The **dial** frequency, which is what decides the band; the TX
     * audio offset is not added. Null if an import carried only `BAND`.
     */
    val freqHz: Long? = null,

    /** ADIF `BAND`, e.g. `40m`. */
    val band: String? = null,

    /** ADIF `MODE`, e.g. `FT8`, or `MFSK` for FT4. */
    val mode: String,

    /** ADIF `SUBMODE`, e.g. `FT4`. */
    val submode: String? = null,

    /** Our call as transmitted: `STATION_CALLSIGN`. POTA requires it or `OPERATOR`. */
    val stationCallsign: String? = null,

    /** Our grid: `MY_GRIDSQUARE`. */
    val myGrid: String? = null,

    /** Their grid: `GRIDSQUARE`. Not every contact carries one. */
    val grid: String? = null,

    /** Report we sent: `RST_SENT`, FT8 style, e.g. `-12`. */
    val rstSent: String? = null,

    /** Report they sent: `RST_RCVD`. */
    val rstRcvd: String? = null,

    // Where they are. Null until an entity table exists (ADR 0012), but kept in the
    // record now because the Roster tab sorts by region and state, and imported logs often carry them already.

    /** `STATE`: their state or province. */
    val state: String? = null,

    /** `DXCC`: their entity code. */
    val dxcc: Int? = null,

    /** `CQZ`. */
    val cqZone: Int? = null,

    /** `ITUZ`. */
    val ituZone: Int? = null,

    /** `CONT`: their continent. */
    val continent: String? = null,

    // POTA/SOTA, mapped as the ADIF spec defines them. The activation profile
    // that fills these is deferred (ADR 0011); the fields are not.

    /** `SIG`: their special-interest programme, `POTA` for park-to-park. */
    val sig: String? = null,

    /** `SIG_INFO`: their park reference. */
    val sigInfo: String? = null,

    /** `MY_SIG`: `POTA` when we are activating. */
    val mySig: String? = null,

    /** `MY_SIG_INFO`: our park reference. */
    val mySigInfo: String? = null,

    /** `MY_STATE`: recommended by POTA. */
    val myState: String? = null,

    /** `COMMENT`. */
    val comment: String? = null,

    /** Every other field from an import, by upper-case ADIF name, written back unchanged. */
    val extra: Map<String, String> = emptyMap()
) {

    companion object {

        /** ADIF's MODE and SUBMODE for an on-air [Mode]. FT4 is a submode of MFSK in ADIF 3.1.6. */
        fun adifModeOf(mode: Mode): Pair<String, String?> = when (mode.name) {
            "FT4" -> "MFSK" to "FT4"
            else -> mode.name to null
        }

        /** An FT8/FT4 report as the protocol shows it: `-12`, `+05`. */
        fun formatReport(db: Int): String = String.format(Locale.ROOT, "%+03d", db)

        /**
         * The log record for a contact the QSO engine has run.
         *
         * Refuses a contact that is not [Qso.loggable] unless [logAnyway] is set:
         * auto-logging only ever logs complete contacts, and "Log anyway" is the
         * operator's decision, never the app's.
         */
        fun fromQso(
            qso: Qso,
            station: StationProfile,
            /** Null when there is no CAT link to read it from; the record then has no FREQ or BAND. */
            dialHz: Long?,
            mode: Mode,
            logAnyway: Boolean = false
        ): LoggedQso {
            require(qso.loggable || logAnyway) { "incomplete contact with ${qso.partner}" }
            val (adifMode, adifSubmode) = adifModeOf(mode)
            return LoggedQso(
                call = qso.partner,
                timeOnMs = qso.startedAtMs,
                timeOffMs = qso.lastActivityMs,
                freqHz = dialHz,
                band = dialHz?.let(AdifBand::forHz),
                mode = adifMode,
                submode = adifSubmode,
                stationCallsign = station.normalisedCallsign.ifEmpty { null },
                myGrid = station.normalisedGrid.ifEmpty { null },
                grid = qso.partnerGrid,
                rstSent = qso.reportSentDb?.let(::formatReport),
                rstRcvd = qso.reportReceivedDb?.let(::formatReport),
                // A contest contact's exchange and IDs.
                extra = ContestLog.fields(qso)
            )
        }
    }
}
