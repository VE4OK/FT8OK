package com.ft8ok.model

/**
 * What the operator may type for transmission from the CQ ⋯ panel:
 * the word after CQ, a callsign to call, and free text.
 *
 * Every rule here is the protocol's, read from `ft8_lib` (`message.c`), not a style
 * choice: a CQ word is 1-4 letters or exactly 3 digits (`parse_cq_modifier`); free
 * text is at most 13 characters from a 42-character alphabet
 * (`ftx_message_encode_free`, `FT8_CHAR_TABLE_FULL`).
 *
 * **The encoder's own verdict comes in from outside** ([Encoding]), because it lives
 * in native code: what the app shows is what the library will actually do, and the
 * rules here only add what the library does not check - mainly that text meant as a
 * call has not quietly become free text.
 */
object TxText {

    /** The longest free text FT8 carries. */
    const val FREE_TEXT_MAX = 13

    /** Every character free text can carry. Lower case is not among them. */
    const val ALPHABET = " 0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ+-./?"

    /** The CQ words offered as chips (NA removed). Others are typed. */
    val CQ_WORDS = listOf("POTA", "SOTA", "DX", "QRP")

    /** As it will be sent: upper case, ends trimmed, runs of spaces made one. */
    fun normalise(typed: String): String = typed.uppercase().trim().replace(SPACES, " ")

    /** The first character free text cannot carry, or null if there is none. */
    fun refusedChar(text: String): Char? = text.firstOrNull { it !in ALPHABET }

    /** A word FT8 can put after CQ in a standard message: 1-4 letters, or exactly 3 digits. */
    fun isCqWord(word: String): Boolean =
        (word.length in 1..4 && word.all { it in 'A'..'Z' }) ||
            (word.length == 3 && word.all { it in '0'..'9' })

    /**
     * Shaped like a callsign: 3-11 of A-Z, 0-9 and `/`, with at least one letter and
     * one digit. A shape test only - whether FT8 can carry it is the encoder's answer.
     */
    fun isCallsignShape(call: String): Boolean =
        call.length in 3..11 &&
            call.all { it in 'A'..'Z' || it in '0'..'9' || it == '/' } &&
            call.any { it in 'A'..'Z' } && call.any { it in '0'..'9' }

    /**
     * Does [text] read as an addressed message - a CQ, or two callsigns? Text like
     * that is meant to be answered, so it must never go out as free text, which
     * nobody's software answers.
     */
    fun looksLikeCall(text: String): Boolean {
        val words = normalise(text).split(' ')
        if (words.size >= 2 && words[0] in ADDRESS_WORDS) return true
        return words.size >= 2 && isCallsignShape(words[0]) && isCallsignShape(words[1])
    }

    /** How the encoder would carry a message, as the native library reports it. */
    enum class Encoding {
        /** It cannot be carried at all. */
        NONE,
        FREE_TEXT,
        /** Types 1 and 2: callsigns, grid or report. */
        STANDARD,
        /** Type 4: a compound or long callsign, sent with the other one hashed. */
        NONSTANDARD_CALL,
        /** Any other 77-bit type. */
        OTHER
    }

    enum class Reason { EMPTY, BAD_CHARACTER, TOO_LONG, UNSENDABLE }

    sealed interface Verdict {
        /** Sent as the library encodes it: it decodes back exactly as typed, and others can answer it. */
        data class Standard(val text: String) : Verdict

        /**
         * Sent as free text, forced, so others see exactly [text]. [looksLikeCall]: it
         * reads like a call, but no software will answer free text - say so.
         */
        data class FreeText(val text: String, val looksLikeCall: Boolean = false) : Verdict

        /** Not queued, and why. [char] for [Reason.BAD_CHARACTER], [length] for [Reason.TOO_LONG]. */
        data class Refused(val reason: Reason, val char: Char? = null, val length: Int = 0) : Verdict
    }

    /**
     * Judge text typed in the TEXT tab. **What is typed is what others see**, or it is
     * not sent.
     *
     * @param auto what the encoder makes of it when left to choose.
     * @param decodedAs what that encoding decodes back as - what other stations see.
     * @param freeFits whether it fits as free text (length and alphabet).
     *
     * The encoder garbles rather than refuses what it cannot carry. **Measured**
     * against this `ft8_lib` built on the host: `CQ HIHI TEST` comes back
     * as `CQ HIHI <...>` (sent on air once), `W1AW VE4OK EN` as `W1AW VE4OK +00`,
     * `TNX FER QSO` as `<...> <...> +00` - each a standard message, none what was
     * typed. So a standard message is sent only when it decodes back exactly;
     * anything else that fits goes as free text, forced, which comes back exactly.
     */
    fun judgeText(text: String, auto: Encoding, decodedAs: String?, freeFits: Boolean): Verdict {
        val t = normalise(text)
        if (t.isEmpty()) return Verdict.Refused(Reason.EMPTY)
        refusedChar(t)?.let { return Verdict.Refused(Reason.BAD_CHARACTER, char = it) }
        if (auto != Encoding.NONE && auto != Encoding.FREE_TEXT && decodedAs == t) return Verdict.Standard(t)
        if (freeFits) return Verdict.FreeText(t, looksLikeCall(t))
        if (t.length > FREE_TEXT_MAX) return Verdict.Refused(Reason.TOO_LONG, length = t.length)
        return Verdict.Refused(Reason.UNSENDABLE)
    }

    /**
     * May a message the app built (a CQ with a word, a call) be sent? Only as an
     * addressed message that decodes back exactly as built: never as free text, and
     * never garbled (a callsign FT8 can only send hashed comes back as `<...>`).
     * [Encoding.OTHER] counts: a contest exchange (type 0.3) is the
     * call contest mode builds.
     */
    fun sendsAsBuilt(message: String, auto: Encoding, decodedAs: String?): Boolean =
        (auto == Encoding.STANDARD || auto == Encoding.NONSTANDARD_CALL || auto == Encoding.OTHER) &&
            decodedAs == message

    private val SPACES = Regex(" +")

    /** First words that make a message addressed, whatever follows. */
    private val ADDRESS_WORDS = setOf("CQ", "QRZ", "DE")
}
