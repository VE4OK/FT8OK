package com.ft8ok

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioManager
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.ft8ok.diag.EventLog
import com.ft8ok.dsp.Ft8Decoder
import com.ft8ok.dsp.SnrEstimator
import com.ft8ok.qso.QsoEngine
import com.ft8ok.qso.TxScheduler
import com.ft8ok.rig.RigSession
import com.ft8ok.rig.TxGuard
import com.ft8ok.model.DialFrequencies
import com.ft8ok.rx.LiveRxPipeline
import com.ft8ok.tx.AutoTx
import com.ft8ok.log.AdifBand
import com.ft8ok.log.ContestLog
import com.ft8ok.log.CtyDat
import com.ft8ok.log.EntityLookup
import com.ft8ok.log.LoggedQso
import com.ft8ok.logbook.LogSession
import com.ft8ok.logbook.NotLogged
import com.ft8ok.qso.Qso
import com.ft8ok.model.Ft8Message
import com.ft8ok.qso.HeardGrids
import com.ft8ok.settings.SettingsStore
import com.ft8ok.tx.TxSession
import com.ft8ok.rx.ReplyWatch
import com.ft8ok.rx.RxSession
import com.ft8ok.ui.shell.AppShell
import androidx.lifecycle.Lifecycle

/**
 * The app: the tabbed shell and the wiring behind it.
 *
 * Two rules are enforced here rather than anywhere else, because this is the only
 * place that knows about the Android lifecycle:
 *
 *  - **Foreground only** (rule 2). Leaving the screen stops audio capture and
 *    releases the device; there is no service, no notification and no background
 *    path. Receive restarts by itself on return, and the screen says Paused for a
 *    while rather than letting the gap pass for a quiet band. TX is hard-disarmed
 *    here too.
 *  - **The screen stays on** while the app is up, because the operator is reading a
 *    15 s cycle and a screen that sleeps mid-slot is useless in the field.
 */
class MainActivity : ComponentActivity() {

    private companion object {
        /** Our own action for the USB permission result. Package-scoped on send. */
        const val ACTION_USB_PERMISSION = "com.ft8ok.USB_PERMISSION"

        /** Report lines kept in events.log without Detailed logs: something went wrong. */
        val REPORT_PROBLEMS = listOf("***", "failed", "FAILED", "PTT:", "audio:", "NOT USB", "NO DECODE")
    }

    private lateinit var session: RxSession
    private lateinit var rig: RigSession

    /** The radio's last reported dial frequency, kept across a disconnect. */
    private var lastKnownDialHz: Long? = null
    private lateinit var tx: TxSession

    /**
     * `adb shell run-as com.ft8ok cat files/events.log` (debug builds). Connects,
     * disarms, errors and the operator's actions always; the troubleshooting lines
     * only with Detailed logs on ([applyDetailedLogs]). Copied to logcat in debug
     * builds only.
     */
    private val events by lazy { EventLog(java.io.File(filesDir, "events.log"), toLogcat = BuildConfig.DEBUG) }

    /**
     * Every message heard or sent: about 14 hours at 27 decodes a slot, across its two
     * files. Written only with Detailed logs on.
     */
    private val decodeLog by lazy { EventLog(java.io.File(filesDir, "decodes.log"), maxBytes = 4_000_000L, toLogcat = false) }
    private lateinit var settings: SettingsStore
    private lateinit var autoTx: AutoTx
    private lateinit var log: LogSession

    /** Every station's recently sent grid, so a contact that never carried one still logs it. */
    private val heardGrids = HeardGrids()

    /** Contacts with both reports that left without a log entry. */
    private val notLogged = NotLogged()

    /** Their grid: from the exchange, or else from what they were heard sending. */
    private fun gridOf(contact: Qso, nowMs: Long): String? =
        contact.partnerGrid ?: heardGrids.gridOf(contact.partner, nowMs)

