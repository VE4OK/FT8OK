package com.ft8ok.tx

import com.ft8ok.dsp.Decode
import com.ft8ok.model.ContestSetup
import com.ft8ok.model.Mode
import com.ft8ok.model.SlotParity
import com.ft8ok.model.StationProfile
import com.ft8ok.qso.Qso
import com.ft8ok.qso.QsoEngine
import com.ft8ok.qso.TxScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A contest CQ run, Field Day. */
class AutoTxContestTest {

    private val profile = StationProfile(callsign = "VE4OK", grid = "FN25")
    private val fd = ContestSetup(ContestSetup.Kind.FIELD_DAY, "FD", "2A MB")
    private val engine = QsoEngine(myCall = "VE4OK", myGrid = "FN25", contest = fd)
    private val dial = 7_074_000L
    private val evenSlot = (1_760_000_010_000L / 30_000) * 30_000

    private fun decode(message: String) = Decode(score = 20, dtSeconds = 0.5, frequencyHz = 1500, message = message)

    private fun oddSlot(tx: AutoTx, k: Int, vararg heard: Pair<String, Int>) {
        val slot = evenSlot + k * 30_000L + 15_000
        val snr = heard.associate { it.first to it.second }
        tx.onDecodes(engine, heard.map { decode(it.first) }, { snr[it.message] }, slot + 14_800, SlotParity.ODD, slotStartMs = slot)
    }

    private fun send(tx: AutoTx, k: Int): String? {
        val message = tx.decide(evenSlot + k * 30_000L + 1_000, profile, dial) ?: return null
        tx.onTransmitStarted()
        tx.onTransmitFinished(engine, transmitted = true)
        return message
    }

    private fun running(dupes: Set<String> = emptySet()): Pair<AutoTx, MutableList<Qso>> {
        val tx = AutoTx(Mode.FT8, TxScheduler(Mode.FT8))
        val logged = mutableListOf<Qso>()
        tx.onContactComplete = { logged += it }
        tx.isDupe = { it in dupes }
        tx.arm(profile)
        tx.startCq(engine, SlotParity.EVEN, word = "POTA")
        assertEquals("the contest's word, not the panel's", "CQ FD VE4OK FN25", send(tx, 0))
        return tx to logged
    }

    @Test
    fun `callers are worked one at a time, never combined`() {
        val (tx, logged) = running()
        oddSlot(tx, 0, "VE4OK K1ABC 3A CT" to -10, "VE4OK W9XYZ 1D WI" to -13)
        assertEquals("K1ABC", tx.qso?.partner)
        assertEquals("W9XYZ", tx.nextCaller)
        assertEquals("K1ABC VE4OK R 2A MB", send(tx, 1))

        oddSlot(tx, 1, "VE4OK K1ABC RR73" to -10, "VE4OK W9XYZ 1D WI" to -13)
        assertEquals("K1ABC VE4OK 73", send(tx, 2))
        assertEquals(listOf("K1ABC"), logged.map { it.partner })
        assertEquals("3A CT", logged.single().exchangeReceived)

        // Our 73 went out: next caller.
        oddSlot(tx, 2, "VE4OK W9XYZ 1D WI" to -13)
        assertEquals("W9XYZ VE4OK R 2A MB", send(tx, 3))
        assertTrue(tx.armed)
    }

    @Test
    fun `report and grid - two callers get plain messages, never a combined one`() {
        // The same calls as AutoTxChainTest's combined case, in Report+grid: the
        // International FT Challenge prohibits DXpedition-style messages.
        val rgEngine = QsoEngine(myCall = "VE4OK", myGrid = "FN25", contest = ContestSetup(ContestSetup.Kind.REPORT_GRID, "", "FN25"))
        val tx = AutoTx(Mode.FT8, TxScheduler(Mode.FT8))
        val logged = mutableListOf<Qso>()
        tx.onContactComplete = { logged += it }
        tx.arm(profile)
        tx.startCq(rgEngine, SlotParity.EVEN)
        fun sendRg(k: Int): String? {
            val m = tx.decide(evenSlot + k * 30_000L + 1_000, profile, dial) ?: return null
            tx.onTransmitStarted()
            tx.onTransmitFinished(rgEngine, transmitted = true)
            return m
        }
        fun heardRg(k: Int, vararg heard: Pair<String, Int>) {
            val slot = evenSlot + k * 30_000L + 15_000
            val snr = heard.associate { it.first to it.second }
            tx.onDecodes(rgEngine, heard.map { decode(it.first) }, { snr[it.message] }, slot + 14_800, SlotParity.ODD, slotStartMs = slot)
        }
        assertEquals("a plain CQ", "CQ VE4OK FN25", sendRg(0))
        heardRg(0, "VE4OK K1ABC FN42" to -10, "VE4OK W9XYZ EN37" to -13)
        assertEquals("K1ABC VE4OK -10", sendRg(1))
        heardRg(1, "VE4OK K1ABC R-08" to -10, "VE4OK W9XYZ EN37" to -13)
        assertEquals("plain RR73, the waiting caller after", "K1ABC VE4OK RR73", sendRg(2))
        assertEquals(listOf("K1ABC"), logged.map { it.partner })
        heardRg(2, "VE4OK W9XYZ EN37" to -13)
        assertEquals("W9XYZ VE4OK -13", sendRg(3))
    }

    @Test
    fun `a dupe calling is not answered on its own`() {
        val (tx, _) = running(dupes = setOf("K1ABC"))
        oddSlot(tx, 0, "VE4OK K1ABC 3A CT" to -10)
        assertNull("no contact with the dupe", tx.qso)
        assertEquals("CQ goes on", "CQ FD VE4OK FN25", send(tx, 1))
    }

    @Test
    fun `a tap still answers a dupe`() {
        val (tx, _) = running(dupes = setOf("K1ABC"))
        tx.onRowTapped(engine, "VE4OK K1ABC 3A CT", -10, evenSlot + 29_000, SlotParity.ODD)
        assertEquals("K1ABC", tx.qso?.partner)
        assertEquals("K1ABC VE4OK R 2A MB", tx.nextMessage)
    }

    @Test
    fun `the tap warning is off for a station in our own contest`() {
        val tx = AutoTx(Mode.FT8, TxScheduler(Mode.FT8))
        tx.onRowTapped(engine, "CQ FD K1ABC FN31", -10, evenSlot, SlotParity.ODD)
        assertEquals("K1ABC VE4OK 2A MB", tx.nextMessage)
        assertNull(tx.contestPartner)
        val plain = QsoEngine(myCall = "VE4OK", myGrid = "FN25")
        tx.onRowTapped(plain, "CQ FD K1ABC FN31", -10, evenSlot, SlotParity.ODD)
        assertEquals("standard mode still warns", "K1ABC", tx.contestPartner?.call)
    }
}
