package com.ft8ok.model

/**
 * One decoded FT8 message, read rather than guessed at.
 *
 * The decoder hands us a line of text; everything above needs to know what *kind*
 * of thing was said, because that is what drives the QSO. Parsing it in one place,
 * with tests, beats pattern-matching strings at each call site — a QSO engine that
 * mistakes a grid for a signal report will send the wrong thing on the air.
 *
 * Covers the standard messages an FT8 QSO is made of, and contest exchanges
 * ([Contest]), which name a sender but are never answered. Anything else — free
 * text, telemetry, WSPR-in-FT8 — parses as [Unknown], which is a real answer and not
 * a failure: the app shows it in the feed and simply does not build a QSO out of it.
 *
 * **Hashed callsigns appear as `<...>`** when the decoder knows a callsign is
 * present but has not heard the message that defines it. They are kept as written:
 * they are legitimate on the air, and treating one as a callsign we could answer
 * would mean transmitting to a station we cannot name.
 *
 * **A hash the decoder resolved, `<VE3XAA/QRP>`, is the call itself**, so its
 * brackets are dropped: the brackets only say how it travelled, and a station with
 * a call FT8 cannot carry in full sends every message but its CQ that way.
 * Kept, they made `VE4OK <VE3XAA/QRP> -12` a message from
 * somebody other than the VE3XAA/QRP we called.
 */
sealed interface Ft8Message {

    val raw: String

    /** `CQ W3XAA EM13`, `CQ DX NO5Q EL15`, `CQ POTA K1ABC FN42`. */
    data class Cq(
        val caller: String,
        val grid: String?,
        /** `DX`, `POTA`, a zone number — the qualifier after CQ, when there is one. */
        val directedTo: String?,
        override val raw: String
    ) : Ft8Message

    /**
     * `W3XAA K1ABC FN42` — an answer carrying the sender's grid — or `W3XAA K1ABC`
     * with none: not every station sends its location.
     * Either way the first call of a contact, answered with a report.
     */
    data class Reply(
        val to: String,
        val from: String,
        /** Null when they sent no grid. */
        val grid: String?,
        override val raw: String
    ) : Ft8Message

    /**
     * `K1ABC W3XAA -12` or `W3XAA K1ABC R-09`.
     *
     * @param roger true for the `R` form, which means "I have your report **and**
     *   here is mine". The distinction drives the whole second half of a QSO.
     */
    data class Report(
        val to: String,
        val from: String,
        val reportDb: Int,
        val roger: Boolean,
        override val raw: String
    ) : Ft8Message

    /** `K1ABC W3XAA RR73`, `... RRR`, `... 73`. */
    data class Acknowledge(
        val to: String,
        val from: String,
        val kind: Kind,
        override val raw: String
    ) : Ft8Message {
        enum class Kind {
            /** `RRR` — received, but does not itself close the QSO. */
            RRR,

            /** `RR73` — received, and goodbye. The usual closer. */
            RR73,

            /** `73` — goodbye. */
            SEVENTY_THREE
        }

        /** Does this message end the contact, rather than merely acknowledge? */
        val closes: Boolean get() = kind != Kind.RRR
    }

    /**
     * `K1ABC RR73; W9XYZ <KH1/KH7Z> -12` - FT8's DXpedition message (type 0.1), sent
     * by a WSJT-X Fox and by MSHV's multi-answer mode: one transmission that closes
     * one contact and sends the report that opens the next.
     *
     * Two ordinary messages in one, so it is modelled as the two: [closing], an RR73
     * to the first station, and [report], a plain report to the second, both from
     * [from]. Only these roles exist - the first station only ever gets RR73, the
     * second only a report - so it never carries two arbitrary messages.
     *
     * The sender goes on the air only as a 10-bit hash. The decoder names it when it
     * has heard that call in full before (usually the CQ that was answered), and
     * otherwise shows `<...>`, which is [HASHED] here. A named sender is given
     * without its brackets so it compares equal to the call we are working.
     */
    data class Dxpedition(
        val closing: Acknowledge,
        val report: Report,
        override val raw: String
    ) : Ft8Message {
        val from: String get() = report.from

        /** The half of this message addressed to [call], or null if neither is. */
        fun partFor(call: String): Ft8Message? = when {
            closing.to.equals(call, ignoreCase = true) -> closing
            report.to.equals(call, ignoreCase = true) -> report
            else -> null
        }
    }

