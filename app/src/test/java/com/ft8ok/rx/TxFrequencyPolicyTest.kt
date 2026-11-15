package com.ft8ok.rx

import com.ft8ok.dsp.Occupancy
import com.ft8ok.dsp.TxFrequency
import com.ft8ok.model.Mode
import com.ft8ok.model.SlotParity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parity rule, which had a bug that only the radio could show.
 *
 * Every test here is about *which map* governs the choice. The choosing itself is
 * `TxFrequencyTest`'s business.
 */
class TxFrequencyPolicyTest {

    private val mode = Mode.FT8

    /** A slot boundary far from the epoch, whose parity is EVEN. */
    private val evenSlot = (1_760_000_010_000L / 30_000) * 30_000

    /** A map with one loud signal, so [TxFrequency.isBusy] has something to find. */
    private fun mapWithSignalAt(hz: Int): Occupancy.Map {
        val binHz = 12_000.0 / 2048
        val bins = FloatArray(((Occupancy.BAND_HIGH_HZ - Occupancy.BAND_LOW_HZ) / binHz).toInt() + 1)
        val from = ((hz - Occupancy.SIGNAL_WIDTH_HZ / 2 - Occupancy.BAND_LOW_HZ) / binHz).toInt()
        val to = ((hz + Occupancy.SIGNAL_WIDTH_HZ / 2 - Occupancy.BAND_LOW_HZ) / binHz).toInt()
        for (i in from..to) if (i in bins.indices) bins[i] = 20f
        return Occupancy.Map(
            aboveFloorDb = bins,
            binHz = binHz,
            noiseFloorDb = -100.0,
            occupied = listOf(Occupancy.Band(hz - 25, hz + 25)),
            gaps = emptyList(),
            candidates = listOf(1500, 2000)
        )
    }

    private fun emptyMap(candidates: List<Int>): Occupancy.Map = Occupancy.Map(
        aboveFloorDb = FloatArray(400),
        binHz = 12_000.0 / 2048,
        noiseFloorDb = -100.0,
        occupied = emptyList(),
        gaps = emptyList(),
        candidates = candidates
    )

    @Test
    fun `the tx parity is the next slot's, and it flips at every boundary`() {
        // Inside the EVEN slot we would transmit in the ODD slot that follows.
        assertEquals(SlotParity.ODD, TxFrequencyPolicy.txParityAt(mode, evenSlot + 1_000))
        assertEquals(SlotParity.ODD, TxFrequencyPolicy.txParityAt(mode, evenSlot + 14_900))
        // One millisecond later we are in the ODD slot, and the next is EVEN.
        assertEquals(SlotParity.EVEN, TxFrequencyPolicy.txParityAt(mode, evenSlot + 15_000))
    }

    @Test
    fun `the governing map is the one for the parity we would transmit in`() {
        // Our spot is clear in the EVEN map and buried in the ODD one. We are inside
        // the EVEN slot, so we would transmit in the ODD slot - and the ODD map is
        // what must decide. Getting this backwards is the bug this class exists for:
        // it leaves a frequency sitting on a loud station for as long as it likes.
        val held = 1000
        val readings = mapOf(
            SlotParity.EVEN to TxFrequencyPolicy.Reading(emptyMap(listOf(held)), evenSlot),
            SlotParity.ODD to TxFrequencyPolicy.Reading(mapWithSignalAt(held), evenSlot - 15_000)
        )

        val decision = TxFrequencyPolicy.decide(
            mode = mode,
            nowMs = evenSlot + 14_000,
            readings = readings,
            heldHz = held,
            txMode = TxFrequency.Mode.FULL_AUTO
        )

        assertEquals(TxFrequency.Reason.MOVED_SPOT_BUSY, decision.reason)
        assertNotEquals(held, decision.frequencyHz)
    }

    @Test
    fun `the same held frequency survives when the governing parity is clear`() {
        val held = 1000
        val readings = mapOf(
            SlotParity.EVEN to TxFrequencyPolicy.Reading(mapWithSignalAt(held), evenSlot),
            SlotParity.ODD to TxFrequencyPolicy.Reading(emptyMap(listOf(1500)), evenSlot - 15_000)
        )

        // Inside the EVEN slot: the ODD map governs, and it is clear at 1000.
        val decision = TxFrequencyPolicy.decide(
            mode = mode,
            nowMs = evenSlot + 14_000,
            readings = readings,
            heldHz = held,
            txMode = TxFrequency.Mode.FULL_AUTO
        )
        assertEquals(TxFrequency.Reason.HELD, decision.reason)
        assertEquals(held, decision.frequencyHz)
    }

