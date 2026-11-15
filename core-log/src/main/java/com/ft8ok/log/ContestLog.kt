package com.ft8ok.log

import com.ft8ok.model.ContestSetup
import com.ft8ok.qso.Qso

/**
 * What a contest contact adds to its log record, and what makes a call a dupe.
 *
 * **Fields, verified** against the ADIF 3.1.6 specification (adif.org) and
 * WSJT-X's `logbook.cpp`:
 * - `CONTEST_ID`: `ARRL-FIELD-DAY` is in ADIF's Contest_ID enumeration and is what
 *   WSJT-X writes. The grid contests get none, as from WSJT-X: one setting covers NA
 *   VHF (three IDs by month), WW Digi and ARRL Digi, and only the operator knows which.
 * - `STX_STRING` / `SRX_STRING`: the exchange sent and received, as sent (ADIF:
 *   "contest QSO transmitted/received information"). WSJT-X writes SRX_STRING for
 *   Field Day; STX_STRING is written here too, so the log says what we sent.
 * - `CLASS` and `ARRL_SECT`: their class and section, as WSJT-X writes them. DX is
 *   not in ADIF's ARRL_SECT enumeration; WSJT-X writes it anyway ("because N1MM does
 *   the same"), and so does this.
 * - `APP_FT8OK_CONTEST`: which of our contest modes logged it (ADIF's application-
 *   defined `APP_{PROGRAMID}_{FIELDNAME}` form), so a dupe can be told from a contact
 *   in another contest or none.
 *
 * RST_SENT/RST_RCVD stay empty unless a signal report really was exchanged (a station
 * not in the contest): contest exchanges carry no dB report. WSJT-X puts the Field Day
 * class there instead; that is not a report, so it is not copied.
 */
object ContestLog {

    const val CONTEST_ID = "CONTEST_ID"
    const val STX_STRING = "STX_STRING"
    const val SRX_STRING = "SRX_STRING"
    const val CLASS = "CLASS"
    const val ARRL_SECT = "ARRL_SECT"
    const val APP_CONTEST = "APP_FT8OK_CONTEST"

    /**
     * How far back a contact counts as one in the same contest, for dupes: 48 hours,
     * longer than any contest these formats are used in (Field Day is 24 h of
     * operating; NA VHF at most 33 h). A chosen default.
     */
    const val DUPE_WINDOW_MS = 48 * 3_600_000L

    /** The extra ADIF fields for [qso]; empty for a standard contact. */
    fun fields(qso: Qso): Map<String, String> {
        val c = qso.contest ?: return emptyMap()
        val out = linkedMapOf(APP_CONTEST to c.kind.name)
        qso.exchangeSent?.let { out[STX_STRING] = it }
        qso.exchangeReceived?.let { out[SRX_STRING] = it }
        if (c.kind == ContestSetup.Kind.FIELD_DAY) {
            out[CONTEST_ID] = ContestSetup.FIELD_DAY_CONTEST_ID
            qso.exchangeReceived?.split(' ')?.takeIf { it.size == 2 }?.let { (cls, sec) ->
                out[CLASS] = cls
                out[ARRL_SECT] = sec
            }
        }
        return out
    }

    /**
     * Is [call] a dupe: worked on [band] in a contact logged in contest mode [kind],
     * within [DUPE_WINDOW_MS] before [nowMs]? Marked, never refused.
     */
    fun isDupe(log: List<LoggedQso>, call: String, band: String?, kind: ContestSetup.Kind, nowMs: Long): Boolean =
        band != null && inContest(log, kind, nowMs).any {
            it.call.equals(call, ignoreCase = true) && it.band.equals(band, ignoreCase = true)
        }

    /** The contacts of [log] that make dupes in contest mode [kind] at [nowMs]. */
    fun inContest(log: List<LoggedQso>, kind: ContestSetup.Kind, nowMs: Long): List<LoggedQso> =
        log.filter { it.extra[APP_CONTEST] == kind.name && nowMs - it.timeOnMs in 0..DUPE_WINDOW_MS }
}
