package com.ft8ok.ui.radio

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import com.ft8ok.ui.shell.noTabSwipe
import com.ft8ok.ui.theme.appSliderColors
import com.ft8ok.ui.settings.SettingRow
import com.ft8ok.ui.settings.SettingsButton
import androidx.compose.material3.Text
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.rememberTextMeasurer
import com.ft8ok.R
import com.ft8ok.data.LogRepository
import com.ft8ok.dsp.TxFrequency
import com.ft8ok.log.ContestLog
import com.ft8ok.log.EntityLookup
import com.ft8ok.model.ContestSetup
import com.ft8ok.model.Ft8Message
import com.ft8ok.model.Mode
import com.ft8ok.model.SlotParity
import com.ft8ok.model.StationProfile
import com.ft8ok.qso.QsoEngine
import com.ft8ok.qso.TxScheduler
import com.ft8ok.logbook.LogSession
import com.ft8ok.settings.SettingsStore
import com.ft8ok.rig.RigSession
import com.ft8ok.rig.TxGuard
import com.ft8ok.rx.FeedFilter
import com.ft8ok.rx.FeedLine
import com.ft8ok.rx.FeedSections
import com.ft8ok.rx.RxSession
import com.ft8ok.tx.AutoTx
import com.ft8ok.tx.TxSession
import com.ft8ok.time.TimeEngine
import com.ft8ok.ui.strip.LaneState
import com.ft8ok.ui.strip.OccupancyStrip
import com.ft8ok.ui.theme.Tokens
import kotlin.math.roundToInt
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The Live tab: the home screen. The occupancy strip, the banners,
 * and the decode feed. The top bar, the TX dock and the bottom nav belong to the app
 * shell, so they are the same on every tab.
 *
 * Errors are banners above the list, never pop-ups.
 */
@Composable
fun LiveScreen(
    session: RxSession,
    rig: RigSession,
    settings: SettingsStore? = null,
    autoTx: AutoTx? = null,
    /** The logbook: banners for its file, Undo after a contact. */
    log: LogSession? = null,
    /** Tapping a decode row offers to work that station. */
    onCall: ((RxSession.DecodeRow) -> Unit)? = null,
    hasPermission: Boolean,
    onRequestPermission: () -> Unit,
    onConnectRadio: () -> Unit,
    /** The station profile is incomplete: the banner takes the operator to Settings. */
    onOpenSettings: () -> Unit = {},
    /** Callsign to country, for the note at the end of each row (cty.dat, ADR 0012). */
    entities: EntityLookup = EntityLookup.NONE,
    modifier: Modifier = Modifier
) {
    val now = session.clockTick.takeIf { it > 0 } ?: System.currentTimeMillis()

    Column(modifier = modifier.fillMaxSize()) {
        // Everything that can appear or vanish lives above the feed, which absorbs
        // the change; nothing below the feed moves (see TxReport). Rule 9: errors are
        // banners, never pop-ups. The radio and level details that used to sit on
        // their own lines are in Settings now and come back here only when something
        // is wrong. The banners sit right under the top bar, above
        // the strip, so the strip moves down while one shows.
        if (!hasPermission) {
            Banner(stringResource(R.string.rx_needs_permission), Tokens.StripColors.Strong, onRequestPermission)
        }
        if (settings != null && !settings.profile.canTransmit) {
            Banner(stringResource(R.string.live_setup_needed), Tokens.StripColors.Strong, onOpenSettings)
        }
        if (rig.problem == RigSession.Problem.NeedsPermission) {
            Banner(stringResource(R.string.rig_permission), Tokens.StripColors.Marker, onConnectRadio)
        }
        session.problem?.let { Banner(problemText(it), Tokens.StripColors.Hot) }
        // A CAT link that will not open must say why: the reason carries the radio
        // menu settings to check, and without it the operator has only "no radio".
        (rig.problem as? RigSession.Problem.CannotConnect)?.let {
            Banner(it.detail, Tokens.StripColors.Strong)
        }
        // Rule 2: say so. Receive restarts by itself on return, so until the next full
        // slot is decoded the banner is what stops the gap passing for a quiet band.
        if (session.pausedByLifecycle) {
            Banner(stringResource(R.string.rx_paused), Tokens.StripColors.Label)
        } else if (session.awaitingFirstSlot) {
            Banner(stringResource(R.string.rx_resumed), Tokens.StripColors.Label)
        }
        if (session.clipped) {
            Banner(stringResource(R.string.rx_clipped), Tokens.StripColors.Strong)
        }
        // A far-out clock and an empty band look the same, so it says both.
        if (session.noGridFound) {
            Banner(stringResource(R.string.rx_no_grid), Tokens.StripColors.Label)
        }
        // The rig is linked but on no FT8 frequency and none of the operator's own:
        // the frequency guard will refuse to transmit, and the top bar's band alone is
        // easy to miss.
        val customDials = settings?.customDialsHz.orEmpty()
        if (rig.connected && rig.state.dialHz != null && !TxGuard.allowedDial(rig.state.dialHz!!, customDials, com.ft8ok.Flavor.MODE)) {
            Banner(stringResource(R.string.live_off_frequency), Tokens.StripColors.LabelStale)
        }
        // The wrong mode for FT8 - LSB on 80 m - receives nothing and
        // must not transmit. The app does not put it back on its own; the banner is the one tap that does.
        val wrongMode = rig.connected && rig.state.mode != null &&
            (rig.state.mode != "USB" || rig.state.dataMode == false)
        when {
            rig.busy -> Banner(stringResource(R.string.rig_setting), Tokens.StripColors.Label)
            wrongMode -> Banner(
                stringResource(R.string.rig_wrong_mode, rig.state.modeLabel ?: "?"),
                Tokens.StripColors.Hot,
                onClick = rig::setFt8Mode
            )
        }
        rig.commandError?.let { Banner(stringResource(R.string.rig_command_failed, it), Tokens.StripColors.Hot) }
        // A contest station in the partner slot: our standard messages may not be taken
        // by its contest mode. A warning, not a refusal.
        autoTx?.contestPartner?.let { pick ->
            val what = when (val m = pick.message) {
                is Ft8Message.Cq -> "CQ ${m.directedTo}"
                is Ft8Message.Contest -> stringResource(
                    when (m.kind) {
                        Ft8Message.Contest.Kind.GRID -> R.string.contest_kind_grid
                        Ft8Message.Contest.Kind.FIELD_DAY -> R.string.contest_kind_field_day
                        Ft8Message.Contest.Kind.RTTY -> R.string.contest_kind_rtty
                        Ft8Message.Contest.Kind.EU_VHF -> R.string.contest_kind_eu_vhf
                    }
                )
                else -> ""
            }
            Banner(stringResource(R.string.live_contest_partner, pick.call, what), Tokens.StripColors.Strong)
        }

        // The log. A file that cannot be written is an error; no folder yet is only a
        // note, because every contact is still kept in the app's database.
        log?.let { l ->
            l.fileError?.let { Banner(stringResource(R.string.log_file_error, it), Tokens.StripColors.Hot) }
            if (l.folderLabel == null) Banner(stringResource(R.string.log_no_folder), Tokens.StripColors.Label, onOpenSettings)
        }
        // Nothing heard yet: say whether we are listening. It was inside the empty
        // feed, under the strip.
        if (session.feed.isEmpty()) {
            Text(
                text = stringResource(
                    if (session.running) R.string.rx_listening else R.string.rx_idle
                ),
                color = Tokens.StripColors.Label,
                fontSize = Tokens.Strip.LabelSize,
                modifier = Modifier.padding(Tokens.Strip.Padding)
            )
        }

        OccupancyStrip(
            evenLane = session.laneFor(SlotParity.EVEN, now),
            oddLane = session.laneFor(SlotParity.ODD, now),
            txParity = session.txParity(),
            txFrequencyHz = session.txFrequencyHz,
            mode = session.txMode,
            onTapFrequency = session::setTxFrequency,
            onTapAuto = session::resumeAutoFrequency,
            modifier = Modifier.padding(top = 4.dp)
        )

        settings?.let { FilterChips(it) }

        log?.let { l -> l.lastLogged?.let { LoggedRow(it, onUndo = l::undo, onDismiss = l::dismissLogged) } }

        Feed(
            session, onCall, settings?.profile?.normalisedCallsign ?: "",
            partner = autoTx?.qso?.partner,
            filter = settings?.feedFilter ?: FeedFilter.ALL,
            custom = settings?.feedCustom ?: "",
            contacts = log?.contacts.orEmpty(),
            contest = settings?.contest?.kind,
            entities = entities,
            modifier = Modifier.weight(1f)
        )
    }
}