    @Test
    fun `crossing a slot boundary brings the other map into force`() {
        val held = 1000
        val readings = mapOf(
            SlotParity.EVEN to TxFrequencyPolicy.Reading(mapWithSignalAt(held), evenSlot),
            SlotParity.ODD to TxFrequencyPolicy.Reading(emptyMap(listOf(1500)), evenSlot - 15_000)
        )

        fun decideAt(nowMs: Long) = TxFrequencyPolicy.decide(
            mode = mode,
            nowMs = nowMs,
            readings = readings,
            heldHz = held,
            txMode = TxFrequency.Mode.FULL_AUTO
        )

        // Last moment of the EVEN slot: the ODD map governs and holds us at 1000.
        assertEquals(TxFrequency.Reason.HELD, decideAt(evenSlot + 14_999).reason)
        // First moment of the ODD slot: the EVEN map governs, 1000 is buried, move.
        assertEquals(TxFrequency.Reason.MOVED_SPOT_BUSY, decideAt(evenSlot + 15_000).reason)
    }

    @Test
    fun `a stale map holds rather than acting on an old picture`() {
        val readings = mapOf(
            SlotParity.ODD to TxFrequencyPolicy.Reading(
                mapWithSignalAt(1000),
                // Four minutes old: we have been transmitting, not listening.
                evenSlot - 240_000
            )
        )
        val decision = TxFrequencyPolicy.decide(
            mode = mode,
            nowMs = evenSlot + 1_000,
            readings = readings,
            heldHz = 1000,
            txMode = TxFrequency.Mode.FULL_AUTO
        )
        assertEquals(TxFrequency.Reason.HELD_NO_FRESH_DATA, decision.reason)
        assertEquals(1000, decision.frequencyHz)
    }

    @Test
    fun `with nothing heard for that parity there is nothing to pick`() {
        val decision = TxFrequencyPolicy.decide(
            mode = mode,
            nowMs = evenSlot + 1_000,
            readings = emptyMap(),
            heldHz = null,
            txMode = TxFrequency.Mode.FULL_AUTO
        )
        assertNull(decision.frequencyHz)
    }

    @Test
    fun `user-set is never second-guessed, whichever parity is in force`() {
        val readings = mapOf(
            SlotParity.ODD to TxFrequencyPolicy.Reading(mapWithSignalAt(1000), evenSlot)
        )
        val decision = TxFrequencyPolicy.decide(
            mode = mode,
            nowMs = evenSlot + 1_000,
            readings = readings,
            heldHz = 1000,
            txMode = TxFrequency.Mode.USER_SET
        )
        assertEquals(TxFrequency.Reason.USER_SET, decision.reason)
        assertEquals(1000, decision.frequencyHz)
    }

    @Test
    fun `a committed parity decides the map, not the clock`() {
        // In a contact we transmit EVEN. Inside an ODD slot the clock says "next is
        // EVEN" - fine - but inside an EVEN slot it says ODD, and judging against
        // the ODD lane moved us mid-contact on air. The committed
        // parity must win in both.
        val held = 1000
        val readings = mapOf(
            SlotParity.EVEN to TxFrequencyPolicy.Reading(emptyMap(listOf(held)), evenSlot - 30_000),
            SlotParity.ODD to TxFrequencyPolicy.Reading(mapWithSignalAt(held), evenSlot - 15_000)
        )

        val decision = TxFrequencyPolicy.decide(
            mode = mode,
            nowMs = evenSlot + 14_000,
            readings = readings,
            heldHz = held,
            txMode = TxFrequency.Mode.FULL_AUTO,
            txParity = SlotParity.EVEN
        )

        assertEquals(TxFrequency.Reason.HELD, decision.reason)
        assertEquals(held, decision.frequencyHz)
    }