    /**
     * What the Log tab offers to log by hand: the contact in the partner slot, if it
     * has both reports and no entry yet, then those that left. Read during
     * composition, so it follows the partner slot and the list as they change.
     */
    private fun notLoggedNow(): List<NotLogged.Entry> {
        val current = autoTx.qso?.takeIf { it.reportsOnAir && !autoTx.isLogged(it) }
        val now = session.timeEngine.correctedNowMs()
        val head = current?.let { NotLogged.Entry(it, rig.state.dialHz, gridOf(it, now), current = true) }
        return listOfNotNull(head) + notLogged.entries.filter {
            current == null || AutoTx.keyOf(it.qso) != AutoTx.keyOf(current)
        }
    }

    /**
     * The operator logs a contact the app would not. Marked logged
     * at once, so neither its completion nor its leaving the slot logs or lists it
     * again; Undo puts it back on the list.
     */
    private fun forceLog(entry: NotLogged.Entry) {
        val contact = entry.qso
        if (autoTx.isLogged(contact)) return
        autoTx.markLogged(contact)
        notLogged.remove(contact)
        events.add("logged by hand: ${contact.partner} sent ${contact.reportSentDb} rcvd ${contact.reportReceivedDb}")
        val record = LoggedQso.fromQso(
            contact.copy(partnerGrid = entry.grid), settings.profile, entry.dialHz, autoTx.mode, logAnyway = true
        )
        log.log(record, onUndone = {
            autoTx.unmarkLogged(contact)
            events.add("undone: logged by hand ${contact.partner}")
            // Back on the list unless it is still in the partner slot, which shows it anyway.
            if (autoTx.qso?.let(AutoTx::keyOf) != AutoTx.keyOf(contact)) {
                notLogged.add(entry.copy(current = false), session.timeEngine.correctedNowMs())
            }
        })
    }

    /** Callsign to country (cty.dat, ADR 0012). Empty for the moment it takes to load. */
    private var entities by mutableStateOf<EntityLookup>(EntityLookup.NONE)

    /** Rebuilt on every use, since it bakes in the callsign, grid and contest mode. */
    private val qsoEngine: QsoEngine
        get() = QsoEngine(
            myCall = settings.profile.normalisedCallsign,
            myGrid = settings.profile.normalisedGrid,
            contest = settings.contest
        )
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        session = RxSession(
            audioManager = getSystemService(AUDIO_SERVICE) as AudioManager,
            onMain = { block -> main.post(block) },
            mode = Flavor.MODE
        )
        settings = SettingsStore(this)
        events.detailed = settings.detailedLogs
        logDeviceInfo()
        Ft8Decoder.myCall = settings.profile.normalisedCallsign
        Ft8Decoder.protocol = Ft8Decoder.Protocol.valueOf(Flavor.MODE.name)
        tx = TxSession(
            audioManager = getSystemService(AUDIO_SERVICE) as AudioManager,
            onMain = { block -> main.post(block) },
            transmitBuilt = Flavor.TRANSMIT_BUILT,
            mode = Flavor.MODE
        )
        rig = RigSession(
            usbManager = getSystemService(USB_SERVICE) as UsbManager,
            onMain = { block -> main.post(block) }
        )
        autoTx = AutoTx(Flavor.MODE)
        log = LogSession(this, settings, onMain = { block -> main.post(block) })
        // A contact complete enough to log is logged at once, with
        // Undo on screen. The dial frequency is read now: a band change disarms and
        // ends the contact, so it is still the one the contact was made on.
        autoTx.onContactComplete = { contact ->
            // Answering a CQ, the grid was in the CQ, not in the exchange.
            val grid = gridOf(contact, session.timeEngine.correctedNowMs())
            log.log(LoggedQso.fromQso(contact.copy(partnerGrid = grid), settings.profile, rig.state.dialHz, autoTx.mode))
            // A dropped contact that came back and completed is no longer waiting.
            notLogged.remove(contact)
        }
        // Both reports and no log: kept for the Log tab. The dial is
        // the last one KNOWN: on a band change the radio has already moved.
        autoTx.onContactLeft = { contact ->
            val now = session.timeEngine.correctedNowMs()
            notLogged.add(NotLogged.Entry(contact, lastKnownDialHz, gridOf(contact, now)), now)
        }
        // Contest mode: a dupe is worked on this band in this contest, from the
        // logbook in use. Asked only for callers in a CQ run.
        session.replyTarget = {
            autoTx.qso?.partner?.let { ReplyWatch.Target(it, settings.profile.normalisedCallsign, autoTx.armed) }
        }
        autoTx.isDupe = { call ->
            settings.contest?.let { c ->
                ContestLog.isDupe(
                    log.contacts.map { it.qso }, call, rig.state.dialHz?.let(AdifBand::forHz), c.kind,
                    session.timeEngine.correctedNowMs()
                )
            } == true
        }
        // Settings › CQ PREFERENCES, read at each transmission.
        autoTx.cqMissLimit = { settings.cqMissLimit }
        autoTx.cqStopLimit = { settings.cqStopLimit }
        wireAutoTx()
        // About 100 KB of text: parsed off the main thread, swapped in when ready.
        Thread {
            val table = CtyDat.parse(assets.open("cty/cty.dat").bufferedReader().use { it.readText() })
            main.post { entities = table }
        }.start()
        registerUsbPermissionReceiver()