/**
 * What the last transmission did, as the radio reported it.
 *
 * **Above the feed, deliberately.** It was below the ARM/HALT row, and it grows from
 * nothing to several lines when a transmission finishes - which moved Halt 335 px up
 * the screen, measured on the phone. Rule 3 says Halt is always in the same place,
 * and a control that moves when the thing it aborts has just run is the worst
 * possible time for it to move. Everything below the weighted feed is now
 * fixed-height; variable content goes above it, where the feed absorbs the change,
 * which is also where rule 9 puts banners.
 */
@Composable
internal fun TxReport(tx: TxSession) {
    val text = tx.report ?: return
    Text(
        text = text.trim(),
        color = Tokens.StripColors.Label,
        fontSize = Tokens.Strip.LabelSize,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier.padding(horizontal = Tokens.Settings.Inner, vertical = 2.dp)
    )
}

/** What one parity's lane should show right now, ages included. */
internal fun RxSession.laneFor(parity: SlotParity, nowMs: Long): LaneState {
    val reading = readings[parity]
    return LaneState(
        map = reading?.map,
        // Age from the END of the slot it came from: that is when we stopped
        // hearing it, and it is what "30 s ago" means to an operator.
        heardAgoMs = reading?.let { (nowMs - (it.heardAtMs + mode.slotMs)).coerceAtLeast(0) },
        pickHz = pickFor(parity)
    )
}

@Composable
private fun problemText(problem: RxSession.Problem): String = when (problem) {
    RxSession.Problem.NoUsbAudio -> stringResource(R.string.rx_no_usb)
    is RxSession.Problem.CannotStart -> problem.detail
}

/**
 * One glanceable line: what the clock is doing, how much has been heard, how hot the
 * audio is. Monospace so the numbers do not jiggle as they change.
 */
@Composable
internal fun StatusLine(session: RxSession) {
    val engine = session.timeEngine
    val clockText = when (engine.sync) {
        TimeEngine.Sync.NONE -> stringResource(R.string.rx_clock_none)
        TimeEngine.Sync.TENTATIVE -> stringResource(R.string.rx_clock_tentative, engine.offsetMs)
        TimeEngine.Sync.SYNCED -> stringResource(R.string.rx_clock_synced, engine.offsetMs)
        TimeEngine.Sync.HOLDING -> stringResource(R.string.rx_clock_holding, engine.offsetMs)
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Tokens.Settings.Inner),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = clockText,
            color = if (engine.sync == TimeEngine.Sync.SYNCED) Tokens.StripColors.Marker
            else Tokens.StripColors.Label,
            fontSize = Tokens.Settings.HelpSize,
            fontFamily = FontFamily.Monospace
        )
        Text(
            text = stringResource(R.string.rx_counts, session.slotsHeard, session.decodesHeard),
            color = Tokens.StripColors.Label,
            fontSize = Tokens.Settings.HelpSize,
            fontFamily = FontFamily.Monospace
        )
        Text(
            text = session.peakDbfs?.let { stringResource(R.string.rx_level, it) } ?: "",
            color = Tokens.StripColors.Label,
            fontSize = Tokens.Settings.HelpSize,
            fontFamily = FontFamily.Monospace
        )
    }
}

/**
 * What the radio says it is doing.
 *
 * Its own line because the dial frequency is the context for everything below it: an
 * audio offset of 1391 Hz means nothing until you know the band, and the log will
 * need it for every QSO.
 *
 * Shows **what the radio told us and nothing more**. No band label is invented for a
 * frequency outside the verified table — "off band" is an honest answer and the
 * right one to see before transmitting.
 */
