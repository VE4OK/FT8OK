package com.ft8ok.model

import com.ft8ok.model.TxText.Encoding
import com.ft8ok.model.TxText.Reason
import com.ft8ok.model.TxText.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The encoder answers (`auto`, `decodedAs`, `freeFits`) used here are the ones
 * measured once with this project's `ft8_lib` built on the host: each
 * message encoded with `ftx_message_encode` and decoded straight back.
 */
class TxTextTest {

    @Test
    fun `a CQ word is one to four letters or exactly three digits`() {
        for (word in listOf("POTA", "SOTA", "DX", "QRP", "A", "250")) assertTrue(word, TxText.isCqWord(word))
        for (word in listOf("", "TESTS", "25", "2500", "P0TA", "W/", "dx")) assertFalse(word, TxText.isCqWord(word))
    }

    @Test
    fun `NA is not offered, the four chips are`() {
        assertEquals(listOf("POTA", "SOTA", "DX", "QRP"), TxText.CQ_WORDS)
    }

    @Test
    fun `typing is made upper case and tidied`() {
        assertEquals("TNX 73 GL", TxText.normalise("  tnx  73 gl "))
    }

    @Test
    fun `text the encoder already sends as free text goes as free text`() {
        assertEquals(
            Verdict.FreeText("TNX 73 GL"),
            TxText.judgeText("tnx 73 gl", Encoding.FREE_TEXT, "TNX 73 GL", freeFits = true)
        )
        assertEquals(
            Verdict.FreeText("P2P CA-1234"),
            TxText.judgeText("P2P CA-1234", Encoding.FREE_TEXT, "P2P CA-1234", freeFits = true)
        )
    }

    @Test
    fun `CQ HIHI TEST went out as CQ HIHI hashed - now it goes as free text, as typed`() {
        // Sent on air from the first build, which trusted the
        // encoder's "standard" because the text started with CQ.
        assertEquals(
            Verdict.FreeText("CQ HIHI TEST", looksLikeCall = true),
            TxText.judgeText("CQ HIHI TEST", Encoding.STANDARD, "CQ HIHI <...>", freeFits = true)
        )
    }

    @Test
    fun `plain words the encoder would turn into hashed callsigns go as free text`() {
        assertEquals(
            Verdict.FreeText("TNX FER QSO"),
            TxText.judgeText("TNX FER QSO", Encoding.STANDARD, "<...> <...> +00", freeFits = true)
        )
    }

    @Test
    fun `a call with its grid cut short is not sent as a different call`() {
        // The encoder makes W1AW VE4OK +00 of it; free text keeps it as typed, with a warning.
        assertEquals(
            Verdict.FreeText("W1AW VE4OK EN", looksLikeCall = true),
            TxText.judgeText("W1AW VE4OK EN", Encoding.STANDARD, "W1AW VE4OK +00", freeFits = true)
        )
    }

    @Test
    fun `a standard message that decodes back exactly goes as one`() {
        assertEquals(
            Verdict.Standard("CQ POTA VE4OK FN25"),
            TxText.judgeText("CQ POTA VE4OK FN25", Encoding.STANDARD, "CQ POTA VE4OK FN25", freeFits = false)
        )
        assertEquals(
            Verdict.Standard("W1AW VE4OK RR73"),
            TxText.judgeText("W1AW VE4OK RR73", Encoding.STANDARD, "W1AW VE4OK RR73", freeFits = false)
        )
    }

    @Test
    fun `too long, a refused character, and nothing at all are each named`() {
        assertEquals(
            Verdict.Refused(Reason.TOO_LONG, length = 14),
            TxText.judgeText("POTA CA-123456", Encoding.NONE, "", freeFits = false)
        )
        assertEquals(
            Verdict.Refused(Reason.BAD_CHARACTER, char = '!'),
            TxText.judgeText("73 GL!", Encoding.NONE, "", freeFits = false)
        )
        assertEquals(Verdict.Refused(Reason.EMPTY), TxText.judgeText("   ", Encoding.NONE, "", freeFits = false))
    }

    @Test
    fun `addressed text is recognised by its callsigns or its first word`() {
        assertTrue(TxText.looksLikeCall("W1AW VE4OK FN25"))
        assertTrue(TxText.looksLikeCall("CQ TEST"))
        assertFalse(TxText.looksLikeCall("TNX 73 GL"))
        assertFalse(TxText.looksLikeCall("P2P CA-1234"))
        assertFalse(TxText.looksLikeCall("73"))
    }

    @Test
    fun `a message the app built is sent only if it comes back exactly`() {
        assertTrue(TxText.sendsAsBuilt("CQ POTA VE4OK FN25", Encoding.STANDARD, "CQ POTA VE4OK FN25"))
        assertFalse("garbled", TxText.sendsAsBuilt("CQ HIHI TEST", Encoding.STANDARD, "CQ HIHI <...>"))
        assertFalse("free text", TxText.sendsAsBuilt("TNX 73 GL", Encoding.FREE_TEXT, "TNX 73 GL"))
        assertFalse("not encodable", TxText.sendsAsBuilt("X", Encoding.NONE, ""))
        assertTrue("a Field Day exchange", TxText.sendsAsBuilt("W1AW VE4OK 2A MB", Encoding.OTHER, "W1AW VE4OK 2A MB"))
    }

    @Test
    fun `the free text alphabet is the library's 42 characters`() {
        assertEquals(42, TxText.ALPHABET.length)
        assertEquals(null, TxText.refusedChar("AZ09 +-./?"))
        assertEquals('a', TxText.refusedChar("Aa"))
    }
}
