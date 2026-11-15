package com.ft8ok.tx

import com.ft8ok.dsp.Decode
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

/**
 * Chaining contacts in a CQ run with DXpedition (0.1) messages:
 * our RR73 to one station and the next caller's report in one transmission.
 */
class AutoTxChainTest {

    private val profile = StationProfile(callsign = "VE4OK", grid = "FN25")
    private val engine = QsoEngine(myCall = "VE4OK", myGrid = "FN25")
    private val dial = 14_074_000L

    /** An EVEN slot boundary; we call CQ in even slots, callers answer in odd. */
    private val evenSlot = (1_760_000_010_000L / 30_000) * 30_000

    private fun decode(message: String) = Decode(score = 20, dtSeconds = 0.5, frequencyHz = 1500, message = message)

    /** The odd slot after our even slot [k] is decoded, with [snr] per message. */
    private fun oddSlot(tx: AutoTx, k: Int, vararg heard: Pair<String, Int>) {
        val slot = evenSlot + k * 30_000L + 15_000
        val snr = heard.associate { it.first to it.second }
        tx.onDecodes(engine, heard.map { decode(it.first) }, { snr[it.message] }, slot + 14_800, SlotParity.ODD, slotStartMs = slot)
    }

    /** Our even slot [k]: what goes out, sent in full. */
    private fun send(tx: AutoTx, k: Int): String? {
        val message = tx.decide(evenSlot + k * 30_000L + 1_000, profile, dial) ?: return null
        tx.onTransmitStarted()
        tx.onTransmitFinished(engine, transmitted = true)
        return message
    }

    /** A CQ run, armed, our first CQ sent. [missLimit]: Settings › CQ PREFERENCES (0 = off). */
    private fun running(missLimit: Int = AutoTx.DEFAULT_CQ_MISS_LIMIT): Pair<AutoTx, MutableList<Qso>> {
        val tx = AutoTx(Mode.FT8, TxScheduler(Mode.FT8))
        tx.cqMissLimit = { missLimit }
        val logged = mutableListOf<Qso>()
        tx.onContactComplete = { logged += it }
        tx.arm(profile)
        tx.startCq(engine, SlotParity.EVEN)
        assertEquals("CQ VE4OK FN25", send(tx, 0))
        return tx to logged
    }

    @Test
    fun `two callers at once are worked in one transmission each`() {
        val (tx, logged) = running()
        oddSlot(tx, 0, "VE4OK K1ABC FN42" to -10, "VE4OK W9XYZ EN37" to -13)
        assertEquals("first heard goes first", "K1ABC", tx.qso?.partner)
        assertEquals("W9XYZ", tx.nextCaller)
        assertEquals("K1ABC VE4OK -10", send(tx, 1))

        oddSlot(tx, 1, "VE4OK K1ABC R-08" to -10, "VE4OK W9XYZ EN37" to -13)
        // -13 goes out as -14: the message carries even reports only, as WSJT-X sends it.
        assertEquals("K1ABC RR73; W9XYZ <VE4OK> -14", send(tx, 2))
        assertEquals("K1ABC logged when our RR73 went out", listOf("K1ABC"), logged.map { it.partner })
        assertEquals("W9XYZ", tx.qso?.partner)
        assertEquals(-14, tx.qso?.reportSentDb)
        assertEquals("EN37", tx.qso?.partnerGrid)
        assertEquals("TIME_ON is their first call", evenSlot + 15_000 + 14_800, tx.qso?.startedAtMs)
        assertNull(tx.nextCaller)

        oddSlot(tx, 2, "VE4OK W9XYZ R-11" to -13)
        assertEquals("nobody waiting: an ordinary RR73", "W9XYZ VE4OK RR73", send(tx, 3))
        assertEquals(listOf("K1ABC", "W9XYZ"), logged.map { it.partner })
        assertTrue("the run stays armed", tx.armed)
    }

    @Test
    fun `a station that missed the combined RR73 gets it again with the next report`() {
        val (tx, _) = running()
        oddSlot(tx, 0, "VE4OK K1ABC FN42" to -10, "VE4OK W9XYZ EN37" to -12)
        send(tx, 1)
        oddSlot(tx, 1, "VE4OK K1ABC R-08" to -10)
        assertEquals("K1ABC RR73; W9XYZ <VE4OK> -12", send(tx, 2))

        // K1ABC repeats their R-report, W9XYZ has not answered: both again.
        oddSlot(tx, 2, "VE4OK K1ABC R-08" to -10)
        assertEquals("K1ABC RR73; W9XYZ <VE4OK> -12", send(tx, 3))
        assertEquals("still working W9XYZ", "W9XYZ", tx.qso?.partner)

        // Nothing from either: W9XYZ has had two messages unanswered, so the run
        // drops them and calls CQ ("2 tries max in CQ mode").
        oddSlot(tx, 3)
        assertEquals("CQ VE4OK FN25", send(tx, 4))
    }

