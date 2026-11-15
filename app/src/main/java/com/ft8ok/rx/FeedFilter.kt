package com.ft8ok.rx

import com.ft8ok.model.Ft8Message

/**
 * The Live feed's filter chips, `CQ` · `To me` · `All` · `Custom`. **Display only:** decoding, the Roster, the Map and auto-answer see
 * every decode whatever is chosen here.
 *
 * Our own transmissions and decodes that mention our call show under every filter:
 * a filter that hid a station calling us would hide the one row that
 * needs a thumb.
 */
enum class FeedFilter {
    ALL, CQ, TO_ME, CUSTOM;

    /**
     * Should [row] show?
     *
     * @param custom the Custom chip's text: space-separated terms, every one of which
     *   must appear in the message, ignoring case. Blank shows everything.
     */
    fun shows(row: RxSession.DecodeRow, myCall: String, custom: String = ""): Boolean {
        if (this == ALL || row.tx) return true
        val text = row.decode.message
        if (Ft8Message.mentions(text, myCall)) return true
        return when (this) {
            CQ -> Ft8Message.parse(text) is Ft8Message.Cq
            // To me is the mentions above and nothing else; All returned at the top.
            ALL, TO_ME -> false
            CUSTOM -> terms(custom).all { text.contains(it, ignoreCase = true) }
        }
    }

    companion object {
        fun terms(custom: String): List<String> = custom.trim().split(' ').filter { it.isNotEmpty() }
    }
}
