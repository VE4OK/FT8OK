package com.ft8ok.rx

import com.ft8ok.dsp.Decode
import com.ft8ok.dsp.ReplyMatch
import com.ft8ok.model.Mode
import com.ft8ok.model.SlotParity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ReplyWatchTest {

    private val even = SlotParity.EVEN
    private val slot = 1_794_000_000_000L
    private val target = ReplyWatch.Target("IV3XAA", "VE4OK", armed = true)

    private fun decode(message: String, hz: Int = 1844, dt: Double = 0.64) =
        Decode(score = 20, dtSeconds = dt, frequencyHz = hz, message = message)

    private fun watchHeard(message: String = "CQ IV3XAA JN65") = ReplyWatch(Mode.FT8).apply {
        observe(listOf(decode(message)), even, slot)
    }

    @Test
    fun `a partner not heard is looked for where they were last`() {
        val watch = watchHeard()
        watch.observe(emptyList(), even, slot + 30_000)
        val plan = watch.plan(target, emptyList(), even, ownTransmission = false)
        assertNotNull(plan)
        assertEquals(ReplyWatch.Heard.NOT_HEARD, plan!!.heard)
        assertEquals(1844, plan.frequencyHz)
        assertEquals(0.64, plan.dtSeconds, 0.0)
        assertEquals("W9XYZ", plan.decoyCall)
    }

    @Test
    fun `a reply heard the ordinary way is marked, and so is traffic to someone else`() {
        val watch = watchHeard()
        val toUs = listOf(decode("VE4OK IV3XAA R-12"))
        assertEquals(ReplyWatch.Heard.TO_US, watch.plan(target, toUs, even, false)?.heard)
        val toOther = listOf(decode("YB1XA IV3XAA 73"))
        assertEquals(ReplyWatch.Heard.TO_OTHER, watch.plan(target, toOther, even, false)?.heard)
    }

    @Test
    fun `the partner's newest place is used`() {
        val watch = watchHeard()
        val moved = listOf(decode("YB1XA IV3XAA 73", hz = 844, dt = 0.72))
        watch.observe(moved, even, slot + 30_000)
        assertEquals(844, watch.plan(target, moved, even, false)?.frequencyHz)
    }

    @Test
    fun `no test without a partner, in the other parity, or in our own transmission`() {
        val watch = watchHeard()
        assertNull(watch.plan(null, emptyList(), even, false))
        assertNull(watch.plan(target, emptyList(), SlotParity.ODD, false))
        assertNull(watch.plan(target, emptyList(), even, true))
    }

    @Test
    fun `a partner never heard has no place to look`() {
        assertNull(ReplyWatch(Mode.FT8).plan(target, emptyList(), even, false))
    }

    @Test
    fun `a partner gone quiet for five minutes is forgotten`() {
        val watch = watchHeard()
        watch.observe(emptyList(), even, slot + 300_030)
        assertNull(watch.plan(target, emptyList(), even, false))
    }

    @Test
    fun `calls that do not pack as plain messages are skipped`() {
        val watch = watchHeard("CQ VE3XAA/QRP FN03")
        assertNull(watch.plan(ReplyWatch.Target("VE3XAA/QRP", "VE4OK", true), emptyList(), even, false))
        val us = watchHeard()
        assertNull(us.plan(ReplyWatch.Target("IV3XAA", "VE4OK/P", true), emptyList(), even, false))
    }

    @Test
    fun `the partner's own grid goes with the test, from any message that carried it`() {
        assertEquals("JN65", watchHeard().plan(target, emptyList(), even, false)?.partnerGrid)

        val rGrid = listOf(decode("VE4OK IV3XAA R JN66"))
        val watch = ReplyWatch(Mode.FT8).apply { observe(rGrid, even, slot) }
        assertEquals("JN66", watch.plan(target, rGrid, even, false)?.partnerGrid)

        val noGrid = ReplyWatch(Mode.FT8).apply { observe(listOf(decode("YB1XA IV3XAA -05")), even, slot) }
        assertNull(noGrid.plan(target, emptyList(), even, false)?.partnerGrid)

        // In `IV3XAA K1ABC FN42` the grid is K1ABC's.
        val other = ReplyWatch(Mode.FT8).apply {
            observe(listOf(decode("YB1XA IV3XAA -05"), decode("IV3XAA K1ABC FN42", hz = 900)), even, slot)
        }
        assertNull(other.plan(target, emptyList(), even, false)?.partnerGrid)
    }

    // --- what counts as heard ---------------------------------------------------------

    private val notHeard = ReplyWatch.Plan("IV3XAA", "VE4OK", "W9XYZ", true, ReplyWatch.Heard.NOT_HEARD, 1844, 0.64)

    @Test
    fun `a close match on a partner not heard is a reply, at their spot, marked`() {
        val reply = ReplyWatch(Mode.FT8).accept(notHeard, ReplyMatch(35, "VE4OK IV3XAA R-12", 36), emptyList())
        assertEquals(Decode(0, 0.64, 1844, "VE4OK IV3XAA R-12", fromReplyList = true), reply)
    }

    @Test
    fun `over the gate, or with the decoy as close, it is not`() {
        val watch = ReplyWatch(Mode.FT8)
        assertNull(watch.accept(notHeard, ReplyMatch(ReplyWatch.ACT_MAX_HARD + 1, "VE4OK IV3XAA R-12", 60), emptyList()))
        assertNull(watch.accept(notHeard, ReplyMatch(20, "VE4OK IV3XAA R-12", ReplyWatch.ACT_MAX_HARD), emptyList()))
    }

    @Test
    fun `never when the partner was heard in the slot`() {
        val watch = ReplyWatch(Mode.FT8)
        val match = ReplyMatch(5, "VE4OK IV3XAA R-12", 60)
        assertNull(watch.accept(notHeard.copy(heard = ReplyWatch.Heard.TO_OTHER), match, emptyList()))
        assertNull(watch.accept(notHeard.copy(heard = ReplyWatch.Heard.TO_US), match, emptyList()))
    }

    @Test
    fun `never with another station's message to us near the spot`() {
        val watch = ReplyWatch(Mode.FT8)
        val match = ReplyMatch(20, "VE4OK IV3XAA RR73", 60)
        val near = decode("VE4OK K4XAA RR73", hz = 1844 + ReplyWatch.OTHER_CALLER_HZ)
        assertNull(watch.accept(notHeard, match, listOf(near)))
        val far = decode("VE4OK K4XAA RR73", hz = 1844 + ReplyWatch.OTHER_CALLER_HZ + 1)
        assertNotNull(watch.accept(notHeard, match, listOf(far)))
        // Someone else's traffic nearby is not a reply to us.
        assertNotNull(watch.accept(notHeard, match, listOf(decode("W1AW K4XAA RR73", hz = 1850))))
    }

    @Test
    fun `only a message from the partner to us`() {
        val watch = ReplyWatch(Mode.FT8)
        assertNull(watch.accept(notHeard, ReplyMatch(5, "W9XYZ IV3XAA R-12", 60), emptyList()))
        assertNull(watch.accept(notHeard, ReplyMatch(5, "VE4OK K1ABC R-12", 60), emptyList()))
    }

    @Test
    fun `the decoy is never our partner or us`() {
        val watch = ReplyWatch(Mode.FT8).apply { observe(listOf(decode("CQ W9XYZ EN52")), even, slot) }
        val plan = watch.plan(ReplyWatch.Target("W9XYZ", "VE4OK", true), emptyList(), even, false)
        assertEquals("K1ABC", plan?.decoyCall)
    }
}