    /**
     * A contest exchange, in one of the four forms WSJT-X sends
     * (`genStdMsgs`, `unpack77`):
     * - [Kind.GRID] `W9XYZ K1ABC R EM48`: NA VHF, WW Digi and ARRL Digi send a grid
     *   where a report would go. Only the `R` form is here; the plain one is a [Reply].
     * - [Kind.FIELD_DAY] `W9XYZ K1ABC R 2B EMA`: class and ARRL section (type 0.3/0.4).
     * - [Kind.RTTY] `TU; W9XYZ K1ABC R 579 MA`: report and state, province or serial (type 3).
     * - [Kind.EU_VHF] `<PA9XYZ> <G4ABC/P> R 570007 JO22DB`: report and serial, then a
     *   6-character grid, both calls hashed (type 5).
     *
     * **Shown and counted as heard, never answered:** the engine sends only standard
     * messages, so it ignores these. [from] is the sender, [to] the addressee.
     */
    data class Contest(
        val to: String,
        val from: String,
        val kind: Kind,
        /** `R`: they have our exchange. */
        val roger: Boolean,
        /** `TU;`: an RTTY Roundup thank-you to the previous station. */
        val thanks: Boolean,
        /** What follows the calls and the R, as sent: `2B EMA`, `579 MA`, `570007 JO22DB`, `EM48`. */
        val exchange: String,
        /** The sender's 4-character grid, for the grid forms; null for the others. */
        val grid: String?,
        override val raw: String
    ) : Ft8Message {
        enum class Kind { GRID, FIELD_DAY, RTTY, EU_VHF }
    }

    /** Anything we do not model. Shown, never acted on. */
    data class Unknown(override val raw: String) : Ft8Message

