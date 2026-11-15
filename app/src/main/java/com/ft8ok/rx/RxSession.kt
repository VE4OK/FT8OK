package com.ft8ok.rx

import android.Manifest
import android.media.AudioManager
import androidx.annotation.RequiresPermission
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ft8ok.audio.SampleRing
import com.ft8ok.audio.UsbAudioDevices
import com.ft8ok.audio.UsbAudioIn
import com.ft8ok.dsp.Decode
import com.ft8ok.dsp.Ft8Decoder
import com.ft8ok.dsp.Occupancy
import com.ft8ok.dsp.ReplyMatch
import com.ft8ok.dsp.SnrEstimator
import com.ft8ok.dsp.TxFrequency
import com.ft8ok.model.Mode
import com.ft8ok.model.SlotParity
import com.ft8ok.time.TimeEngine
import java.util.concurrent.Executors

/**
 * One receiving session: the radio's audio, the clock, the decoder and the strip,
 * wired together and exposed as screen state.
 *
 * The thin shell of "pure core, thin shell". It makes no decisions of its own — the
 * slot grid belongs to [Mode], the clock to [TimeEngine], the pass schedule and
 * dedup to [LiveRxPipeline], the frequency policy to [TxFrequency]. What lives here
 * is the Android lifetime of those objects and the conversion of their results into
 * something a composable can read.
 *
 * **Receiving stops the moment the app is no longer on screen** (ADR 0003).
 * [stop] releases the audio device outright; there is no background path.
 */
