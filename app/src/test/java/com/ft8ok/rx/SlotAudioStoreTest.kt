package com.ft8ok.rx

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SlotAudioStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun store(maxFiles: Int = 10, minFree: Long = 0) =
        SlotAudioStore(File(tmp.root, "slots"), maxFiles, minFree)

    @Test
    fun `writes a 12 kHz 16-bit mono wav with the samples clipped to full scale`() {
        val file = store().save("a.wav", floatArrayOf(0f, 0.5f, -2f))
        val bytes = file!!.readBytes()
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        assertEquals("RIFF", String(bytes, 0, 4, Charsets.US_ASCII))
        assertEquals("data", String(bytes, 36, 4, Charsets.US_ASCII))
        assertEquals(50, bytes.size)
        assertEquals(1.toShort(), b.getShort(22))
        assertEquals(12_000, b.getInt(24))
        assertEquals(16.toShort(), b.getShort(34))
        assertEquals(6, b.getInt(40))
        assertEquals(16383.toShort(), b.getShort(46))
        assertEquals((-32767).toShort(), b.getShort(48))
    }

    @Test
    fun `the oldest files go first and no partial file stays`() {
        val s = store(maxFiles = 2)
        listOf("1.wav", "2.wav", "3.wav").forEach { assertNotNull(s.save(it, FloatArray(4))) }

        val left = File(tmp.root, "slots").list()!!.sorted()
        assertEquals(listOf("2.wav", "3.wav"), left)
        assertFalse(left.any { it.endsWith(".tmp") })
    }

    @Test
    fun `nothing is written when the phone is short of space`() {
        assertNull(store(minFree = Long.MAX_VALUE).save("a.wav", FloatArray(4)))
    }

    @Test
    fun `the name carries the slot and sorts by time`() {
        val slotMs = 1_794_139_785_000L // 2026-11-08 ... UTC
        val name = SlotAudioStore.fileName(slotMs, "40m", "VE3XAA/QRP", "not_heard", 26)
        assertEquals(true, name.endsWith("-40m-VE3XAA_QRP-not_heard-h26.wav"))
        assertEquals(true, SlotAudioStore.fileName(slotMs, null, "K1ABC", "to_us", 3).contains("-unknown-K1ABC-"))
        assertEquals(
            true,
            SlotAudioStore.fileName(slotMs, "40m", "K1ABC", "to_us", 3) <
                SlotAudioStore.fileName(slotMs + 15_000, "20m", "AA1A", "to_us", 3)
        )
    }
}