    @Test
    fun `no one waiting means ordinary messages throughout`() {
        val (tx, _) = running()
        oddSlot(tx, 0, "VE4OK K1ABC FN42" to -10)
        send(tx, 1)
        oddSlot(tx, 1, "VE4OK K1ABC R-08" to -10)
        assertEquals("K1ABC VE4OK RR73", send(tx, 2))
    }

    @Test
    fun `a tap puts a caller ahead of the first heard`() {
        val (tx, _) = running()
        oddSlot(tx, 0, "VE4OK K1ABC FN42" to -10, "VE4OK W9XYZ EN37" to -12, "VE4OK N0CAL EM28" to -6)
        assertEquals("W9XYZ", tx.nextCaller)
        tx.onRowTapped(engine, "VE4OK N0CAL EM28", -6, evenSlot + 29_900, SlotParity.ODD, 0.5)
        assertEquals("the contact carries on", "K1ABC", tx.qso?.partner)
        assertEquals("N0CAL", tx.nextCaller)
        send(tx, 1)
        oddSlot(tx, 1, "VE4OK K1ABC R-08" to -10, "VE4OK N0CAL EM28" to -6, "VE4OK W9XYZ EN37" to -12)
        assertEquals("K1ABC RR73; N0CAL <VE4OK> -06", send(tx, 2))
    }

    @Test
    fun `search and pounce queues nobody`() {
        val tx = AutoTx(Mode.FT8, TxScheduler(Mode.FT8))
        tx.arm(profile)
        tx.callStation(engine, "K1ABC", evenSlot - 15_000, SlotParity.ODD)
        send(tx, 0)
        oddSlot(tx, 0, "VE4OK K1ABC -10" to -10, "VE4OK W9XYZ EN37" to -12)
        assertNull(tx.nextCaller)
        assertEquals("K1ABC VE4OK R-10", send(tx, 1))
        oddSlot(tx, 1, "VE4OK K1ABC RR73" to -10, "VE4OK W9XYZ EN37" to -12)
        assertEquals("K1ABC VE4OK 73", send(tx, 2))
    }

    @Test
    fun `a partner with a compound call is closed the ordinary way`() {
        val (tx, _) = running()
        oddSlot(tx, 0, "VE4OK K1ABC/P -10" to -10, "VE4OK W9XYZ EN37" to -12)
        send(tx, 1)
        oddSlot(tx, 1, "VE4OK K1ABC/P R-08" to -10, "VE4OK W9XYZ EN37" to -12)
        assertEquals("K1ABC/P VE4OK RR73", send(tx, 2))
    }

    @Test
    fun `our last 73 goes as the RR73 half when a caller waits`() {
        // KC2XA and NA1XA: both answered our CQ with a report, so the
        // RR73 was theirs and our 73 the last word - sent alone, one contact at a time.
        val (tx, logged) = running()
        oddSlot(tx, 0, "VE4OK KC2XA -25" to -20, "VE4OK NA1XA -01" to 3)
        assertEquals("KC2XA VE4OK R-20", send(tx, 1))
        oddSlot(tx, 1, "VE4OK KC2XA RR73" to -20, "VE4OK NA1XA -01" to 3)
        assertEquals(listOf("KC2XA"), logged.map { it.partner })
        assertEquals("KC2XA RR73; NA1XA <VE4OK> +02", send(tx, 2))
        assertEquals("NA1XA", tx.qso?.partner)
        assertEquals("logged once", listOf("KC2XA"), logged.map { it.partner })
        // They sent their report first, so a repeat to them carries the R.
        oddSlot(tx, 2)
        assertEquals("NA1XA VE4OK R+02", send(tx, 3))
        oddSlot(tx, 3, "VE4OK NA1XA RR73" to 3)
        assertEquals("NA1XA VE4OK 73", send(tx, 4))
        assertEquals(listOf("KC2XA", "NA1XA"), logged.map { it.partner })
    }

