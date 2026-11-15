package com.ft8ok.ui.shell

import com.ft8ok.model.DialFrequencies
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PickerRowsTest {

    private val table = DialFrequencies.ALL.filter { it.mode == "FT8" && it.hz <= 74_000_000 }

    @Test
    fun `custom dials sit in frequency order among the table`() {
        val rows = pickerRows(table, listOf(21_090_000, 7_075_500))
        assertEquals(rows.map { it.hz }.sorted(), rows.map { it.hz })
        assertEquals(table.size + 2, rows.size)

        val custom = rows.indexOfFirst { it.hz == 21_090_000L }
        assertNull(rows[custom].entry)
        assertTrue(rows[custom - 1].hz < 21_090_000)
        assertTrue(rows[custom + 1].hz > 21_090_000)
    }

    @Test
    fun `the table row comes first at the same frequency`() {
        val rows = pickerRows(table, listOf(14_074_000))
        val first = rows.indexOfFirst { it.hz == 14_074_000L }
        assertNotNull(rows[first].entry)
        assertNull(rows[first + 1].entry)
    }

    @Test
    fun `no custom dials leaves the table in order`() {
        assertEquals(table.sortedBy { it.hz }, pickerRows(table, emptyList()).map { it.entry })
    }
}
