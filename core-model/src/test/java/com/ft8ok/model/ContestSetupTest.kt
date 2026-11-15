package com.ft8ok.model

import com.ft8ok.model.ContestSetup.Kind
import com.ft8ok.model.ContestSetup.Problem
import com.ft8ok.model.ContestSetup.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContestSetupTest {

    private fun build(kind: Kind, call: String = "VE4OK", grid: String = "FN25", word: String = "TEST", cls: String = "2A", sec: String = "MB") =
        ContestSetup.build(kind, call, grid, word, cls, sec)

    @Test
    fun `field day exchange is class and section`() {
        assertEquals(Result.Ok(ContestSetup(Kind.FIELD_DAY, "FD", "2A MB")), build(Kind.FIELD_DAY))
    }

    @Test
    fun `grid exchange is our grid, with the CQ word chosen`() {
        assertEquals(Result.Ok(ContestSetup(Kind.GRID, "WW", "FN25")), build(Kind.GRID, word = "WW"))
        assertEquals(Result.Refused(Problem.BAD_CQ_WORD), build(Kind.GRID, word = "FD"))
        assertEquals(Result.Refused(Problem.NO_GRID), build(Kind.GRID, grid = ""))
    }

    @Test
    fun `a grid contest may call with any word FT8 can carry`() {
        for (word in listOf("YBDX", "A", "123")) {
            assertEquals(word, Result.Ok(ContestSetup(Kind.GRID, word, "FN25")), build(Kind.GRID, word = word))
        }
        for (word in listOf("", "YBDXX", "1234", "12", "YB1")) {
            assertEquals(word, Result.Refused(Problem.BAD_CQ_WORD), build(Kind.GRID, word = word))
        }
    }

    @Test
    fun `a custom word's CQ is from a station in our contest`() {
        val setup = ContestSetup(Kind.GRID, "YBDX", "FN25")
        assertTrue(setup.sameContestAs(Ft8Message.parse("CQ YBDX 5B4XAA KM64")))
        assertTrue(setup.sameContestAs(Ft8Message.parse("CQ TEST K1ABC FN42")))
    }

    @Test
    fun `field day class is 1 to 32 transmitters and A to F`() {
        for (ok in listOf("1A", "2B", "9F", "10A", "32F")) assertTrue(ok, ContestSetup.isFieldDayClass(ok))
        for (bad in listOf("0A", "33A", "2G", "2", "A", "02A", "2a", "100A")) assertFalse(bad, ContestSetup.isFieldDayClass(bad))
        assertEquals(Result.Refused(Problem.BAD_CLASS), build(Kind.FIELD_DAY, cls = "2Z"))
    }

    @Test
    fun `sections are WSJT-X's 86, DX included`() {
        assertEquals(86, ContestSetup.ARRL_SECTIONS.size)
        assertEquals(86, ContestSetup.ARRL_SECTIONS.toSet().size)
        assertTrue(ContestSetup.isSection("DX"))
        assertEquals(Result.Refused(Problem.BAD_SECTION), build(Kind.FIELD_DAY, sec = "MAN"))
    }

    @Test
    fun `contest messages need a plain call`() {
        assertEquals(Result.Refused(Problem.CALL_NOT_PLAIN), build(Kind.FIELD_DAY, call = "VE4OK/P"))
        assertEquals(Result.Refused(Problem.CALL_NOT_PLAIN), build(Kind.GRID, call = "VE4OK/7"))
    }

    // ---- Report+grid ----

    private fun rg(word: String = "", call: String = "VE4OK", grid: String = "FN25") =
        ContestSetup.build(Kind.REPORT_GRID, call, grid, "TEST", "", "", word)

    @Test
    fun `report and grid sends standard messages, with a plain CQ by default`() {
        val ok = rg() as Result.Ok
        assertEquals(ContestSetup(Kind.REPORT_GRID, "", "FN25"), ok.setup)
        assertFalse(ok.setup.sendsExchange)
        assertTrue((build(Kind.GRID) as Result.Ok).setup.sendsExchange)
        assertTrue((build(Kind.FIELD_DAY) as Result.Ok).setup.sendsExchange)
        assertEquals(ContestSetup(Kind.REPORT_GRID, "TEST", "FN25"), (rg("TEST") as Result.Ok).setup)
    }

    @Test
    fun `report and grid needs a grid and a word FT8 carries, but takes any call`() {
        assertEquals(Result.Refused(Problem.NO_GRID), rg(grid = ""))
        assertEquals(Result.Refused(Problem.BAD_CQ_WORD), rg("FD"))
        assertTrue("standard messages carry /P", rg(call = "VE4OK/P") is Result.Ok)
    }

    @Test
    fun `report and grid matches no contest message, and a CQ only by a word it has`() {
        val plain = (rg() as Result.Ok).setup
        assertFalse(plain.sameContestAs(Ft8Message.parse("CQ K1ABC FN42")))
        assertFalse(plain.sameContestAs(Ft8Message.parse("CQ FD K1ABC FN42")))
        val test = (rg("TEST") as Result.Ok).setup
        assertTrue(test.sameContestAs(Ft8Message.parse("CQ TEST K1ABC FN42")))
    }
}