    @Test
    fun `with CQ preferences off, after two unanswered RR73s the partner is dropped and the caller gets a plain report`() {
        // W6XAA / K2XAA: the caller's arrival kept W6XAA alive for a
        // third RR73, sent combined with our call hashed. With CQ PREFERENCES on, a
        // silent partner gets one RR73 only (see below).
        val (tx, logged) = running(missLimit = 0)
        oddSlot(tx, 0, "VE4OK W6XAA FN20" to -7)
        send(tx, 1)
        oddSlot(tx, 1, "VE4OK W6XAA R-07" to -7)
        assertEquals("W6XAA VE4OK RR73", send(tx, 2))
        oddSlot(tx, 2)
        assertEquals("W6XAA VE4OK RR73", send(tx, 3))
        oddSlot(tx, 3, "VE4OK K2XAA FN20" to -14)
        assertEquals("K2XAA", tx.qso?.partner)
        assertEquals("K2XAA VE4OK -14", send(tx, 4))
        assertEquals("W6XAA logged once", listOf("W6XAA"), logged.map { it.partner })
    }

    @Test
    fun `a call with no grid is answered with a report`() {
        // AD9XA: "VE4OK AD9XA" was not understood, so not answered.
        val (tx, _) = running()
        oddSlot(tx, 0, "VE4OK AD9XA" to 0)
        assertEquals("AD9XA VE4OK +00", send(tx, 1))
        assertNull(tx.qso?.partnerGrid)
    }

    @Test
    fun `a partner silent twice gives way to a waiting caller`() {
        val (tx, _) = running()
        oddSlot(tx, 0, "VE4OK K1ABC FN42" to -10, "VE4OK W9XYZ EN37" to -12)
        assertEquals("K1ABC VE4OK -10", send(tx, 1))
        oddSlot(tx, 1, "VE4OK W9XYZ EN37" to -12)
        assertEquals("K1ABC VE4OK -10", send(tx, 2))
        // The slot after our second try is still K1ABC's: nothing from them.
        oddSlot(tx, 2, "VE4OK W9XYZ EN37" to -12)
        assertEquals("W9XYZ", tx.qso?.partner)
        assertEquals("W9XYZ VE4OK -12", send(tx, 3))
        assertTrue("the run stays armed", tx.armed)
    }

    @Test
    fun `in a CQ run a partner silent twice is dropped even with nobody waiting`() {
        // VA6XAA: five reports went out before the operator stepped in.
        val (tx, logged) = running()
        oddSlot(tx, 0, "VE4OK VA6XAA DO21" to -4)
        assertEquals("VA6XAA VE4OK -04", send(tx, 1))
        oddSlot(tx, 1)
        assertEquals("VA6XAA VE4OK -04", send(tx, 2))
        oddSlot(tx, 2)
        assertNull(tx.qso)
        assertEquals("CQ VE4OK FN25", send(tx, 3))
        assertTrue(tx.armed)

        // Heard again with the R-report that never got through: carried on, not restarted.
        oddSlot(tx, 3, "VE4OK VA6XAA R-09" to -4)
        assertEquals("VA6XAA VE4OK RR73", send(tx, 4))
        assertEquals(listOf("VA6XAA"), logged.map { it.partner })
        assertEquals(-4, logged.single().reportSentDb)
        assertEquals("DO21", logged.single().partnerGrid)
    }

    @Test
    fun `a dropped partner calling again later is answered`() {
        val (tx, _) = running()
        oddSlot(tx, 0, "VE4OK VA6XAA DO21" to -4)
        send(tx, 1); oddSlot(tx, 1); send(tx, 2); oddSlot(tx, 2)
        assertEquals("CQ VE4OK FN25", send(tx, 3))
        oddSlot(tx, 3, "VE4OK VA6XAA DO21" to -6)
        assertEquals("VA6XAA VE4OK -06", send(tx, 4))
    }

    @Test
    fun `a partner answering after two tries is kept`() {
        val (tx, _) = running()
        oddSlot(tx, 0, "VE4OK K1ABC FN42" to -10, "VE4OK W9XYZ EN37" to -12)
        send(tx, 1)
        oddSlot(tx, 1, "VE4OK W9XYZ EN37" to -12)
        send(tx, 2)
        oddSlot(tx, 2, "VE4OK K1ABC R-08" to -10, "VE4OK W9XYZ EN37" to -12)
        assertEquals("K1ABC RR73; W9XYZ <VE4OK> -12", send(tx, 3))
    }

