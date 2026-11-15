package com.ft8ok.rx

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Keeps the audio of the slots the reply list tested, so a `LIST` line
 * can be checked later against the sound itself: `decodes.log` holds only the verdict.
 *
 * Each file is exactly what the decoder and the list were given, from the slot start, as
 * 16-bit mono WAV. The app's private storage, so no permission; read it over adb. The
 * oldest files go first, and nothing is written when the phone is short of space.
 */
class SlotAudioStore(
    private val dir: File,
    private val maxFiles: Int = MAX_FILES,
    private val minFreeBytes: Long = MIN_FREE_BYTES,
    private val sampleRate: Int = LiveRxPipeline.SAMPLE_RATE
) {
    /** Save [samples] (nominally +-1.0) as [name]; null when there was no room to. */
    fun save(name: String, samples: FloatArray): File? {
        if (!dir.isDirectory && !dir.mkdirs()) return null
        if (dir.usableSpace < minFreeBytes) return null
        val target = File(dir, name)
        // Renamed once whole, so a pull over adb never gets half a file.
        val partial = File(dir, "$name.tmp")
        partial.writeBytes(wav(samples))
        if (!partial.renameTo(target)) {
            partial.delete()
            return null
        }
        prune()
        return target
    }

    private fun prune() {
        val saved = dir.listFiles { f -> f.name.endsWith(".wav") }?.sortedBy { it.name } ?: return
        saved.take((saved.size - maxFiles).coerceAtLeast(0)).forEach { it.delete() }
    }

    private fun wav(samples: FloatArray): ByteArray {
        val dataBytes = samples.size * 2
        val out = ByteBuffer.allocate(HEADER_BYTES + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        out.put("RIFF".toByteArray(Charsets.US_ASCII)).putInt(36 + dataBytes)
        out.put("WAVEfmt ".toByteArray(Charsets.US_ASCII)).putInt(16)
        out.putShort(1).putShort(1) // PCM, mono
        out.putInt(sampleRate).putInt(sampleRate * 2)
        out.putShort(2).putShort(16) // block align, bits per sample
        out.put("data".toByteArray(Charsets.US_ASCII)).putInt(dataBytes)
        for (s in samples) out.putShort((s.coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort())
        return out.array()
    }

    companion object {
        /** About 180 MB at 360 kB a slot; the log gained about 100 tests in a day. */
        const val MAX_FILES = 500
        const val MIN_FREE_BYTES = 500_000_000L
        private const val HEADER_BYTES = 44

        private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.US).withZone(ZoneOffset.UTC)

        /** Sorts by time. Names the slot, so a file can be matched to its `LIST` line. */
        fun fileName(slotMs: Long, band: String?, partner: String, heard: String, hardErrors: Int): String {
            val call = partner.replace(Regex("[^A-Za-z0-9]"), "_")
            return "${STAMP.format(Instant.ofEpochMilli(slotMs))}-${band ?: "unknown"}-$call-$heard-h$hardErrors.wav"
        }
    }
}
