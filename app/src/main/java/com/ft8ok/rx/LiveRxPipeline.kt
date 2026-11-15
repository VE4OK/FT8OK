package com.ft8ok.rx

import com.ft8ok.dsp.Decode
import com.ft8ok.dsp.DeepDecode
import com.ft8ok.dsp.Occupancy
import com.ft8ok.model.Mode
import com.ft8ok.model.SlotParity
import com.ft8ok.time.BandConsensus
import com.ft8ok.time.GridSearch
import com.ft8ok.time.TimeEngine
import java.util.concurrent.Executor

/**
 * The receive loop: slot boundaries in, decodes and an occupancy map out.
 *
 * This is the wiring the architecture's data-flow diagram describes — audio is
 * sliced by the slot grid, decoded in passes, and the results fan out to the clock,
 * the call list and the occupancy map. It owns no audio and no UI; it is handed a
 * way to slice and a way to decode, which is what makes the whole loop testable on
 * the JVM with neither a phone nor a radio.
 *
 * Deliberately free of Android types for that reason.
 */
class LiveRxPipeline(
    private val mode: Mode,
    private val timeEngine: TimeEngine,
    /**
     * Cut audio out of the capture buffer.
     *
     * **The epoch passed here is host time, not corrected time** — see [onAudio].
     */
    private val slice: (startEpochMs: Long, durationMs: Long) -> FloatArray?,
    private val decode: (FloatArray) -> List<Decode>,
    /**
     * After the final pass, look again with what it found taken out,
     * for at most the milliseconds given. Null: no second look, and the final pass
     * closes the slot.
     */
    private val decodeDeeper: ((FloatArray, Long) -> DeepDecode)? = null,
    /**
     * Analyse a pass's audio: a signal report for each message in `measure` (the
     * ones this pass found first), and, given `occupancyOf` (the final pass, with
     * every decode of the slot), the occupancy map.
     *
     * One call rather than two because both need the same power spectrum, and an
     * FFT per pass is worth not doing twice. Every pass rather than the final one
     * only, so a row's report shows with the row: it used to
     * follow about 3 s later.
     */
    private val analyseSlot: (samples: FloatArray, measure: List<Decode>, occupancyOf: List<Decode>?) -> SlotAnalysis,
    /**
     * Reports for messages a second look found, on the audio of that look, against the
     * slot's own noise floor: reports stay comparable with the slot's other rows, and
     * only the neighbours' power is gone. Null: measured on the untouched audio.
     */
    private val measureOn: ((samples: FloatArray, measure: List<Decode>, noisePerHz: Double?) -> Map<String, Double>)? = null,
    private val onUpdate: (SlotUpdate) -> Unit,
    /**
     * Once a slot is closed and its update has gone out, on the decode thread: for work
     * nothing waits for, with the slot's audio.
     */
    private val onSlotClosed: ((FloatArray, SlotUpdate) -> Unit)? = null,
    /**
     * After a second look that ran, before the slot closes, on the decode thread: test
     * the partner's possible replies against what the second look left, given the slot's audio, start, parity and every decode so far. What it
     * returns joins the closing update like any other decode, so the feed and the QSO
     * engine get it in time. Keep it short: TX is waiting. Not called when the second
     * look did not run, since what it left would then be another slot's.
     */
    private val listen: ((FloatArray, Long, SlotParity, List<Decode>) -> List<Decode>)? = null,
    /** Decoding runs here, never on the capture thread: a dropped chunk is a lost slot. */
    private val decodeOn: Executor = Executor { it.run() },
    private val log: (String) -> Unit = {},
    private val nowMs: () -> Long = System::currentTimeMillis,
    /**
     * May a grid search run now? False during a contact, where a jump in the clock
     * would move the partner's slots, and while the audio holds our own transmission:
     * it has come back as a decode before, and we must not lock to us.
     */
    private val mayGridSearch: () -> Boolean = { false },
    private val onGridSearch: (GridSearchReport) -> Unit = {}
) {

    /**
     * One grid search, run because the clock has no consensus yet.
     *
     * @param lock what the band says so far, or null when nothing has been heard.
     * @param appliedMs the correction this search made, or null if it changed nothing.
     */
    data class GridSearchReport(
        val lock: GridSearch.Lock?,
        val appliedMs: Long?,
        val windows: Int,
        val millis: Long
    )

    /**
     * What one finished slot's spectrum tells us.
     *
     * @param snrByMessage signal report per decoded message, keyed the same way
     *   decodes are deduped. **This is the number that gets sent on the air** and
     *   lands in the other operator's log, so it is kept separate from
     *   [Decode.score], which is a sync-correlation score and not an SNR at all.
     *   Absent for a message whose SNR could not be estimated — null means "we do
     *   not know", never "0 dB".
     * @param noisePerHz the slot's noise floor, for [measureOn].
     */
    data class SlotAnalysis(
        val occupancy: Occupancy.Map?,
        val snrByMessage: Map<String, Double> = emptyMap(),
        val noisePerHz: Double? = null
    )

    /** One decode pass's worth of news about a slot. */
    data class SlotUpdate(
        /** Slot boundary in **corrected** time — the one to show and to log. */
        val slotStartMs: Long,
        val parity: SlotParity,
        /** Which pass of [Mode.decodePassesMs] produced this, from 0. */
        val pass: Int,
        /** True for the slot's last pass: nothing more will be heard from this slot. */
        val isFinal: Boolean,
        /** Messages not seen in an earlier pass of this slot. The call list appends these. */
        val newDecodes: List<Decode>,
        /** Everything decoded in this slot so far. */
        val allDecodes: List<Decode>,
        /** Only computed on the final pass, when the whole slot is available. */
        val occupancy: Occupancy.Map?,
        /**
         * Signal report per message of the slot so far: each measured once, in the
         * pass that first decoded it, and never revised by a later pass. The final pass's map therefore covers the whole slot.
         */
        val snrByMessage: Map<String, Double> = emptyMap(),
        /** Any sample at full scale. Clipped audio decodes worse while looking healthy. */
        val clipped: Boolean,
        val decodeMillis: Long,
        /** Spectrum and reports for this pass, after the decode; the cost of 4zi. */
        val analyseMillis: Long = 0,
        val audioMs: Int,
        /** What this slot did to the clock. Non-null on the final pass only. */
        val clock: TimeEngine.Outcome? = null,
        /** The second look, on the update that closes a slot which had one. */
        val deeper: Deeper? = null
    )

    /**
     * What taking the decoded signals out of the audio achieved.
     *
     * @param found messages new to this slot.
     * @param millis how long it took.
     * @param cutShort true when time ran out before it had finished.
     */
    data class Deeper(val removed: Int, val found: Int, val millis: Long, val cutShort: Boolean)

    private val lock = Any()
    private val gridSearch = GridSearch(mode)
    private var currentSlotMs: Long? = null
    private var passesRun = 0

    /**
     * What one slot's passes have found so far. Handed to each pass when it is
     * scheduled, so a final pass that finishes after the boundary (it can: +15.1 s
     * was measured) still works on its own slot and not on the next
     * slot's fresh state. Guarded by [lock].
     */
    private class SlotState {
        val seen = LinkedHashSet<String>()
        val decoded = mutableListOf<Decode>()
        val snrs = HashMap<String, Double>()
    }

    private var slot = SlotState()

    /**
     * Called whenever new audio has arrived — about ten times a second.
     *
     * **The two timebases must not be mixed, and this is the only place they meet.**
     * The slot grid lives in corrected time, because that is true time and what the
     * rest of the band is using. The capture buffer is anchored in host time, because
     * that is what the audio timestamps are in. So the boundary is computed in
     * corrected time and converted back before it is used to index audio.
     *
     * Getting this wrong is quietly disastrous and was got wrong once already:
     * the boundary was computed in corrected time and used directly to
     * slice, so a -1660 ms correction changed only *when* the decode fired, never
     * *where* the slice began. DT never moved, the log reported the correction as
     * applied, and the obvious reading was "band sync does not work" — when the
     * estimate had been right all along.
     */
    fun onAudio() {
        timeEngine.tick()

        val correctedNow = timeEngine.correctedNowMs()
        val slotMs = mode.slotStartMs(correctedNow)
        val positionMs = mode.positionInSlotMs(correctedNow)

        val work = synchronized(lock) {
            if (currentSlotMs != slotMs) {
                currentSlotMs = slotMs
                passesRun = 0
                slot = SlotState()
            }

            val passes = mode.decodePassesMs
            if (passesRun >= passes.size || positionMs < passes[passesRun]) return
            val pass = passesRun
            passesRun++
            Triple(pass, pass == passes.size - 1, slot)
        }

        val (pass, isFinal, state) = work
        // Host time, not corrected time. The conversion is the whole point.
        val sliceStartHostMs = slotMs - timeEngine.offsetMs
        decodeOn.execute { runPass(slotMs, sliceStartHostMs, pass, isFinal, state) }
    }

    private fun runPass(slotStartMs: Long, sliceStartHostMs: Long, pass: Int, isFinal: Boolean, state: SlotState) {
        val audio = slice(sliceStartHostMs, mode.slotMs)
        if (audio == null || audio.isEmpty()) {
            // Normal at the very start of a session, before the ring has filled.
            log("slot ${slotStartMs / 1000 % 60}s pass $pass: no audio buffered yet")
            return
        }

        val started = nowMs()
        val found = try {
            decode(audio)
        } catch (t: Throwable) {
            log("decode failed: ${t.javaClass.simpleName} ${t.message}")
            emptyList()
        }
        val decodeMillis = nowMs() - started

        // The final pass reports what the untouched audio gave before anything else is
        // tried, so a second look can only add to it and never delays a row.
        val deeper = decodeDeeper.takeIf { isFinal }
        var closing = publish(slotStartMs, audio, pass, isFinal && deeper == null, found, decodeMillis, null, state)

        if (deeper != null) {
            val deepStarted = nowMs()
            val budgetMs = slotStartMs + mode.deepDecodeEndMs - timeEngine.correctedNowMs()
            var ran = false
            val deep = if (budgetMs <= 0) {
                DeepDecode(cutShort = true)
            } else {
                try {
                    deeper(audio, budgetMs).also { ran = true }
                } catch (t: Throwable) {
                    log("second look failed: ${t.javaClass.simpleName} ${t.message}")
                    DeepDecode(cutShort = true)
                }
            }
            val listed = if (ran) listenFor(audio, slotStartMs, deep, state) else emptyList()
            closing = publish(slotStartMs, audio, pass + 1, true, deep.decodes + listed, nowMs() - deepStarted, withListed(deep, listed), state)
        }

        if (isFinal) {
            try {
                onSlotClosed?.invoke(audio, closing)
            } catch (t: Throwable) {
                log("after the slot: ${t.javaClass.simpleName} ${t.message}")
            }
        }
        if (isFinal && timeEngine.needsGridSearch && mayGridSearch()) searchForGrid()
    }

    /** What [listen] found in this slot; nothing if it fails. */
    private fun listenFor(audio: FloatArray, slotStartMs: Long, deep: DeepDecode, state: SlotState): List<Decode> {
        val hook = listen ?: return emptyList()
        val soFar = synchronized(lock) { state.decoded.toList() } + deep.decodes
        return try {
            hook(audio, slotStartMs, mode.parityOf(slotStartMs), soFar).map { it.copy(fromReplyList = true) }
        } catch (t: Throwable) {
            log("reply list failed: ${t.javaClass.simpleName} ${t.message}")
            emptyList()
        }
    }

    /**
     * [deep] with [listed] added to its last look, so their reports are measured on the
     * audio with the slot's other signals taken out, as for a station a look found.
     */
    private fun withListed(deep: DeepDecode, listed: List<Decode>): DeepDecode {
        if (listed.isEmpty()) return deep
        val last = deep.looks.lastOrNull() ?: return deep.copy(decodes = deep.decodes + listed)
        return deep.copy(
            decodes = deep.decodes + listed,
            looks = deep.looks.dropLast(1) + DeepDecode.Look(last.audio, last.decodes + listed)
        )
    }

    /** Add [found] to the slot and tell [onUpdate]; [isFinal] closes the slot. */
    private fun publish(
        slotStartMs: Long,
        audio: FloatArray,
        pass: Int,
        isFinal: Boolean,
        found: List<Decode>,
        decodeMillis: Long,
        deep: DeepDecode?,
        state: SlotState
    ): SlotUpdate {
        val fresh = synchronized(lock) {
            // A later pass re-decodes the same audio and finds the same messages
            // again. The call list must see each message once, in the pass that
            // first resolved it (append-only within a cycle).
            val new = found.filter { state.seen.add(dedupKey(it)) }
            state.decoded.addAll(new)
            new
        }
        val all = synchronized(lock) { state.decoded.toList() }

        // Reports for this pass's new messages before the update goes out, so each
        // row arrives with its report. A pass with nothing new and no map to make
        // skips the FFT.
        val analyseStarted = nowMs()
        val looks = if (measureOn != null) deep?.looks.orEmpty() else emptyList()
        val inLooks = looks.flatMapTo(HashSet()) { it.decodes }
        val measure = fresh.filterNot { it in inLooks }
        val analysis = if (isFinal || fresh.isNotEmpty()) {
            try {
                analyseSlot(audio, measure, if (isFinal) all else null)
            } catch (t: Throwable) {
                log("spectrum failed: ${t.javaClass.simpleName} ${t.message}")
                SlotAnalysis(null)
            }
        } else {
            SlotAnalysis(null)
        }
        val cleanedReports = HashMap<String, Double>()
        for (look in looks) {
            val mine = look.decodes.filter { it in fresh }
            if (mine.isEmpty()) continue
            try {
                cleanedReports += measureOn!!(look.audio, mine, analysis.noisePerHz)
            } catch (t: Throwable) {
                log("spectrum failed: ${t.javaClass.simpleName} ${t.message}")
            }
        }
        val analyseMillis = nowMs() - analyseStarted
        val snrsSoFar = synchronized(lock) {
            // A message is measured once, in its first pass: a later pass found it
            // again but is not in `fresh`, so it cannot overwrite the report.
            for ((key, snr) in analysis.snrByMessage + cleanedReports) state.snrs.putIfAbsent(key, snr)
            // A reply the list found is usually below what the spectrum can measure. It
            // still needs a report: the QSO engine skips a message without one.
            for (d in fresh) if (d.fromReplyList) state.snrs.putIfAbsent(dedupKey(d), LISTED_REPORT_DB)
            HashMap(state.snrs)
        }

        // Only the final pass talks to the clock, and only with the complete set:
        // an early pass sees the strong signals, which are not a random sample of
        // the band and would bias the consensus toward whoever is loudest.
        val clock = if (isFinal) {
            timeEngine.observe(
                // A listed reply's DT is where the partner was before, not a measurement.
                all.filterNot { it.fromReplyList }.map { BandConsensus.DtSample(it.dtSeconds, confidenceOf(it)) }
            )
        } else {
            null
        }

        val update = SlotUpdate(
            slotStartMs = slotStartMs,
            parity = mode.parityOf(slotStartMs),
            pass = pass,
            isFinal = isFinal,
            newDecodes = fresh,
            allDecodes = all,
            occupancy = analysis.occupancy,
            snrByMessage = snrsSoFar,
            clipped = isClipped(audio),
            decodeMillis = decodeMillis,
            analyseMillis = analyseMillis,
            audioMs = (audio.size * 1000L / SAMPLE_RATE).toInt(),
            clock = clock,
            deeper = deep?.let { Deeper(it.removed, fresh.count { d -> !d.fromReplyList }, decodeMillis, it.cutShort) }
        )
        onUpdate(update)
        return update
    }

    /**
     * Decode the newest audio at offsets the slot grid does not know about, and let
     * what is heard set the grid. After the final pass, so it costs
     * the slot nothing, and only until the clock has a consensus: a slot with enough
     * stations has just given it one, and this never runs on a healthy band.
     *
     * Nothing here reaches the feed. Once the grid is right the normal passes hear
     * these same stations.
     */
    private fun searchForGrid() {
        val started = nowMs()
        var windows = 0
        for (k in 0 until mode.gridSearchWindows) {
            val startHostMs = started - mode.slotMs - k * mode.gridSearchStepMs
            val audio = slice(startHostMs, mode.slotMs) ?: continue
            if (audio.isEmpty()) continue
            windows++
            val found = try {
                decode(audio)
            } catch (t: Throwable) {
                log("grid search decode failed: ${t.javaClass.simpleName} ${t.message}")
                emptyList()
            }
            for (d in found) {
                gridSearch.add(d.message, d.frequencyHz, timeEngine.gridHostMs(startHostMs, d.dtSeconds))
            }
        }
        val heard = gridSearch.lock(started)
        val applied = heard?.let { timeEngine.acceptGrid(it.gridHostMs, it.stations) }
        onGridSearch(GridSearchReport(heard, applied, windows, nowMs() - started))
    }

    /** Forget the current slot. Call when capture stops, so a restart starts clean. */
    fun reset() {
        gridSearch.reset()
        synchronized(lock) {
            currentSlotMs = null
            passesRun = 0
            slot = SlotState()
        }
    }

    companion object {
        /** What the decoder and the capture ring both work in. */
        const val SAMPLE_RATE = 12_000

        /**
         * How much the same message may move in frequency between passes and still
         * be the same message. Comfortably inside FT8's 50 Hz signal width, so two
         * genuinely different stations are never merged.
         */
        const val DEDUP_TOLERANCE_HZ = 10

        /**
         * The report for a reply the list found when the audio left after the second
         * look shows no measurable signal: the bottom of the FT8 scale, where such a
         * reply is. A chosen default.
         */
        const val LISTED_REPORT_DB = -24.0

        /** Also the key of [SlotUpdate.snrByMessage], so the two cannot drift apart. */
        fun dedupKey(d: Decode): String =
            "${d.message}@${d.frequencyHz / DEDUP_TOLERANCE_HZ}"

        /**
         * How much to trust one measurement relative to the others in its slot.
         *
         * The decoder's sync-correlation score, and nothing more is claimed for it:
         * **it is not an SNR** and must never be sent as a signal
         * report. As a within-slot ranking of how firmly each message resolved, it
         * is exactly the right input to a weighted median.
         */
        private fun confidenceOf(d: Decode): Double = maxOf(1.0, d.score.toDouble())

        private fun isClipped(samples: FloatArray): Boolean {
            for (s in samples) if (s >= 0.999f || s <= -0.999f) return true
            return false
        }
    }
}