    @Test
    fun `a waiting caller missed in two of their slots is dropped`() {
        val (tx, _) = running()
        oddSlot(tx, 0, "VE4OK K1ABC FN42" to -10, "VE4OK W9XYZ EN37" to -12)
        send(tx, 1)
        // K1ABC missed our report; W9XYZ is missed once and still waits.
        oddSlot(tx, 1, "VE4OK K1ABC FN42" to -10)
        assertEquals("W9XYZ", tx.nextCaller)
        send(tx, 2)
        // Missed a second time: gone, so the close is an ordinary RR73.
        oddSlot(tx, 2, "VE4OK K1ABC R-08" to -10)
        assertNull(tx.nextCaller)
        assertEquals("K1ABC VE4OK RR73", send(tx, 3))
    }

    // ---- Settings › CQ PREFERENCES: move on after missed replies ----

    @Test
    fun `a partner repeating their grid is dropped after two and the next slot is CQ`() {
        // EA3XAA: JN11 seven times to our reports, never dropped, because
        // each repeat reset the silence count.
        val (tx, _) = running()
        oddSlot(tx, 0, "VE4OK EA3XAA JN11" to -5)
        assertEquals("EA3XAA VE4OK -05", send(tx, 1))
        oddSlot(tx, 1, "VE4OK EA3XAA JN11" to -4)
        assertEquals("EA3XAA VE4OK -04", send(tx, 2))
        oddSlot(tx, 2, "VE4OK EA3XAA JN11" to -6)
        assertNull("dropped as their slot is decoded", tx.qso)
        assertEquals("the very next slot is ours to use", "CQ VE4OK FN25", send(tx, 3))
        assertTrue(tx.armed)
    }

    @Test
    fun `a repeating partner gives way to a waiting caller in the next slot`() {
        val (tx, _) = running()
        oddSlot(tx, 0, "VE4OK K1XAA FN42" to -11)
        send(tx, 1)
        oddSlot(tx, 1, "VE4OK K1XAA FN42" to -12)
        send(tx, 2)
        oddSlot(tx, 2, "VE4OK K1XAA FN42" to -14, "VE4OK G4XAA IO91" to -9)
        assertEquals("G4XAA", tx.qso?.partner)
        assertEquals("G4XAA VE4OK -09", send(tx, 3))
    }

    @Test
    fun `a dropped repeating partner who comes back with their R-report is finished`() {
        val (tx, logged) = running()
        oddSlot(tx, 0, "VE4OK EA3XAA JN11" to -5)
        send(tx, 1); oddSlot(tx, 1, "VE4OK EA3XAA JN11" to -5)
        send(tx, 2); oddSlot(tx, 2, "VE4OK EA3XAA JN11" to -5)
        assertEquals("CQ VE4OK FN25", send(tx, 3))
        oddSlot(tx, 3, "VE4OK EA3XAA R-17" to -5)
        assertEquals("EA3XAA VE4OK RR73", send(tx, 4))
        assertEquals(listOf("EA3XAA"), logged.map { it.partner })
    }

    @Test
    fun `silence after our RR73 ends the contact with no second RR73`() {
        // WB5XA and F4XAC: the second RR73 went to stations already
        // working others.
        val (tx, logged) = running()
        oddSlot(tx, 0, "VE4OK WB5XA EM34" to -1)
        send(tx, 1)
        oddSlot(tx, 1, "VE4OK WB5XA R+03" to -1)
        assertEquals("WB5XA VE4OK RR73", send(tx, 2))
        oddSlot(tx, 2)
        assertNull(tx.qso)
        assertEquals("CQ VE4OK FN25", send(tx, 3))
        assertEquals("logged once", listOf("WB5XA"), logged.map { it.partner })
    }

    @Test
    fun `an R-report repeated to our RR73 gets one more RR73 then the run moves on`() {
        // EA3XAA: R-17 six times, four RR73s, until the operator stepped in.
        val (tx, logged) = running()
        oddSlot(tx, 0, "VE4OK EA3XAA JN11" to -5)
        send(tx, 1)
        oddSlot(tx, 1, "VE4OK EA3XAA R-17" to -5)
        assertEquals("EA3XAA VE4OK RR73", send(tx, 2))
        oddSlot(tx, 2, "VE4OK EA3XAA R-17" to -5)
        assertEquals("EA3XAA VE4OK RR73", send(tx, 3))
        oddSlot(tx, 3, "VE4OK EA3XAA R-17" to -5)
        assertNull(tx.qso)
        assertEquals("CQ VE4OK FN25", send(tx, 4))
        assertEquals("logged once", listOf("EA3XAA"), logged.map { it.partner })
    }