@Composable
internal fun RigLine(rig: RigSession, onConnect: () -> Unit) {
    val state = rig.state
    val needsTap = rig.problem == RigSession.Problem.NeedsPermission

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (needsTap) Modifier.clickable(onClick = onConnect) else Modifier)
            .padding(horizontal = Tokens.Settings.Inner, vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        when {
            needsTap -> Text(
                text = stringResource(R.string.rig_permission),
                color = Tokens.StripColors.Marker,
                fontSize = Tokens.Settings.TextSize,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )

            !rig.connected -> Text(
                text = stringResource(if (rig.connecting) R.string.rig_connecting else R.string.rig_none),
                color = Tokens.StripColors.Label,
                fontSize = Tokens.Settings.TextSize,
                fontFamily = FontFamily.Monospace
            )

            // Connected but the radio has not said anything yet. Distinct from "no
            // radio" on purpose: the two look identical from the outside and have
            // completely different remedies.
            state.dialHz == null -> Text(
                text = stringResource(R.string.rig_linked),
                color = Tokens.StripColors.Label,
                fontSize = Tokens.Settings.TextSize,
                fontFamily = FontFamily.Monospace
            )

            else -> {
                Text(
                    text = state.band ?: stringResource(R.string.rig_off_band),
                    color = if (state.onFt8Frequency) Tokens.StripColors.Marker
                    else Tokens.StripColors.LabelStale,
                    fontSize = Tokens.Settings.TextSize,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = stringResource(R.string.rig_dial, megahertz(state.dialHz!!)),
                    color = Tokens.StripColors.Frequency,
                    fontSize = Tokens.Settings.TextSize,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = state.modeLabel ?: "",
                    color = Tokens.StripColors.Label,
                    fontSize = Tokens.Settings.TextSize,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

/** Dial frequency as MHz to the Hz, grouped so it reads at a glance. */
internal fun megahertz(hz: Long): String =
    "%d.%03d.%03d".format(hz / 1_000_000, (hz / 1_000) % 1_000, hz % 1_000)

/**
 * The decode feed: newest at the bottom, append-only within a cycle, except that
 * rows mentioning our call stay at the end of their slot.
 *
 * Positions otherwise never shift once a row is placed, so a row
 * cannot move under a thumb that is already reaching for it. While following, a
 * mention stays put at the bottom and the later rows of its slot go in above it;
 * scrolled up, it moves down by that many rows - the accepted exception. This is the ancestor of zone 4's
 * call list, not the thing itself.
 */
@Composable
private fun Feed(
    session: RxSession,
    onCall: ((RxSession.DecodeRow) -> Unit)? = null,
    /** Our callsign: rows that mention it are highlighted. */
    myCall: String = "",
    /** The station we are calling or working: their rows get a subtle tint. */
    partner: String? = null,
    /** The chosen filter chip and the Custom chip's text. Display only. */
    filter: FeedFilter = FeedFilter.ALL,
    custom: String = "",
    /** The active logbook: a sender already worked on the row's band is drawn dim. */
    contacts: List<LogRepository.Stored> = emptyList(),
    /** Contest mode, for the dupe mark; null when off. */
    contest: ContestSetup.Kind? = null,
    entities: EntityLookup = EntityLookup.NONE,
    modifier: Modifier = Modifier
) {
    val state = rememberLazyListState()
    val feed = session.feed
    val items = remember(feed, myCall, filter, custom) {
        FeedSections.build(feed, myCall) { filter.shows(it, myCall, custom) }
    }
    val scope = rememberCoroutineScope()
    // Once per logbook change, not per row: a set lookup is all a row then costs.
    val worked = remember(contacts) {
        contacts.mapTo(HashSet()) { workedKey(it.qso.call, it.qso.band) }
    }
    // In contest mode, the calls worked in this contest, by band: marked DUPE.
    val dupes = remember(contacts, contest) {
        contest?.let { k ->
            ContestLog.inContest(contacts.map { it.qso }, k, System.currentTimeMillis())
                .mapTo(HashSet()) { workedKey(it.call, it.band) }
        } ?: emptySet()
    }

    // The number columns, sized once from the widest value each can hold in the
    // font the phone actually uses. Measured, not counted: "monospace" is
    // proportional on the test phone, so padded text did not line up.
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val columns = remember(measurer, density) {
        fun widthOf(values: List<String>) = with(density) { values.maxOf { measurer.measure(it, FeedRowStyle).size.width }.toDp() }
        FeedColumns(
            snr = widthOf(FeedLine.WIDEST_SNR),
            dt = widthOf(FeedLine.WIDEST_DT),
            frequency = widthOf(FeedLine.WIDEST_FREQUENCY)
        )
    }
    val context = FeedRowContext(myCall, partner, session.feedBand, worked, dupes, entities, columns, session.mode)

    // Live or paused. Scrolling up pauses the display only -
    // decoding, auto-answer and the dock carry on - and reaching the bottom again,
    // or the Back to live chip, resumes it.
    // At the bottom means the last row whole on screen. "Last row visible" counted a
    // half-hidden one as the bottom, so a cut-off feed was never put right.
    val atBottom by remember { derivedStateOf { !state.canScrollForward } }
    // Only a finger pauses the feed. Any scroll away from the bottom used to count,
    // and the feed's own scroll to the newest row then paused it, "0 new" (on the
    // phone).
    val dragged by state.interactionSource.collectIsDraggedAsState()
    var following by remember { mutableStateOf(true) }
    var rowsWhenPaused by remember { mutableIntStateOf(0) }
    LaunchedEffect(dragged, atBottom) {
        if (atBottom) following = true
        else if (dragged && following) {
            following = false
            rowsWhenPaused = feed.size
        }
    }

    // Keyed on the newest row, not the count: once the feed is at its limit the
    // count stops changing and the list would stop following the band.
    LaunchedEffect(feed.lastOrNull()) {
        if (following && items.isNotEmpty()) state.scrollToItem(items.size - 1)
    }

    // Another filter is a fresh view of the band: back to live, at the newest rows.
    LaunchedEffect(filter, custom) {
        following = true
        if (items.isNotEmpty()) state.scrollToItem(items.size - 1)
    }

    // Following, and the bottom went out of view with no new row: the space above
    // the dock changed (the Logged/Undo row, a banner, a longer status line), or a
    // row grew. Seen once: "Logged F4XAB" pushed our TX row off the bottom
    // until the next slot's decodes.
    val lastIndex by rememberUpdatedState(items.size - 1)
    LaunchedEffect(following, atBottom, dragged) {
        if (following && !atBottom && !dragged && lastIndex >= 0) state.scrollToItem(lastIndex)
    }

    Box(modifier = modifier.fillMaxWidth()) {
        // Empty: the listening / not-receiving line is above the strip.
        if (feed.isNotEmpty()) {
            LazyColumn(state = state, modifier = Modifier.fillMaxWidth()) {
                items(items, key = { it.key }) { item ->
                    when (item) {
                        is FeedSections.Header -> Box(Modifier.fillMaxWidth().swallowTaps()) { SlotHeader(item) }
                        is FeedSections.BandChange -> Box(Modifier.fillMaxWidth().swallowTaps()) { BandDivider(item) }
                        is FeedSections.Line -> Box(Modifier.padding(horizontal = Tokens.Strip.Padding)) {
                            FeedRow(item.row, onCall, context)
                        }
                    }
                }
            }
            if (!following) {
                val newRows = (feed.size - rowsWhenPaused).coerceAtLeast(0)
                Text(
                    text = stringResource(R.string.feed_back_to_live, newRows),
                    color = Tokens.StripColors.Strong,
                    fontSize = Tokens.Strip.LabelSize,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 8.dp)
                        .background(Tokens.StripColors.Background, RoundedCornerShape(16.dp))
                        .border(1.dp, Tokens.StripColors.Strong, RoundedCornerShape(16.dp))
                        .clickable {
                            following = true
                            scope.launch { state.scrollToItem(items.size - 1) }
                        }
                        .heightIn(min = Tokens.Feed.BackToLiveHeight)
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                )
            }
        }
    }
}

/**
 * Where the radio changed band. Rows above it were heard on
 * another band: they stay readable, dimmed, and cannot be tapped.
 */
@Composable
private fun BandDivider(change: FeedSections.BandChange) {
    Text(
        text = change.band?.let { stringResource(R.string.feed_band_change, it) }
            ?: stringResource(R.string.feed_band_unknown),
        color = Tokens.StripColors.Strong,
        fontSize = Tokens.Strip.LabelSize,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Tokens.Feed.SlotDividerThickness)
            .background(Tokens.StripColors.Background)
            .padding(vertical = 2.dp)
    )
}

/** The line that opens each 15 s slot: UTC time, parity, and what is in it. */
@Composable
private fun SlotHeader(header: FeedSections.Header) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Tokens.Feed.SlotDividerThickness)
            .background(Tokens.StripColors.Background)
            .padding(horizontal = Tokens.Strip.Padding, vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = "%s  %s".format(
                utcClock(header.slotStartMs),
                stringResource(if (header.parity == SlotParity.EVEN) R.string.top_even else R.string.top_odd)
            ),
            color = Tokens.StripColors.Frequency,
            fontSize = Tokens.Strip.LabelSize,
            fontFamily = FontFamily.Monospace
        )
        Text(
            text = when {
                header.decodes == 0 && header.ourTx -> stringResource(R.string.feed_our_tx)
                header.ourTx -> pluralStringResource(R.plurals.feed_decodes_and_tx, header.decodes, header.decodes)
                else -> pluralStringResource(R.plurals.feed_decodes, header.decodes, header.decodes)
            },
            color = Tokens.StripColors.Label,
            fontSize = Tokens.Strip.LabelSize,
            fontFamily = FontFamily.Monospace
        )
    }
}

