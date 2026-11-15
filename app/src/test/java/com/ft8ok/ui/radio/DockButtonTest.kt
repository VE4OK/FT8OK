package com.ft8ok.ui.radio

import com.ft8ok.ui.radio.DockButton.Kind
import com.ft8ok.ui.radio.DockButton.Press
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DockButtonTest {

    private fun resolve(
        tab: MoreTab,
        canTransmit: Boolean = true,
        cqWord: String = "",
        callingCq: Boolean = false,
        readyCall: String? = null,
        partner: String? = null,
        textReady: Boolean = false,
        textQueued: Boolean = false
    ) = DockButton.resolve(tab, canTransmit, cqWord, callingCq, readyCall, partner, textReady, textQueued)

    @Test
    fun `the button follows the tab the panel was left on`() {
        assertEquals(Kind.CQ, resolve(MoreTab.CQ).kind)
        assertEquals(Kind.CALL, resolve(MoreTab.CALL, readyCall = "W1AW").kind)
        assertEquals(Kind.TEXT, resolve(MoreTab.TEXT, textReady = true).kind)
    }

    @Test
    fun `a callsign typed on CALL is what the button calls, not a CQ`() {
        // A call typed in CALL and then CQ pressed still sent CQ.
        val state = resolve(MoreTab.CALL, readyCall = "W1AW")
        assertEquals("W1AW", state.label)
        assertEquals(Press.CALL, DockButton.press(state))
    }

    @Test
    fun `CQ toggles a run, with its word on the label`() {
        val off = resolve(MoreTab.CQ, cqWord = "POTA")
        assertEquals("POTA", off.label)
        assertEquals(Press.START_CQ, DockButton.press(off))
        assertEquals(Press.STOP_CQ, DockButton.press(resolve(MoreTab.CQ, callingCq = true)))
    }

    @Test
    fun `no callsign ready, no call`() {
        val state = resolve(MoreTab.CALL, readyCall = null)
        assertFalse(state.enabled)
        assertEquals(Press.NOTHING, DockButton.press(state))
    }

    @Test
    fun `calling the partner again does not restart the contact`() {
        val state = resolve(MoreTab.CALL, readyCall = "W1AW", partner = "w1aw")
        assertTrue(state.lit)
        assertEquals(Press.NOTHING, DockButton.press(state))
    }

    @Test
    fun `text queues, and a second press drops it`() {
        assertEquals(Press.QUEUE_TEXT, DockButton.press(resolve(MoreTab.TEXT, textReady = true)))
        val queued = resolve(MoreTab.TEXT, textReady = false, textQueued = true)
        assertTrue(queued.enabled)
        assertEquals(Press.DROP_TEXT, DockButton.press(queued))
        assertEquals(Press.NOTHING, DockButton.press(resolve(MoreTab.TEXT, textReady = false)))
    }

    @Test
    fun `nothing works without a callsign and grid`() {
        for (tab in MoreTab.entries) {
            val state = resolve(tab, canTransmit = false, readyCall = "W1AW", textReady = true)
            assertEquals(tab.name, Press.NOTHING, DockButton.press(state))
        }
    }
}