    @Test
    fun `at one missed reply the first repeat moves on`() {
        val (tx, _) = running(missLimit = 1)
        oddSlot(tx, 0, "VE4OK AD9XB EN52" to 12)
        send(tx, 1)
        oddSlot(tx, 1)
        assertNull(tx.qso)
        assertEquals("CQ VE4OK FN25", send(tx, 2))
    }

    @Test
    fun `at three missed replies a silent partner gets three tries`() {
        val (tx, _) = running(missLimit = 3)
        oddSlot(tx, 0, "VE4OK VA6XAA DO21" to -4)
        assertEquals("VA6XAA VE4OK -04", send(tx, 1)); oddSlot(tx, 1)
        assertEquals("VA6XAA VE4OK -04", send(tx, 2)); oddSlot(tx, 2)
        assertEquals("VA6XAA VE4OK -04", send(tx, 3)); oddSlot(tx, 3)
        assertNull(tx.qso)
        assertEquals("CQ VE4OK FN25", send(tx, 4))
    }

    @Test
    fun `a step forward starts the count again`() {
        // AD9XB and 9H1XA: each needed our second report, then went on.
        val (tx, logged) = running()
        oddSlot(tx, 0, "VE4OK AD9XB EN52" to 12)
        send(tx, 1); oddSlot(tx, 1)
        assertEquals("AD9XB VE4OK +12", send(tx, 2))
        oddSlot(tx, 2, "VE4OK AD9XB R-01" to 12)
        assertEquals("AD9XB VE4OK RR73", send(tx, 3))
        oddSlot(tx, 3, "VE4OK AD9XB R-01" to 12)
        assertEquals("one miss at the close is allowed", "AD9XB VE4OK RR73", send(tx, 4))
        assertEquals(listOf("AD9XB"), logged.map { it.partner })
    }

    @Test
    fun `with CQ preferences off a repeating partner is kept`() {
        val (tx, _) = running(missLimit = 0)
        oddSlot(tx, 0, "VE4OK EA3XAA JN11" to -5)
        for (k in 1..5) {
            assertEquals("EA3XAA", tx.qso?.partner)
            send(tx, k)
            oddSlot(tx, k, "VE4OK EA3XAA JN11" to -5)
        }
        assertEquals("EA3XAA", tx.qso?.partner)
    }

    @Test
    fun `search and pounce is not affected`() {
        // EA4XA: our tenth R-report got through.
        val tx = AutoTx(Mode.FT8, TxScheduler(Mode.FT8))
        tx.cqMissLimit = { 1 }
        tx.arm(profile)
        tx.callStation(engine, "EA4XA", evenSlot - 15_000, SlotParity.ODD)
        send(tx, 0)
        for (k in 0..4) {
            oddSlot(tx, k, "VE4OK EA4XA -24" to -6)
            assertEquals("EA4XA VE4OK R-06", send(tx, k + 1))
        }
        oddSlot(tx, 5, "VE4OK EA4XA RR73" to -6)
        assertEquals("EA4XA VE4OK 73", send(tx, 6))
    }

    @Test
    fun `re-arming gives the partner a fresh count`() {
        val (tx, _) = running()
        oddSlot(tx, 0, "VE4OK EA3XAA JN11" to -5)
        send(tx, 1); oddSlot(tx, 1, "VE4OK EA3XAA JN11" to -5)
        tx.disarmByOperator()
        tx.arm(profile)
        assertEquals("EA3XAA VE4OK -05", send(tx, 2))
        oddSlot(tx, 2, "VE4OK EA3XAA JN11" to -5)
        assertEquals("kept: one miss since the re-arm", "EA3XAA", tx.qso?.partner)
    }

    // ---- A partner dropped as busy elsewhere, back with their R-report (DL1XAA) ----