/**
 * The feed rows' text. `tnum` asks for equal-width digits where the font has them,
 * so a column of numbers reads straight; Monospace stays, for phones where it is.
 */
private val FeedRowStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = Tokens.Feed.RowTextSize,
    fontFeatureSettings = "tnum"
)

/** Widths of the SNR, DT and frequency columns, measured once from their widest values. */
@Immutable
private data class FeedColumns(val snr: Dp, val dt: Dp, val frequency: Dp)

/** What every feed row needs to know besides its own decode. One per Feed composition. */
@Immutable
private data class FeedRowContext(
    val myCall: String,
    /** The station we are calling or working. */
    val partner: String?,
    /** The band the radio is on. A row from another band offers nothing to work. */
    val currentBand: String?,
    /** [workedKey]s of the active logbook. */
    val worked: Set<String>,
    /** [workedKey]s worked in the contest we are in; empty outside contest mode. */
    val dupes: Set<String>,
    val entities: EntityLookup,
    val columns: FeedColumns,
    val mode: Mode
)

/**
 * Takes a tap and does nothing with it, for feed items with nothing to offer.
 *
 * Compose stretches the tap area of anything clickable smaller than 48 dp (our rows
 * are 24) and gives a tap that lands on nothing tappable to the nearest stretched
 * neighbour. Without this, a tap on a `<...>` row put the station in the row above
 * into the partner slot (on air: "FR5XA VO1XA GN28" taken for a
 * `VE4OK <...>` reply). A handler here makes the tap land on this item, so no
 * neighbour gets it.
 */
private fun Modifier.swallowTaps(): Modifier = pointerInput(Unit) { detectTapGestures { } }

/** A call on a band, as the worked-before mark looks it up. */
private fun workedKey(call: String, band: String?): String =
    "${call.uppercase()}|${band?.lowercase()}"

@Composable
private fun FeedRow(
    row: RxSession.DecodeRow,
    onCall: ((RxSession.DecodeRow) -> Unit)? = null,
    context: FeedRowContext
) {
    val otherBand = row.band != context.currentBand
    val parsed = remember(row.decode.message) { Ft8Message.parse(row.decode.message) }
    // Who this row offers to work: the SENDER, which for a CQ is the caller and for
    // a reply is the station transmitting. Never the addressee - answering the
    // station someone else is calling is how you transmit on top of a contact in
    // progress. A hashed callsign offers nothing, because it cannot be addressed.
    val sender = when (parsed) {
        is Ft8Message.Cq -> parsed.caller
        is Ft8Message.Reply -> parsed.from
        is Ft8Message.Report -> parsed.from
        is Ft8Message.Acknowledge -> parsed.from
        is Ft8Message.Dxpedition -> parsed.from
        // Tappable: a contest station can be called, with a banner that it may not
        // complete.
        is Ft8Message.Contest -> parsed.from
        is Ft8Message.Unknown -> null
    }?.takeIf { it != Ft8Message.HASHED && !row.tx }
    val callable = sender?.takeIf { !otherBand }
    // WSJT-X's convention, which FT8 operators already read without thinking:
    // our own transmissions tinted amber, anything mentioning our callsign red.
    // The station we are calling or working, subtly, so their messages can be
    // found among the others. Below the other two: a row of
    // theirs addressed to us is red.
    val mentionsUs = !row.tx && Ft8Message.mentions(row.decode.message, context.myCall)
    val isPartner = context.partner != null && callable.equals(context.partner, ignoreCase = true)
    val tint = when {
        row.tx -> Tokens.StripColors.Strong.copy(alpha = Tokens.Feed.TintAlpha)
        mentionsUs -> Tokens.StripColors.Hot.copy(alpha = Tokens.Feed.TintAlpha)
        isPartner -> Tokens.StripColors.Marker.copy(alpha = Tokens.Feed.PartnerTintAlpha)
        else -> null
    }
    // Other stations' exchanges step back, so CQs and our own rows stand out.
    val quiet = !row.tx && !mentionsUs && !isPartner && parsed !is Ft8Message.Cq
    val fade = (if (otherBand) Tokens.Feed.OtherBandAlpha else 1f) * (if (quiet) Tokens.Feed.QuietAlpha else 1f)
    fun Color.faded() = copy(alpha = alpha * fade)
    val worked = sender != null && workedKey(sender, row.band) in context.worked

    // The note: the sender's country, left out when it does not fit beside the
    // message. No distance shown.
    val country = if (row.tx) null else sender?.let { context.entities.entityOf(it)?.name }
    // A dupe in the contest we are in takes the note's place: marked, never refused.
    val dupe = !row.tx && sender != null && workedKey(sender, row.band) in context.dupes

    // The report column is OUR measured SNR of them - the number that would be sent
    // on the air - measured in the pass that decoded the row, "--" when it
    // could not be. Never the decoder's sync score, which is not an SNR. Our own
    // transmission has the same columns: TX where the report goes, nothing for DT,
    // our audio frequency. The slot header carries the time and parity.
    val line = remember(row, parsed) {
        if (row.tx) FeedLine.buildTx(row.decode.frequencyHz, row.decode.message)
        else FeedLine.build(
            row.snrDb,
            FeedLine.dtFromOnTime(row.decode.dtSeconds, context.mode),
            row.decode.frequencyHz,
            row.decode.message,
            parsed
        )
    }
    // A reply the reply list found rather than the decoder.
    val listMark = if (row.decode.fromReplyList) stringResource(R.string.feed_reply_list_mark) else ""
    val message = remember(line, worked, fade, listMark) {
        buildAnnotatedString {
            append(line.message)
            append(listMark)
            line.cq?.let { addStyle(SpanStyle(color = Tokens.Feed.CqWord.faded()), it.first, it.last + 1) }
            if (worked) line.sender?.let { addStyle(SpanStyle(color = Tokens.Feed.WorkedCall.faded()), it.first, it.last + 1) }
        }
    }
    val textColour = Tokens.StripColors.Frequency.faded()

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Tokens.Feed.ColumnGap),
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (tint != null) Modifier.background(tint, RoundedCornerShape(3.dp))
                else Modifier
            )
            .then(
                if (onCall != null && callable != null) {
                    Modifier.clickable { onCall(row) }
                } else {
                    Modifier.swallowTaps()
                }
            )
            // A row with our call in it is the one most likely to be tapped in a
            // hurry, so it is taller. Scaled from the row's own
            // measured height, text centred, tint and tap area filling it.
            .then(if (mentionsUs) Modifier.scaleHeight(Tokens.Feed.MentionRowScale) else Modifier)
            .padding(vertical = Tokens.Feed.RowPadding)
    ) {
        // Fixed widths, right-aligned: the columns line up in any font.
        for ((value, width) in listOf(
            line.snr to context.columns.snr,
            line.dt to context.columns.dt,
            line.frequency to context.columns.frequency
        )) {
            Text(
                text = value,
                style = FeedRowStyle,
                color = textColour,
                textAlign = TextAlign.End,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.width(width)
            )
        }
        if (dupe) MessageAndNote(message, stringResource(R.string.feed_dupe), textColour, Tokens.Feed.Dupe.faded())
        else MessageAndNote(message, country, textColour, Tokens.Feed.Note.faded())
    }
}

