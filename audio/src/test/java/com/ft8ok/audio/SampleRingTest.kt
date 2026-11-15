package com.ft8ok.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The alignment arithmetic, on the JVM.
 *
 * This is the class the whole clock depends on: if a slice starts a few milliseconds
 * from where it claims to, every DT is wrong by that much, the band consensus
 * faithfully corrects for it, and the error is invisible because everything agrees
 * with everything else. So these tests check *which samples come back*, not merely
 * that some do.
 */
class SampleRingTest {

    private val rate = UsbAudioIn.TARGET_RATE

    /** A ring whose sample values are their own absolute index, so a slice can be
     *  checked against exactly the samples it should have returned. */
    private fun ringOfIndices(seconds: Int, count: Int, epochOfZero: Double = 1_000_000.0): SampleRing {
        val ring = SampleRing(rate, seconds)
        ring.anchor(epochOfZero)
        var written = 0
        // Deliberately irregular chunks: real audio does not arrive in neat blocks,
        // and the whole point of absolute indexing is that it does not matter.
        for (chunk in listOf(1234, 777, 4001, 9999, 13, 20_000)) {
            if (written >= count) break
            val n = minOf(chunk, count - written)
            ring.append(FloatArray(n) { (written + it).toFloat() }, n)
            written += n
        }
        while (written < count) {
            val n = minOf(5_000, count - written)
            ring.append(FloatArray(n) { (written + it).toFloat() }, n)
            written += n
        }
        return ring
    }

    @Test
    fun `an unanchored ring refuses to slice rather than guessing`() {
        val ring = SampleRing(rate, 40)
        ring.append(FloatArray(rate))
        assertTrue(!ring.isAnchored)
        assertNull(ring.sliceByEpoch(0, 15_000))
    }

    @Test
    fun `a slice starts at the exact sample, whatever the chunk boundaries were`() {
        val ring = ringOfIndices(seconds = 40, count = rate * 30, epochOfZero = 1_000_000.0)
        // One second in: sample 12000, whatever chunk happened to contain it.
        val slice = ring.sliceByEpoch(1_001_000, 15_000)
        assertNotNull(slice)
        assertEquals(12_000f, slice!![0], 0f)
        assertEquals(12_001f, slice[1], 0f)
        assertEquals(15_000 * rate / 1000, slice.size)
    }

    @Test
    fun `alignment does not drift across the buffer`() {
        val ring = ringOfIndices(seconds = 40, count = rate * 35)
        for (secondsIn in 0..20) {
            val slice = ring.sliceByEpoch(1_000_000L + secondsIn * 1000L, 1_000)
            assertNotNull("second $secondsIn", slice)
            assertEquals(
                "second $secondsIn starts at the wrong sample",
                (secondsIn * rate).toFloat(), slice!![0], 0f
            )
        }
    }

    @Test
    fun `a decode pass before the slot ends gets what exists, not nothing`() {
        // 11.8 s into a 15 s slot: the first pass must get 11.8 s of audio. This is
        // what buys the operator seconds before the cycle closes.
        val ring = ringOfIndices(seconds = 40, count = (rate * 11.8).toInt())
        val slice = ring.sliceByEpoch(1_000_000, 15_000)
        assertNotNull(slice)
        assertEquals((rate * 11.8).toInt(), slice!!.size)
        assertEquals(0f, slice[0], 0f)
    }

    @Test
    fun `audio that has not arrived yet is not a slice of silence`() {
        val ring = ringOfIndices(seconds = 40, count = rate * 5)
        // Returning zeros here would feed the decoder silence and the clock a slot
        // with no decodes, which looks exactly like a dead band.
        assertNull(ring.sliceByEpoch(1_010_000, 15_000))
    }

    @Test
    fun `audio from before the stream started is refused`() {
        val ring = ringOfIndices(seconds = 40, count = rate * 5)
        assertNull(ring.sliceByEpoch(999_000, 15_000))
    }

    @Test
    fun `overwritten audio is refused rather than returned torn`() {
        // A ring of 20 s, 60 s written: the start of the first slot is long gone.
        val ring = ringOfIndices(seconds = 20, count = rate * 60)
        assertNull(ring.sliceByEpoch(1_000_000, 15_000))
        // ...while recent audio is still perfectly good.
        val recent = ring.sliceByEpoch(1_000_000 + 50_000, 1_000)
        assertNotNull(recent)
        assertEquals((50 * rate).toFloat(), recent!![0], 0f)
    }

    @Test
    fun `anchoring part-way through the stream places sample zero correctly`() {
        val ring = SampleRing(rate, 40)
        // The timestamp arrives when frame 36 000 (3 s in) was captured at epoch
        // 1 003 000, so sample zero was at 1 000 000.
        ring.anchor(1_003_000.0, atSampleIndex = 3 * rate.toLong())
        assertEquals(1_000_000.0, ring.epochOfSampleZero, 1e-6)

        ring.append(FloatArray(rate * 10) { it.toFloat() })
        val slice = ring.sliceByEpoch(1_002_000, 1_000)
        assertEquals((2 * rate).toFloat(), slice!![0], 0f)
    }

    @Test
    fun `reset forgets the anchor as well as the audio`() {
        val ring = ringOfIndices(seconds = 40, count = rate * 5)
        ring.reset()
        assertTrue(!ring.isAnchored)
        assertEquals(0L, ring.written)
        assertNull(ring.sliceByEpoch(1_000_000, 15_000))
    }

    @Test
    fun `the newest sample reports the time it was captured`() {
        val ring = ringOfIndices(seconds = 40, count = rate * 7)
        assertEquals(1_007_000.0, ring.newestEpochMs()!!, 1e-6)
    }
}
