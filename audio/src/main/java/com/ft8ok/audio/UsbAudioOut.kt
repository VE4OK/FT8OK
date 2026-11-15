package com.ft8ok.audio

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack

/**
 * Sends generated audio to the radio's modulator over the one USB cable.
 *
 * **Only generated FT8 audio may ever reach the radio**. This
 * class plays exactly what it is handed and nothing else: no tones of its own, no
 * sounds, no fallback beep when something goes wrong.
 *
 * **Amplitude is capped here as well as by the caller.** The measured linear limit
 * on this radio is −11 dBFS and it saturates by −6; past that,
 * more drive produces distortion rather than output, and distortion is splatter on
 * other people's frequencies. [MAX_AMPLITUDE] is an independent ceiling so a bug
 * upstream cannot turn into an over-driven transmitter.
 *
 * **It does not key the radio.** PTT belongs to the `radio` module, and sequencing
 * the two belongs above both. A class that could both key the transmitter and
 * generate audio would be the obvious place for a mistake to become a transmission.
 */
class UsbAudioOut(
    private val device: AudioDeviceInfo,
    /**
     * Needed because **Android media volume is a floating gain stage between our
     * samples and the radio**. It is pinned
     * to maximum while transmitting and restored afterwards. Without this the app
     * transmitted correct audio at -11 dBFS through a volume of 5/15 and produced
     * no measurable RF at all, while every number it could see looked right.
     */
    private val audioManager: AudioManager,
    private val log: (String) -> Unit = {}
) {

    /** The operator's media volume, restored when we are done with it. */
    private var volumeToRestore: Int? = null

    private var track: AudioTrack? = null

    @Volatile
    private var playing = false

    /** True while samples are being written to the device. */
    val isPlaying: Boolean get() = playing

    /** Peak sample magnitude of the last playback, 0..32767. 0 means we sent silence. */
    var lastPeak = 0
        private set

    /**
     * When the first sample of the last [play] left the phone, on the
     * `System.nanoTime` timebase, from `AudioTrack.getTimestamp`. Null when the
     * device never reported a timestamp. **Assumes** the timestamp's frame count
     * starts at our first written sample, i.e. that silence played while the track
     * was starved before it is not counted - true of the documented behaviour, not
     * yet checked on this phone against an outside measurement.
     */
    var firstFrameNanos: Long? = null
        private set

    /** Frames of silence the last [play] wrote before the signal. */
    var lastPadFrames: Int = 0
        private set

    /** Every frame written to this track since it opened, warm-up silence included. */
    @Volatile
    var totalFramesWritten: Long = 0
        private set

    /** The track frame at which the last [play]'s signal began. */
    var signalStartFrame: Long = 0
        private set

    /**
     * How many of the signal's first samples the last [play] left out because it
     * was starting late ([StartPlanner.lateSkipFrames]). The signal that went out
     * began this far into the message.
     */
    var lastSkippedFrames: Int = 0
        private set

    /**
     * When the last [play] began with a target: frames already queued on the track
     * ([StartPlanner.queuedFrames]) and nanoseconds left until the target. Null
     * without a target, or before the track has reported a timestamp.
     */
    var queuedAtStart: Pair<Long, Long>? = null
        private set

    /**
     * Keep the track running on silence until [keepGoing] turns false: a warm-up
     * before a transmission, so the start-up delay of a fresh track (99 to 430 ms
     * measured, and different every time) is over before the signal's start is
     * planned. Silence only - nothing else may reach the radio (rule 3).
     */
    fun feedSilence(keepGoing: () -> Boolean) {
        val t = track ?: return
        val zeros = ShortArray(PAD_CHUNK_FRAMES)
        val stamp = android.media.AudioTimestamp()
        while (keepGoing()) {
            val n = t.write(zeros, 0, zeros.size)
            if (n < 0) {
                log("audio out: warm-up write returned $n")
                return
            }
            totalFramesWritten += n
            if (firstFrameNanos == null && t.getTimestamp(stamp) && stamp.framePosition > 0) {
                firstFrameNanos = stamp.nanoTime - stamp.framePosition * 1_000_000_000L / SAMPLE_RATE
            }
        }
    }

    /** True when the stream was actually routed to USB, checked after opening. */
    var routedToUsb = false
        private set

    /**
     * Open the output. Returns null on success, or a reason the operator can act on.
     */
    fun open(): String? {
        if (track != null) return null

        val minBuffer = AudioTrack.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) return "getMinBufferSize returned $minBuffer"

        val t = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                // The MINIMUM buffer, not a multiple of it. On the playback path
                // buffer size is latency almost 1:1 — measured, x4 cost
                // 452 ms against 196 ms for x1, with no underruns either way. The
                // capture path wants a generous buffer; this one does not, because
                // latency here delays the start of a transmission inside its slot.
                .setBufferSizeInBytes(minBuffer)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (t: Throwable) {
            return "AudioTrack: ${t.javaClass.simpleName} ${t.message}"
        }

        t.setPreferredDevice(device)
        t.play()

        val routed = t.routedDevice
        routedToUsb = routed != null && UsbAudioDevices.isUsb(routed.type)
        if (!routedToUsb) {
            t.stop()
            t.release()
            return "audio is not routed to USB (${UsbAudioDevices.typeName(routed?.type)}) - " +
                "it would come out of the phone's speaker, not the radio"
        }

        track = t
        pinVolume()
        return null
    }

    /**
     * Take the media volume to maximum for the duration, remembering what it was.
     *
     * A user-visible system setting, changed deliberately and restored in [close].
     * The alternative is a transmitter whose drive depends on where the operator
     * last left the volume keys, which is not a transmitter anyone can calibrate.
     */
    private fun pinVolume() {
        try {
            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            if (current != max) {
                volumeToRestore = current
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, max, 0)
                log("media volume pinned $current -> $max (will restore)")
            }
        } catch (t: Throwable) {
            // Some devices refuse under a volume policy. Say so rather than
            // transmitting at an unknown gain and calling the result a measurement.
            log("could not pin media volume (${t.message}) - drive is not deterministic")
        }
    }

    private fun restoreVolume() {
        val old = volumeToRestore ?: return
        volumeToRestore = null
        try {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, old, 0)
            log("media volume restored to $old")
        } catch (t: Throwable) {
            log("could not restore media volume: ${t.message}")
        }
    }

    /**
     * Play [samples] (mono, ±1.0) until they run out or [shouldContinue] returns
     * false.
     *
     * [shouldContinue] is polled between chunks and is how the hard stop and Halt
     * reach the audio: a transmission must be able to stop part-way, immediately,
     * without waiting for the message to finish.
     *
     * @return the number of samples actually written.
     */
    fun play(
        samples: FloatArray,
        amplitude: Double = MAX_AMPLITUDE,
        /**
         * When the first of [samples] should leave the phone (`System.nanoTime`
         * base), or null to play at once. Silence is written until then, measured
         * against the track's own timestamps as they arrive ([StartPlanner]).
         */
        startAtNanos: Long? = null,
        /** The output delay to assume until the track reports a timestamp. */
        assumedLatencyNanos: Long = 0,
        /** Never more silence than this, however far off the target is. */
        maxPadFrames: Long = Long.MAX_VALUE,
        /**
         * With [startAtNanos]: how far behind it the signal may still start whole.
         * Later than that, its first samples are left out so it sits this far behind
         * ([StartPlanner.lateSkipFrames]). Null never leaves anything out.
         */
        maxLateShiftFrames: Long? = null,
        shouldContinue: () -> Boolean = { true }
    ): Int {
        val t = track ?: return 0
        val level = amplitude.coerceIn(0.0, MAX_AMPLITUDE)

        playing = true
        firstFrameNanos = null
        lastPadFrames = 0
        lastSkippedFrames = 0
        queuedAtStart = null
        // Frames already queued on this track, by a warm-up (feedSilence), before us.
        val before = totalFramesWritten
        val stamp = android.media.AudioTimestamp()
        if (startAtNanos != null && t.getTimestamp(stamp) && stamp.framePosition > 0) {
            val now = System.nanoTime()
            queuedAtStart = StartPlanner.queuedFrames(before, stamp.framePosition, stamp.nanoTime, now, SAMPLE_RATE) to
                (startAtNanos - now)
        }
        var written = 0
        // What actually goes down the wire, so a silent transmission can be told
        // apart from a silent RADIO. Reported rather than assumed: the first two
        // failures both looked identical from in here.
        var peak = 0
        try {
            // Silence until the signal is due, a little at a time, re-planned after
            // every piece so a timestamp can correct the estimate as soon as it exists.
            if (startAtNanos != null) {
                val writeStartNanos = System.nanoTime()
                val zeros = ShortArray(PAD_CHUNK_FRAMES)
                while (shouldContinue()) {
                    val stamped = t.getTimestamp(stamp) && stamp.framePosition > 0
                    if (stamped && firstFrameNanos == null) {
                        firstFrameNanos = stamp.nanoTime - stamp.framePosition * 1_000_000_000L / SAMPLE_RATE
                    }
                    val left = minOf(
                        StartPlanner.silenceFramesLeft(
                            targetNanos = startAtNanos,
                            sampleRate = SAMPLE_RATE,
                            writtenFrames = before + written,
                            stampFrame = if (stamped) stamp.framePosition else null,
                            stampNanos = if (stamped) stamp.nanoTime else null,
                            writeStartNanos = writeStartNanos,
                            assumedLatencyNanos = assumedLatencyNanos
                        ),
                        maxPadFrames - written
                    )
                    if (left <= 0) break
                    val n = t.write(zeros, 0, minOf(left, PAD_CHUNK_FRAMES.toLong()).toInt())
                    if (n < 0) {
                        log("audio out: write returned $n")
                        return written
                    }
                    written += n
                }
                // Starting late: past the allowed shift, leave out the start so the
                // rest stays where receivers look for it.
                if (maxLateShiftFrames != null) {
                    var stamped = false
                    fun skip(): Long {
                        stamped = t.getTimestamp(stamp) && stamp.framePosition > 0
                        return StartPlanner.lateSkipFrames(
                            targetNanos = startAtNanos,
                            sampleRate = SAMPLE_RATE,
                            writtenFrames = before + written,
                            stampFrame = if (stamped) stamp.framePosition else null,
                            stampNanos = if (stamped) stamp.nanoTime else null,
                            writeStartNanos = writeStartNanos,
                            assumedLatencyNanos = assumedLatencyNanos,
                            maxShiftFrames = maxLateShiftFrames
                        )
                    }
                    var frames = skip()
                    // A cold stream may not yet have said when its frames play, and
                    // the estimate then rests on the last delay measured - 95 ms one
                    // start, 418 the next, which put a late start 324 ms past its
                    // cap (VE3XAC). Already cutting, wait for the first
                    // timestamp: the wait comes out of a start being dropped anyway,
                    // and the cut lands exactly.
                    var waited = 0
                    while (frames > 0 && !stamped && waited < STAMP_WAIT_FRAMES && shouldContinue()) {
                        val n = t.write(zeros, 0, PAD_CHUNK_FRAMES)
                        if (n < 0) {
                            log("audio out: write returned $n")
                            return written
                        }
                        written += n
                        waited += n
                        frames = skip()
                    }
                    lastSkippedFrames = frames.coerceAtMost(samples.size.toLong()).toInt()
                }
                lastPadFrames = written
            }
            signalStartFrame = before + written

            val chunk = ShortArray(CHUNK_SAMPLES)
            val skipped = lastSkippedFrames
            var index = skipped
            while (index < samples.size && shouldContinue()) {
                val count = minOf(CHUNK_SAMPLES, samples.size - index)
                for (i in 0 until count) {
                    // Cut in mid-message: fade in as the message's own start does,
                    // or the jump to full level is a click on the air.
                    val into = index + i - skipped
                    val envelope = if (skipped > 0 && into < SKIP_RAMP_FRAMES) {
                        (1.0 - kotlin.math.cos(Math.PI * into / SKIP_RAMP_FRAMES)) / 2.0
                    } else 1.0
                    val value = samples[index + i] * level * envelope
                    val sample = (value.coerceIn(-1.0, 1.0) * Short.MAX_VALUE).toInt()
                    if (kotlin.math.abs(sample) > peak) peak = kotlin.math.abs(sample)
                    chunk[i] = sample.toShort()
                }
                val n = t.write(chunk, 0, count)
                if (n < 0) {
                    log("audio out: write returned $n")
                    break
                }
                written += n
                index += count
                if (firstFrameNanos == null && t.getTimestamp(stamp) && stamp.framePosition > 0) {
                    firstFrameNanos = stamp.nanoTime - stamp.framePosition * 1_000_000_000L / SAMPLE_RATE
                }
            }
        } finally {
            playing = false
            lastPeak = peak
            totalFramesWritten = before + written
        }
        return written
    }

    /**
     * Wait until the [writtenFrames] handed to [play] have actually been played.
     *
     * [play] returns when the last sample is *written*, with the output buffer
     * (about 0.2 s) still to play. Silencing then threw that away: every
     * transmission was cut at full tone a symbol or so early, and its fade-out
     * never reached the radio. Gives up after [timeoutMs], or fades out if [shouldContinue] goes false.
     */
    fun drain(writtenFrames: Int, timeoutMs: Long, shouldContinue: () -> Boolean = { true }) {
        val t = track ?: return
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            // The head position is an unsigned 32-bit count; a transmission is far
            // too short to wrap it.
            if ((t.playbackHeadPosition.toLong() and 0xFFFF_FFFFL) >= writtenFrames) return
            if (!shouldContinue()) {
                fadeOut()
                return
            }
            Thread.sleep(5)
        }
    }

    /**
     * Ramp the level down over [FADE_MS] before stopping, so a transmission stopped
     * early (Halt, the hard stop, an SWR abort) ends without a click - a step from
     * full tone to nothing spreads energy either side of our signal.
     */
    fun fadeOut() {
        val t = track ?: return
        try {
            for (step in FADE_STEPS - 1 downTo 0) {
                t.setVolume(step.toFloat() / FADE_STEPS)
                Thread.sleep(FADE_MS / FADE_STEPS)
            }
        } catch (_: Throwable) {
            // Silencing follows regardless.
        }
    }

    /**
     * Stop immediately, discarding anything still buffered.
     *
     * `pause` then `flush` rather than `stop`, because `stop` lets the buffered
     * audio drain — which is the opposite of what Halt means.
     */
    fun silence() {
        val t = track ?: return
        try {
            t.pause()
            t.flush()
        } catch (e: Throwable) {
            log("audio out: could not silence: ${e.message}")
        }
    }

    /** Close the output and release the device. */
    fun close() {
        silence()
        restoreVolume()
        try { track?.stop() } catch (_: Throwable) { }
        try { track?.release() } catch (_: Throwable) { }
        track = null
        playing = false
    }

    companion object {
        /** How long [fadeOut] takes, in [FADE_STEPS] steps: the modulator's own ramp is 20 ms. */
        const val FADE_MS = 20L
        const val FADE_STEPS = 5

        /**
         * **48 kHz, which is what the radio's codec actually accepts on OUTPUT.**
         *
         * Not the decoder's 12 kHz. This was 12 000 and the radio keyed but produced
         * no power: AudioTrack accepted every sample and reported them written, so
         * nothing looked wrong from in here, but the codec does not support that rate
         * on the output path. The hardware tests had already measured it: 48 kHz is
         * the only rate good in both directions - and the modulator takes its rate as a
         * parameter precisely so the two paths need not share one.
         *
         * A write() that returns the full count means the samples reached a buffer,
         * never that anything was radiated.
         */
        const val SAMPLE_RATE = 48_000

        /**
         * Hard ceiling on what may reach the modulator, independent of the caller.
         *
         * −6 dBFS. The radio saturates here and is linear only to about −11,
         * so this is not the operating level — it is the point past
         * which a bug upstream would be producing splatter rather than power.
         */
        const val MAX_AMPLITUDE = 0.5

        /** Small enough that [shouldContinue] is checked often during a transmission. */
        private const val CHUNK_SAMPLES = 4800   // 100 ms at 48 kHz

        /** Silence goes out in 10 ms pieces, so the start is re-planned that often. */
        private const val PAD_CHUNK_FRAMES = 480

        /**
         * A signal cut in mid-message fades in over this many frames (10 ms), so it
         * starts without a click. Not tied to any mode's symbol length.
         */
        private const val SKIP_RAMP_FRAMES = 480

        /**
         * How long a late start that is being cut waits for the track's first
         * timestamp (600 ms). The slowest cold start measured was 418 ms.
         */
        private const val STAMP_WAIT_FRAMES = 28_800
    }
}
