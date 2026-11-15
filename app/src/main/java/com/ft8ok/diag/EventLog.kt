package com.ft8ok.diag

import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors

/**
 * A plain text file of what happened, in the app's private storage, kept across
 * sessions so a glitch can be read afterwards over adb:
 *
 *     adb shell run-as com.ft8ok cat files/events.log
 *
 * Not logcat alone: on the test phone logcat's buffer holds about a minute, because
 * the system floods it, and on air the lines that would have explained a
 * receive stall had scrolled away before anyone looked. No permission is needed for
 * the app's own files (rule 1), and nothing here leaves the phone.
 *
 * Writes happen on one background thread, so a caller on the audio or main thread
 * never waits on storage. Bounded: past [maxBytes] the file is moved to
 * `events.log.1` (replacing the previous one) and a new one started.
 */
class EventLog(
    private val file: File,
    private val maxBytes: Long = MAX_BYTES,
    /** False for a log too busy for logcat: `decodes.log` writes a line per decode. */
    private val toLogcat: Boolean = true
) {

    private val writer = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ft8ok-events").apply { isDaemon = true }
    }

    private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    /**
     * Settings › Diagnostics › Detailed logs. While false, [detail] writes nothing:
     * the per-slot, timing and frequency-move lines are for troubleshooting, not for
     * every phone.
     */
    @Volatile var detailed: Boolean = false

    /** Record [line] only while [detailed] is on. */
    fun detail(line: String) {
        if (detailed) add(line)
    }

    /** Record [line], stamped with the phone's own UTC time. */
    fun add(line: String) {
        val now = System.currentTimeMillis()
        if (toLogcat) Log.i(TAG, line)
        writer.execute {
            try {
                if (file.length() > maxBytes) {
                    val old = File(file.path + ".1")
                    old.delete()
                    file.renameTo(old)
                }
                file.appendText("${stamp.format(Date(now))} $line\n")
            } catch (_: Throwable) {
                // A log that cannot be written must never take the radio down with it.
            }
        }
    }

    companion object {
        const val TAG = "FT8OK"
        const val MAX_BYTES = 1_000_000L
    }
}
