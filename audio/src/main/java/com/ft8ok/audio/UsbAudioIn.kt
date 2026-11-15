package com.ft8ok.audio

import android.Manifest
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaRecorder
import androidx.annotation.RequiresPermission
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Continuous capture from the radio's USB codec into a [SampleRing].
 *
 * Proven in the first hardware test build and lifted here with its hard-won details intact:
 * the UNPROCESSED source, the generous input buffer, the routing check, and the
 * timestamp anchoring. Everything that decides *what to do* with the audio lives
 * above this class; its entire job is "correctly timed samples, continuously".
 *
 * Capture must stop the moment the app leaves the foreground (ADR 0003).
 * That is the caller's call to make — there is no path in here that keeps recording
 * on its own, and [stop] releases the device outright.
 */
class UsbAudioIn(
    private val device: AudioDeviceInfo,
    /** Decimated samples land here, at [TARGET_RATE]. */
    val ring: SampleRing,
    /** Peak and RMS of each raw chunk, before decimation. About 10 per second. */
    private val onLevel: (peakDbfs: Double, rmsDbfs: Double) -> Unit = { _, _ -> },
    /** Called on the capture thread after each chunk is in the ring. Keep it quick. */
    private val onChunk: () -> Unit = {},
    private val log: (String) -> Unit = {}
) {

    @Volatile
    private var running = false
    private var thread: Thread? = null

    val isRunning: Boolean get() = running

    /**
     * Open the device and start capturing.
     *
     * @return null on success, or a reason it could not start. The string is for the
     *   operator to read, so it says what is wrong rather than naming an exception.
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start(): String? {
        if (running) return "already running"

        val minBuffer = AudioRecord.getMinBufferSize(
            SOURCE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) return "getMinBufferSize returned $minBuffer"

        val recorder = try {
            AudioRecord.Builder()
                // UNPROCESSED: AGC, noise suppression or echo cancellation would
                // mangle FT8 tones. This is not a voice signal.
                .setAudioSource(MediaRecorder.AudioSource.UNPROCESSED)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SOURCE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build()
                )
                // Generous on capture: a large input buffer buys tolerance to
                // scheduling hiccups and costs no accuracy, because the timeline is
                // anchored by timestamp rather than by when we happen to read. At
                // least a second: four times the minimum was about 0.16 s, and a
                // longer stall than that loses audio (see TimelineGuard).
                .setBufferSizeInBytes(maxOf(minBuffer * 4, SOURCE_RATE * 2 * MIN_BUFFER_SECONDS))
                .build()
        } catch (t: Throwable) {
            return "AudioRecord: ${t.javaClass.simpleName} ${t.message}"
        }

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            return "AudioRecord did not initialise"
        }

        // Explicit routing, per ARCHITECTURE: never the default system route.
        // setPreferredDevice (API 23), not setCommunicationDevice (API 31).
        recorder.setPreferredDevice(device)
        recorder.startRecording()

        val routed = recorder.routedDevice
        if (routed == null || !UsbAudioDevices.isUsb(routed.type)) {
            recorder.stop()
            recorder.release()
            return "audio is not routed to USB (${UsbAudioDevices.typeName(routed?.type)}) - " +
                "we would be recording the phone's own microphone, not the radio"
        }

        ring.reset()
        running = true
        thread = Thread { captureLoop(recorder) }.apply {
            name = "ft8ok-audio-in"
            // Dropped audio is lost decodes, and a lost slot never comes back.
            priority = Thread.MAX_PRIORITY
            start()
        }
        return null
    }

    /** Stop capturing and release the device. Safe to call when not running. */
    fun stop() {
        running = false
        try { thread?.join(STOP_TIMEOUT_MS) } catch (_: Throwable) { }
        thread = null
    }

    private fun captureLoop(recorder: AudioRecord) {
        val chunkFrames = SOURCE_RATE / CHUNKS_PER_SECOND
        val raw = ShortArray(chunkFrames)
        val decimated = FloatArray(chunkFrames / DECIM + 1)
        val timestamp = AudioTimestamp()
        var framesRead = 0L
        val guard = TimelineGuard()
        val chunkMs = 1000.0 / CHUNKS_PER_SECOND

        try {
            while (running) {
                val readStart = System.nanoTime()
                val n = recorder.read(raw, 0, raw.size)
                val readMs = (System.nanoTime() - readStart) / 1_000_000.0
                if (n <= 0) {
                    log("audio in: read returned $n")
                    continue
                }

                if (!ring.isAnchored) anchorTimeline(recorder, timestamp, framesRead)

                var peak = 0
                var sumSquares = 0.0
                for (i in 0 until n) {
                    val s = raw[i].toInt()
                    if (abs(s) > peak) peak = abs(s)
                    sumSquares += s.toDouble() * s.toDouble()
                }
                onLevel(dbfs(peak.toDouble()), dbfs(sqrt(sumSquares / n)))

                // Decimate 48 kHz to 12 kHz. A boxcar average is adequate because
                // the rig's own SSB filter has already limited the content to about
                // 3 kHz, comfortably under what this rate can represent.
                var out = 0
                var i = 0
                while (i + DECIM <= n) {
                    var sum = 0f
                    for (k in 0 until DECIM) sum += raw[i + k].toFloat()
                    decimated[out++] = (sum / DECIM) / SHORT_FULL_SCALE
                    i += DECIM
                }
                ring.append(decimated, out)
                framesRead += n

                if (ring.isAnchored) {
                    val slip = guard.onRead(
                        monoMs = System.nanoTime() / 1_000_000.0,
                        heldMs = ring.written * 1000.0 / ring.sampleRate,
                        waited = readMs > chunkMs / 2
                    )
                    if (slip != 0.0) {
                        ring.shift(slip)
                        // The read's own duration says which kind of gap it was: one
                        // read blocked for the whole gap means Android delivered no
                        // audio at all (the ~10 s dropouts); short
                        // reads mean the time was lost elsewhere.
                        log("audio in: timeline slipped %+.0f ms (audio %s) - corrected; this read took %.0f ms".format(
                            slip, if (slip > 0) "lost" else "gained", readMs
                        ))
                    }
                }

                if (ring.isAnchored) onChunk()
            }
        } catch (t: Throwable) {
            log("audio in failed: ${t.javaClass.simpleName} ${t.message}")
        } finally {
            try { recorder.stop() } catch (_: Throwable) { }
            try { recorder.release() } catch (_: Throwable) { }
            running = false
        }
    }

    /**
     * Establish when sample zero happened, once.
     *
     * `getTimestamp` reports a frame position paired with a monotonic nanotime;
     * reading the wall clock immediately afterwards converts that pair into epoch
     * time, and the start of the stream follows from the frame position. This is
     * what makes DT a measurement of clocks rather than of our own buffering.
     */
    private fun anchorTimeline(recorder: AudioRecord, timestamp: AudioTimestamp, framesRead: Long) {
        if (recorder.getTimestamp(timestamp, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS) {
            val nowNano = System.nanoTime()
            val nowEpoch = System.currentTimeMillis()
            val epochOfTimestampedFrame = nowEpoch - (nowNano - timestamp.nanoTime) / 1_000_000.0
            // The ring holds decimated samples, so the source frame position has to
            // be converted into the ring's own index space.
            ring.anchor(epochOfTimestampedFrame, timestamp.framePosition / DECIM)
            log("audio in: timeline anchored from the audio timestamp")
        } else if (framesRead > SOURCE_RATE) {
            // Fall back rather than never starting - but say so, because this
            // silently degrades DT and therefore the clock.
            ring.anchor(System.currentTimeMillis() - framesRead * 1000.0 / SOURCE_RATE, 0)
            log("audio in: getTimestamp unavailable - timeline estimated, DT less accurate")
        }
    }

    companion object {
        /** What we ask the codec for. The IC-7300 advertises 48 kHz. */
        const val SOURCE_RATE = 48_000

        /** What the decoder requires, and therefore what the ring holds. */
        const val TARGET_RATE = 12_000

        const val DECIM = SOURCE_RATE / TARGET_RATE

        /** 100 ms chunks: often enough to drive the pass schedule, cheap to ignore. */
        const val CHUNKS_PER_SECOND = 10

        /** The capture buffer holds at least this much audio. */
        private const val MIN_BUFFER_SECONDS = 1

        private const val STOP_TIMEOUT_MS = 1_500L

        private const val SHORT_FULL_SCALE = 32768f

        private fun dbfs(amplitude: Double): Double =
            if (amplitude <= 0.0) Double.NEGATIVE_INFINITY
            else 20.0 * log10(amplitude / SHORT_FULL_SCALE)
    }
}

/** Finding the radio among the phone's audio devices. */
object UsbAudioDevices {

    /** The radio's codec, or null if nothing USB is attached. */
    fun findInput(am: AudioManager): AudioDeviceInfo? =
        am.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { isUsb(it.type) }

    /** The radio's modulator input. For later: nothing transmits yet. */
    fun findOutput(am: AudioManager): AudioDeviceInfo? =
        am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { isUsb(it.type) }

    fun isUsb(type: Int): Boolean =
        type == AudioDeviceInfo.TYPE_USB_DEVICE ||
            type == AudioDeviceInfo.TYPE_USB_HEADSET ||
            type == AudioDeviceInfo.TYPE_USB_ACCESSORY

    fun typeName(type: Int?): String = when (type) {
        null -> "nothing"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB device"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB headset"
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB accessory"
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "built-in microphone"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "built-in speaker"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired headset"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth SCO"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth A2DP"
        else -> "type $type"
    }
}