    @Test
    fun `in a contact the frequency holds even when the spot is busy`() {
        val held = 1000
        val readings = mapOf(
            SlotParity.ODD to TxFrequencyPolicy.Reading(mapWithSignalAt(held), evenSlot - 15_000)
        )

        val decision = TxFrequencyPolicy.decide(
            mode = mode,
            nowMs = evenSlot + 14_000,
            readings = readings,
            heldHz = held,
            txMode = TxFrequency.Mode.FULL_AUTO,
            inQso = true,
            txParity = SlotParity.ODD
        )

        assertEquals(TxFrequency.Reason.HELD_IN_QSO, decision.reason)
        assertEquals(held, decision.frequencyHz)
    }

    // --- One pick per lane -------

    @Test
    fun `each lane is judged only against its own map`() {
        // 1000 is clear on EVEN and buried on ODD. One shared frequency used to be
        // judged against each lane in turn; now only ODD's pick moves.
        val readings = mapOf(
            SlotParity.EVEN to TxFrequencyPolicy.Reading(emptyMap(listOf(1000)), evenSlot),
            SlotParity.ODD to TxFrequencyPolicy.Reading(mapWithSignalAt(1000), evenSlot - 15_000)
        )
        val d = TxFrequencyPolicy.decideLanes(
            mode, evenSlot + 14_000, readings,
            held = mapOf(SlotParity.EVEN to 1000, SlotParity.ODD to 1000),
            txMode = TxFrequency.Mode.FULL_AUTO, inContact = false, committedParity = null
        )
        assertEquals(TxFrequency.Reason.HELD, d.getValue(SlotParity.EVEN).reason)
        assertEquals(1000, d.getValue(SlotParity.EVEN).frequencyHz)
        assertEquals(TxFrequency.Reason.MOVED_SPOT_BUSY, d.getValue(SlotParity.ODD).reason)
    }

    @Test
    fun `each lane makes its own first pick`() {
        val readings = mapOf(
            SlotParity.EVEN to TxFrequencyPolicy.Reading(emptyMap(listOf(800)), evenSlot),
            SlotParity.ODD to TxFrequencyPolicy.Reading(emptyMap(listOf(2200)), evenSlot - 15_000)
        )
        val d = TxFrequencyPolicy.decideLanes(
            mode, evenSlot + 14_000, readings, held = emptyMap(),
            txMode = TxFrequency.Mode.FULL_AUTO, inContact = false, committedParity = null
        )
        assertEquals(800, d.getValue(SlotParity.EVEN).frequencyHz)
        assertEquals(2200, d.getValue(SlotParity.ODD).frequencyHz)
    }

    @Test
    fun `in a contact only the committed lane holds`() {
        // We transmit EVEN in the contact: that lane holds though its spot is busy.
        // Nobody listens for us on ODD, so that lane keeps choosing.
        val readings = mapOf(
            SlotParity.EVEN to TxFrequencyPolicy.Reading(mapWithSignalAt(1000), evenSlot),
            SlotParity.ODD to TxFrequencyPolicy.Reading(mapWithSignalAt(1000), evenSlot - 15_000)
        )
        val d = TxFrequencyPolicy.decideLanes(
            mode, evenSlot + 14_000, readings,
            held = mapOf(SlotParity.EVEN to 1000, SlotParity.ODD to 1000),
            txMode = TxFrequency.Mode.FULL_AUTO, inContact = true, committedParity = SlotParity.EVEN
        )
        assertEquals(TxFrequency.Reason.HELD_IN_QSO, d.getValue(SlotParity.EVEN).reason)
        assertEquals(1000, d.getValue(SlotParity.EVEN).frequencyHz)
        assertEquals(TxFrequency.Reason.MOVED_SPOT_BUSY, d.getValue(SlotParity.ODD).reason)
    }

    @Test
    fun `in a contact with no committed parity both lanes hold`() {
        val readings = mapOf(
            SlotParity.EVEN to TxFrequencyPolicy.Reading(mapWithSignalAt(1000), evenSlot),
            SlotParity.ODD to TxFrequencyPolicy.Reading(mapWithSignalAt(1000), evenSlot - 15_000)
        )
        val d = TxFrequencyPolicy.decideLanes(
            mode, evenSlot + 14_000, readings,
            held = mapOf(SlotParity.EVEN to 1000, SlotParity.ODD to 1000),
            txMode = TxFrequency.Mode.FULL_AUTO, inContact = true, committedParity = null
        )
        assertTrue(d.values.all { it.reason == TxFrequency.Reason.HELD_IN_QSO })
    }
}