/**
 * The message, then [note] at the right edge if it fits beside it; otherwise no
 * note. The message always keeps its room.
 */
@Composable
private fun RowScope.MessageAndNote(message: AnnotatedString, note: String?, colour: Color, noteColour: Color) {
    val gapPx = with(LocalDensity.current) { Tokens.Feed.NoteGap.roundToPx() }
    Layout(
        content = {
            Text(message, style = FeedRowStyle, color = colour, maxLines = 1, softWrap = false)
            if (note != null) Text(note, style = FeedRowStyle, color = noteColour, maxLines = 1, softWrap = false)
        },
        modifier = Modifier.weight(1f)
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0)
        val messagePlaceable = measurables[0].measure(loose)
        val room = constraints.maxWidth - messagePlaceable.width - gapPx
        val note = measurables.getOrNull(1)
            ?.measure(loose.copy(maxWidth = Constraints.Infinity))
            ?.takeIf { it.width <= room }
        val height = maxOf(messagePlaceable.height, note?.height ?: 0)
        layout(constraints.maxWidth, height) {
            messagePlaceable.place(0, (height - messagePlaceable.height) / 2)
            note?.place(constraints.maxWidth - note.width, (height - note.height) / 2)
        }
    }
}

/**
 * The filter chips above the feed: `CQ` · `To me` · `All` · `Custom`. Custom shows a field for its terms beside the chips while it is chosen.
 * Display only: decoding and auto-answer carry on whatever is chosen.
 */
@Composable
private fun FilterChips(settings: SettingsStore) {
    val focus = LocalFocusManager.current
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Tokens.Strip.Padding, vertical = 3.dp)
    ) {
        for (f in listOf(FeedFilter.CQ, FeedFilter.TO_ME, FeedFilter.ALL, FeedFilter.CUSTOM)) {
            val on = f == settings.feedFilter
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .height(Tokens.Feed.ChipHeight)
                    .background(
                        if (on) Tokens.StripColors.Faint else Tokens.StripColors.Background,
                        RoundedCornerShape(14.dp)
                    )
                    .border(
                        1.dp,
                        if (on) Tokens.StripColors.Occupied else Tokens.StripColors.TxBand,
                        RoundedCornerShape(14.dp)
                    )
                    .clickable { settings.chooseFeedFilter(f) }
                    .padding(horizontal = 12.dp)
            ) {
                Text(
                    text = stringResource(
                        when (f) {
                            FeedFilter.CQ -> R.string.feed_filter_cq
                            FeedFilter.TO_ME -> R.string.feed_filter_to_me
                            FeedFilter.ALL -> R.string.feed_filter_all
                            FeedFilter.CUSTOM -> R.string.feed_filter_custom
                        }
                    ),
                    color = if (on) Tokens.StripColors.Frequency else Tokens.StripColors.Label,
                    fontSize = Tokens.Strip.LabelSize
                )
            }
        }
        if (settings.feedFilter == FeedFilter.CUSTOM) {
            val style = TextStyle(
                color = Tokens.StripColors.Frequency,
                fontSize = Tokens.Strip.LabelSize,
                fontFamily = FontFamily.Monospace
            )
            BasicTextField(
                value = settings.feedCustom,
                onValueChange = settings::chooseFeedCustom,
                singleLine = true,
                textStyle = style,
                cursorBrush = SolidColor(Tokens.StripColors.Frequency),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Characters,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (settings.feedCustom.isEmpty()) {
                            Text(stringResource(R.string.feed_custom_hint), style = style.copy(color = Tokens.StripColors.Label))
                        }
                        inner()
                    }
                },
                modifier = Modifier
                    .weight(1f)
                    .height(Tokens.Feed.ChipHeight)
                    .border(1.dp, Tokens.StripColors.Occupied, RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp)
                    .wrapContentHeight(Alignment.CenterVertically)
            )
        }
    }
}

/** Lays the content out [scale] times its own height, centred vertically. */
private fun Modifier.scaleHeight(scale: Float): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val height = (placeable.height * scale).roundToInt()
    layout(placeable.width, height) { placeable.place(0, (height - placeable.height) / 2) }
}

/** A banner above the list. Never a dialog: a pop-up mid-cycle costs a slot. */
@Composable
internal fun Banner(text: String, colour: Color, onClick: (() -> Unit)? = null) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Tokens.Strip.Padding, vertical = 3.dp)
            .background(colour.copy(alpha = 0.18f), RoundedCornerShape(4.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 8.dp, vertical = 5.dp)
    ) {
        Text(
            text = text,
            color = colour,
            fontSize = Tokens.Strip.LabelSize,
            fontWeight = FontWeight.Bold
        )
    }
}

/**
 * UTC hh:mm:ss for a slot boundary, for the slot headers.
 *
 * Arithmetic rather than a date formatter: this is a UTC wall-clock label on a time
 * we have already corrected ourselves, and routing it through the device's locale
 * and time zone would only give something else a chance to shift it.
 */
private fun utcClock(epochMs: Long): String {
    val seconds = Math.floorDiv(epochMs, 1000L)
    return "%02d:%02d:%02d".format(
        Math.floorMod(Math.floorDiv(seconds, 3600L), 24L),
        Math.floorMod(Math.floorDiv(seconds, 60L), 60L),
        Math.floorMod(seconds, 60L)
    )
}

/**
 * The TX test controls: hold to transmit, never latching.
 *
 * Two buttons with very different consequences, so they look different.
 *
 * **Self test** encodes a message, synthesises it and decodes it back. No radio, no
 * transmission; it proves the encoder, the modulator and the decoder agree.
 *
 * **Hold to transmit** keys the radio only after a finger has stayed on it for
 * [HOLD_TO_KEY_MS], then for as long as it stays down. It is `onPress` +
 * `tryAwaitRelease`, deliberately not a toggle: the setup TX test cannot latch, and
 * a button that transmits while held cannot be left on by accident or by a crash.
 * Letting go, the watchdog in the PTT, and the end of the generated audio each stop
 * it independently.
 *
 * The wait is there because a scroll of the Settings page that starts on this button
 * is a press too: before it, the radio keyed for about half a second until the
 * scroll took the gesture over (seen on a release build). A scroll cancels the press
 * well inside the wait, so it never keys.
 */
