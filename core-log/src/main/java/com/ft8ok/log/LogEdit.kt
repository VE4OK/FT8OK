package com.ft8ok.log

import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * Editing one log entry by hand: the fields as text, and the
 * checks that keep what is saved a valid ADIF record.
 *
 * **A field the operator did not change is kept exactly as it was**, even if it
 * would not pass the checks: a contact imported from another program may carry an
 * SSB `59` report or a six-figure frequency the form cannot show, and editing its
 * comment must not reject or round them.
 */
object LogEdit {

    /** The form's text, one string per field. */
    data class Form(
        val call: String,
        /** UTC, `yyyy-MM-dd HH:mm`. */
        val timeOn: String,
        /** Dial frequency in MHz, e.g. `14.074`. Empty for none. */
        val freqMhz: String,
        val grid: String,
        val rstSent: String,
        val rstRcvd: String,
        val comment: String
    )

    enum class Field { CALL, TIME_ON, FREQ, GRID, RST_SENT, RST_RCVD, COMMENT }

    enum class Problem {
        /** Callsign: letters, digits and `/`, with a digit. */
        BAD_CALL,
        /** Not `yyyy-MM-dd HH:mm`. */
        BAD_TIME,
        /** Not a number, or in no amateur band. */
        BAD_FREQ,
        /** Not a 4- or 6-character Maidenhead locator. */
        BAD_GRID,
        /** Not a report: `-12`, `+05`, or up to 8 plain characters. */
        BAD_REPORT,
        /** Outside printable ASCII: an `.adi` file cannot carry it. */
        NOT_ASCII
    }

    sealed interface Outcome {
        data class Ok(val qso: LoggedQso) : Outcome
        data class Invalid(val problems: Map<Field, Problem>) : Outcome
    }

    private val TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    private val CALL = Regex("^[A-Z0-9]+(/[A-Z0-9]+)*$")
    private val GRID = Regex("^[A-R]{2}[0-9]{2}([A-X]{2})?$")
    private val SIGNED = Regex("^[+-]?[0-9]{1,2}$")
    private val FREQ = Regex("^[0-9]{1,5}(\\.[0-9]{1,6})?$")

    fun formOf(q: LoggedQso) = Form(
        call = q.call,
        timeOn = timeText(q.timeOnMs),
        freqMhz = q.freqHz?.let(::mhzText) ?: "",
        grid = q.grid ?: "",
        rstSent = q.rstSent ?: "",
        rstRcvd = q.rstRcvd ?: "",
        comment = q.comment ?: ""
    )

    /** [original] with the form's changes, or what is wrong with them. */
    fun apply(original: LoggedQso, form: Form): Outcome {
        val before = formOf(original)
        val problems = LinkedHashMap<Field, Problem>()
        var q = original

        if (form.call.trim() != before.call) {
            val call = form.call.trim().uppercase()
            if (call.length !in 3..15 || !CALL.matches(call) || call.none(Char::isDigit)) {
                problems[Field.CALL] = Problem.BAD_CALL
            } else q = q.copy(call = call)
        }

        if (form.timeOn.trim() != before.timeOn) {
            val ms = parseTime(form.timeOn.trim())
            if (ms == null) problems[Field.TIME_ON] = Problem.BAD_TIME
            else {
                // The contact moves as a whole: TIME_OFF keeps its distance from TIME_ON.
                val shift = ms - original.timeOnMs
                q = q.copy(timeOnMs = ms, timeOffMs = original.timeOffMs?.plus(shift))
            }
        }

        if (form.freqMhz.trim() != before.freqMhz) {
            val text = form.freqMhz.trim()
            if (text.isEmpty()) q = q.copy(freqHz = null)
            else {
                val hz = if (FREQ.matches(text)) BigDecimal(text).movePointRight(6).toLong() else null
                val band = hz?.let(AdifBand::forHz)
                if (hz == null || band == null) problems[Field.FREQ] = Problem.BAD_FREQ
                else q = q.copy(freqHz = hz, band = band)
            }
        }

        if (form.grid.trim() != before.grid) {
            val grid = form.grid.trim().uppercase()
            if (grid.isEmpty()) q = q.copy(grid = null)
            else if (!GRID.matches(grid)) problems[Field.GRID] = Problem.BAD_GRID
            // The usual written form: field and square upper case, subsquare lower.
            else q = q.copy(grid = grid.take(4) + grid.drop(4).lowercase())
        }

        report(form.rstSent, before.rstSent)?.let { r ->
            r.fold({ q = q.copy(rstSent = it) }, { problems[Field.RST_SENT] = Problem.BAD_REPORT })
        }
        report(form.rstRcvd, before.rstRcvd)?.let { r ->
            r.fold({ q = q.copy(rstRcvd = it) }, { problems[Field.RST_RCVD] = Problem.BAD_REPORT })
        }

        if (form.comment.trim() != before.comment) {
            val comment = form.comment.trim()
            if (!comment.all { it in ' '..'~' }) problems[Field.COMMENT] = Problem.NOT_ASCII
            else q = q.copy(comment = comment.ifEmpty { null })
        }

        return if (problems.isEmpty()) Outcome.Ok(q) else Outcome.Invalid(problems)
    }

    /**
     * A changed report, or null if unchanged. FT8 style when it is a number (`-5`
     * becomes `-05`, as the app writes them); otherwise short plain text, for a
     * contact in another mode.
     */
    private fun report(text: String, before: String): Result<String?>? {
        val t = text.trim()
        if (t == before) return null
        return when {
            t.isEmpty() -> Result.success(null)
            SIGNED.matches(t) -> Result.success(LoggedQso.formatReport(t.toInt()))
            t.length <= 8 && t.all { it in '!'..'~' } -> Result.success(t)
            else -> Result.failure(IllegalArgumentException(t))
        }
    }

    fun timeText(epochMs: Long): String =
        LocalDateTime.ofEpochSecond(Math.floorDiv(epochMs, 1000L), 0, ZoneOffset.UTC).format(TIME)

    private fun parseTime(text: String): Long? = try {
        LocalDateTime.parse(text, TIME).toInstant(ZoneOffset.UTC).toEpochMilli()
    } catch (_: DateTimeParseException) {
        null
    }

    /** `14.074`, `7.074`, `10.136`: MHz with trailing zeros dropped, no floating point. */
    fun mhzText(hz: Long): String = BigDecimal.valueOf(hz).movePointLeft(6).stripTrailingZeros().toPlainString()
}
