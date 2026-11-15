package com.ft8ok.rx

import com.ft8ok.dsp.Decode
import com.ft8ok.model.SlotParity
import org.junit.Assert.assertEquals
import org.junit.Test

class FeedOrderTest {

    private val s0 = 1_760_000_010_000L / 15_000 * 15_000

    private fun rx(slot: Long, text: String) = RxSession.DecodeRow(
        slot, SlotParity.EVEN, Decode(score = 20, dtSeconds = 0.1, frequencyHz = 1000, message = text)
    )

    private fun tx(slot: Long, text: String) = rx(slot, text).copy(tx = true)

    private fun List<RxSession.DecodeRow>.texts() = map { it.decode.message }

    @Test
    fun `rows arriving in order are appended`() {
        val feed = FeedOrder.insert(emptyList(), listOf(rx(s0, "a"), rx(s0, "b")), 200)
        assertEquals(listOf("a", "b", "c"), FeedOrder.insert(feed, listOf(rx(s0 + 15_000, "c")), 200).texts())
    }

    @Test
    fun `late decodes of the slot before go above our transmission, not below it`() {
        var feed = FeedOrder.insert(emptyList(), listOf(rx(s0, "early")), 200)
        feed = FeedOrder.insert(feed, listOf(tx(s0 + 15_000, "OURS")), 200)
        feed = FeedOrder.insert(feed, listOf(rx(s0, "late")), 200)
        feed = FeedOrder.insert(feed, listOf(rx(s0 + 30_000, "next")), 200)
        assertEquals(listOf("early", "late", "OURS", "next"), feed.texts())
    }

    @Test
    fun `our transmission comes before anything decoded in its own slot`() {
        var feed = FeedOrder.insert(emptyList(), listOf(rx(s0, "heard")), 200)
        feed = FeedOrder.insert(feed, listOf(tx(s0, "OURS")), 200)
        assertEquals(listOf("OURS", "heard"), feed.texts())
    }

    @Test
    fun `the limit drops the oldest rows`() {
        val feed = FeedOrder.insert(emptyList(), (1..5).map { rx(s0 + it * 15_000L, "$it") }, 3)
        assertEquals(listOf("3", "4", "5"), feed.texts())
    }
}