@Composable
internal fun TxTestRow(
    tx: TxSession,
    rig: RigSession,
    profile: StationProfile,
    modifier: Modifier = Modifier
) {
    // True between the touch and the key-down: the button shows it is about to key.
    var waiting by remember { mutableStateOf(false) }
    Column(modifier = modifier.fillMaxWidth()) {
        // The TX report is NOT here. It is many lines long after a transmission and
        // nothing below the feed may change height: see TxReport.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Tokens.Settings.Inner),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // The same message hold-to-TX sends, from the station profile; so, like it,
            // only once a callsign and grid are set. It never reaches the radio.
            SettingsButton(
                text = stringResource(R.string.tx_self_test),
                onClick = { tx.runSelfTest(testMessage(profile), TEST_BASE_HZ) },
                enabled = profile.canTransmit,
                modifier = Modifier.weight(1f).heightIn(min = Tokens.MinTouchTarget)
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = Tokens.MinTouchTarget)
                    .background(
                        when {
                            tx.transmitting -> Tokens.StripColors.Hot
                            waiting -> Tokens.StripColors.Strong
                            else -> Tokens.Settings.TransmitButton
                        },
                        RoundedCornerShape(Tokens.Settings.ButtonRadius)
                    )
                    // Cannot transmit without a callsign and grid: the gesture is not
                    // even attached until the profile is complete.
                    .pointerInput(rig.connected, profile.canTransmit) {
                        if (!profile.canTransmit) return@pointerInput
                        detectTapGestures(
                            onPress = {
                                coroutineScope {
                                    waiting = true
                                    // Keys only if the press lasts HOLD_TO_KEY_MS; a lift
                                    // or a scroll before then cancels this first.
                                    val keying = launch {
                                        delay(HOLD_TO_KEY_MS)
                                        waiting = false
                                        tx.startHoldTransmit(
                                            ptt = rig.ptt(),
                                            message = testMessage(profile),
                                            baseHz = TEST_BASE_HZ,
                                            amplitude = TxSession.DEFAULT_AMPLITUDE,
                                            swrAbort = profile.effectiveSwrAbort,
                                            meters = rig.meters()?.let { read -> TxSession.MeterSource { sub -> read(sub) } },
                                            // The hold-to-TX test: allowed in FT4OK
                                            // before its automatic TX is.
                                            holdTest = true
                                        )
                                    }
                                    // Suspends until the finger lifts or the gesture is
                                    // cancelled; either way we stop. This is the whole
                                    // reason it is a press rather than a click. In a
                                    // finally because leaving the screen mid-press (a tab
                                    // swipe) cancels this coroutine instead of returning.
                                    try {
                                        tryAwaitRelease()
                                    } finally {
                                        keying.cancel()
                                        waiting = false
                                        tx.stopHoldTransmit()
                                    }
                                }
                            }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = tx.live?.let { m ->
                        // While transmitting the button shows the radio's own meters -
                        // the only thing that can tell RF from no RF.
                        "%s  SWR %s".format(
                            m.watts?.let { "%.0f W".format(it) } ?: "-- W",
                            m.swr?.let { "%.1f".format(it) } ?: "--"
                        )
                    } ?: stringResource(
                        when {
                            tx.transmitting -> R.string.tx_transmitting
                            waiting -> R.string.tx_hold_waiting
                            else -> R.string.tx_hold
                        }
                    ),
                    color = if (tx.transmitting) Color.White else Tokens.Settings.TransmitInk,
                    fontWeight = FontWeight.Bold,
                    fontSize = Tokens.Settings.ButtonTextSize
                )
            }
        }
    }
}

/**
 * The test transmission: the operator's own callsign, twice, with their grid.
 *
 * **Not a CQ, deliberately**. A CQ solicits replies, and a test
 * transmission that gets answered wastes a stranger's time; "CQ TEST" is worse
 * still, since it announces a contest. `VE4OK VE4OK FN25` is a valid standard
 * message that asks for nothing - anyone who sees the grid and wants a contact can
 * still call, which is the only response worth having.
 *
 * **And it is the operator's callsign.** A placeholder taken from decoded traffic
 * would, on the air, transmit under somebody else's call. Never seed a
 * transmittable string from decoded traffic.
 *
 * Built from the station profile, so the app can only ever send OUR callsign. The
 * self test uses it too.
 */
private fun testMessage(profile: StationProfile): String =
    "${profile.normalisedCallsign} ${profile.normalisedCallsign} ${profile.normalisedGrid}"

/** Mid-band, inside the TX window, clear of the edges. */
private const val TEST_BASE_HZ = 1500.0

/** How long a finger must stay on HOLD TO TX before the radio keys; a scroll cancels sooner. */
private const val HOLD_TO_KEY_MS = 500L

/**
 * Station setup: who we are, and the limit we transmit within.
 *
 * Shown expanded until a callsign and grid exist, because **the app cannot transmit
 * without them** and there is deliberately no default — a placeholder callsign would
 * be somebody else's, which this project nearly transmitted once already. Once set,
 * it collapses to a single line so it stops taking space from the band.
 */
@Composable
internal fun StationSetup(
    settings: SettingsStore,
    log: LogSession?,
    modifier: Modifier = Modifier,
    /** False in Settings, where the fields are always open and need no header line. */
    collapsible: Boolean = true
) {
    val profile = settings.profile
    var expanded by remember(profile.canTransmit) { mutableStateOf(!collapsible || !profile.canTransmit) }

    Column(modifier = modifier.fillMaxWidth().padding(horizontal = Tokens.Settings.Inner)) {
        if (collapsible) Row(
            modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (profile.canTransmit) {
                    "%s  %s  SWR %.1f".format(
                        profile.normalisedCallsign, profile.normalisedGrid, profile.effectiveSwrAbort
                    )
                } else {
                    stringResource(R.string.setup_needed)
                },
                color = if (profile.canTransmit) Tokens.StripColors.Label else Tokens.StripColors.Strong,
                fontSize = Tokens.Strip.LabelSize,
                fontFamily = FontFamily.Monospace,
                fontWeight = if (profile.canTransmit) FontWeight.Normal else FontWeight.Bold
            )
            Text(
                text = stringResource(if (expanded) R.string.setup_hide else R.string.setup_edit),
                color = Tokens.StripColors.Occupied,
                fontSize = Tokens.Strip.LabelSize,
                fontFamily = FontFamily.Monospace
            )
        }

        if (!expanded) return@Column

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = profile.callsign,
                onValueChange = settings::setCallsign,
                label = { Text(stringResource(R.string.setup_callsign), fontSize = Tokens.Settings.HelpSize) },
                singleLine = true,
                textStyle = LocalTextStyle.current.copy(
                    fontFamily = FontFamily.Monospace, color = Tokens.StripColors.Frequency
                ),
                modifier = Modifier.weight(1.4f)
            )
            OutlinedTextField(
                value = profile.grid,
                onValueChange = settings::setGrid,
                label = { Text(stringResource(R.string.setup_grid), fontSize = Tokens.Settings.HelpSize) },
                singleLine = true,
                textStyle = LocalTextStyle.current.copy(
                    fontFamily = FontFamily.Monospace, color = Tokens.StripColors.Frequency
                ),
                modifier = Modifier.weight(1f)
            )
        }

        // The grid is typed, never taken from GPS: the app has no location permission
        // and rule 5 says it never will.
        profile.blocker?.let { blocker ->
            Text(
                text = stringResource(
                    when (blocker) {
                        StationProfile.Blocker.NO_CALLSIGN -> R.string.setup_no_callsign
                        StationProfile.Blocker.BAD_CALLSIGN -> R.string.setup_bad_callsign
                        StationProfile.Blocker.NO_GRID -> R.string.setup_no_grid
                        StationProfile.Blocker.BAD_GRID -> R.string.setup_bad_grid
                    }
                ),
                color = Tokens.StripColors.Strong,
                fontSize = Tokens.Settings.HelpSize,
                modifier = Modifier.padding(top = 2.dp)
            )
        }

        log?.let { LogFolderRow(it) }
    }
}

