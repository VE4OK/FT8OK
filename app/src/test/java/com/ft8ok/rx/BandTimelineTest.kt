package com.ft8ok.rx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BandTimelineTest {

    private val slot = 15_000L
    private val t0 = 1_760_000_010_000L / 30_000 * 30_000

    @Test
    fun `the band is unknown until the first reading`() {
        val bands = BandTimeline()
        assertNull(bands.bandAt(t0))
        bands.onBand("20m", t0 + 100)
        assertNull("the slot began before the reading", bands.bandAt(t0))
        assertEquals("20m", bands.bandAt(t0 + slot))
    }

    @Test
    fun `a slot the band changed during straddles it, its neighbours do not`() {
        // VE7XAA: 20 m to 17 m eight seconds into a slot.
        val bands = BandTimeline()
        bands.onBand("20m", t0 - 10 * slot)
        bands.onBand("17m", t0 + 8_000)
        assertFalse(bands.straddles(t0 - slot, slot))
        assertTrue(bands.straddles(t0, slot))
        assertFalse(bands.straddles(t0 + slot, slot))
        assertEquals("20m", bands.bandAt(t0 - slot))
        assertEquals("17m", bands.bandAt(t0 + slot))
    }

    @Test
    fun `a change exactly on a boundary belongs to the slot it starts`() {
        val bands = BandTimeline()
        bands.onBand("20m", t0 - slot)
        bands.onBand("17m", t0)
        assertFalse(bands.straddles(t0 - slot, slot))
        assertFalse(bands.straddles(t0, slot))
        assertEquals("17m", bands.bandAt(t0))
    }

    @Test
    fun `a repeat of the same band is not a change`() {
        val bands = BandTimeline()
        bands.onBand("20m", t0 - slot)
        bands.onBand("20m", t0 + 5_000)
        assertFalse(bands.straddles(t0, slot))
    }

    @Test
    fun `losing the radio keeps the last band and drops nothing`() {
        val bands = BandTimeline()
        bands.onBand("20m", t0 - slot)
        assertNull(bands.onBand(null, t0 + 5_000))
        assertNull(bands.current)
        assertEquals("20m", bands.known)
        assertFalse(bands.straddles(t0, slot))
        assertEquals("20m", bands.bandAt(t0 + slot))
    }

    @Test
    fun `back on the same band, nothing changed`() {
        val bands = BandTimeline()
        bands.onBand("20m", t0 - slot)
        bands.onBand(null, t0 + 5_000)
        assertNull(bands.onBand("20m", t0 + slot + 5_000))
        assertFalse(bands.straddles(t0 + slot, slot))
        assertEquals("20m", bands.bandAt(t0 + 2 * slot))
    }

    @Test
    fun `back on another band, the gap is of unknown band`() {
        val bands = BandTimeline()
        bands.onBand("20m", t0 - slot)
        bands.onBand(null, t0 + 5_000)
        assertEquals(t0 + 5_000, bands.onBand("17m", t0 + 2 * slot + 5_000))
        assertNull(bands.bandAt(t0 + slot))
        assertTrue(bands.straddles(t0 + 2 * slot, slot))
        assertEquals("17m", bands.bandAt(t0 + 3 * slot))
    }

    @Test
    fun `the first reading drops no slot, which stays of unknown band`() {
        val bands = BandTimeline()
        assertNull(bands.onBand("20m", t0 + 5_000))
        assertFalse(bands.straddles(t0, slot))
        assertNull(bands.bandAt(t0))
        assertEquals("20m", bands.bandAt(t0 + slot))
    }
}
