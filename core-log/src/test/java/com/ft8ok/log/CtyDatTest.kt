package com.ft8ok.log

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CtyDatTest {

    /** Three records in cty.dat's own layout: header at column 0, prefixes indented. */
    private val sample = listOf(
        "United States:            05:  08:  NA:   37.60:    91.87:     5.0:  K:",
        "    AA,K,N,W,=W1AW/KH6(31)[61]{OC}<21.0/157.0>,",
        "    KL7(1)[1];",
        "Hawaii:                   31:  61:  OC:   21.12:   157.48:    10.0:  KH6:",
        "    AH6,KH6,NH6,WH6;",
        "African Italy:            33:  37:  AF:   35.67:   -12.67:    -1.0:  *IG9:",
        "    IG9,IH9;"
    ).joinToString("\r\n")

    @Test
    fun `entity lines, prefixes, exact calls and overrides`() {
        val cty = CtyDat.parse(sample)
        assertEquals(3, cty.entityCount)
        val us = cty.entityOf("N0ABC")!!
        assertEquals("United States", us.name)
        assertEquals(-91.87, us.lon, 1e-9) // cty.dat's west-positive, turned east-positive
        assertEquals(1, cty.entityOf("KL7XX")!!.cqZone)
        assertEquals("Hawaii", cty.entityOf("KH6ABC")!!.name)
        val override = cty.entityOf("W1AW/KH6")!!
        assertEquals(31, override.cqZone)
        assertEquals("OC", override.continent)
        assertTrue(cty.entityOf("IG9AA")!!.waeOnly)
        assertEquals("IG9", cty.entityOf("IG9AA")!!.prefix)
    }

    @Test
    fun `slashes - operating suffixes and area digits keep the entity, a prefix part wins`() {
        assertEquals("VE4OK", CtyDat.lookupKey("VE4OK/P"))
        assertEquals("W1AW", CtyDat.lookupKey("W1AW/7"))
        assertEquals("KH6", CtyDat.lookupKey("KH6/W1ABC"))
        assertEquals("KH6", CtyDat.lookupKey("W1ABC/KH6"))
        assertEquals("KH6", CtyDat.lookupKey("W1ABC/KH6/P"))
    }

    @Test
    fun `hashed and empty calls have no entity`() {
        val cty = CtyDat.parse(sample)
        assertNull(cty.entityOf("<...>"))
        assertNull(cty.entityOf(""))
        assertNull(cty.entityOf("ZZ9ZZ"))
    }

    /** The shipped file (app/src/main/assets/cty/cty.dat). */
    @Test
    fun `the bundled cty dat places well-known calls`() {
        val cty = CtyDat.parse(File("../app/src/main/assets/cty/cty.dat").readText())
        assertTrue("entities: ${cty.entityCount}", cty.entityCount > 300)
        val cases = mapOf(
            "K1ABC" to "United States",
            "W7TTT" to "United States",
            "VE4OK" to "Canada",
            "VA3XYZ" to "Canada",
            "VE4OK/P" to "Canada",
            "DL1ABC" to "Fed. Rep. of Germany",
            "G4DEF" to "England",
            "JA1XYZ" to "Japan",
            "UA3ABC" to "European Russia",
            "UA9ABC" to "Asiatic Russia",
            "EA8ZZZ" to "Canary Islands",
            "KH6/W1ABC" to "Hawaii",
            "VK3ABC" to "Australia"
        )
        for ((call, name) in cases) assertEquals(call, name, cty.entityOf(call)?.name)
        assertEquals("NA", cty.entityOf("K1ABC")!!.continent)
    }
}
