package com.ft8ok.model

/**
 * Contest mode: the contest format we send, and our exchange in it.
 * Contest mode off is no setup at all (null), not a kind of setup.
 *
 * The formats are WSJT-X's (`genStdMsgs`, `pack77_03`, `pack77_1`).
 * In both built so far a contact is four messages, the caller skipping the grid
 * message: `CQ FD K1ABC FN42`, `K1ABC W9XYZ 6A WI`, `W9XYZ K1ABC R 2B EMA`, `K1ABC
 * W9XYZ RR73`. In the grid contests the exchange is the 4-character grid, so their
 * first message looks like an ordinary call.
 *
 * Report+grid keeps the standard sequence; see [sendsExchange]. The Roundup
 * exchange is planned, not built: no FT4 contest uses it.
 */
data class ContestSetup(
    val kind: Kind,
    /** The word after CQ: `FD`, or `TEST`/`WW` for the grid contests. */
    val cqWord: String,
    /** What follows the two calls (and the R) in our exchange: `2A EMA`, or our grid `FN25`. */
    val exchange: String
) {

    enum class Kind {
        /** NA VHF, WW Digi, ARRL Digi: the grid where the report would go (type 1, `R FN42`). */
        GRID,

        /** ARRL Field Day: class and section (type 0.3/0.4). */
        FIELD_DAY,

        /**
         * Contests whose exchange is an ordinary contact, report and grid (the
         * International FT Challenge, DARC FT4, Africa FT4 DX, FT4 Field Day, RSGB FT4):
         * standard messages, but contest mode all the same, for what it adds: no
         * combined (DXpedition) messages, which the FT Challenge prohibits, dupes
         * marked, the CQ word, the contest field in the log.
         */
        REPORT_GRID
    }

    /**
     * Does this contest replace the report with its own exchange? False for
     * [Kind.REPORT_GRID], whose contacts follow the standard sequence. The QSO engine
     * builds contest messages only when true; everything else contest mode does
     * applies either way.
     */
    val sendsExchange: Boolean get() = kind != Kind.REPORT_GRID

    /** Is [message] an exchange in this contest's format? */
    fun matches(message: Ft8Message.Contest): Boolean = when (kind) {
        Kind.GRID -> message.kind == Ft8Message.Contest.Kind.GRID
        Kind.FIELD_DAY -> message.kind == Ft8Message.Contest.Kind.FIELD_DAY
        // Its exchange is a standard report, never a contest message.
        Kind.REPORT_GRID -> false
    }

    /**
     * Is [message] - a contest CQ or exchange - from a station in this contest? A CQ
     * is judged by its word: FD for Field Day, TEST or WW for the grid contests (TEST
     * is also EU VHF's, which cannot be told apart from the CQ).
     */
    fun sameContestAs(message: Ft8Message): Boolean = when (message) {
        is Ft8Message.Contest -> matches(message)
        is Ft8Message.Cq -> when (kind) {
            Kind.GRID -> message.directedTo == cqWord || message.directedTo in GRID_CQ_WORDS
            Kind.FIELD_DAY -> message.directedTo == FIELD_DAY_CQ_WORD
            Kind.REPORT_GRID -> cqWord.isNotEmpty() && message.directedTo == cqWord
        }
        else -> false
    }

    /** Why contest mode cannot be switched on as set. */
    enum class Problem {
        /** FT8's contest messages carry only plain standard calls (no `/P`, no compound call). */
        CALL_NOT_PLAIN,

        /** The grid contests send our grid, and there is none in Settings. */
        NO_GRID,

        /** Not 1-32 transmitters and a class letter A-F. */
        BAD_CLASS,

        /** Not one of WSJT-X's 86 sections. */
        BAD_SECTION,

        /** Not a word WSJT-X's grid contests call with. */
        BAD_CQ_WORD
    }

    companion object {

        /** The CQ words of the grid contests: TEST (NA VHF, ARRL Digi), WW (WW Digi). WSJT-X `genCQMsg`. */
        val GRID_CQ_WORDS = listOf("TEST", "WW")

        /** WSJT-X's Field Day CQ word. */
        const val FIELD_DAY_CQ_WORD = "FD"

        /**
         * The Report+grid contests' CQ words: none (a plain `CQ`, as most of them are
         * worked) or TEST. A custom word is the grid contests' typed one.
         */
        val REPORT_GRID_CQ_WORDS = listOf("", "TEST")

        /**
         * A word a grid contest may call with: any FT8 can carry after CQ, for contests
         * WSJT-X has no mode for (`CQ YBDX`). Not FD, which promises
         * a Field Day exchange we would not send.
         */
        fun isGridCqWord(word: String): Boolean = TxText.isCqWord(word) && word != FIELD_DAY_CQ_WORD

        /**
         * ADIF CONTEST_ID for Field Day: in the ADIF 3.1.6 Contest_ID enumeration, and
         * what WSJT-X writes (`logbook.cpp`). The grid contests get none, as from
         * WSJT-X: one setting covers several contests.
         */
        const val FIELD_DAY_CONTEST_ID = "ARRL-FIELD-DAY"

        /**
         * The ARRL/RAC sections a Field Day message can carry: WSJT-X's `csec`
         * (packjt77.f90), in its order. DX is among them.
         */
        val ARRL_SECTIONS = listOf(
            "AB", "AK", "AL", "AR", "AZ", "BC", "CO", "CT", "DE", "EB",
            "EMA", "ENY", "EPA", "EWA", "GA", "GH", "IA", "ID", "IL", "IN",
            "KS", "KY", "LA", "LAX", "NS", "MB", "MDC", "ME", "MI", "MN",
            "MO", "MS", "MT", "NC", "ND", "NE", "NFL", "NH", "NL", "NLI",
            "NM", "NNJ", "NNY", "TER", "NTX", "NV", "OH", "OK", "ONE", "ONN",
            "ONS", "OR", "ORG", "PAC", "PR", "QC", "RI", "SB", "SC", "SCV",
            "SD", "SDG", "SF", "SFL", "SJV", "SK", "SNJ", "STX", "SV", "TN",
            "UT", "VA", "VI", "VT", "WCF", "WI", "WMA", "WNY", "WPA", "WTX",
            "WV", "WWA", "WY", "DX", "PE", "NB"
        )

        /**
         * A Field Day class: 1-32 transmitters (what 0.3/0.4 carry) and a letter A-F.
         * The ARRL's classes are A-F, and WSJT-X reads a received class as Field Day
         * only for A-F (`bFieldDay_msg`), though the message has room for A-H.
         */
        private val FD_CLASS = Regex("^([1-9]|[12][0-9]|3[0-2])[A-F]$")

        fun isFieldDayClass(cls: String): Boolean = FD_CLASS.matches(cls)

        fun isSection(section: String): Boolean = section in ARRL_SECTIONS

        /**
         * The setup for [kind] as entered, or why it cannot be switched on. Values are
         * taken as typed: normalise ([TxText.normalise]) before calling.
         *
         * @param myCall our call as sent; @param myGrid our 4-character grid as sent.
         */
        fun build(
            kind: Kind,
            myCall: String,
            myGrid: String,
            gridCqWord: String,
            fdClass: String,
            fdSection: String,
            /** The Report+grid CQ word: "" for a plain CQ, or a word as for the grid contests. */
            reportGridCqWord: String = ""
        ): Result {
            // Report+grid sends standard messages, which carry any call the app can send.
            if (kind != Kind.REPORT_GRID && !Ft8Message.isPlainStandardCall(myCall)) return Result.Refused(Problem.CALL_NOT_PLAIN)
            return when (kind) {
                Kind.REPORT_GRID -> when {
                    !StationProfile(callsign = myCall, grid = myGrid).hasValidGrid -> Result.Refused(Problem.NO_GRID)
                    reportGridCqWord.isNotEmpty() && !isGridCqWord(reportGridCqWord) -> Result.Refused(Problem.BAD_CQ_WORD)
                    // The exchange is our report and grid, sent as ever: nothing fixed to show.
                    else -> Result.Ok(ContestSetup(kind, reportGridCqWord, myGrid))
                }
                Kind.GRID -> when {
                    !StationProfile(callsign = myCall, grid = myGrid).hasValidGrid -> Result.Refused(Problem.NO_GRID)
                    !isGridCqWord(gridCqWord) -> Result.Refused(Problem.BAD_CQ_WORD)
                    else -> Result.Ok(ContestSetup(kind, gridCqWord, myGrid))
                }
                Kind.FIELD_DAY -> when {
                    !isFieldDayClass(fdClass) -> Result.Refused(Problem.BAD_CLASS)
                    !isSection(fdSection) -> Result.Refused(Problem.BAD_SECTION)
                    else -> Result.Ok(ContestSetup(kind, FIELD_DAY_CQ_WORD, "$fdClass $fdSection"))
                }
            }
        }
    }

    sealed interface Result {
        data class Ok(val setup: ContestSetup) : Result
        data class Refused(val problem: Problem) : Result
    }
}
