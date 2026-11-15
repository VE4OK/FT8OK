package com.ft8ok.rx

import com.ft8ok.dsp.Decode
import com.ft8ok.model.SlotParity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedSectionsTest {

    private val t0 = 1_760_000_010_000L / 30_000 * 30_000

    private fun row(slot: Int, message: String, tx: Boolean = false) = RxSession.DecodeRow(
        slotStartMs = t0 + slot * 15_000L,
        parity = if (slot % 2 == 0) SlotParity.EVEN else SlotParity.ODD,
        decode = Decode(score = 20, dtSeconds = 0.1, frequencyHz = 1500, message = message),
        tx = tx
    )

    @Test
    fun `a header before each slot, counting decodes but not our transmission`() {
        val items = FeedSections.build(
            listOf(
                row(0, "CQ K1ABC FN42"),
                row(0, "CQ W2DEF FN31"),
                row(1, "K1ABC VE4OK FN25", tx = true),
                row(2, "VE4OK K1ABC -12")
            )
        )
        val headers = items.filterIsInstance<FeedSections.Header>()
        assertEquals(3, headers.size)
        assertEquals(2, headers[0].decodes)
        assertEquals(SlotParity.EVEN, headers[0].parity)
        assertEquals(0, headers[1].decodes)
        assertTrue(headers[1].ourTx)
        assertEquals(7, items.size)
        assertTrue(items[0] is FeedSections.Header && items[3] is FeedSections.Header)
    }

    @Test
    fun `keys are unique, so the list can keep rows in place`() {
        val items = FeedSections.build(listOf(row(0, "CQ A1A FN42"), row(0, "CQ A1A FN42"), row(1, "CQ B1B EM12")))
        assertEquals(items.size, items.map { it.key }.toSet().size)
    }

    private fun messages(items: List<FeedSections.Item>) =
        items.filterIsInstance<FeedSections.Line>().map { it.row.decode.message }

    @Test
    fun `decodes mentioning our call go to the end of their slot, each group in arrival order`() {
        val items = FeedSections.build(
            listOf(
                row(0, "CQ K1ABC FN42"),
                row(0, "VE4OK W2DEF FN31"),
                row(0, "CQ N0XYZ EM28"),
                row(0, "VE4OK K1ABC -12"),
                row(0, "W3GHI K4JKL R-05"),
                row(1, "CQ JA1XYZ PM95")
            ),
            myCall = "VE4OK"
        )
        assertEquals(
            listOf("CQ K1ABC FN42", "CQ N0XYZ EM28", "W3GHI K4JKL R-05", "VE4OK W2DEF FN31", "VE4OK K1ABC -12", "CQ JA1XYZ PM95"),
            messages(items)
        )
        // The next slot still follows under its own header: the move stays inside the slot.
        assertTrue(items[items.size - 2] is FeedSections.Header)
    }

    @Test
    fun `our transmission stays first in its slot even though it carries our call`() {
        val items = FeedSections.build(
            listOf(
                row(1, "K1ABC VE4OK FN25", tx = true),
                row(1, "CQ W2DEF FN31"),
                row(1, "VE4OK N0XYZ EM28")
            ),
            myCall = "VE4OK"
        )
        assertEquals(listOf("K1ABC VE4OK FN25", "CQ W2DEF FN31", "VE4OK N0XYZ EM28"), messages(items))
    }

    @Test
    fun `a hashed mention counts, and no call set moves nothing`() {
        val feed = listOf(row(0, "<VE4OK> W2DEF -10"), row(0, "CQ K1ABC FN42"))
        assertEquals(listOf("CQ K1ABC FN42", "<VE4OK> W2DEF -10"), messages(FeedSections.build(feed, "VE4OK")))
        assertEquals(listOf("<VE4OK> W2DEF -10", "CQ K1ABC FN42"), messages(FeedSections.build(feed)))
    }

    @Test
    fun `a later pass slots rows in above a mention without changing any row's key`() {
        val pass1 = listOf(row(0, "CQ K1ABC FN42"), row(0, "VE4OK W2DEF FN31"))
        val pass2 = pass1 + row(0, "CQ N0XYZ EM28")
        val before = FeedSections.build(pass1, "VE4OK").filterIsInstance<FeedSections.Line>()
            .associate { it.key to it.row.decode.message }
        val after = FeedSections.build(pass2, "VE4OK").filterIsInstance<FeedSections.Line>()
            .associate { it.key to it.row.decode.message }
        before.forEach { (key, message) -> assertEquals(message, after[key]) }
        assertEquals("VE4OK W2DEF FN31", messages(FeedSections.build(pass2, "VE4OK")).last())
    }

    @Test
    fun `an empty feed has no sections`() {
        assertTrue(FeedSections.build(emptyList()).isEmpty())
    }

    @Test
    fun `a band change is marked once, before the first slot on the new band`() {
        val items = FeedSections.build(
            listOf(
                row(0, "CQ K1ABC FN42").copy(band = "20m"),
                row(1, "CQ W2DEF FN31").copy(band = "20m"),
                row(3, "CQ VE7XAA CN89").copy(band = "17m"),
                row(4, "CQ N0XYZ EM28").copy(band = "17m")
            )
        )
        val changes = items.filterIsInstance<FeedSections.BandChange>()
        assertEquals(listOf("17m"), changes.map { it.band })
        val at = items.indexOf(changes.single())
        assertEquals(t0 + 3 * 15_000L, (items[at + 1] as FeedSections.Header).slotStartMs)
        assertEquals("20m", items.filterIsInstance<FeedSections.Header>().first().band)
    }

    @Test
    fun `a filter leaves out rows and empty slots, and headers still count the whole slot`() {
        val items = FeedSections.build(
            listOf(
                row(0, "CQ K1ABC FN42").copy(band = "20m"),
                row(0, "W2DEF K1ABC -12").copy(band = "20m"),
                row(1, "W2DEF N0XYZ R-09").copy(band = "20m"),
                row(2, "CQ VE7XAA CN89").copy(band = "17m")
            )
        ) { Ft8MessageCq(it) }
        val headers = items.filterIsInstance<FeedSections.Header>()
        // Slot 1 had no CQ: no header for it.
        assertEquals(listOf(t0, t0 + 30_000L), headers.map { it.slotStartMs })
        assertEquals(2, headers[0].decodes)
        assertEquals(listOf("CQ K1ABC FN42", "CQ VE7XAA CN89"),
            items.filterIsInstance<FeedSections.Line>().map { it.row.decode.message })
        // The band line still goes before the first shown slot on the new band.
        assertEquals(1, items.filterIsInstance<FeedSections.BandChange>().size)
    }

    private fun Ft8MessageCq(r: RxSession.DecodeRow) =
        com.ft8ok.model.Ft8Message.parse(r.decode.message) is com.ft8ok.model.Ft8Message.Cq
}