        setContent {
            var granted by remember { mutableStateOf(hasRecordAudio()) }

            // Contest mode switched in Settings: a CQ run in progress
            // calls with the new word from its next CQ, and the log says when it changed.
            val contestNow = settings.contest
            LaunchedEffect(contestNow) {
                autoTx.changeCqWord(qsoEngine, settings.cqWord.ifEmpty { null })
                events.add("contest mode: ${contestNow?.let {
                    val cq = if (it.cqWord.isEmpty()) "CQ" else "CQ ${it.cqWord}"
                    "${it.kind.name.lowercase()} ($cq, sending ${if (it.sendsExchange) it.exchange else "standard messages"})"
                } ?: "off"}")
            }

            val request = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { allowed ->
                granted = allowed
                if (allowed) startReceiving()
            }

            AppShell(
                session = session,
                rig = rig,
                tx = tx,
                settings = settings,
                autoTx = autoTx,
                log = log,
                onArm = {
                    if (autoTx.armed) autoTx.disarmByOperator()
                    // A radio in the wrong mode is refused here, with the reason on the
                    // dock, rather than armed and then silently not sent.
                    else autoTx.arm(settings.profile, modeRefusal())
                },
                onCall = { row ->
                    // Tapping a row answers it if it is addressed to us, or calls
                    // its sender. It does NOT arm - picking who to work and deciding
                    // to transmit stay separate decisions (rule 3).
                    // Never a station heard on another band, and never a band change:
                    // a tap works with the radio where it is. The
                    // screens offer no such row; this is the backstop.
                    if (row.band != session.band) {
                        events.add("tap ignored: \"${row.decode.message}\" was heard on ${row.band ?: "an unknown band"}, the radio is on ${session.band ?: "an unknown band"}")
                    } else autoTx.onRowTapped(
                        engine = qsoEngine,
                        text = row.decode.message,
                        ourReportDb = row.snrDb?.let { SnrEstimator.toReport(it) },
                        nowMs = session.timeEngine.correctedNowMs(),
                        // We answer in the opposite slot to the one the station was
                        // heard in LAST, not the tapped row's: the row may be minutes
                        // old, or a Roster/Map entry, and stations change parity
                        // (VE3XAB: odd answering N9XAA, then CQ on even).
                        heardIn = session.latestParityFor(row),
                        dtSeconds = row.decode.dtSeconds
                    )
                },
                onCq = {
                    // Our CQ goes in the parity of the next slot. Starting CQ, like
                    // tapping a station, does not arm.
                    if (autoTx.callingCq) autoTx.stopCq()
                    else autoTx.startCq(qsoEngine, session.txParity(), settings.cqWord.ifEmpty { null })
                },
                // The CQ ⋯ panel. Each only queues; ARM transmits.
                onCqWord = { word ->
                    settings.chooseCqWord(word)
                    autoTx.changeCqWord(qsoEngine, settings.cqWord.ifEmpty { null })
                    events.add("CQ word: ${settings.cqWord.ifEmpty { "none" }}")
                },
                onCallSign = { call, txIn, heard ->
                    autoTx.callByCallsign(qsoEngine, call, session.timeEngine.correctedNowMs(), txIn, heard)
                },
                onText = { text ->
                    // With no contact or CQ run fixing a side, the next slot's.
                    autoTx.queueText(text, autoTx.committedParity ?: session.txParity())
                },
                cqMessage = { word -> qsoEngine.cq(word) },
                callMessage = { call -> qsoEngine.begin(call, 0L).reply },
                onPick = { step ->
                    // Picks the next message only; arming stays a separate press.
                    // Null is AUTO: back to the automatic sequence.
                    val now = session.timeEngine.correctedNowMs()
                    if (step == null) autoTx.auto(now) else autoTx.choose(qsoEngine, step, now)
                },
                onHalt = {
                    // Stop the audio first, then disarm and keep the partner. The
                    // PTT release happens inside the transmission path and its
                    // watchdog backs it up.
                    tx.stopHoldTransmit()
                    tx.cancelWarm()
                    autoTx.halt(qsoEngine)
                },
                hasPermission = granted,
                onRequestPermission = { request.launch(Manifest.permission.RECORD_AUDIO) },
                onConnectRadio = {
                    if (rig.needsPermission()) requestUsbPermission()
                    else rig.connect(byOperator = true)
                },
                onDisconnectRadio = {
                    // No CAT link means no PTT: stop and disarm before closing it.
                    tx.stopHoldTransmit()
                    tx.cancelWarm()
                    autoTx.disarm(getString(R.string.rig_disconnected))
                    rig.disconnect(byOperator = true)
                },
                onReceiveOn = { on ->
                    settings.chooseReceiveOn(on)
                    if (on) startReceiving() else session.stop()
                },
                onDetailedLogs = { on ->
                    settings.chooseDetailedLogs(on)
                    applyDetailedLogs(on, deleteWhenOff = true)
                },
                // The radio's USB audio can appear a moment after its CAT port, and
                // there is no Start button any more: keep trying while it is missing.
                onTick = {
                    if (!session.running) startReceiving()
                    notLogged.prune(session.timeEngine.correctedNowMs())
                },
                notLoggedEntries = ::notLoggedNow,
                onForceLog = ::forceLog,
                entities = entities
            )
        }
    }

    /**
     * What this app is running on, once per start, so a log from another phone or
     * tablet says so. It also marks each restart.
     */
    /**
     * Settings › Diagnostics › Detailed logs: the troubleshooting lines in
     * events.log, every decode in decodes.log, and the a priori test slots' audio in
     * files/slots (up to 500 recordings, about 170 MB). Turning it off deletes the
     * last two; [deleteWhenOff] is false at start-up, so an install never deletes
     * anything by itself.
     */
    private fun applyDetailedLogs(on: Boolean, deleteWhenOff: Boolean) {
        events.detailed = on
        session.onDecodeLog = if (on) decodeLog::add else null
        session.slotAudio = if (on) com.ft8ok.rx.SlotAudioStore(java.io.File(filesDir, "slots")) else null
        if (!on && deleteWhenOff) {
            Thread({
                java.io.File(filesDir, "slots").deleteRecursively()
                java.io.File(filesDir, "decodes.log").delete()
                java.io.File(filesDir, "decodes.log.1").delete()
            }, "ft8ok-clear-logs").start()
        }
    }

    /**
     * What of a transmission's report goes in events.log: all of it with Detailed
     * logs on; otherwise only the lines that say something went wrong (an SWR abort,
     * NO RF, a failed key or audio path).
     */
    private fun reportForLog(report: String?): String {
        if (report == null) return ""
        if (events.detailed) return report
        return report.lineSequence()
            .filter { line -> REPORT_PROBLEMS.any { it in line } }
            .joinToString("\n")
    }

    private fun logDeviceInfo() {
        val version = packageManager.getPackageInfo(packageName, 0).versionName
        val config = resources.configuration
        val bits = if (android.os.Process.is64Bit()) 64 else 32
        events.detail(
            "device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} " +
                "(API ${Build.VERSION.SDK_INT}), app $version, ABIs ${Build.SUPPORTED_ABIS.joinToString()}, " +
                "$bits-bit process, screen ${config.screenWidthDp}x${config.screenHeightDp} dp, " +
                "font scale ${config.fontScale}"
        )
    }

    /**
     * The permission check is written out here, immediately before the call, rather
     * than delegated to [hasRecordAudio].
     *
     * Not style: lint recognises the check only when it is in the same method as the
     * call it guards, and behind a helper it reports MissingPermission — which it did.
     * The annotation on [RxSession.start] is only worth having if the
     * check is somewhere lint can see it.
     */
    private fun startReceiving() {
        // Rule 2: never from the background. Receive now starts by itself (there is
        // no Start button), and the once-a-second retry keeps
        // running while the activity is merely paused - this gate is what stops it
        // reopening the microphone off screen.
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        if (!settings.receiveOn) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        session.start()
    }

    /**
     * The radio is definitely in the wrong mode for FT8: LSB, or USB
     * with data off. Null when right or not known yet - an unknown data mode is a
     * moment after any mode broadcast, and [TxSession.guard] still refuses to key on it.
     */
    private fun modeRefusal(): String? =
        when (val refusal = TxGuard.check(rig.connected, rig.state, settings.customDialsHz, Flavor.MODE)) {
            TxGuard.Refusal.NOT_USB, TxGuard.Refusal.DATA_OFF -> guardText(refusal)
            else -> null
        }

    private fun guardText(refusal: TxGuard.Refusal?): String? = when (refusal) {
        null -> null
        TxGuard.Refusal.NO_RADIO -> getString(R.string.tx_guard_no_radio)
        TxGuard.Refusal.NO_FREQUENCY -> getString(R.string.tx_guard_no_frequency)
        TxGuard.Refusal.OFF_LIST -> getString(R.string.tx_guard_off_list)
        TxGuard.Refusal.NOT_USB, TxGuard.Refusal.DATA_OFF ->
            getString(R.string.tx_guard_wrong_mode, rig.state.modeLabel ?: "?")
        TxGuard.Refusal.DATA_UNKNOWN -> getString(R.string.tx_guard_data_unknown)
    }

    private fun hasRecordAudio(): Boolean =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /**
     * Join receive to transmit.
     *
     * Two hooks: finished slots drive the QSO engine, and every audio chunk asks
     * whether this is the moment to transmit. The second is on the audio thread, ten
     * times a second, because the window to start a transmission is 3.5 s wide and a
     * once-a-second check could miss it.
     */
    private fun wireAutoTx() {
        // The last line before keying, for every transmission including the TX test.
        tx.guard = { guardText(TxGuard.check(rig.connected, rig.state, settings.customDialsHz, Flavor.MODE)) }
        rig.onLog = events::add
        rig.onStateChanged = { before, after ->
            // Rule 3: a frequency change disarms and clears the partner. Judged against
            // the last frequency KNOWN, which a disconnect does not forget: the first
            // reading after connecting is not a change, unless the radio moved while
            // the link was down.
            val known = lastKnownDialHz
            if (after.dialHz != null && known != null && after.dialHz != known) {
                autoTx.onFrequencyChanged()
            }
            after.dialHz?.let { lastKnownDialHz = it }
            // The Roster and the Map follow the band; unknown while disconnected.
            // From the ADIF band edges, not [after.band]: that is the
            // dial table's label, null on a custom frequency, which would read as a lost
            // radio and refuse every tap (a contest on 21.090).
            session.onBandChanged(after.dialHz?.let(AdifBand::forHz))
            // What the app believes, so a stale top bar shows up in the log.
            if (before.dialHz != after.dialHz && after.dialHz != null) {
                events.add("radio frequency: ${com.ft8ok.radio.Civ.formatHz(after.dialHz!!)}")
            }
            // The radio left USB-D under us: stop, say why, and change nothing on the
            // radio - the banner offers the fix.
            if (autoTx.armed) modeRefusal()?.let { autoTx.disarm(it) }
            if (before.modeLabel != after.modeLabel && after.modeLabel != null) {
                events.add("radio mode: ${after.modeLabel}")
            }
            // Where we may transmit follows the radio's SSB-D transmit passband.
            // Kept through a disconnect: it is a menu setting.
            // Logged when the radio first says it or changes it, even when it matches
            // the default, so the log shows the radio actually answered.
            after.txPassbandHz?.let { hz ->
                if (before.txPassbandHz != hz) {
                    events.add("radio TX passband (SSB-D TBW): ${hz.first}-${hz.last} Hz")
                }
                session.setRadioTxPassband(hz)
            }
        }
        autoTx.onEvent = events::add
        session.onLog = events::add
        session.onDetailLog = events::detail
        applyDetailedLogs(settings.detailedLogs, deleteWhenOff = false)
        // Judge the TX frequency against the lane we will actually transmit in, and
        // hold it during a contact (ADR 0013).
        session.committedTxParity = { autoTx.committedParity }
        session.inContact = { autoTx.qso != null }
        session.onSlotComplete = { update ->
            // One line a slot: a receive stall shows as a run of zeros, and the
            // position says when the final pass reached us. The decode
            // and spectrum times are that pass's, so the cost of measuring reports on
            // every pass can be read against the decode's.
            events.detail(
                "slot %s %s: %d decodes, final pass at +%d ms (%s)".format(
                    java.time.Instant.ofEpochMilli(update.slotStartMs).toString().substring(11, 19),
                    update.parity.name.lowercase(), update.allDecodes.size,
                    session.timeEngine.correctedNowMs() - update.slotStartMs,
                    update.deeper?.let {
                        "subtraction removed %d, found %d in %d ms%s, spectrum %d ms".format(
                            it.removed, it.found, it.millis, if (it.cutShort) ", cut short" else "", update.analyseMillis
                        )
                    } ?: "decode ${update.decodeMillis} ms, spectrum ${update.analyseMillis} ms"
                )
            )
            // For hashes addressed to us; picks up a profile change by the next slot.
            Ft8Decoder.myCall = settings.profile.normalisedCallsign
            for (d in update.allDecodes) heardGrids.observe(Ft8Message.parse(d.message), update.slotStartMs)
            autoTx.onDecodes(
                engine = qsoEngine,
                decodes = update.allDecodes,
                snrFor = { d -> update.snrByMessage[LiveRxPipeline.dedupKey(d)]?.let { SnrEstimator.toReport(it) } },
                nowMs = session.timeEngine.correctedNowMs(),
                // The slot these decodes came from, not the clock: the final decode
                // pass runs 300 ms before the boundary and a slew could tip the
                // reading into the next slot, flipping the parity we answer in.
                slotParity = update.parity,
                slotStartMs = update.slotStartMs
            )
        }

        session.onTxTick = {
            val now = session.timeEngine.correctedNowMs()
            // Start the audio output on silence about a second before a slot we
            // expect to transmit in, so its start-up delay is over before the signal
            // is planned. Does not key the radio.
            if (autoTx.shouldPrewarm(now)) tx.prewarm()
            val message = autoTx.decide(
                nowMs = now,
                profile = settings.profile,
                dialHz = rig.state.dialHz,
                customDial = rig.state.dialHz?.let { dial ->
                    !DialFrequencies.isConventional(dial, Flavor.MODE.name) &&
                        TxGuard.allowedDial(dial, settings.customDialsHz, Flavor.MODE)
                } == true
            )
            if (message != null) {
                autoTx.onTransmitStarted()
                val slot = autoTx.mode.slotStartMs(now)
                val audioHz = session.txFrequencyHz ?: 1500
                main.post { session.addTxRow(slot, autoTx.mode.parityOf(slot), message, audioHz) }
                // What we MEAN to put on the air, in UTC as our corrected clock has it,
                // so a DT read off another receiver (a KiwiSDR, a friend's WSJT-X) can
                // be lined up with it. The DT others should see is the partner shift
                // plus our clock error and the radio's own delay, neither measured.
                val shiftMs = autoTx.txShiftMs()
                val targetMs = autoTx.txStartTargetMs(slot)
                events.detail(
                    "tx plan: \"%s\" slot %s %s, start %s UTC (nominal +%d ms, partner shift %+d ms%s), clock correction %+d ms, expected DT at others %+.2f s".format(
                        message,
                        java.time.Instant.ofEpochMilli(slot).toString().substring(11, 19),
                        autoTx.mode.parityOf(slot).name.lowercase(),
                        java.time.Instant.ofEpochMilli(targetMs).toString().substring(11, 23),
                        autoTx.mode.nominalTxStartMs,
                        shiftMs,
                        autoTx.qso?.partner?.let { " for $it" } ?: "",
                        session.timeEngine.offsetMs,
                        shiftMs / 1000.0
                    )
                )
                val scheduler = TxScheduler(autoTx.mode)
                tx.startHoldTransmit(
                    ptt = rig.ptt(),
                    message = message,
                    baseHz = audioHz.toDouble(),
                    amplitude = TxSession.DEFAULT_AMPLITUDE,
                    // Stop at the hard stop, so a transmission can never run into
                    // the next slot however long the audio is.
                    continueWhile = { autoTx.mayContinueTransmitting && !scheduler.mustStop(session.timeEngine.correctedNowMs()) },
                    meters = rig.meters()?.let { read -> TxSession.MeterSource { sub -> read(sub) } },
                    swrAbort = settings.profile.effectiveSwrAbort,
                    // When the transmission is actually over, not when it starts:
                    // startHoldTransmit returns at once and transmits on a worker.
                    onFinished = { transmitted ->
                        events.add("sent \"$message\" (transmitted=$transmitted)\n${reportForLog(tx.report)}")
                        autoTx.onTransmitFinished(qsoEngine, transmitted)
                    },
                    // The decision is now taken from the slot boundary; the signal
                    // itself waits for the nominal start, moved to the partner's
                    // time during a contact (ADR 0005).
                    startAtMs = autoTx.txStartTargetMs(slot),
                    // A late start goes out whole up to this far behind the target,
                    // then with its start left out; counted from the band's time.
                    maxLateShiftMs = autoTx.lateShiftAllowanceMs(),
                    clockMs = { session.timeEngine.correctedNowMs() },
                    // Typed text goes as free text, forced; everything else must
                    // encode as an addressed message.
                    freeText = autoTx.lastSentFreeText
                )
            }
        }
    }

    /**
     * Ask Android for access to the CAT device.
     *
     * A USB device permission, not a manifest one — it grants nothing beyond this
     * one device and does not appear in the merged manifest, so the permission guard
     * is untouched by it.
     */
    private fun requestUsbPermission() {
        val device = rig.device() ?: return
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(packageName)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        usbManager().requestPermission(device, PendingIntent.getBroadcast(this, 0, intent, flags))
    }

    private fun usbManager() = getSystemService(USB_SERVICE) as UsbManager

    private fun registerUsbPermissionReceiver() {
        val filter = IntentFilter(ACTION_USB_PERMISSION).apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        // RECEIVER_NOT_EXPORTED is API 33; below that the two-argument call is the
        // only option and is equivalent for a receiver nothing else broadcasts to.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(usbReceiver, filter)
        }
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_USB_PERMISSION,
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    rig.connect()
                    startReceiving()
                }

                // Unplugged mid-session: drop the link rather than leave a stale
                // frequency on screen that the frequency guard might later trust.
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    // No CAT link means no PTT and no frequency to check against.
                    autoTx.disarm("Radio disconnected")
                    rig.disconnect()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Cheap and idempotent: picks the radio up when it was plugged in while we
        // were away, or when permission was granted from the system dialog.
        rig.connect()
        // Back on screen: receive again, and the banner says it was paused. Posted,
        // because the lifecycle only reads RESUMED once onResume has returned, and
        // startReceiving refuses anything less.
        main.post { startReceiving() }
    }

    /** Rule 2: no capture while we are not on screen. */
    override fun onPause() {
        super.onPause()
        session.pauseForLifecycle()
        // Rule 3: leaving the screen HARD-DISARMS TX. Not pauses - disarms. Coming
        // back must be a deliberate act, because an app that resumed transmitting on
        // its own when it regained focus would key the radio without anyone asking.
        tx.stopHoldTransmit()
        tx.cancelWarm()
        autoTx.disarm("App left the screen")
        // The CAT link is read-only and costs nothing while idle, but holding a USB
        // port open in the background is exactly the kind of thing rule 2 exists to
        // prevent, and the radio may be unplugged while we are away.
        rig.disconnect()
    }

    override fun onDestroy() {
        super.onDestroy()
        session.shutdown()
        tx.shutdown()
        rig.disconnect()
        try { unregisterReceiver(usbReceiver) } catch (_: IllegalArgumentException) { }
    }
}

