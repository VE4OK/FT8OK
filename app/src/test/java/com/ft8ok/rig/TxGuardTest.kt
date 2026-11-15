package com.ft8ok.rig

import com.ft8ok.radio.RigState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TxGuardTest {

    private val ready = RigState(dialHz = 7_074_000, mode = "USB", dataMode = true)

    @Test
    fun `usb-d on a table frequency may transmit`() {
        assertNull(TxGuard.check(true, ready, emptyList()))
    }

    @Test
    fun `lsb on 80 m is refused`() {
        // The case seen on air.
        val lsb = RigState(dialHz = 3_573_000, mode = "LSB", dataMode = null)
        assertEquals(TxGuard.Refusal.NOT_USB, TxGuard.check(true, lsb, emptyList()))
    }

    @Test
    fun `plain usb and unknown data mode are refused`() {
        assertEquals(TxGuard.Refusal.DATA_OFF, TxGuard.check(true, ready.copy(dataMode = false), emptyList()))
        assertEquals(TxGuard.Refusal.DATA_UNKNOWN, TxGuard.check(true, ready.copy(dataMode = null), emptyList()))
    }

    @Test
    fun `off the table needs a custom entry`() {
        val odd = ready.copy(dialHz = 7_075_500)
        assertEquals(TxGuard.Refusal.OFF_LIST, TxGuard.check(true, odd, emptyList()))
        assertNull(TxGuard.check(true, odd, listOf(7_075_500L)))
    }

    @Test
    fun `no link or no frequency is refused`() {
        assertEquals(TxGuard.Refusal.NO_RADIO, TxGuard.check(false, ready, emptyList()))
        assertEquals(TxGuard.Refusal.NO_FREQUENCY, TxGuard.check(true, RigState(), emptyList()))
    }
}
