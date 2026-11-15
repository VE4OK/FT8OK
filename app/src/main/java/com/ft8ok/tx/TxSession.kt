package com.ft8ok.tx

import android.media.AudioManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ft8ok.audio.UsbAudioDevices
import com.ft8ok.audio.UsbAudioOut
import com.ft8ok.dsp.Ft8Decoder
import com.ft8ok.dsp.Ft8Modulator
import com.ft8ok.radio.Civ
import com.ft8ok.radio.Ptt
import java.util.concurrent.Executors

/**
 * Transmitting, as far as v1 goes: a self-test that needs no radio, and a
 * hold-to-transmit test that does.
 *
 * **Nothing here transmits on its own.** There is no automatic path: the operator
 * holds a button, and letting go stops it. That is rule 3's "the setup TX test is
 * hold-to-transmit only (cannot latch)" taken literally — there is no state in this
 * class that can keep the radio keyed after the finger lifts, and three independent
 * things stop it: the button release, the watchdog in [Ptt], and the end of the
 * generated audio.
 *
 * The automatic TX path — answering a CQ on a slot boundary — is not wired up. It
 * needs `TxScheduler` driving it and an armed state the operator sets deliberately,
 * and it is not something to switch on in the same session the transmitter is
 * keyed for the first time.
 */
