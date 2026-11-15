package com.ft8ok.log

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * ADIF `.adi` files: write the log out, read one back in.
 *
 * Format per the ADIF 3.1.6 specification (https://adif.org/316/ADIF_316.htm),
 * verified: data specifiers `<NAME:LENGTH>` or `<NAME:LENGTH:TYPE>`
 * with case-independent names; an optional header, present only when the file
 * does not start with `<`, ended by `<EOH>`; each record ended by `<EOR>`;
 * `FREQ` in MHz; dates `YYYYMMDD`; times `HHMMSS` or `HHMM`, UTC.
 *
 * `.adi` is an ASCII format (the international fields are ADX-only), so the
 * writer refuses non-ASCII text rather than emit a length that some readers would
 * count in bytes and others in characters. The reader counts characters.
 */
object Adif {

    /** The ADIF version these files claim, matching the spec the format was checked against. */
    const val VERSION = "3.1.6"

    const val PROGRAM_ID = "FT8OK"

    // --- writing ---------------------------------------------------------------

    /** A whole `.adi` file: header, then one record per contact, in the order given. */
    fun write(qsos: List<LoggedQso>): String = buildString {
        append("FT8OK ADIF export\n")
        field("ADIF_VER", VERSION)
        field("PROGRAMID", PROGRAM_ID)
        append("<EOH>\n")
        for (qso in qsos) {
            for ((name, value) in fieldsOf(qso)) field(name, value)
            append("<EOR>\n")
        }
    }

    /**
     * Fields as bare ADIF data specifiers, no header or `<EOR>`. Lets the database keep
     * the fields it has no column for in one text column; [decodeFields] reverses it.
     */
    fun encodeFields(fields: Map<String, String>): String = buildString {
        for ((name, value) in fields.toSortedMap()) field(name, value)
    }

    /** The inverse of [encodeFields]. */
    fun decodeFields(text: String): Map<String, String> =
        if (text.isBlank()) emptyMap() else records("$text<EOR>").singleOrNull() ?: emptyMap()

    private fun StringBuilder.field(name: String, value: String) {
        require(value.all { it.code in 0x20..0x7E || it == '\n' || it == '\r' }) {
            "$name is not ASCII: \"$value\" - an .adi file cannot carry it"
        }
        append('<').append(name).append(':').append(value.length).append('>').append(value).append(' ')
    }

    /** The ADIF fields of one contact, in a stable order, empty values left out. */
    fun fieldsOf(q: LoggedQso): List<Pair<String, String>> {
        val on = Instant.ofEpochMilli(q.timeOnMs).atOffset(ZoneOffset.UTC)
        val off = q.timeOffMs?.let { Instant.ofEpochMilli(it).atOffset(ZoneOffset.UTC) }
        val known = listOf(
            "CALL" to q.call,
            "QSO_DATE" to DATE.format(on),
            "TIME_ON" to TIME.format(on),
            "QSO_DATE_OFF" to off?.let(DATE::format),
            "TIME_OFF" to off?.let(TIME::format),
            "BAND" to q.band,
            "FREQ" to q.freqHz?.let(::mhz),
            "MODE" to q.mode,
            "SUBMODE" to q.submode,
            "STATION_CALLSIGN" to q.stationCallsign,
            "MY_GRIDSQUARE" to q.myGrid,
            "GRIDSQUARE" to q.grid,
            "RST_SENT" to q.rstSent,
            "RST_RCVD" to q.rstRcvd,
            "STATE" to q.state,
            "DXCC" to q.dxcc?.toString(),
            "CQZ" to q.cqZone?.toString(),
            "ITUZ" to q.ituZone?.toString(),
            "CONT" to q.continent,
            "SIG" to q.sig,
            "SIG_INFO" to q.sigInfo,
            "MY_SIG" to q.mySig,
            "MY_SIG_INFO" to q.mySigInfo,
            "MY_STATE" to q.myState,
            "COMMENT" to q.comment
        )
        return known.mapNotNull { (k, v) -> if (v.isNullOrEmpty()) null else k to v } +
            q.extra.toSortedMap().map { (k, v) -> k to v }
    }

    /** Integer Hz to an exact MHz string, no floating point: 7074000 -> `7.074000`. */
    private fun mhz(hz: Long): String =
        "${hz / 1_000_000}.${(hz % 1_000_000).toString().padStart(6, '0')}"

    // --- reading ---------------------------------------------------------------

    /** What an import produced. Nothing is dropped silently: bad records say why. */
    data class ImportResult(
        val qsos: List<LoggedQso>,
        val rejected: List<Rejected>
    )

    /** A record that could not become a contact, with its raw fields so nothing is lost. */
    data class Rejected(val fields: Map<String, String>, val reason: String)

    /** Parse a `.adi` file into contacts. */
    fun read(text: String): ImportResult {
        val qsos = mutableListOf<LoggedQso>()
        val rejected = mutableListOf<Rejected>()
        for (fields in records(text)) {
            try {
                qsos += toQso(fields)
            } catch (e: IllegalArgumentException) {
                rejected += Rejected(fields, e.message ?: "unreadable")
            }
        }
        return ImportResult(qsos, rejected)
    }

    /** The raw records of a file, each as upper-case field name to value. Header skipped. */
    fun records(text: String): List<Map<String, String>> {
        var i = 0
        if (!text.startsWith("<")) {
            val eoh = Regex("<eoh>", RegexOption.IGNORE_CASE).find(text)
                ?: throw IllegalArgumentException("file has a header but no <EOH>")
            i = eoh.range.last + 1
        }
        val out = mutableListOf<Map<String, String>>()
        var current = linkedMapOf<String, String>()
        while (true) {
            val open = text.indexOf('<', i)
            if (open < 0) break
            val close = text.indexOf('>', open)
            if (close < 0) break
            val spec = text.substring(open + 1, close).split(':')
            val name = spec[0].trim().uppercase()
            i = close + 1
            when {
                name == "EOR" -> {
                    if (current.isNotEmpty()) out += current
                    current = linkedMapOf()
                }
                spec.size >= 2 -> {
                    val len = spec[1].trim().toIntOrNull()
                        ?: throw IllegalArgumentException("bad length in <${spec.joinToString(":")}>")
                    require(i + len <= text.length) { "<$name> runs past the end of the file" }
                    current[name] = text.substring(i, i + len)
                    i += len
                }
                // A bare tag other than EOR (a stray EOH, say) carries no data.
            }
        }
        return out
    }

    private val KNOWN = setOf(
        "CALL", "QSO_DATE", "TIME_ON", "QSO_DATE_OFF", "TIME_OFF", "BAND", "FREQ", "MODE",
        "SUBMODE", "STATION_CALLSIGN", "MY_GRIDSQUARE", "GRIDSQUARE", "RST_SENT", "RST_RCVD",
        "STATE", "DXCC", "CQZ", "ITUZ", "CONT", "SIG", "SIG_INFO", "MY_SIG", "MY_SIG_INFO",
        "MY_STATE", "COMMENT"
    )

    /** One raw record to a contact. Throws [IllegalArgumentException] naming what is wrong. */
    fun toQso(f: Map<String, String>): LoggedQso {
        fun opt(name: String) = f[name]?.trim()?.ifEmpty { null }
        fun req(name: String) = opt(name) ?: throw IllegalArgumentException("no $name")

        val dateOn = parseDate(req("QSO_DATE"))
        val onMs = dateOn.atTime(parseTime(req("TIME_ON"))).toInstant(ZoneOffset.UTC).toEpochMilli()

        val offMs = opt("TIME_OFF")?.let { t ->
            val explicitDate = opt("QSO_DATE_OFF")
            val ms = (explicitDate?.let(::parseDate) ?: dateOn)
                .atTime(parseTime(t)).toInstant(ZoneOffset.UTC).toEpochMilli()
            // No QSO_DATE_OFF and an off-time before the on-time: the contact
            // crossed midnight UTC.
            if (explicitDate == null && ms < onMs) ms + 86_400_000L else ms
        }

        val freqHz = opt("FREQ")?.let {
            try {
                BigDecimal(it).movePointRight(6).setScale(0, RoundingMode.HALF_UP).longValueExact()
            } catch (e: NumberFormatException) {
                throw IllegalArgumentException("bad FREQ \"$it\"")
            }
        }

        return LoggedQso(
            call = req("CALL").uppercase(),
            timeOnMs = onMs,
            timeOffMs = offMs,
            freqHz = freqHz,
            band = opt("BAND")?.lowercase() ?: freqHz?.let(AdifBand::forHz),
            mode = req("MODE").uppercase(),
            submode = opt("SUBMODE")?.uppercase(),
            stationCallsign = opt("STATION_CALLSIGN"),
            myGrid = opt("MY_GRIDSQUARE"),
            grid = opt("GRIDSQUARE"),
            rstSent = opt("RST_SENT"),
            rstRcvd = opt("RST_RCVD"),
            state = opt("STATE"),
            dxcc = opt("DXCC")?.toIntOrNull(),
            cqZone = opt("CQZ")?.toIntOrNull(),
            ituZone = opt("ITUZ")?.toIntOrNull(),
            continent = opt("CONT"),
            sig = opt("SIG"),
            sigInfo = opt("SIG_INFO"),
            mySig = opt("MY_SIG"),
            mySigInfo = opt("MY_SIG_INFO"),
            myState = opt("MY_STATE"),
            comment = opt("COMMENT"),
            extra = f.filterKeys { it !in KNOWN }
        )
    }

    private val DATE = DateTimeFormatter.ofPattern("yyyyMMdd")
    private val TIME = DateTimeFormatter.ofPattern("HHmmss")
    private val TIME_HHMM = DateTimeFormatter.ofPattern("HHmm")

    private fun parseDate(s: String): LocalDate = try {
        LocalDate.parse(s, DATE)
    } catch (e: DateTimeParseException) {
        throw IllegalArgumentException("bad date \"$s\"")
    }

    private fun parseTime(s: String): LocalTime = try {
        when (s.length) {
            6 -> LocalTime.parse(s, TIME)
            4 -> LocalTime.parse(s, TIME_HHMM)
            else -> throw IllegalArgumentException("bad time \"$s\"")
        }
    } catch (e: DateTimeParseException) {
        throw IllegalArgumentException("bad time \"$s\"")
    }
}