    /** DL1XAA answers, gets our report, then is heard finishing with KB6XAA: dropped. */
    private fun dl1trkDroppedBusy(): Pair<AutoTx, MutableList<Qso>> {
        val (tx, logged) = running()
        oddSlot(tx, 0, "VE4OK DL1XAA JO63" to -20)
        assertEquals("DL1XAA VE4OK -20", send(tx, 1))
        oddSlot(tx, 1, "KB6XAA DL1XAA R-07" to -20)
        assertNull("dropped as busy", tx.qso)
        assertEquals("CQ VE4OK FN25", send(tx, 2))
        return tx to logged
    }

    @Test
    fun `a partner dropped as busy who comes back with their R-report is finished`() {
        val (tx, logged) = dl1trkDroppedBusy()
        oddSlot(tx, 2, "VE4OK DL1XAA R-06" to -16)
        assertEquals("DL1XAA VE4OK RR73", send(tx, 3))
        assertEquals(listOf("DL1XAA"), logged.map { it.partner })
        assertEquals(-20, logged.single().reportSentDb)
    }

    @Test
    fun `a returning dropped partner waits for the contact in progress, then goes ahead of new callers`() {
        // DL1XAA sent R-06 five times while PC2Q, OZ1XAA and DL2XAA
        // were worked, and was never answered (finish the contact in progress,
        // then the returning station, then new callers).
        val (tx, logged) = dl1trkDroppedBusy()
        oddSlot(tx, 2, "VE4OK PC2Q JO22" to 1)
        assertEquals("PC2Q VE4OK +01", send(tx, 3))

        oddSlot(tx, 3, "VE4OK PC2Q R-14" to 1, "VE4OK DL1XAA R-06" to -16, "VE4OK DL2XAA JO64" to -18)
        assertEquals("the contact in progress is not interrupted", "PC2Q", tx.qso?.partner)
        assertEquals("DL1XAA", tx.nextCaller)
        assertEquals("plain: DL1XAA needs an RR73 of their own", "PC2Q VE4OK RR73", send(tx, 4))

        // PC2Q quiet after our RR73; DL1XAA still waiting, DL2XAA still calling.
        oddSlot(tx, 4, "VE4OK DL1XAA R-06" to -16, "VE4OK DL2XAA JO64" to -18)
        assertEquals("DL1XAA RR73; DL2XAA <VE4OK> -18", send(tx, 5))
        assertEquals(listOf("PC2Q", "DL1XAA"), logged.map { it.partner })
        assertEquals("DL2XAA", tx.qso?.partner)
    }

    @Test
    fun `a returning partner heard only while busy is still finished when the contact ends`() {
        // Their R-report arrives in the slot where the contact in progress ends: it is
        // read before that contact is over, and must not be lost.
        val (tx, logged) = dl1trkDroppedBusy()
        oddSlot(tx, 2, "VE4OK PC2Q JO22" to 1)
        send(tx, 3)
        oddSlot(tx, 3, "VE4OK PC2Q R-14" to 1)
        assertEquals("PC2Q VE4OK RR73", send(tx, 4))
        oddSlot(tx, 4, "VE4OK DL1XAA R-06" to -16)
        assertEquals("DL1XAA VE4OK RR73", send(tx, 5))
        assertEquals(listOf("PC2Q", "DL1XAA"), logged.map { it.partner })
    }

    @Test
    fun `a returning partner heard working someone else again is forgotten`() {
        val (tx, _) = dl1trkDroppedBusy()
        oddSlot(tx, 2, "VE4OK PC2Q JO22" to 1)
        send(tx, 3)
        oddSlot(tx, 3, "VE4OK PC2Q R-14" to 1, "VE4OK DL1XAA R-06" to -16)
        send(tx, 4)
        oddSlot(tx, 4, "CQ DL1XAA JO63" to -16)
        assertNull(tx.qso)
        assertEquals("CQ VE4OK FN25", send(tx, 5))
    }

    // ---- A combined-message partner who missed our RR73 (HA5XA, ON7XA) ----

