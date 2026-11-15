package com.ft8ok.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StationProfileTest {

    @Test
    fun `an empty profile cannot transmit, and says which part is missing`() {
        // There is deliberately no default callsign: a placeholder would be somebody
        // else's, and we have already nearly transmitted one station's call by
        // accident (it came from our own decode feed).
        val empty = StationProfile()
        assertTrue(!empty.canTransmit)
        assertEquals(StationProfile.Blocker.NO_CALLSIGN, empty.blocker)

        val callOnly = StationProfile(callsign = "VE4OK")
        assertTrue("a QSO's first message carries the grid", !callOnly.canTransmit)
        assertEquals(StationProfile.Blocker.NO_GRID, callOnly.blocker)
    }

    @Test
    fun `a complete profile can transmit`() {
        val profile = StationProfile(callsign = "VE4OK", grid = "FN25")
        assertTrue(profile.canTransmit)
        assertNull(profile.blocker)
    }

    @Test
    fun `what is typed is normalised to what is sent`() {
        val profile = StationProfile(callsign = " ve4ok ", grid = " fn25 ")
        assertEquals("VE4OK", profile.normalisedCallsign)
        assertEquals("FN25", profile.normalisedGrid)
        assertTrue(profile.canTransmit)
    }

    @Test
    fun `portable suffixes are allowed, because people operate portable`() {
        // This app is built for SOTA and POTA. Rejecting /P would reject its users.
        assertTrue(StationProfile("VE4OK/P", "FN25").canTransmit)
        assertTrue(StationProfile("VE4OK/QRP", "FN25").canTransmit)
    }

    @Test
    fun `obvious rubbish is rejected, and the encoder decides the rest`() {
        // Deliberately permissive: the 77-bit protocol has its own rules and this
        // app does not get to declare a legitimate callsign invalid. Only the
        // clearly impossible is caught here.
        assertTrue(!StationProfile("VE 4OK", "FN25").hasValidCallsign)
        assertTrue(!StationProfile("VE4OK!", "FN25").hasValidCallsign)
        assertTrue(!StationProfile("A", "FN25").hasValidCallsign)
        // ...while unusual but real shapes pass through to the encoder.
        assertTrue(StationProfile("2E0ABC", "FN25").hasValidCallsign)
        assertTrue(StationProfile("VP2EXA", "FN25").hasValidCallsign)
    }

    @Test
    fun `a grid must be a four-character locator`() {
        assertTrue(StationProfile("VE4OK", "FN25").hasValidGrid)
        assertTrue(!StationProfile("VE4OK", "EN").hasValidGrid)
        assertTrue(!StationProfile("VE4OK", "FN25ab").hasValidGrid)
        assertTrue(!StationProfile("VE4OK", "ZZ99").hasValidGrid)   // fields stop at R
        assertEquals(StationProfile.Blocker.BAD_GRID, StationProfile("VE4OK", "ZZ99").blocker)
    }

    @Test
    fun `the SWR abort defaults to 2 and can be raised for an awkward antenna`() {
        assertEquals(2.0, StationProfile().effectiveSwrAbort, 0.001)
        // Up to 3.0 can be acceptable in some odd situations.
        assertEquals(3.0, StationProfile(swrAbort = 3.0).effectiveSwrAbort, 0.001)
        assertEquals(2.5, StationProfile(swrAbort = 2.5).effectiveSwrAbort, 0.001)
    }

    @Test
    fun `the SWR abort is bounded at both ends`() {
        // Too low and an ordinary antenna trips it constantly, so the operator learns
        // to ignore the alarm. Too high and it is not protecting the radio.
        assertEquals(
            StationProfile.MIN_SWR_ABORT,
            StationProfile(swrAbort = 1.0).effectiveSwrAbort, 0.001
        )
        assertEquals(
            StationProfile.MAX_SWR_ABORT,
            StationProfile(swrAbort = 10.0).effectiveSwrAbort, 0.001
        )
    }
}