class TxSession(
    private val audioManager: AudioManager,
    private val onMain: (() -> Unit) -> Unit,
    /** False refuses every keying ([com.ft8ok.Flavor.TRANSMIT_BUILT]). */
    private val transmitBuilt: Boolean = true,
    /** The app's mode: the waveform's shape and the self test's slot. */
    private val mode: com.ft8ok.model.Mode = com.ft8ok.model.Mode.FT8
) {

    private val shape = Ft8Modulator.shapeFor(mode.name)

    /** The PTT watchdog: one slot of the mode ([WATCHDOG_MS] for FT8; 7.5 s for FT4). */
    private val watchdogMs: Long = mode.slotMs

    /** Reads one raw meter value. Implemented by the rig; injected so this stays testable. */
    fun interface MeterSource {
        fun readMeter(sub: Int): Int?
    }

    /** Live meter readings during a transmission, for the display. */
    data class Meters(
        val watts: Double? = null,
        val swr: Double? = null,
        val alcPercent: Double? = null
    )

    /** Updated while transmitting; null when not. */
    var live by mutableStateOf<Meters?>(null)
        private set

    /** What the last self-test or transmission did. Shown verbatim. */
    var report by mutableStateOf<String?>(null)
        private set

    var transmitting by mutableStateOf(false)
        private set

    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ft8ok-tx").apply { isDaemon = true }
    }

    @Volatile
    private var held = false

    /**
     * Encode a message, synthesise it, and decode it back — **with no radio and no
     * transmission**.
     *
     * This is what validates the modulator. The waveform tests on the JVM check
     * tone placement and shaping, but only a round trip proves the encoder, the
     * synthesis and the decoder agree about what an FT8 signal is. It needs the
     * native decoder, which is why it runs here rather than in a unit test.
     */
    fun runSelfTest(message: String, baseHz: Double = 1500.0) {
        worker.execute {
            val result = buildString {
                appendLine("self-test: \"$message\" at ${baseHz.toInt()} Hz")

                val tones = Ft8Decoder.encode(message)
                if (tones == null) {
                    appendLine("ENCODE FAILED - the protocol cannot carry this message")
                    return@buildString
                }
                appendLine("encoded to ${tones.size} tones")

                val audio = Ft8Modulator.synthesise(
                    tones = tones,
                    baseHz = baseHz,
                    sampleRate = Ft8Decoder.REQUIRED_SAMPLE_RATE,
                    shape = shape
                )
                appendLine("synthesised %.2f s".format(audio.size.toDouble() / Ft8Decoder.REQUIRED_SAMPLE_RATE))

                // Decoded at SEVERAL known offsets. One offset tells us the message
                // survives; several tell us whether the timing is right, which is
                // what the transmission depends on. A constant difference between
                // what we padded and what comes back is a systematic delay in our
                // own synthesis, and it would put every transmission late.
                var allMatched = true
                // Pads inside the decoder's start search for the mode: FT4's covers only
                // about -0.5..+1.5 s.
                val pads = if (mode.slotMs < 10_000) listOf(0.3, 0.6, 1.2) else listOf(0.5, 1.0, 2.0)
                for (padSeconds in pads) {
                    val slot = FloatArray((Ft8Decoder.REQUIRED_SAMPLE_RATE * mode.slotMs / 1000).toInt())
                    val offset = (Ft8Decoder.REQUIRED_SAMPLE_RATE * padSeconds).toInt()
                    audio.copyInto(slot, offset, 0, minOf(audio.size, slot.size - offset))

                    val decodes = Ft8Decoder.decode(slot)
                    val mine = decodes.firstOrNull { it.message.trim() == message.trim() }
                    if (mine == null) {
                        appendLine("  pad %.2f s: NO DECODE".format(padSeconds))
                        allMatched = false
                    } else {
                        appendLine(
                            "  pad %.2f s -> DT %+.2f (%+.2f) %4d Hz".format(
                                padSeconds, mine.dtSeconds, mine.dtSeconds - padSeconds, mine.frequencyHz
                            )
                        )
                    }
                }
                appendLine(if (allMatched) "ROUND TRIP OK" else "ROUND TRIP MISMATCH")
            }
            onMain { report = result }
        }
    }

    /**
     * Why the radio must not be keyed now, or null (USB-D on a frequency the operator
     * can pick). Checked before every transmission, the
     * hold-to-TX test included, as the last line behind the arm check.
     */
    var guard: () -> String? = { null }

    /**
     * Key the radio and transmit [message] for as long as the button is held.
     *
     * @param ptt the link's PTT, or null when there is no CAT link — in which case
     *   nothing happens, because without CAT we cannot key and must not try.
     */
    // No permission annotation: this PLAYS audio, and AudioTrack needs none. An
    // earlier version copied RECORD_AUDIO across from the capture path, which lint
    // rightly rejected - the only thing it would have achieved is a check that
    // always passes and a reader believing transmission needs the microphone.
    fun startHoldTransmit(
        ptt: Ptt?,
        message: String,
        baseHz: Double,
        amplitude: Double,
        /**
         * Polled between audio chunks; transmission stops the moment it is false.
         *
         * The hold button passes "the finger is still down"; automatic TX passes
         * "the slot has not reached its hard stop". Having ONE transmission path
         * with a different stop condition, rather than two paths, means the ordering
         * that matters - audio silenced before unkeying, PTT refreshed only while
         * still wanted - cannot drift between them.
         */
        continueWhile: () -> Boolean = { held },
        /** Reads the radio's meters while transmitting. Null when there is no CAT link. */
        meters: MeterSource? = null,
        /** SWR at which to abort, from the station profile. */
        swrAbort: Double = com.ft8ok.model.StationProfile.DEFAULT_SWR_ABORT,
        /**
         * Called on the main thread when the transmission is over, with true if the
         * radio was keyed and it ended without a fault (no SWR abort, no error).
         * Stopping because [continueWhile] went false counts as ending normally: the
         * hard stop truncating a late start is designed behaviour, and the caller
         * knows whether it was a Halt. Called with false, too, when nothing could be
         * started - a caller waiting on it must never wait forever.
         */
        onFinished: ((transmitted: Boolean) -> Unit)? = null,
        /**
         * When the signal should leave the phone, on [clockMs]'s timebase, or null
         * to start as soon as possible (the hold-to-transmit test). The audio is
         * preceded by just enough silence to land on it, allowing for the output
         * delay last measured ([outputLatencyMs]). A start already too late for it
         * goes out as soon as it can, cut short at the front only past
         * [maxLateShiftMs].
         */
        startAtMs: Long? = null,
        /**
         * How far behind [startAtMs] the whole message may still go out; later than
         * that its start is left out ([com.ft8ok.model.Mode.maxLateShiftMs]). Null
         * never leaves anything out (the hold-to-transmit test).
         */
        maxLateShiftMs: Long? = null,
        /** The clock [startAtMs] is on: the band-corrected time. */
        clockMs: () -> Long = System::currentTimeMillis,
        /** Send [message] as free text, forced (operator-typed text). */
        freeText: Boolean = false,
        /**
         * The Settings hold-to-TX test, keyed only while a finger is on the button.
         * The one keying allowed while [transmitBuilt] is false: FT4OK's dummy-load
         * test comes before its automatic TX is switched on.
         */
        holdTest: Boolean = false
    ) {
        val refuse = { onMain { onFinished?.invoke(false) } }
        if (!transmitBuilt && !holdTest) {
            // FT4OK before its automatic TX is switched on. Checked
            // first, for every keying but the hold-to-TX test.
            report = "Automatic transmit is not switched on for this mode yet - hold-to-TX test only."
            return refuse()
        }
        if (transmitting) return refuse()
        if (ptt == null) {
            report = "No CAT link, so no way to key the radio. Connect it first."
            return refuse()
        }
        guard()?.let {
            report = it
            return refuse()
        }

        val device = UsbAudioDevices.findOutput(audioManager)
        if (device == null) {
            report = "No USB audio output. Is the radio attached?"
            return refuse()
        }

        val tones = Ft8Decoder.encode(message, freeText)
        if (tones == null) {
            report = "Cannot encode \"$message\" - not transmitting."
            return refuse()
        }

        held = true
        transmitting = true
        worker.execute {
            // Audio feeding a transmitter must not be starved by UI work. An early test build's
            // working tone output ran at MAX_PRIORITY for the same reason.
            Thread.currentThread().priority = Thread.MAX_PRIORITY
            val lines = StringBuilder("hold transmit: \"$message\" at ${baseHz.toInt()} Hz\n")
            // A stream already running on silence, if one was warmed for this slot;
            // otherwise open one now and pay its start-up delay inside the slot.
            val warmed = takeWarm()
            val out = warmed?.out ?: UsbAudioOut(device, audioManager) { line -> lines.appendLine(line) }
            if (warmed != null) {
                lines.append(warmed.sink)
                warmed.sink = lines
                lines.appendLine("warm: stream running %d ms before this transmission".format((System.nanoTime() - warmed.startedNanos) / 1_000_000))
            }
            var completed = false
            try {
                val failure = if (warmed != null) null else out.open()
                if (failure != null) {
                    lines.appendLine("audio: $failure")
                    return@execute
                }

                // Synthesised at FULL SCALE. The level is applied once, by
                // UsbAudioOut, which is also where the hard ceiling lives. Passing
                // the amplitude here as well scaled it twice - 0.28 x 0.28 = 0.078,
                // which is -22 dBFS where -11 was intended, and with media volume at
                // 5/15 on top produced no measurable RF at all.
                val audio = Ft8Modulator.synthesise(
                    tones, baseHz, UsbAudioOut.SAMPLE_RATE, shape
                )
                lines.appendLine("%d Hz, routed to %s".format(UsbAudioOut.SAMPLE_RATE, if (out.routedToUsb) "USB" else "NOT USB"))

                // UsbAudioOut has pinned the media volume to maximum by now; report
                // what it ended up as, since it is a gain stage in series with the
                // drive.
                val volume = audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
                val maxVolume = audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
                lines.appendLine(
                    "drive %.3f (%.1f dBFS), media volume $volume/$maxVolume".format(
                        amplitude, 20 * kotlin.math.log10(amplitude)
                    ) + if (volume == 0) "  *** MUTED ***" else if (volume < maxVolume) "  *** media volume is not at maximum, and it scales the drive ***" else ""
                )

                // Key AFTER the audio is ready and the output is open. Keying first
                // would put a carrier on the air while we did arithmetic.
                val keyFailure = ptt.key(watchdogMs)
                if (keyFailure != null) {
                    lines.appendLine("PTT: $keyFailure")
                    return@execute
                }
                lines.appendLine("keyed")

                // Poll the meters while transmitting. This is the only way the app
                // can see its own transmission: three silent failures in a row all
                // looked like successes from the inside because nothing here could
                // tell RF from no RF.
                // The operator's limit, converted once into the raw units the radio
                // reports, so the comparison is exact at the calibration points.
                val effectiveAbort = swrAbort.coerceIn(
                    com.ft8ok.model.StationProfile.MIN_SWR_ABORT,
                    com.ft8ok.model.StationProfile.MAX_SWR_ABORT
                )
                val swrAbortRaw = Civ.rawFromSwr(effectiveAbort)
                lines.appendLine("SWR abort at %.1f (raw $swrAbortRaw)".format(effectiveAbort))

                // Silence until the signal is due. Keyed with no audio, an SSB
                // transmitter puts out nothing. How much silence is decided while it
                // plays, from the track's own timestamps (StartPlanner): sizing it up
                // front from the last transmission's delay put the start ±300 ms off
                // when a fresh track took a different time to start.
                val startAtNanos = startAtMs?.let { System.nanoTime() + (it - clockMs()) * 1_000_000 }
                val writeStartNanos = System.nanoTime()

                var lastPoll = 0L
                var sawPower = 0
                var abort = false
                val written = out.play(
                    audio,
                    amplitude,
                    startAtNanos = startAtNanos,
                    assumedLatencyNanos = outputLatencyMs * 1_000_000,
                    maxPadFrames = MAX_PAD_MS * UsbAudioOut.SAMPLE_RATE / 1000,
                    maxLateShiftFrames = maxLateShiftMs?.let { it * UsbAudioOut.SAMPLE_RATE / 1000 }
                ) {
                    // Four ways to stop now: the finger, the audio running out, the
                    // watchdog, and SWR. Refreshing only while still held means
                    // letting go stops it even if this loop somehow continued.
                    val keepGoing = continueWhile()
                    if (keepGoing) ptt.refresh()

                    val now = System.currentTimeMillis()
                    if (meters != null && now - lastPoll >= METER_POLL_MS) {
                        lastPoll = now
                        val swrRaw = meters.readMeter(Civ.SUB_METER_SWR)
                        val powerRaw = meters.readMeter(Civ.SUB_METER_RFPOWER)
                        val alcRaw = meters.readMeter(Civ.SUB_METER_ALC)
                        if (powerRaw != null && powerRaw > sawPower) sawPower = powerRaw
                        onMain {
                            live = Meters(
                                watts = powerRaw?.let { Civ.wattsFromPowerRaw(it) },
                                swr = swrRaw?.let { Civ.swrFromRaw(it) },
                                alcPercent = alcRaw?.let { Civ.alcFractionFromRaw(it) * 100 }
                            )
                        }
                        // SWR is a safety abort, not a warning. Above 2.0 the
                        // transmission stops immediately.
                        if (swrRaw != null && swrRaw > swrAbortRaw) {
                            lines.appendLine(
                                "*** SWR ABORT: %.1f exceeds the %.1f limit (raw $swrRaw) - stopping ***".format(
                                    Civ.swrFromRaw(swrRaw), effectiveAbort
                                )
                            )
                            abort = true
                        }
                    }
                    keepGoing && !abort
                }
                completed = !abort
                // Let the end of the message play out of the buffer, fade and all,
                // before unkeying - or, if it was stopped early, fade it rather than
                // cutting a full-level tone.
                // A late start's skipped samples were never meant to be written.
                if (written - out.lastPadFrames >= audio.size - out.lastSkippedFrames && !abort) {
                    out.drain(out.totalFramesWritten.toInt(), outputLatencyMs + DRAIN_MARGIN_MS) { continueWhile() }
                } else {
                    out.fadeOut()
                }
                reportTiming(lines, out, writeStartNanos, warm = warmed != null, startAtMs, clockMs)
                if (sawPower > 0) {
                    lines.appendLine(
                        "RF CONFIRMED: peak %.1f W (raw %d)".format(Civ.wattsFromPowerRaw(sawPower), sawPower)
                    )
                } else if (meters != null) {
                    lines.appendLine("*** NO RF: the radio reported no power while keyed ***")
                }
                lines.appendLine(
                    "wrote %d samples (%.2f s), peak %d/32767 (%.1f dBFS)".format(
                        written, written.toDouble() / UsbAudioOut.SAMPLE_RATE,
                        out.lastPeak,
                        if (out.lastPeak > 0) 20 * kotlin.math.log10(out.lastPeak / 32767.0) else -99.0
                    )
                )
                // Only worth saying when nothing could check. With meters attached
                // the line above is a measurement, not a hope.
                if (meters == null) {
                    lines.appendLine(
                        "(no CAT meters: a full count means the samples were ACCEPTED, " +
                            "not that RF came out - check the radio)"
                    )
                }
            } catch (t: Throwable) {
                lines.appendLine("failed: ${t.javaClass.simpleName} ${t.message}")
            } finally {
                // Order matters: stop the audio, then unkey. Unkeying first would
                // leave audio going into a receiving radio, which is harmless, but
                // the reverse - audio still playing into a keyed transmitter after
                // we think we have stopped - is not.
                try { out.silence() } catch (_: Throwable) { }
                try { ptt.release() } catch (_: Throwable) { }
                try { out.close() } catch (_: Throwable) { }
                lines.appendLine("unkeyed")
                onMain {
                    transmitting = false
                    live = null
                    report = lines.toString()
                    onFinished?.invoke(completed)
                }
            }
        }
    }

    /**
     * The phone's output delay, ms: from writing the first sample to it leaving the
     * phone. Measured on every transmission that reports a timestamp; 196 ms (the
     * minimum-buffer figure measured) until then. It does not include the
     * radio's own delay from USB audio to RF, which is unmeasured.
     */
    @Volatile
    var outputLatencyMs: Long = DEFAULT_OUTPUT_LATENCY_MS
        private set

    /** Say when the signal actually left the phone, and learn the output delay. */
    private fun reportTiming(
        lines: StringBuilder,
        out: UsbAudioOut,
        writeStartNanos: Long,
        warm: Boolean,
        startAtMs: Long?,
        clockMs: () -> Long
    ) {
        val first = out.firstFrameNanos
        if (first == null) {
            lines.appendLine("timing: the audio device gave no timestamp; assumed output delay ${outputLatencyMs} ms")
            return
        }
        val padMs = out.lastPadFrames * 1000L / UsbAudioOut.SAMPLE_RATE
        // When the signal's first frame played: frame 0's time plus its index.
        val signalNanos = first + out.signalStartFrame * 1_000_000_000L / UsbAudioOut.SAMPLE_RATE
        val signalLeftMs = clockMs() - (System.nanoTime() - signalNanos) / 1_000_000
        val skippedMs = out.lastSkippedFrames * 1000L / UsbAudioOut.SAMPLE_RATE
        // Skipped: what went out began [skippedMs] into the message, so receivers
        // place the message's start that much earlier than the signal left.
        val vsTarget = startAtMs?.let {
            "%+d ms against the target".format(signalLeftMs - it) +
                if (skippedMs > 0) ", late start: first $skippedMs ms left out, so others see it %+d ms".format(signalLeftMs - skippedMs - it) else ""
        } ?: "no target"
        val delay = if (warm) {
            // Not a start-up delay: the stream was already running. Not learned from.
            "warm"
        } else {
            val measured = (first - writeStartNanos) / 1_000_000
            if (measured in 1..MAX_PLAUSIBLE_LATENCY_MS) outputLatencyMs = measured
            "cold, output delay $measured ms"
        }
        lines.appendLine("timing: $delay, silence $padMs ms, signal left the phone $vsTarget")
        // Silence already in the track when the signal was handed over, against the
        // time left to the target: queued beyond the target cannot be taken back.
        out.queuedAtStart?.let { (frames, leadNanos) ->
            lines.appendLine(
                "queued: %d ms of silence already in the track, target %d ms away".format(
                    frames * 1000L / UsbAudioOut.SAMPLE_RATE, leadNanos / 1_000_000
                )
            )
        }
    }

    /** A stream opened ahead of a transmission and kept running on silence. */
    private class Warm(val out: UsbAudioOut, val startedNanos: Long) {
        /** Where the stream's own log lines go: the warm-up, then the transmission that takes it. */
        @Volatile var sink = StringBuilder()
        @Volatile var claimed = false
        var expired = false
        var failed = false
        var thread: Thread? = null
    }

    private val warmLock = Any()
    private var warm: Warm? = null

    /**
     * Open the USB output now and keep it running on silence, so the next
     * transmission's start is planned against a stream that is already playing.
     *
     * A fresh track took 99 to 430 ms to start on air, different every
     * time, and the half second between the slot boundary and the signal could not
     * absorb the slow ones. The radio is NOT keyed by this: silence into an unkeyed
     * radio goes nowhere. Media volume is pinned from now, as for a transmission.
     * Expires by itself after [WARM_MAX_MS] if no transmission takes it.
     */
    fun prewarm() {
        if (transmitting) return
        val device = UsbAudioDevices.findOutput(audioManager) ?: return
        val w: Warm
        synchronized(warmLock) {
            if (warm != null) return
            lateinit var created: Warm
            created = Warm(UsbAudioOut(device, audioManager) { line -> created.sink.appendLine(line) }, System.nanoTime())
            w = created
            warm = w
        }
        w.thread = Thread {
            Thread.currentThread().priority = Thread.MAX_PRIORITY
            val failure = w.out.open()
            if (failure != null) {
                w.sink.appendLine("warm: $failure")
                w.failed = true
            } else {
                val deadline = w.startedNanos + WARM_MAX_MS * 1_000_000
                w.out.feedSilence { !w.claimed && System.nanoTime() < deadline }
            }
            synchronized(warmLock) {
                if (!w.claimed) {
                    w.expired = true
                    if (warm === w) warm = null
                }
            }
            if (w.expired) {
                try { w.out.close() } catch (_: Throwable) { }
            }
        }.apply { name = "ft8ok-audio-warm"; start() }
    }

    /** Take the warm stream for a transmission, or null if there is none to take. */
    private fun takeWarm(): Warm? {
        val w = synchronized(warmLock) {
            val w = warm ?: return null
            if (w.expired) return null
            w.claimed = true
            warm = null
            w
        }
        // The feeder stops within one 10 ms write; it must, before we write too.
        w.thread?.join(500)
        if (w.failed || w.thread?.isAlive == true) {
            try { w.out.close() } catch (_: Throwable) { }
            return null
        }
        return w
    }

    /** Close a warm stream nobody has taken: leaving the screen, or Halt. */
    fun cancelWarm() {
        val w = synchronized(warmLock) {
            val w = warm ?: return
            w.claimed = true
            warm = null
            w
        }
        Thread {
            w.thread?.join(500)
            try { w.out.close() } catch (_: Throwable) { }
        }.start()
    }

    /** Let go. Stops the audio and unkeys. */
    fun stopHoldTransmit() {
        held = false
    }

    fun shutdown() {
        held = false
        cancelWarm()
        worker.shutdownNow()
    }

    companion object {
        /**
         * Watchdog for a held transmission.
         *
         * Longer than one FT8 message so a full test transmission is not cut off,
         * and far shorter than anything that could be called leaving the radio
         * keyed. [com.ft8ok.radio.CivPtt] refuses anything above one slot anyway.
         * FT8's value; the session uses one slot of its own mode.
         */
        const val WATCHDOG_MS = 15_000L

        /**
         * Default drive level: −11 dBFS, the measured linear limit on this radio.
         * Output is set with the radio's RF POWER control, never by
         * driving the modulator harder — past this it compresses, and compression is
         * splatter rather than power.
         */
        const val DEFAULT_AMPLITUDE = 0.28

        /**
         * How often to read the meters while transmitting.
         *
         * Three reads per poll on a half-duplex bus that is also carrying our audio
         * timing, so not too often - but often enough that an SWR fault stops the
         * transmission in a fraction of a second rather than at the end of it.
         */
        const val METER_POLL_MS = 400L

        /** See [outputLatencyMs]. */
        const val DEFAULT_OUTPUT_LATENCY_MS = 196L

        /** A measured delay beyond this is a bad timestamp, not a delay. */
        const val MAX_PLAUSIBLE_LATENCY_MS = 1_000L

        /** How much longer than the measured output delay to wait for the buffer to play out. */
        const val DRAIN_MARGIN_MS = 300L

        /** Never hold more silence than this before a signal. */
        const val MAX_PAD_MS = 1_500L

        /** A warm stream nobody has taken closes itself after this. */
        // Covers the 1 s lead plus the wait at the boundary for the last slot's
        // decodes (Mode.decodeWaitMs), with room to spare.
        const val WARM_MAX_MS = 5_000L
    }
}