    @Test
    fun `a closed partner who missed our RR73 gets it after the next contact, ahead of new callers`() {
        // ON7XA: their R-18 came again in the slot M3XAA sent R-03, and
        // the run let them go; the operator sent the RR73 by hand.
        val (tx, logged) = running()
        oddSlot(tx, 0, "VE4OK ON7XA JO11" to -9)
        assertEquals("ON7XA VE4OK -09", send(tx, 1))
        oddSlot(tx, 1, "VE4OK ON7XA R-18" to -9, "VE4OK M3XAA IO84" to -10)
        assertEquals("ON7XA RR73; M3XAA <VE4OK> -10", send(tx, 2))
        oddSlot(tx, 2, "VE4OK M3XAA IO84" to -10)
        assertEquals("M3XAA VE4OK -10", send(tx, 3))
        oddSlot(tx, 3, "VE4OK ON7XA R-18" to -9, "VE4OK M3XAA R-03" to -10, "VE4OK G4XYZ IO91" to -12)
        assertEquals("plain: ON7XA is owed an RR73 of their own", "M3XAA VE4OK RR73", send(tx, 4))
        oddSlot(tx, 4, "VE4OK ON7XA R-18" to -9, "VE4OK G4XYZ IO91" to -12)
        assertEquals("ON7XA RR73; G4XYZ <VE4OK> -12", send(tx, 5))
        assertEquals("each logged once", listOf("ON7XA", "M3XAA"), logged.map { it.partner })
    }

    @Test
    fun `a missed RR73 is sent again when the contact in progress ends in the same slot`() {
        // HA5XA: F4XAA's 73 and HA5XA's R-10 again in one slot.
        val (tx, logged) = running()
        oddSlot(tx, 0, "VE4OK HA5XA JN97" to -15)
        assertEquals("HA5XA VE4OK -15", send(tx, 1))
        oddSlot(tx, 1, "VE4OK HA5XA R-10" to -15, "VE4OK F4XAA -13" to -18)
        assertEquals("HA5XA RR73; F4XAA <VE4OK> -18", send(tx, 2))
        oddSlot(tx, 2, "VE4OK HA5XA R-10" to -15, "VE4OK F4XAA R-13" to -18)
        assertEquals("F4XAA VE4OK RR73", send(tx, 3))
        oddSlot(tx, 3, "VE4OK F4XAA 73" to -18, "VE4OK HA5XA R-10" to -15)
        assertEquals("HA5XA VE4OK RR73", send(tx, 4))
        oddSlot(tx, 4, "VE4OK HA5XA 73" to -15)
        assertEquals("CQ VE4OK FN25", send(tx, 5))
        assertEquals("each logged once", listOf("HA5XA", "F4XAA"), logged.map { it.partner })
    }

    @Test
    fun `after the extra RR73 the usual missed-reply limit ends it`() {
        val (tx, logged) = running()
        oddSlot(tx, 0, "VE4OK HA5XA JN97" to -15)
        send(tx, 1)
        oddSlot(tx, 1, "VE4OK HA5XA R-10" to -15, "VE4OK F4XAA -13" to -18)
        send(tx, 2)
        oddSlot(tx, 2, "VE4OK HA5XA R-10" to -15, "VE4OK F4XAA R-13" to -18)
        assertEquals("F4XAA VE4OK RR73", send(tx, 3))
        oddSlot(tx, 3, "VE4OK F4XAA 73" to -18, "VE4OK HA5XA R-10" to -15)
        assertEquals("HA5XA VE4OK RR73", send(tx, 4))
        oddSlot(tx, 4, "VE4OK HA5XA R-10" to -15)
        assertEquals("one miss at the close is allowed", "HA5XA VE4OK RR73", send(tx, 5))
        oddSlot(tx, 5, "VE4OK HA5XA R-10" to -15)
        assertNull(tx.qso)
        assertEquals("CQ VE4OK FN25", send(tx, 6))
        // Still repeating: ignored, never a second contact.
        oddSlot(tx, 6, "VE4OK HA5XA R-10" to -15)
        assertNull(tx.qso)
        assertEquals("CQ VE4OK FN25", send(tx, 7))
        assertEquals("each logged once", listOf("HA5XA", "F4XAA"), logged.map { it.partner })
    }

    // ---- A logged station's R-report gets RR73, not a new contact ----

    @Test
    fun `a logged station's late R-report while we are free gets RR73, not a new contact`() {
        // Not "WB5XA VE4OK R-01", a second contact.
        val (tx, logged) = running()
        oddSlot(tx, 0, "VE4OK WB5XA EM34" to -1)
        send(tx, 1)
        oddSlot(tx, 1, "VE4OK WB5XA R+03" to -1)
        assertEquals("WB5XA VE4OK RR73", send(tx, 2))
        oddSlot(tx, 2)
        assertEquals("quiet after our close", "CQ VE4OK FN25", send(tx, 3))
        oddSlot(tx, 3, "VE4OK WB5XA R+03" to -1)
        assertEquals("WB5XA VE4OK RR73", send(tx, 4))
        oddSlot(tx, 4, "VE4OK WB5XA 73" to -1)
        assertEquals("CQ VE4OK FN25", send(tx, 5))
        assertEquals("logged once", listOf("WB5XA"), logged.map { it.partner })
    }

