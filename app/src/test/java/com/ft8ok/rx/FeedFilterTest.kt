package com.ft8ok.rx

import com.ft8ok.dsp.Decode
import com.ft8ok.model.SlotParity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedFilterTest {

    private fun row(message: String, tx: Boolean = false) = RxSession.DecodeRow(
        slotStartMs = 0L,
        parity = SlotParity.EVEN,
        decode = Decode(score = 20, dtSeconds = 0.1, frequencyHz = 1500, message = message),
        tx = tx
    )

    private val cq = row("CQ POTA K1ABC FN42")
    private val other = row("W9XYZ K1ABC -12")
    private val toUs = row("VE4OK W9XYZ EN52")
    private val ours = row("W9XYZ VE4OK FN25", tx = true)

    private fun FeedFilter.shown(custom: String = "") =
        listOf(cq, other, toUs, ours).filter { shows(it, "VE4OK", custom) }

    @Test
    fun `All shows everything`() {
        assertEquals(4, FeedFilter.ALL.shown().size)
    }

    @Test
    fun `CQ shows CQs, and never hides a station calling us or our own transmission`() {
        assertEquals(listOf(cq, toUs, ours), FeedFilter.CQ.shown())
    }

    @Test
    fun `To me is our conversation only`() {
        assertEquals(listOf(toUs, ours), FeedFilter.TO_ME.shown())
    }

    @Test
    fun `Custom needs every term, in any case`() {
        assertEquals(listOf(cq, toUs, ours), FeedFilter.CUSTOM.shown("pota k1abc"))
        assertEquals(listOf(cq, other, toUs, ours), FeedFilter.CUSTOM.shown("K1ABC"))
        assertTrue("blank shows everything", FeedFilter.CUSTOM.shown("  ").size == 4)
        assertFalse(FeedFilter.CUSTOM.shows(other, "VE4OK", "POTA"))
    }
}