class RxSession(
    private val audioManager: AudioManager,
    /** Runs a block on the main thread. Screen state is only ever written there. */
    private val onMain: (() -> Unit) -> Unit,
    /** The mode this session receives. Public: the screen must not assume FT8 either. */
    val mode: Mode = Mode.FT8
) {

    /** What one parity's lane currently knows. */
    data class ParityReading(
        val map: Occupancy.Map?,
        /** Corrected-time epoch of the slot this came from. */
        val heardAtMs: Long
    )

    /** One line of the decode feed. */
    data class DecodeRow(
        val slotStartMs: Long,
        val parity: SlotParity,
        val decode: Decode,
        /** Our measured signal report, or null until the slot has been analysed. */
        val snrDb: Double? = null,
        /** One of OUR transmissions, not a decode: [decode] carries the message and TX audio frequency. */
        val tx: Boolean = false,
        /** The band it was heard (or sent) on, e.g. "20m"; null when the frequency was unknown. */
        val band: String? = null
    )

    var running by mutableStateOf(false)
        private set

    /**
     * Why receiving is not working, or null when all is well.
     *
     * A value rather than a sentence, because every user-visible string lives in
     * resources and this class cannot reach them. The screen
     * turns it into words.
     */
    sealed interface Problem {
        /** Nothing USB is attached, so there is no radio to listen to. */
        data object NoUsbAudio : Problem

        /** The audio layer refused to start, and said why in its own terms. */
        data class CannotStart(val detail: String) : Problem
    }

    var problem by mutableStateOf<Problem?>(null)
        private set

    var peakDbfs by mutableStateOf<Int?>(null)
        private set

    /** True when the last slot's audio hit full scale. Clipped audio decodes badly. */
    var clipped by mutableStateOf(false)
        private set

    var slotsHeard by mutableStateOf(0)
        private set

    var decodesHeard by mutableStateOf(0)
        private set

    var readings by mutableStateOf<Map<SlotParity, ParityReading>>(emptyMap())
        private set

    /** Oldest first, in slot order with our transmissions in place ([FeedOrder]). */
    var feed by mutableStateOf<List<DecodeRow>>(emptyList())
        private set

    /**
     * Auto's pick for each lane: EVEN and ODD are
     * different crowds, so each lane has its own. Null until that lane has been heard.
     */
    var autoPicks by mutableStateOf<Map<SlotParity, Int?>>(emptyMap())
        private set

    /** The frequency set by hand, for both lanes. Kept while in Auto, to fall back on. */
    var userHz by mutableStateOf<Int?>(null)
        private set

    var txMode by mutableStateOf(TxFrequency.Mode.FULL_AUTO)
        private set

    /**
     * Where we may transmit: the radio's SSB-D transmit passband once it has told us,
     * [Occupancy.TxBand.DEFAULT] until then. Volatile: the decode
     * thread reads it to cut each slot's gaps.
     */
    @Volatile
    var txBand: Occupancy.TxBand = defaultTxBand()
        private set

    /** [Occupancy.TxBand.DEFAULT] with this mode's signal width (FT4 83 Hz). */
    private fun defaultTxBand() = Occupancy.TxBand.DEFAULT.copy(signalWidthHz = signalWidthHz)

    private val signalWidthHz: Int get() = kotlin.math.round(mode.signalWidthHz).toInt()

    /**
     * The radio reported its SSB-D transmit passband ([hz] null: not known, keep the
     * default). A hand-set frequency outside the new band is brought inside it, and an
     * Auto pick outside it is dropped so the lane picks again: outside the passband the
     * radio keys with no power.
     */
    fun setRadioTxPassband(hz: IntRange?) {
        val band = hz?.let { Occupancy.TxBand(it.first, it.last, signalWidthHz) } ?: defaultTxBand()
        if (band == txBand) return
        txBand = band
        onLog?.invoke("tx band: ${band.lowHz}-${band.highHz} Hz" + if (hz == null) " (default, radio has not said)" else " (radio SSB-D TBW)")
        userHz?.let { set ->
            if (!band.fits(set)) {
                val clamped = set.coerceIn(band.searchLowHz, band.searchHighHz)
                userHz = clamped
                onLog?.invoke("tx freq: $set -> $clamped Hz, inside the radio's passband")
            }
        }
        autoPicks = autoPicks.mapValues { (_, pick) -> pick?.takeIf { band.fits(it) } }
        updateTxFrequency()
    }

    /** Where [parity]'s lane would transmit: the hand-set frequency, or Auto's pick for it. */
    fun pickFor(parity: SlotParity): Int? =
        if (txMode == TxFrequency.Mode.USER_SET) userHz else autoPicks[parity]

    /** Where we would transmit next: the pick for the lane we transmit in. */
    val txFrequencyHz: Int? get() = pickFor(txParity())

    /** Exposed for the status line; owns the correction the whole pipeline uses. */
    val timeEngine = TimeEngine(
        mode = mode,
        wallClockMs = System::currentTimeMillis,
        monotonicMs = { android.os.SystemClock.elapsedRealtime() },
        // The decoder reads DT about 0.15 s high on FT8, 0.045 s on FT4. Without this the clock drives the whole slot grid late by
        // that much, and every transmission with it.
        decoderDtBiasSec = Ft8Decoder.dtBiasSec(mode.name)
    )

    /** Redrawn once a second so ages and the slot countdown stay honest. */
    var clockTick by mutableStateOf(0L)
        private set

    private val ring = SampleRing(UsbAudioIn.TARGET_RATE, RING_SECONDS)
    private val decodePool = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ft8ok-decode").apply { isDaemon = true }
    }
    private var audioIn: UsbAudioIn? = null

    private val pipeline = LiveRxPipeline(
        mode = mode,
        timeEngine = timeEngine,
        slice = ring::sliceByEpoch,
        decode = { Ft8Decoder.decode(it) },
        // Both modes: subtraction rebuilds the decoder's own protocol.
        // The reply list that rides on it stays FT8 only (Ft8Decoder.bestReply).
        decodeDeeper = { samples, budgetMs -> Ft8Decoder.decodeDeeper(samples, budgetMs) },
        analyseSlot = { samples, decodes, occupancyOf ->
            val spectrum = Ft8Decoder.spectrum(samples)
            if (spectrum.isEmpty()) {
                LiveRxPipeline.SlotAnalysis(null)
            } else {
                val binHz = Ft8Decoder.binHz()
                val occupancy = occupancyOf?.let { all ->
                    Occupancy.analyse(
                        Occupancy.Snapshot(
                            binHz = binHz,
                            levelsDb = spectrum,
                            decodedHz = all.map { it.frequencyHz }
                        ),
                        txBand = txBand
                    )
                }
                // One noise estimate for the whole slot, shared by every signal in
                // it: the floor is a property of the slot, not of each decode, and
                // estimating it per signal would make reports disagree for no reason.
                val noise = SnrEstimator.noiseDensityPerHz(spectrum, binHz)
                LiveRxPipeline.SlotAnalysis(occupancy, reportsOf(samples, spectrum, decodes, noise), noise)
            }
        },
        measureOn = { samples, decodes, noise ->
            reportsOf(samples, Ft8Decoder.spectrum(samples), decodes, noise)
        },
        onUpdate = { update -> onMain { apply(update) } },
        onSlotClosed = { audio, update -> logReplies(audio, update) },
        listen = { audio, slotMs, parity, soFar -> listenForReply(audio, slotMs, parity, soFar) },
        decodeOn = decodePool,
        mayGridSearch = {
            // The search reaches back this far, so wait until our last signal has left it.
            val reach = mode.slotMs + (mode.gridSearchWindows - 1) * mode.gridSearchStepMs
            val ownTxEnd = lastOwnTxSlotMs?.let { it + mode.slotMs }
            !inContact() && (ownTxEnd == null || timeEngine.correctedNowMs() - ownTxEnd > reach)
        },
        onGridSearch = { report -> onMain { apply(report) } }
    )

    /** Signal reports for [decodes] from [spectrum], the spectrum of [samples], by dedup key. */
    private fun reportsOf(
        samples: FloatArray,
        spectrum: FloatArray,
        decodes: List<Decode>,
        noise: Double?
    ): Map<String, Double> {
        if (spectrum.isEmpty()) return emptyMap()
        val binHz = Ft8Decoder.binHz()
        val sliceSeconds = samples.size.toDouble() / Ft8Decoder.REQUIRED_SAMPLE_RATE
        val txSeconds = mode.txDurationMs / 1000.0
        return buildMap {
            for (d in decodes) {
                SnrEstimator.estimate(
                    spectrumDb = spectrum,
                    binHz = binHz,
                    signalStartHz = d.frequencyHz,
                    sliceSeconds = sliceSeconds,
                    // An early pass holds only part of the transmission.
                    signalSeconds = SnrEstimator.signalSecondsInSlice(
                        sliceSeconds, d.dtSeconds - Ft8Decoder.dtBiasSec(mode.name), txSeconds
                    ),
                    noiseDensityPerHz = noise,
                    signalWidthHz = mode.signalWidthHz
                )?.let { put(LiveRxPipeline.dedupKey(d), it) }
            }
        }
    }

    /** Decode thread only. */
    private val replyWatch = ReplyWatch(mode)

    /** The newest slot's test, from [listenForReply] to [logReplies]. Decode thread only. */
    private class ReplyTest(
        val slotMs: Long,
        val plan: ReplyWatch.Plan,
        val match: ReplyMatch,
        val accepted: Decode?,
        val millis: Long
    )

    private var replyTest: ReplyTest? = null

    /**
     * Test a slot against the partner's possible replies, just before it closes.
     * What [ReplyWatch.accept] lets through goes to the feed and
     * the QSO engine with the slot's other decodes; the log and the saved audio wait for
     * [logReplies], so they never hold up a reply. About 15 ms on the phone.
     */
    private fun listenForReply(audio: FloatArray, slotMs: Long, parity: SlotParity, soFar: List<Decode>): List<Decode> {
        replyTest = null
        replyWatch.observe(soFar, parity, slotMs)
        val plan = replyWatch.plan(replyTarget?.invoke(), soFar, parity, lastOwnTxSlotMs == slotMs) ?: return emptyList()
        val started = System.currentTimeMillis()
        val match = Ft8Decoder.bestReply(
            audio,
            // Heard, the signal was taken out of the cleaned audio, so look at the untouched.
            cleaned = plan.heard == ReplyWatch.Heard.NOT_HEARD,
            to = plan.myCall,
            decoy = plan.decoyCall,
            from = plan.partner,
            grid = plan.partnerGrid,
            frequencyHz = plan.frequencyHz,
            dtSeconds = plan.dtSeconds
        ) ?: return emptyList()
        val accepted = replyWatch.accept(plan, match, soFar)
        replyTest = ReplyTest(slotMs, plan, match, accepted, System.currentTimeMillis() - started)
        return listOfNotNull(accepted)
    }

    /** The test's `LIST` line, an events.log line if it counted, and the slot's audio. */
    private fun logReplies(audio: FloatArray, update: LiveRxPipeline.SlotUpdate) {
        val test = replyTest?.takeIf { it.slotMs == update.slotStartMs } ?: return
        replyTest = null
        onDecodeLog?.invoke(
            DecodeLog.reply(update.slotStartMs, update.parity, band, test.plan, test.match, test.millis, test.accepted != null)
        )
        test.accepted?.let {
            onDetailLog?.invoke(
                "reply list: heard \"%s\" (hard %d, decoy %d, %d ms)".format(
                    it.message, test.match.hardErrors, test.match.decoyHardErrors, test.millis
                )
            )
        }
        slotAudio?.save(
            SlotAudioStore.fileName(update.slotStartMs, band, test.plan.partner, test.plan.heard.name.lowercase(), test.match.hardErrors),
            audio
        )
    }

    /** The slot of our newest transmission. Volatile: the decode thread reads it. */
    @Volatile
    private var lastOwnTxSlotMs: Long? = null

    /** Searches in a row that heard nothing, so the clock is still the phone's own. */
    private var fruitlessSearches by mutableIntStateOf(0)

    /**
     * Audio for several slots and no station heard at any offset. The only way to tell
     * an empty band from a clock that is far out is to say both.
     */
    val noGridFound: Boolean
        get() = running && fruitlessSearches >= GRID_BANNER_SEARCHES

    private fun apply(report: LiveRxPipeline.GridSearchReport) {
        // A tentative lock is still a lock, even once its votes have aged out.
        val fruitless = report.lock == null && report.windows > 0 && timeEngine.sync == TimeEngine.Sync.NONE
        fruitlessSearches = if (fruitless) fruitlessSearches + 1 else 0
        report.appliedMs?.let { applied ->
            onLog?.invoke(
                "clock: grid search found %d station(s), %d transmission(s), %+d ms, %s (%d windows in %d ms)".format(
                    report.lock?.stations ?: 0, report.lock?.transmissions ?: 0, applied,
                    timeEngine.sync.name.lowercase(), report.windows, report.millis
                )
            )
        }
        if (fruitlessSearches == GRID_BANNER_SEARCHES) {
            onLog?.invoke("clock: grid search heard nothing in $GRID_BANNER_SEARCHES slots (${report.millis} ms each)")
        }
    }

    /**
     * The parity we would transmit in: the slot after this one.
     *
     * With no QSO in progress there is no partner dictating it, so the honest answer
     * is "whichever slot comes next" — and it alternates, exactly as our transmission
     * would. Once the QSO engine exists the partner decides it instead.
     */
    fun txParity(): SlotParity =
        committedTxParity() ?: TxFrequencyPolicy.txParityAt(mode, timeEngine.correctedNowMs())

    /**
     * The parity a contact or CQ run has fixed, or null when nothing has. Supplied
     * by the TX side so receive does not depend on it. Without it, during a contact
     * the frequency was judged against the parity we were NOT transmitting in every
     * other slot, and moved on that (events.log: 2125 -> 2348 Hz inside
     * one contact).
     */
    var committedTxParity: () -> SlotParity? = { null }

    /** True while a contact is in progress: the partner listens where we were, so we hold (ADR 0013). */
    var inContact: () -> Boolean = { false }

    /**
     * Slots we transmitted in. We were deaf in them, so their occupancy is not a
     * picture of the band and must not replace the last map heard in that parity;
     * storing it handed the policy a "fresh" map of nothing.
     */
    private val ownTxSlots = ArrayDeque<Long>()

    /** Start capturing. Caller must already hold RECORD_AUDIO. */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start(): Boolean {
        if (running) return true

        val device = UsbAudioDevices.findInput(audioManager)
        if (device == null) {
            problem = Problem.NoUsbAudio
            return false
        }
        if (!Ft8Decoder.ensureLoaded()) {
            problem = Problem.CannotStart("the decoder did not load: ${Ft8Decoder.loadError}")
            return false
        }

        pipeline.reset()
        val input = UsbAudioIn(
            device = device,
            ring = ring,
            onLevel = { peak, _ ->
                val rounded = if (peak.isFinite()) peak.toInt() else null
                onMain { peakDbfs = rounded }
            },
            onChunk = { pipeline.onAudio() },
            log = { line ->
                onLog?.invoke(line)
                onMain { lastLog = line }
            }
        )

        val failure = input.start()
        if (failure != null) {
            problem = Problem.CannotStart(failure)
            return false
        }

        audioIn = input
        startTxTicker()
        // Back from being off screen: the banner stays up until the first full slot
        // is decoded, since a gap must not pass for a quiet band.
        resumedAfterPause = pausedByLifecycle
        pausedByLifecycle = false
        running = true
        problem = null
        fruitlessSearches = 0
        slotsHeard = 0
        decodesHeard = 0
        // The feed is kept: history is the whole session, and the
        // section headers carry the time, so a gap reads as a gap.
        onLog?.invoke("receive started")
        return true
    }

    private val heardStations = HeardStations()
    private var heardVersion by mutableIntStateOf(0)

    /**
     * Every station heard this session. Reading it from a
     * composable recomposes that composable each time a slot adds to it.
     */
    val heard: HeardStations
        get() {
            heardVersion
            return heardStations
        }

    /** Receive last started by itself on return from off screen. */
    private var resumedAfterPause by mutableStateOf(false)

    /**
     * Back on screen and receiving, with no full slot decoded yet. Any return counts,
     * the USB permission dialog included: receive did stop, but "Paused" read as if
     * it still was.
     */
    val awaitingFirstSlot: Boolean
        get() = resumedAfterPause && running && slotsHeard == 0

    /**
     * True when receiving was stopped because the app left the screen, rather than
     * because the operator stopped it.
     *
     * Foreground only (ADR 0003): the app works only while visible, and says "Paused" on
     * return rather than pretending it kept listening.
     */
    var pausedByLifecycle by mutableStateOf(false)
        private set

    /** Stop because the app is no longer on screen. Rule 2 — not optional. */
    fun pauseForLifecycle() {
        if (!running) return
        stop()
        pausedByLifecycle = true
    }

    /** Stop capturing and release the radio's audio device. */
    fun stop() {
        if (running) onLog?.invoke("receive stopped")
        stopTxTicker()
        audioIn?.stop()
        audioIn = null
        pipeline.reset()
        running = false
        peakDbfs = null
    }

    /** Release everything. After this the session is done. */
    fun shutdown() {
        stop()
        decodePool.shutdownNow()
    }

    /**
     * Called every [TX_TICK_MS] on a thread of its own while receive is running.
     * Automatic TX hangs off this rather than off the 1 Hz UI tick, because the
     * transmit window opens 0.5 s into a slot and closes 4.0 s in - a one-second
     * sampling rate could miss it entirely.
     *
     * It used to be called after every captured audio chunk, which tied
     * transmitting to receiving: once the radio's audio stopped arriving for
     * 10 s at 13:53:31 and our slot to call VA2XA passed with no decision made and
     * nothing in the log. The timeline guard already covers lost audio for receive;
     * transmit now does not depend on it at all.
     */
    var onTxTick: (() -> Unit)? = null

    private var txTicker: Thread? = null

    private fun startTxTicker() {
        stopTxTicker()
        txTicker = Thread {
            while (!Thread.currentThread().isInterrupted) {
                try {
                    onTxTick?.invoke()
                } catch (t: Throwable) {
                    onLog?.invoke("tx tick failed: ${t.javaClass.simpleName} ${t.message}")
                }
                try {
                    Thread.sleep(TX_TICK_MS)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }.apply {
            name = "ft8ok-tx-tick"
            isDaemon = true
            // Like the capture thread it replaces for this: a transmission must not
            // wait on UI work to be decided.
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    /**
     * Stop the ticker and wait for a tick in flight, so nothing is decided after
     * receive stops - leaving the screen stops receive and then disarms (rule 2).
     */
    private fun stopTxTicker() {
        val ticker = txTicker ?: return
        txTicker = null
        ticker.interrupt()
        try { ticker.join(TX_TICK_JOIN_MS) } catch (_: InterruptedException) { }
    }

    /** Called once a second by the screen, so ages and countdowns advance. */
    fun tick() {
        timeEngine.refresh()
        clockTick = timeEngine.correctedNowMs()

        // The TX parity flips at every slot boundary, and with it the map that
        // governs our frequency. Re-decide when it does, or the held frequency goes
        // on being judged against the spectrum of the parity we are NOT about to
        // transmit in - which is how a pick ends up sitting on a loud station and
        // staying there.
        val parity = txParity()
        if (parity != lastTxParity) {
            lastTxParity = parity
            updateTxFrequency()
        }
    }

    private var lastTxParity: SlotParity? = null

    /** The user tapped the strip: this frequency, and the app stops choosing. */
    fun setTxFrequency(hz: Int) {
        // Logged only when it changes: a tap snaps to a gap, so repeat taps often
        // land on the same frequency (five lines for 2715 Hz in 3 s).
        // Inside the radio's passband: a lane's map can still be one cut to an older,
        // wider band for up to a slot after the radio changed it.
        val inside = hz.coerceIn(txBand.searchLowHz, txBand.searchHighHz)
        val changed = inside != userHz || txMode != TxFrequency.Mode.USER_SET
        userHz = inside
        txMode = TxFrequency.Mode.USER_SET
        if (changed) onLog?.invoke("tx freq: $inside Hz set by hand")
    }

    /**
     * Hand frequency choice back to the app.
     *
     * Each lane picks afresh from its latest map. The lane of a contact in progress
     * keeps the hand-set frequency instead (ADR 0013: never move mid-contact), and so
     * does a lane with no fresh map to pick from, rather than having nothing.
     */
    fun resumeAutoFrequency() {
        txMode = TxFrequency.Mode.FULL_AUTO
        val hz = userHz
        val heldLane = if (inContact()) committedTxParity() ?: txParity() else null
        autoPicks = SlotParity.entries.associateWith { if (it == heldLane) hz else null }
        onLog?.invoke("tx freq: back to Auto")
        updateTxFrequency()
        if (hz != null) autoPicks = autoPicks.mapValues { it.value ?: hz }
    }

    /** Called with each finished slot, so the QSO engine can follow the conversation. */
    var onSlotComplete: ((LiveRxPipeline.SlotUpdate) -> Unit)? = null

    /** Every line the audio layer logs, and receive starting and stopping, for the record. */
    var onLog: ((String) -> Unit)? = null

    /**
     * Troubleshooting lines (Auto's frequency moves, the reply list's matches),
     * dropped unless Detailed logs is on.
     */
    var onDetailLog: ((String) -> Unit)? = null

    /** A line of `decodes.log` ([DecodeLog]), on the main or the decode thread. */
    var onDecodeLog: ((String) -> Unit)? = null

    /** Where the audio of each slot the reply list tested is kept. Used on the decode thread. */
    var slotAudio: SlotAudioStore? = null

    /** The partner to look for in `decodes.log`, if any. Read on the decode thread. */
    var replyTarget: (() -> ReplyWatch.Target?)? = null

    /** The most recent line the audio layer logged, for diagnostics. */
    var lastLog by mutableStateOf<String?>(null)
        private set

    /**
     * The parity [row]'s sender was last heard transmitting in: from the newest row
     * of theirs in the feed, which is updated on every decode pass. The tapped row's
     * own parity when nothing newer is there.
     */
    fun latestParityFor(row: DecodeRow): SlotParity {
        val call = HeardStations.senderOf(row.decode.message) ?: return row.parity
        return feed.lastOrNull { !it.tx && HeardStations.senderOf(it.decode.message).equals(call, ignoreCase = true) }
            ?.takeIf { it.slotStartMs > row.slotStartMs }
            ?.parity
            ?: row.parity
    }

    /**
     * Our newest decode of [call] on the band the radio is on now, or null if they
     * have not been heard here (the CALL tab answers in the other
     * slot to this one). Searched in the feed, which holds the last [FEED_LIMIT] rows.
     */
    fun lastHeard(call: String): DecodeRow? =
        feed.lastOrNull {
            !it.tx && it.band == band && HeardStations.senderOf(it.decode.message).equals(call, ignoreCase = true)
        }

    /**
     * Put one of our own transmissions in the feed, in sequence with what we hear,
     * so the conversation reads in order on one list. Call on the main thread.
     */
    fun addTxRow(slotStartMs: Long, parity: SlotParity, message: String, audioHz: Int) {
        lastOwnTxSlotMs = slotStartMs
        if (slotStartMs !in ownTxSlots) {
            ownTxSlots.addLast(slotStartMs)
            while (ownTxSlots.size > 8) ownTxSlots.removeFirst()
        }
        val row = DecodeRow(
            slotStartMs, parity,
            Decode(score = 0, dtSeconds = 0.0, frequencyHz = audioHz, message = message),
            tx = true,
            band = band
        )
        feed = FeedOrder.insert(feed, listOf(row), FEED_LIMIT)
        onDecodeLog?.invoke(DecodeLog.row(row, 0))
    }

    /** Which band each slot was heard on. */
    private val bands = BandTimeline()

    /**
     * The band the radio is on, e.g. "20m", or null while its frequency is unknown.
     * The Roster and the Map show this band's stations only.
     */
    var band by mutableStateOf<String?>(null)
        private set

    /**
     * The band the feed dims against: the last one read, kept while the radio is
     * disconnected, since losing the CAT link is not a change of band.
     */
    var feedBand by mutableStateOf<String?>(null)
        private set

    /**
     * The radio is now on [newBand] (null: unknown). Call on the main thread, on
     * every change of band, the first reading and a disconnect included.
     *
     * The occupancy maps are the old band's picture, so they go: a TX frequency
     * picked from them would be a guess about a band we are no longer on.
     */
    fun onBandChanged(newBand: String?) {
        if (newBand == band) return
        // Back on another band after the radio was lost: what was heard in the gap
        // may be either band's, so it is of unknown band.
        bands.onBand(newBand, timeEngine.correctedNowMs())?.let { lostAt ->
            feed = feed.map { if (it.band != null && it.slotStartMs + mode.slotMs > lostAt) it.copy(band = null) else it }
            heardStations.unknownBandSince(lostAt)
            heardVersion += 1
        }
        band = newBand
        feedBand = bands.known
        readings = emptyMap()
        // Picks from the old band's maps are guesses about this one: each lane picks
        // again from its first slot heard here. A hand-set frequency stays.
        autoPicks = emptyMap()
        updateTxFrequency()
    }

    private fun apply(update: LiveRxPipeline.SlotUpdate) {
        // Heard partly on one band and partly on the other: its decodes would put the
        // old band's stations in the new band's Roster, and in front of the QSO
        // engine. Earlier passes of the slot, decoded before the change, keep the
        // band they were heard on.
        if (bands.straddles(update.slotStartMs, mode.slotMs)) {
            if (update.isFinal) {
                onLog?.invoke(
                    "slot %s %s: %d decodes dropped, the band changed during it".format(
                        java.time.Instant.ofEpochMilli(update.slotStartMs).toString().substring(11, 19),
                        update.parity.name.lowercase(), update.allDecodes.size
                    )
                )
                onDecodeLog?.invoke(DecodeLog.slotEnd(update, null, dropped = true))
                slotsHeard += 1
            }
            return
        }
        val slotBand = bands.bandAt(update.slotStartMs)
        if (update.newDecodes.isNotEmpty()) {
            // Each row comes with its report, measured in the same pass.
            val rows = update.newDecodes.map {
                DecodeRow(
                    update.slotStartMs, update.parity, it,
                    snrDb = update.snrByMessage[LiveRxPipeline.dedupKey(it)],
                    band = slotBand
                )
            }
            feed = FeedOrder.insert(feed, rows, FEED_LIMIT)
            onDecodeLog?.let { log -> rows.forEach { log(DecodeLog.row(it, update.pass)) } }
            decodesHeard += update.newDecodes.size
            // The Roster and Map learn of a station on the pass that decodes it, not
            // a second or three later at the final pass: a tap there in between
            // handed over the station's previous message, in its old parity
            // (VE3XAB). The reports come with the rows now.
            heardStations.observe(rows, mode.slotMs)
            heardVersion += 1
        }

        if (update.isFinal) {
            onDecodeLog?.invoke(DecodeLog.slotEnd(update, slotBand, dropped = false))
            onSlotComplete?.invoke(update)
            slotsHeard += 1
            // A slot's own consensus ends the search, so no report will clear this.
            if (!timeEngine.needsGridSearch) fruitlessSearches = 0
            clipped = update.clipped
            update.occupancy?.takeIf { update.slotStartMs !in ownTxSlots }?.let { map ->
                readings = readings + (update.parity to ParityReading(map, update.slotStartMs))
            }
            updateTxFrequency()
        }
    }

    /**
     * Apply the ADR 0013 policy, through [TxFrequencyPolicy] so the parity rule has
     * one home and one set of tests.
     *
     * Called on every final decode pass **and whenever the TX parity changes**. Both
     * are needed: the pass brings a new map, the parity change brings a different
     * map into force. Doing only the first is what left the app holding a frequency
     * chosen for one parity while checking it against the other.
     */
    private fun updateTxFrequency() {
        if (txMode == TxFrequency.Mode.USER_SET) return

        val decisions = TxFrequencyPolicy.decideLanes(
            mode = mode,
            nowMs = timeEngine.correctedNowMs(),
            readings = readings.mapValues {
                TxFrequencyPolicy.Reading(it.value.map?.let(::toCurrentBand), it.value.heardAtMs)
            },
            held = autoPicks,
            txMode = txMode,
            inContact = inContact(),
            committedParity = committedTxParity(),
            freshWithinMs = freshWithinMs(mode)
        )
        for ((lane, decision) in decisions) {
            val before = autoPicks[lane]
            if (decision.frequencyHz != before) logPick(lane, before, decision)
        }
        autoPicks = decisions.mapValues { it.value.frequencyHz }
    }

    /**
     * A map cut to an older TX band, made to fit the current one until the next slot
     * rebuilds it: candidates outside are dropped and the band is replaced, so neither
     * a first pick nor a move can land where the radio would key with no power.
     */
    private fun toCurrentBand(map: Occupancy.Map): Occupancy.Map {
        val band = txBand
        if (map.txBand == band) return map
        return map.copy(candidates = map.candidates.filter(band::fits), txBand = band)
    }

    /**
     * One line per change of a lane's pick, never per hold, so events.log stays small
     * and "why there?" can be answered from it.
     */
    private fun logPick(lane: SlotParity, before: Int?, decision: TxFrequency.Decision) {
        val log = onDetailLog ?: return
        val reading = readings[lane]
        val map = reading?.map
        val busy = if (before != null && map != null) {
            TxFrequency.peakDbAt(map, before)?.let { ", %.0f dB on %d".format(it, before) } ?: ""
        } else {
            ""
        }
        val heard = reading?.let {
            ", %s map %s".format(
                lane.name.lowercase(),
                java.time.Instant.ofEpochMilli(it.heardAtMs).toString().substring(11, 19)
            )
        } ?: ""
        log(
            "tx freq %s: %s -> %s Hz (%s%s%s)".format(
                lane.name.lowercase(), before ?: "none", decision.frequencyHz ?: "none",
                decision.reason.name.lowercase(), busy, heard
            )
        )
    }

    companion object {
        /**
         * How much audio to keep. Several slots, so a decode pass always finds its
         * audio even if a thread was late.
         */
        const val RING_SECONDS = 40

        /** Chosen default: about a minute, as long as a search remembers what it heard. */
        const val GRID_BANNER_SEARCHES = 4

        /**
         * How often [onTxTick] runs. Chosen default: the capture chunks it replaced came
         * about every 100 ms; half that keeps the decision as prompt and costs a
         * few microseconds a tick.
         */
        const val TX_TICK_MS = 50L

        /** How long [stop] waits for a tick in flight. */
        private const val TX_TICK_JOIN_MS = 500L

        /**
         * How many decode rows to keep: the history the feed pages back through and
         * the Roster reads. About half an hour of a busy band at
         * 25 decodes a slot, well past the Roster's 5 min maximum. A chosen default.
         */
        const val FEED_LIMIT = 3_000

        /**
         * How old a parity's map may be and still drive a frequency decision: two
         * slots of that parity, in the app's mode (60 s FT8, 30 s FT4). Older than that and we hold what we have rather than act on a stale
         * picture (ADR 0013).
         */
        fun freshWithinMs(mode: Mode): Long = 2 * 2 * mode.slotMs
    }
}