    @Test
    fun `a logged station's R-report while we are busy is answered after the contact`() {
        val (tx, logged) = running()
        oddSlot(tx, 0, "VE4OK WB5XA EM34" to -1)
        send(tx, 1)
        oddSlot(tx, 1, "VE4OK WB5XA R+03" to -1)
        assertEquals("WB5XA VE4OK RR73", send(tx, 2))
        oddSlot(tx, 2, "VE4OK K1ABC FN42" to -10)
        assertEquals("K1ABC VE4OK -10", send(tx, 3))
        oddSlot(tx, 3, "VE4OK K1ABC R-08" to -10, "VE4OK WB5XA R+03" to -1)
        assertEquals("K1ABC VE4OK RR73", send(tx, 4))
        oddSlot(tx, 4, "VE4OK K1ABC 73" to -10, "VE4OK WB5XA R+03" to -1)
        assertEquals("WB5XA VE4OK RR73", send(tx, 5))
        assertEquals("each logged once", listOf("WB5XA", "K1ABC"), logged.map { it.partner })
    }

    @Test
    fun `a closed partner that took our RR73 and later sends their R-report gets one more`() {
        // Kept aside on release whether or not they had shown a miss yet.
        val (tx, logged) = running()
        oddSlot(tx, 0, "VE4OK HA5XA JN97" to -15)
        send(tx, 1)
        oddSlot(tx, 1, "VE4OK HA5XA R-10" to -15, "VE4OK F4XAA -13" to -18)
        assertEquals("HA5XA RR73; F4XAA <VE4OK> -18", send(tx, 2))
        oddSlot(tx, 2, "VE4OK F4XAA R-13" to -18)
        assertEquals("F4XAA VE4OK RR73", send(tx, 3))
        oddSlot(tx, 3, "VE4OK HA5XA R-10" to -15)
        assertEquals("HA5XA VE4OK RR73", send(tx, 4))
        assertEquals("each logged once", listOf("HA5XA", "F4XAA"), logged.map { it.partner })
    }

    @Test
    fun `a caller heard in their latest slot rides on our RR73 ahead of an older one`() {
        // SP4Q, heard once a cycle earlier, got the combined
        // report and never answered; DL1XA, calling every slot, waited.
        val (tx, _) = running()
        oddSlot(tx, 0, "VE4OK KF9XA EN71" to 16, "VE4OK SP4Q -21" to -12)
        assertEquals("KF9XA VE4OK +16", send(tx, 1))
        oddSlot(tx, 1, "VE4OK KF9XA R+00" to 16, "VE4OK DL1XA JO41" to -4)
        assertEquals("KF9XA RR73; DL1XA <VE4OK> -04", send(tx, 2))
    }

    @Test
    fun `a decode with no measured report is still acted on, at -24`() {
        // DL2XAA: their R-16 and 73 were found by subtraction with no
        // report, skipped, and we sent our report again.
        val (tx, logged) = running()
        val unmeasured = { k: Int, message: String ->
            val slot = evenSlot + k * 30_000L + 15_000
            tx.onDecodes(engine, listOf(decode(message)), { null }, slot + 14_800, SlotParity.ODD, slotStartMs = slot)
        }
        unmeasured(0, "VE4OK DL2XAA JO64")
        assertEquals("DL2XAA VE4OK -24", send(tx, 1))
        unmeasured(1, "VE4OK DL2XAA R-16")
        assertEquals("their R-report was heard", "DL2XAA VE4OK RR73", send(tx, 2))
        assertEquals(listOf("DL2XAA"), logged.map { it.partner })
    }

    @Test
    fun `a waiting caller heard working someone else is dropped`() {
        val (tx, _) = running()
        oddSlot(tx, 0, "VE4OK K1ABC FN42" to -10, "VE4OK W9XYZ EN37" to -12)
        assertEquals("W9XYZ", tx.nextCaller)
        send(tx, 1)
        oddSlot(tx, 1, "VE4OK K1ABC R-08" to -10, "N0CAL W9XYZ R-05" to -12)
        assertNull(tx.nextCaller)
        assertEquals("K1ABC VE4OK RR73", send(tx, 2))
    }
}