    companion object {

        /** A 4-character Maidenhead locator: two letters A–R, then two digits. */
        private val GRID = Regex("^[A-Ra-r]{2}[0-9]{2}$")

        /** A signal report, with or without the leading R: `-12`, `+03`, `R-09`. */
        private val REPORT = Regex("^(R)?([+-][0-9]{1,2})$")

        /** `pack_basecall`'s two shapes: `AB0XYZ` (digit third) and `A0XYZ` (digit second). */
        private val PLAIN_CALL = Regex("^([A-Z0-9][A-Z0-9][0-9]|[A-Z0-9][0-9])[A-Z]{0,3}$")

        /** The hashed-callsign placeholder the decoder emits. */
        const val HASHED = "<...>"

        /**
         * Does [text] mention [call] as a whole word? `<VE4OK>` (a hashed call the
         * decoder has resolved) counts; `VE4OKA` and `XVE4OK` do not. Case-insensitive,
         * and false for a blank call so an unset profile highlights nothing.
         */
        fun mentions(text: String, call: String): Boolean {
            val wanted = call.trim()
            if (wanted.isEmpty()) return false
            return text.split(' ').any { it.trim('<', '>').equals(wanted, ignoreCase = true) }
        }

        /**
         * A call FT8 carries in full in a 28-bit field, with no `/P`, `/R` or prefix:
         * what both partner calls of a DXpedition message must be. Mirrors ft8_lib's
         * `pack_basecall` for ordinary calls (digit second or third, at most three
         * letters after it); its `3DA0`/`3X` special cases read as not plain here, so
         * those stations get ordinary messages. The encoder refuses anything else too.
         */
        fun isPlainStandardCall(call: String): Boolean =
            call.length >= 3 && call.any { it in 'A'..'Z' } && PLAIN_CALL.matches(call)

        /**
         * The report a DXpedition message actually carries for [db]: even values only,
         * -30..+32, an odd one going out one lower - WSJT-X's pack77_01, `n5 = (n+30)/2`
         * truncated. Used for the text, so the feed, the log and the air agree.
         */
        fun dxpeditionReport(db: Int): Int = 2 * ((db + 30) / 2).coerceIn(0, 31) - 30

        /**
         * `K1ABC RR73; W9XYZ <VE4OK> -14`: RR73 to [closeTo] and a report to
         * [reportTo] from [from], in one transmission. Null when
         * either partner call is not [isPlainStandardCall].
         */
        fun dxpeditionText(closeTo: String, reportTo: String, from: String, reportDb: Int): String? {
            if (!isPlainStandardCall(closeTo) || !isPlainStandardCall(reportTo)) return null
            if (closeTo.equals(reportTo, ignoreCase = true)) return null
            return "$closeTo RR73; $reportTo <$from> ${"%+03d".format(dxpeditionReport(reportDb))}"
        }

        /**
         * Parse one decoded line.
         *
         * Never throws and never returns null: an unreadable message is [Unknown].
         */
        fun parse(raw: String): Ft8Message {
            val text = raw.trim()
            val tokens = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (tokens.isEmpty()) return Unknown(raw)

            if (tokens[0].equals("CQ", ignoreCase = true)) return parseCq(tokens, raw)
            if (tokens[0].equals("TU;", ignoreCase = true)) return parseContest(tokens.drop(1), thanks = true, raw)
            if (tokens.size == 5 && tokens[1].equals("RR73;", ignoreCase = true)) return parseDxpedition(tokens, raw)
            if (tokens.size == 2) return parseGridless(tokens, raw)
            if (tokens.size < 3) return Unknown(raw)

            val to = unbracket(tokens[0])
            val from = unbracket(tokens[1])
            val last = tokens[2]

            ackKind(last)?.let { return Acknowledge(to, from, it, raw) }

            if (GRID.matches(last)) return Reply(to, from, last.uppercase(), raw)

            REPORT.matchEntire(last)?.let { m ->
                val value = m.groupValues[2].toIntOrNull() ?: return Unknown(raw)
                return Report(to, from, value, roger = m.groupValues[1] == "R", raw = raw)
            }

            // `W3XAA K1ABC RRR` is covered above. What is left is a contest exchange or
            // something we do not model.
            return parseContest(tokens, thanks = false, raw)
        }

        /** The CQ words WSJT-X's contest modes call with (`m_cqStr`, mainwindow.cpp). */
        val CONTEST_CQ_WORDS = setOf("FD", "RU", "TEST", "WW")

        /** Is [message] a contest station's: a contest exchange, or a CQ FD/RU/TEST/WW? */
        fun isContest(message: Ft8Message): Boolean =
            message is Contest || (message is Cq && message.directedTo in CONTEST_CQ_WORDS)

        private val FD_CLASS = Regex("^[0-9]{1,2}[A-H]$")
        private val SECTION = Regex("^[A-Z]{2,3}$")
        private val RST = Regex("^5[2-9]9$")
        private val RTTY_EXCHANGE = Regex("^([A-Z]{2,3}|X[0-9]{2}|[0-9]{4})$")
        private val EU_EXCHANGE = Regex("^5[2-9][0-9]{4}$")
        private val GRID6 = Regex("^[A-R]{2}[0-9]{2}[A-X]{2}$")

        /**
         * [tokens] from the first call on: `TO FROM [R] exchange...`, the forms the
         * decoder writes for [Contest] (ftx_message_decode_contest and the type 1 `R
         * GRID`). Anything else is [Unknown].
         */
        private fun parseContest(tokens: List<String>, thanks: Boolean, raw: String): Ft8Message {
            if (tokens.size < 3) return Unknown(raw)
            // Free text that happens to look like an exchange stays free text.
            fun isCall(word: String) = word == HASHED ||
                TxText.isCallsignShape(word.removeSurrounding("<", ">").uppercase())
            if (!isCall(tokens[0]) || !isCall(tokens[1])) return Unknown(raw)
            val words = tokens.map { it.uppercase() }
            val roger = words[2] == "R"
            val rest = words.drop(if (roger) 3 else 2)
            val (kind, grid) = when {
                rest.size == 1 && roger && !thanks && GRID.matches(rest[0]) -> Contest.Kind.GRID to rest[0]
                rest.size != 2 -> return Unknown(raw)
                !thanks && FD_CLASS.matches(rest[0]) && SECTION.matches(rest[1]) -> Contest.Kind.FIELD_DAY to null
                RST.matches(rest[0]) && RTTY_EXCHANGE.matches(rest[1]) -> Contest.Kind.RTTY to null
                !thanks && EU_EXCHANGE.matches(rest[0]) && GRID6.matches(rest[1]) -> Contest.Kind.EU_VHF to rest[1].take(4)
                else -> return Unknown(raw)
            }
            return Contest(
                to = unbracket(tokens[0]),
                from = unbracket(tokens[1]),
                kind = kind,
                roger = roger,
                thanks = thanks,
                exchange = rest.joinToString(" "),
                grid = grid,
                raw = raw
            )
        }

        private fun parseCq(tokens: List<String>, raw: String): Ft8Message = when (tokens.size) {
            2 -> Cq(tokens[1], null, null, raw)
            3 -> if (GRID.matches(tokens[2])) {
                Cq(tokens[1], tokens[2].uppercase(), null, raw)
            } else {
                // `CQ DX K1ABC` — a directed CQ with no grid.
                Cq(tokens[2], null, tokens[1].uppercase(), raw)
            }
            4 -> Cq(tokens[2], tokens[3].takeIf { GRID.matches(it) }?.uppercase(), tokens[1].uppercase(), raw)
            else -> Unknown(raw)
        }

        /**
         * `VE4OK AD9XA`: a call with no grid, a standard message with its third field
         * left empty. Both words must be shaped like calls (a hashed `<...>` or
         * `<CALL>` counts), so two words of free text such as `TNX 73` stay [Unknown].
         */
        private fun parseGridless(tokens: List<String>, raw: String): Ft8Message {
            val (to, from) = tokens
            fun isCall(word: String) = word == HASHED ||
                TxText.isCallsignShape(word.removeSurrounding("<", ">").uppercase())
            if (to.uppercase() in setOf("DE", "QRZ") || !isCall(to) || !isCall(from)) return Unknown(raw)
            return Reply(unbracket(to), unbracket(from), null, raw)
        }

        /** `<VE3XAA/QRP>` -> `VE3XAA/QRP`; `<...>` and anything unbracketed unchanged. */
        private fun unbracket(word: String): String =
            if (word != HASHED && word.length > 2 && word.startsWith('<') && word.endsWith('>')) {
                word.substring(1, word.length - 1)
            } else {
                word
            }

        /** `K1ABC RR73; W9XYZ <KH1/KH7Z> -12`, as the decoder writes it. */
        private fun parseDxpedition(tokens: List<String>, raw: String): Ft8Message {
            val sender = tokens[3]
            if (!sender.startsWith('<') || !sender.endsWith('>')) return Unknown(raw)
            val from = if (sender == HASHED) HASHED else sender.substring(1, sender.length - 1)
            if (from.isEmpty()) return Unknown(raw)
            // The report is always plain: the R-form does not exist in this message.
            val m = REPORT.matchEntire(tokens[4])?.takeIf { it.groupValues[1].isEmpty() } ?: return Unknown(raw)
            val db = m.groupValues[2].toIntOrNull() ?: return Unknown(raw)
            return Dxpedition(
                closing = Acknowledge(tokens[0], from, Acknowledge.Kind.RR73, raw),
                report = Report(tokens[2], from, db, roger = false, raw = raw),
                raw = raw
            )
        }

        private fun ackKind(token: String): Acknowledge.Kind? = when (token.uppercase()) {
            "RRR" -> Acknowledge.Kind.RRR
            "RR73" -> Acknowledge.Kind.RR73
            "73" -> Acknowledge.Kind.SEVENTY_THREE
            else -> null
        }
    }
}