/**
 * The SWR abort limit, in Settings > Radio. Adjustable because 2.0 is a sensible
 * default and not a law - a compromise antenna in the field can sit higher and
 * still be worth using.
 */
@Composable
internal fun SwrLimitRow(settings: SettingsStore) {
    val profile = settings.profile
    // The label and value on one line, the slider full width under it.
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Tokens.Settings.Inner, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.setup_swr),
            color = Tokens.Settings.Text,
            fontSize = Tokens.Settings.TextSize,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = "%.1f".format(profile.effectiveSwrAbort),
            color = Tokens.Settings.Text,
            fontSize = Tokens.Settings.TextSize,
            fontWeight = FontWeight.Medium
        )
    }
    Slider(
        value = profile.effectiveSwrAbort.toFloat(),
        onValueChange = { settings.setSwrAbort(it.toDouble()) },
        valueRange = StationProfile.MIN_SWR_ABORT.toFloat()..StationProfile.MAX_SWR_ABORT.toFloat(),
        // One step per tenth, so the value is predictable rather than analogue. The
        // step dots are hidden: 14 of them read as noise, and the value is shown above.
        steps = ((StationProfile.MAX_SWR_ABORT - StationProfile.MIN_SWR_ABORT) * 10).toInt() - 1,
        colors = appSliderColors(ticks = false),
        // Settings is a swipe tab too: a drag here moves the slider, never the tab.
        modifier = Modifier.fillMaxWidth().noTabSwipe().padding(horizontal = Tokens.Settings.Inner)
    )
}

/**
 * Arm and Halt.
 *
 * **Halt is always in the same place and always works** — it is present whether or
 * not TX is armed, whether or not anything is transmitting, and it is the widest
 * target on the row. A control that is only sometimes there is one the operator has
 * to look for, and Halt is pressed when there is no time to look.
 *
 * Arm is the opposite: deliberately plain, never a default, and it says what state
 * it is in rather than what it will do, because a button that reads TRANSMIT when
 * transmitting is already armed is how people key radios by accident.
 */
