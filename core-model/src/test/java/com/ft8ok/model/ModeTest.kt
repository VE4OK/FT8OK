package com.ft8ok.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM: the mode constants and the slot arithmetic, on the JVM.
 *
 * These tests are the enforcement of ADR 0006. FT4 is deferred from v1, so nothing
 * else exercises its numbers; without this class they could rot unnoticed until the
 * day FT4 is switched on, which is exactly the rewrite the abstraction exists to
 * prevent.
 */
class ModeTest {

    @Test
    fun `ft8 slot arithmetic matches the protocol`() {
        val ft8 = Mode.FT8
        assertEquals(15_000, ft8.slotMs)
        assertEquals(500, ft8.nominalTxStartMs)
        // 79 symbols x 1920 samples at 12 kHz = 12.64 s, ending at 13.14 s.
        assertEquals(13_140, ft8.nominalTxEndMs)
        assertEquals(4_000, ft8.latestTxStartMs)
    }

    @Test
    fun `the reason mode constants exist at all - ft8's late start would overrun an ft4 slot`() {
        // It is tempting to say the FT8 late start "would exceed an entire FT4
        // transmission". Taken literally that is not so - 3.5 s is less than FT4's 5.04 s transmission. The real and worse
        // consequence is this one: an FT4 message begun at FT8's late-start limit
        // ends about 9 s into a 7.5 s slot, i.e. inside the NEXT slot, transmitting
        // over whoever answers. Asserted here in the form that is actually true.
        val ft4StartedLateLikeFt8 =
            Mode.FT4.nominalTxStartMs + Mode.FT8.lateStartMs + Mode.FT4.txDurationMs
        assertTrue(
            "FT8's late start applied to FT4 must be shown to overrun the slot - " +
                "the whole point of ADR 0006",
            ft4StartedLateLikeFt8 > Mode.FT4.slotMs
        )
    }

    @Test
    fun `the hard stop is what keeps a late transmission inside its own slot`() {
        for (mode in Mode.ALL) {
            assertTrue(
                "${mode.name}: the hard stop must fall inside the slot, or a late " +
                    "transmission runs into the next one",
                mode.hardStopMs < mode.slotMs
            )
        }

        // FT8: a maximally late start would end at 16.64 s, well past the 15 s slot.
        // The hard stop truncates it, the tail is lost, and the receiver may still
        // decode it.
        assertTrue(
            Mode.FT8.latestTxStartMs + Mode.FT8.txDurationMs > Mode.FT8.hardStopMs
        )

        // What a late start actually sends: the whole message shifted by at most
        // maxLateShiftMs, the start left out beyond that. So the
        // latest whole-message end must fit before the hard stop, in every mode.
        for (mode in Mode.ALL) {
            assertTrue(
                "${mode.name}: a whole message shifted by maxLateShiftMs must end before the hard stop",
                mode.nominalTxEndMs + mode.maxLateShiftMs <= mode.hardStopMs
            )
        }
    }

    @Test
    fun `ft4 slot arithmetic matches WSJT-X`() {
        val ft4 = Mode.FT4
        assertEquals(7_500, ft4.slotMs)
        // The ramp symbol starts 48 ms before the first sync symbol's 0.5 s.
        assertEquals(452, ft4.nominalTxStartMs)
        // 105 symbols x 576 samples at 12 kHz = 5.04 s, ending at 5.492 s.
        assertEquals(5_040, ft4.txDurationMs)
        assertEquals(5_492, ft4.nominalTxEndMs)
        assertEquals(83.33, ft4.signalWidthHz, 0.01)
    }

    @Test
    fun `every mode's decode passes are ascending and land inside the slot`() {
        for (mode in Mode.ALL) {
            val passes = mode.decodePassesMs
            assertTrue("${mode.name}: needs at least one decode pass", passes.isNotEmpty())
            assertEquals("${mode.name}: passes must be ascending", passes.sorted(), passes)
            assertTrue("${mode.name}: last pass must fall inside the slot", passes.last() < mode.slotMs)
            assertTrue(
                "${mode.name}: the final pass must be after the transmission ends, " +
                    "or nothing ever decodes a complete message",
                passes.last() >= mode.nominalTxEndMs
            )
        }
    }

    @Test
    fun `parity alternates and is anchored to the utc epoch`() {
        val ft8 = Mode.FT8
        assertEquals(SlotParity.EVEN, ft8.parityOf(0))
        assertEquals(SlotParity.ODD, ft8.parityOf(15_000))
        assertEquals(SlotParity.EVEN, ft8.parityOf(30_000))
        assertEquals(SlotParity.ODD, ft8.parityOf(45_000))
        // Anchoring to the epoch rather than to a first-seen slot is what lets two
        // devices agree without talking: :00 and :30 of any minute are even.
        val someMinute = 1_760_000_000_000L / 60_000 * 60_000
        assertEquals(SlotParity.EVEN, ft8.parityOf(someMinute))
        assertEquals(SlotParity.ODD, ft8.parityOf(someMinute + 15_000))
    }

    @Test
    fun `slot maths holds before the epoch too`() {
        // floorDiv/floorMod rather than / and %, so a negative time does not land
        // in the slot after the one it belongs to. No radio will run before 1970,
        // but a test clock starting at zero will go negative, and a silent sign bug
        // in the slot grid would be very hard to see.
        val ft8 = Mode.FT8
        assertEquals(-15_000, ft8.slotStartMs(-1))
        assertEquals(14_999, ft8.positionInSlotMs(-1))
        assertEquals(SlotParity.ODD, ft8.parityOf(-15_000))
    }

    @Test
    fun `position in slot is consistent with the slot start`() {
        val ft8 = Mode.FT8
        for (t in listOf(0L, 1L, 7_499L, 14_999L, 15_000L, 1_760_000_123_456L)) {
            assertEquals(t, ft8.slotStartMs(t) + ft8.positionInSlotMs(t))
        }
    }

    @Test
    fun `a transmission may start in the window, and not a millisecond later`() {
        val ft8 = Mode.FT8
        assertTrue("on time", ft8.mayStartTxAt(500))
        assertTrue("2 s late is still inside the window", ft8.mayStartTxAt(2_500))
        assertTrue("the last permitted moment", ft8.mayStartTxAt(4_000))
        assertTrue("one millisecond past it is refused", !ft8.mayStartTxAt(4_001))
        assertTrue("and so is the middle of the slot", !ft8.mayStartTxAt(9_000))
    }

    @Test
    fun `a transmission may not start before the nominal time either`() {
        // Early is not "safe". Starting before the slot's nominal start puts our
        // signal where no receiver is looking for it.
        assertTrue(!Mode.FT8.mayStartTxAt(0))
        assertTrue(!Mode.FT8.mayStartTxAt(499))
    }

    @Test
    fun `every mode refuses a start it could not finish honestly`() {
        for (mode in Mode.ALL) {
            assertTrue(
                ": the window must close before the hard stop",
                mode.latestTxStartMs < mode.hardStopMs
            )
            assertTrue(
                ": nothing may start after the hard stop",
                !mode.mayStartTxAt(mode.hardStopMs)
            )
        }
    }

    @Test
    fun `the other parity is the other one`() {
        assertEquals(SlotParity.ODD, SlotParity.EVEN.other)
        assertEquals(SlotParity.EVEN, SlotParity.ODD.other)
    }

    @Test
    fun `subtraction stops in time for TX to decide, in both modes`() {
        assertEquals(15_200, Mode.FT8.deepDecodeEndMs)
        for (mode in listOf(Mode.FT8, Mode.FT4)) {
            assertTrue(mode.deepDecodeEndMs > mode.decodePassesMs.last())
            assertTrue(mode.deepDecodeEndMs < mode.slotMs + mode.decodeWaitMs)
        }
    }
}