@Composable
internal fun ArmRow(
    autoTx: AutoTx,
    profile: StationProfile,
    onArm: () -> Unit,
    onHalt: () -> Unit,
    onCq: () -> Unit,
    onPick: (QsoEngine.Step?) -> Unit,
    /**
     * What the button beside ⋯ does and says: `CQ POTA`,
     * `CALL W1AW` or `TEXT`. [onCq] is its press. Plain CQ when not given.
     */
    button: DockButton.State = DockButton.State(DockButton.Kind.CQ, "", enabled = profile.canTransmit, lit = autoTx.callingCq),
    moreOpen: Boolean = false,
    /** Opens and closes the CQ ⋯ panel. */
    onMore: () -> Unit = {},
    /** Contest mode as set now, for the pick labels. */
    contest: ContestSetup? = null,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        // What it is doing, or why it is not. Silence should always be explicable.
        val status = when {
            autoTx.transmitting -> stringResource(R.string.tx_auto_transmitting, autoTx.lastSent ?: "")
            autoTx.armed -> autoTx.nextMessage?.let { message ->
                // Armed with something to say and still silent: say what is holding
                // it. Waiting for our own slot is normal and needs no explanation,
                // but a frequency the guard will not transmit on is not, and used to
                // read exactly the same as a transmission about to happen.
                when (autoTx.lastVerdict) {
                    TxScheduler.Verdict.OFF_FREQUENCY -> stringResource(
                        R.string.tx_auto_blocked, stringResource(R.string.tx_block_off_frequency)
                    )
                    TxScheduler.Verdict.FREQUENCY_UNKNOWN -> stringResource(
                        R.string.tx_auto_blocked, stringResource(R.string.tx_block_frequency_unknown)
                    )
                    // Called from the panel and not heard since: say so, so a call
                    // nobody has heard never reads like an answer.
                    else -> if (autoTx.partnerUnheard && autoTx.qso != null) {
                        stringResource(R.string.tx_unheard_will_send, autoTx.qso!!.partner, message)
                    } else {
                        stringResource(R.string.tx_auto_will_send, message)
                    }
                }
            } ?: stringResource(R.string.tx_auto_armed_idle)
            // Why it went off comes first: since disarming keeps the message, "ready
            // to send" alone hid the reason (on air).
            autoTx.nextMessage != null -> autoTx.disarmReason?.let {
                stringResource(R.string.tx_auto_disarmed_ready, it, autoTx.nextMessage!!)
            } ?: stringResource(R.string.tx_auto_ready, autoTx.nextMessage!!)
            autoTx.disarmReason != null -> stringResource(R.string.tx_auto_disarmed, autoTx.disarmReason!!)
            else -> stringResource(R.string.tx_auto_off)
        }
        // Who is next in a CQ run, on the same line (no extra line). Left off when the message already names them.
        val next = autoTx.nextCaller?.takeIf { call -> autoTx.callingCq && !Ft8Message.mentions(status, call) }
        Text(
            text = next?.let { stringResource(R.string.tx_with_next, status, it) } ?: status,
            color = if (autoTx.armed) Tokens.StripColors.Marker else Tokens.StripColors.Label,
            fontSize = Tokens.Strip.LabelSize,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(horizontal = Tokens.Strip.Padding, vertical = 2.dp)
        )

        MessagePicker(autoTx, onPick, contest, Modifier.padding(bottom = Tokens.Dock.PickGap))

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Tokens.Strip.Padding),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = onArm,
                enabled = profile.canTransmit,
                shape = RoundedCornerShape(6.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (autoTx.armed) Tokens.StripColors.Hot else Tokens.StripColors.TxBand,
                    contentColor = Color.White
                ),
                modifier = Modifier.weight(1f).heightIn(min = Tokens.MinTouchTarget)
            ) {
                Text(
                    text = stringResource(if (autoTx.armed) R.string.tx_armed else R.string.tx_arm),
                    fontSize = Tokens.Strip.LabelSize,
                    fontWeight = FontWeight.Bold
                )
            }

            // Calling CQ: picks what to send, like tapping a station, and never arms.
            // The ⋯ joined to it opens the panel for the CQ word, a call and text;
            // it takes a slice of CQ's width, so ARM and HALT
            // keep their sizes and places.
            Row(
                modifier = Modifier.weight(1.25f),
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                val cqColour = if (button.lit) Tokens.StripColors.Occupied else Tokens.StripColors.TxBand
                Button(
                    onClick = onCq,
                    enabled = button.enabled,
                    shape = RoundedCornerShape(topStart = 6.dp, bottomStart = 6.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = cqColour, contentColor = Color.White),
                    contentPadding = PaddingValues(horizontal = 4.dp),
                    modifier = Modifier.weight(1f).heightIn(min = Tokens.MinTouchTarget)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = when (button.kind) {
                                DockButton.Kind.CQ -> when {
                                    button.label.isNotEmpty() -> stringResource(R.string.tx_cq_word, button.label)
                                    button.lit -> stringResource(R.string.tx_cq_on)
                                    else -> stringResource(R.string.tx_cq)
                                }
                                DockButton.Kind.CALL -> if (button.label.isEmpty()) {
                                    stringResource(R.string.tx_call_empty)
                                } else {
                                    stringResource(R.string.tx_call_button, button.label)
                                }
                                DockButton.Kind.TEXT -> stringResource(R.string.tx_text_button)
                            },
                            fontSize = Tokens.Strip.LabelSize,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                        // Contest mode is on: a second line,
                        // on every tab and whatever the button does. Not a control:
                        // the whole button still does what its first line says.
                        if (button.contest) {
                            Text(
                                text = stringResource(R.string.tx_contest_line),
                                color = Tokens.Dock.ContestLine,
                                fontSize = Tokens.Dock.ContestLineSize,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1
                            )
                        }
                    }
                }
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .width(Tokens.Dock.MoreWidth)
                        .heightIn(min = Tokens.MinTouchTarget)
                        .background(
                            if (moreOpen) Tokens.StripColors.Faint else cqColour,
                            RoundedCornerShape(topEnd = 6.dp, bottomEnd = 6.dp)
                        )
                        .then(
                            if (moreOpen) Modifier.border(
                                2.dp, Tokens.StripColors.Occupied, RoundedCornerShape(topEnd = 6.dp, bottomEnd = 6.dp)
                            ) else Modifier
                        )
                        .clickable(onClickLabel = stringResource(R.string.more_open), onClick = onMore)
                ) {
                    Text("⋯", color = Color.White, fontSize = Tokens.Strip.FrequencySize, fontWeight = FontWeight.Bold)
                }
            }

            // Always present, always the same place, always enabled.
            Button(
                onClick = onHalt,
                shape = RoundedCornerShape(6.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Tokens.StripColors.Hot,
                    contentColor = Color.White
                ),
                modifier = Modifier.weight(1.4f).heightIn(min = Tokens.MinTouchTarget)
            ) {
                Text(
                    text = stringResource(R.string.tx_halt),
                    fontSize = Tokens.Strip.FrequencySize,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

/**
 * Which message of the contact goes next: GRID, the report, the close, or AUTO.
 * The report and close buttons say exactly what they
 * would send (`R-09`, `73`). A pick is pinned and lit until AUTO; in auto, AUTO is
 * lit along with the message the sequence will send.
 *
 * Live during our own transmission too: a pick early in it restarts the
 * transmission with the new message, a later one waits for our next slot. Always present and a fixed height, disabled only with nobody to
 * address, so nothing below the feed moves (see TxReport). Picking does not arm.
 */
@Composable
private fun MessagePicker(
    autoTx: AutoTx,
    onPick: (QsoEngine.Step?) -> Unit,
    contest: ContestSetup?,
    modifier: Modifier = Modifier
) {
    val enabled = autoTx.pickerPartner != null
    val selected = autoTx.queuedStep
    val pinned = autoTx.pinned
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = Tokens.Strip.Padding),
        horizontalArrangement = Arrangement.spacedBy(Tokens.Dock.PickGap)
    ) {
        for (step in QsoEngine.Step.entries + listOf(null)) {
            val label = step?.let { autoTx.pickLabel(it, contest) }
            val stepEnabled = enabled && when (step) {
                // A report is never made up: off until we have measured them.
                QsoEngine.Step.REPORT -> label != null
                // AUTO does something only while a pick is pinned.
                null -> pinned != null
                else -> true
            }
            val on = enabled && if (step == null) pinned == null else step == selected
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1f)
                    .height(Tokens.Dock.PickHeight)
                    .background(
                        if (on) Tokens.StripColors.Occupied else Tokens.StripColors.OtherLaneBackground,
                        RoundedCornerShape(4.dp)
                    )
                    .then(if (stepEnabled) Modifier.clickable { onPick(step) } else Modifier)
            ) {
                Text(
                    text = label ?: stringResource(
                        when (step) {
                            QsoEngine.Step.GRID -> R.string.pick_grid
                            QsoEngine.Step.REPORT -> R.string.pick_report
                            QsoEngine.Step.CLOSE -> R.string.pick_close
                            null -> R.string.pick_auto
                        }
                    ),
                    color = when {
                        on -> Color.White
                        stepEnabled || step == null && enabled -> Tokens.StripColors.Frequency
                        else -> Tokens.StripColors.TxBand
                    },
                    fontSize = Tokens.Strip.LabelSize,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
            }
        }
    }
}

/**
 * "Logged VE6XA - Undo", offered for [LogSession.UNDO_WINDOW_MS] after a contact is
 * logged. A row with the banners rather than a snackbar: rule 9
 * keeps everything that appears on its own above the list, never over it.
 */
@Composable
internal fun LoggedRow(logged: LogSession.Logged, onUndo: () -> Unit, onDismiss: () -> Unit) {
    LaunchedEffect(logged) {
        delay(LogSession.UNDO_WINDOW_MS)
        onDismiss()
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Tokens.Strip.Padding, vertical = 3.dp)
            .background(Tokens.StripColors.Occupied.copy(alpha = 0.18f), RoundedCornerShape(4.dp))
            .padding(start = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = stringResource(if (logged.deleted != null) R.string.log_deleted else R.string.log_logged, logged.call),
            color = Tokens.StripColors.Occupied,
            fontSize = Tokens.Strip.LabelSize,
            fontWeight = FontWeight.Bold
        )
        Box(
            modifier = Modifier
                .heightIn(min = Tokens.MinTouchTarget)
                .clickable(onClick = onUndo)
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stringResource(R.string.log_undo),
                color = Tokens.StripColors.Strong,
                fontSize = Tokens.Strip.LabelSize,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/**
 * Where the ADIF file goes. The system folder picker grants access to that one
 * folder, so no storage permission is involved (rule 1).
 */
@Composable
internal fun LogFolderRow(log: LogSession) {
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(log::setFolder)
    }
    // Label on the left, the folder under it, Choose on the right.
    SettingRow(
        label = stringResource(R.string.setup_log_folder),
        help = log.folderLabel ?: stringResource(R.string.setup_log_folder_none)
    ) {
        SettingsButton(
            text = stringResource(R.string.setup_log_folder_choose),
            onClick = { pick.launch(log.pickerStart) }
        )
    }
}
