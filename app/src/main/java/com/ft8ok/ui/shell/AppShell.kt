package com.ft8ok.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ft8ok.R
import com.ft8ok.log.EntityLookup
import com.ft8ok.logbook.LogSession
import com.ft8ok.logbook.NotLogged
import com.ft8ok.model.SlotParity
import com.ft8ok.qso.QsoEngine
import com.ft8ok.rig.RigSession
import com.ft8ok.rx.RxSession
import com.ft8ok.settings.SettingsStore
import com.ft8ok.time.TimeEngine
import com.ft8ok.tx.AutoTx
import com.ft8ok.tx.TxSession
import com.ft8ok.ui.log.LogScreen
import com.ft8ok.ui.map.MapScreen
import com.ft8ok.ui.radio.ArmRow
import com.ft8ok.ui.radio.LiveScreen
import com.ft8ok.ui.radio.DockButton
import com.ft8ok.ui.radio.MoreDraft
import com.ft8ok.ui.radio.MorePanel
import com.ft8ok.ui.radio.MoreTab
import com.ft8ok.ui.radio.callPlan
import com.ft8ok.ui.radio.textVerdict
import com.ft8ok.ui.radio.toQueued
import com.ft8ok.ui.roster.RosterScreen
import com.ft8ok.ui.settings.SettingsScreen
import com.ft8ok.ui.theme.Tokens
import kotlinx.coroutines.delay

/** The bottom-nav destinations. Live is the home screen. */
enum class Tab { LIVE, ROSTER, MAP, LOG, SETTINGS }

/**
 * The frame every tab sits in: the top bar, the tab, the TX dock and the bottom nav.
 *
 * **The dock is on every tab**, so HALT is in the same
 * place whichever screen the operator is on - rule 3 says Halt is always in the same
 * place and always works, and a Halt that exists only on Live is not.
 */
@Composable
fun AppShell(
    session: RxSession,
    rig: RigSession,
    tx: TxSession,
    settings: SettingsStore,
    autoTx: AutoTx,
    log: LogSession,
    onArm: () -> Unit,
    onHalt: () -> Unit,
    onCq: () -> Unit,
    /** A message to pin, or null for AUTO. */
    onPick: (QsoEngine.Step?) -> Unit,
    onCall: (RxSession.DecodeRow) -> Unit,
    /** The CQ ⋯ panel: it only queues, never transmits. */
    onCqWord: (String) -> Unit,
    onCallSign: (call: String, txIn: SlotParity, heard: Boolean) -> Unit,
    onText: (AutoTx.QueuedText) -> Unit,
    cqMessage: (String?) -> String,
    callMessage: (String) -> String?,
    hasPermission: Boolean,
    onRequestPermission: () -> Unit,
    onConnectRadio: () -> Unit,
    onDisconnectRadio: () -> Unit,
    onReceiveOn: (Boolean) -> Unit,
    onDetailedLogs: (Boolean) -> Unit,
    /** Once a second, for whatever must be retried while the app is up. */
    onTick: () -> Unit = {},
    /** Contacts with both reports and no log entry, for the Log tab. */
    notLoggedEntries: () -> List<NotLogged.Entry> = { emptyList() },
    onForceLog: (NotLogged.Entry) -> Unit = {},
    /** Callsign to country, once cty.dat has loaded (ADR 0012). */
    entities: EntityLookup = EntityLookup.NONE
) {
    var tab by rememberSaveable { mutableStateOf(Tab.LIVE) }
    var moreOpen by rememberSaveable { mutableStateOf(false) }
    // Held here, not in the panel, so what was typed survives switching tabs and
    // closing the panel, and the dock button can act on it.
    val draft = remember { MoreDraft() }
    val swipeGate = remember { TabSwipeGate() }

    // One tick a second: ages, the countdown and the TX parity all move on their own,
    // and a strip that only redraws when a slot decodes would quietly lie about how
    // old its own data is.
    LaunchedEffect(Unit) {
        while (true) {
            session.tick()
            onTick()
            delay(1_000)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Tokens.StripColors.Screen)
            // targetSdk 35 forces edge-to-edge (ADR 0007): without this the nav sits
            // under the system navigation bar.
            .safeDrawingPadding()
    ) {
        TopBar(session, rig, settings, autoTx, onAddCustom = { tab = Tab.SETTINGS })

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                // Swipe sideways for the neighbouring tab. Children
                // see the drag first, so the Map's pan and the feed's vertical scroll
                // keep theirs. The sliders did not (Roster, on the phone), so
                // a drag that starts on one is marked with noTabSwipe and ignored here.
                .pointerInput(Unit) {
                    val threshold = Tokens.Nav.SwipeDistance.toPx()
                    var dx = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { dx = 0f },
                        onDragEnd = {
                            val step = when {
                                dx <= -threshold -> 1
                                dx >= threshold -> -1
                                else -> 0
                            }
                            val next = tab.ordinal + step
                            if (step != 0 && next in Tab.entries.indices) tab = Tab.entries[next]
                        },
                        onHorizontalDrag = { change, amount ->
                            // A slider's drag: neither counted nor consumed.
                            if (change.id != swipeGate.exempt) {
                                // Consumed, so a press underneath (hold-to-TX) is
                                // cancelled rather than left running.
                                change.consume()
                                dx += amount
                            }
                        }
                    )
                }
        ) {
            CompositionLocalProvider(LocalTabSwipeGate provides swipeGate) { when (tab) {
                Tab.LIVE -> LiveScreen(
                    session = session,
                    rig = rig,
                    settings = settings,
                    autoTx = autoTx,
                    log = log,
                    onCall = onCall,
                    hasPermission = hasPermission,
                    onRequestPermission = onRequestPermission,
                    onConnectRadio = onConnectRadio,
                    onOpenSettings = { tab = Tab.SETTINGS },
                    entities = entities
                )
                Tab.ROSTER -> RosterScreen(session, settings, onCall, entities, log.contacts)
                Tab.MAP -> MapScreen(session, settings, autoTx, onCall)
                Tab.LOG -> LogScreen(log, notLoggedEntries(), onForceLog)
                Tab.SETTINGS -> SettingsScreen(
                    session = session,
                    rig = rig,
                    tx = tx,
                    settings = settings,
                    log = log,
                    onConnectRadio = onConnectRadio,
                    onDisconnectRadio = onDisconnectRadio,
                    onReceiveOn = onReceiveOn,
                    onDetailedLogs = onDetailedLogs
                )
            } }
        }

        // Over the bottom of the tab, directly above the dock, on every tab like the
        // dock. Queuing a call or a text closes it, so the feed shows what follows.
        if (moreOpen) {
            MorePanel(
                draft = draft,
                settings = settings,
                session = session,
                cqMessage = cqMessage,
                callMessage = callMessage,
                onCqWord = onCqWord,
                onClose = { moreOpen = false }
            )
        }

        // The button beside ⋯ does what the panel was left on: it queues, and ARM transmits.
        val plan = if (draft.tab == MoreTab.CALL) callPlan(draft, session, callMessage) else null
        val queuedText = if (draft.tab == MoreTab.TEXT) textVerdict(draft.text).toQueued() else null
        val button = DockButton.resolve(
            tab = draft.tab,
            canTransmit = settings.profile.canTransmit,
            cqWord = settings.cqWord,
            callingCq = autoTx.callingCq,
            readyCall = plan?.call,
            partner = autoTx.qso?.partner,
            textReady = queuedText != null,
            textQueued = autoTx.text != null,
            contestCqWord = settings.contest?.cqWord
        )
        val onButton = {
            when (DockButton.press(button)) {
                DockButton.Press.START_CQ, DockButton.Press.STOP_CQ -> onCq()
                DockButton.Press.CALL -> plan?.let { onCallSign(it.call, it.txIn, it.heard != null) }
                DockButton.Press.QUEUE_TEXT -> queuedText?.let(onText)
                DockButton.Press.DROP_TEXT -> autoTx.dropText()
                DockButton.Press.NOTHING -> {}
            }
            moreOpen = false
        }

        // Fixed height whatever it says: nothing below the tab may move (see TxReport).
        ArmRow(
            autoTx = autoTx,
            profile = settings.profile,
            onArm = onArm,
            onHalt = onHalt,
            onCq = onButton,
            onPick = onPick,
            button = button,
            moreOpen = moreOpen,
            onMore = { moreOpen = !moreOpen },
            contest = settings.contest,
            modifier = Modifier
                .background(Tokens.StripColors.Background)
                .padding(top = 2.dp, bottom = 6.dp)
        )

        BottomNav(tab) { tab = it }
    }
}

/**
 * One line, every tab: where the radio is, whether the clock is with the
 * band, and the slot we are in with its seconds left. The rig line and level meter
 * that used to sit here are in Settings > Radio and come back as banners on Live
 * only when something is wrong.
 */
@Composable
private fun TopBar(
    session: RxSession,
    rig: RigSession,
    settings: SettingsStore,
    autoTx: AutoTx,
    onAddCustom: () -> Unit
) {
    val now = session.clockTick.takeIf { it > 0 } ?: System.currentTimeMillis()
    val mode = session.mode
    val parity = mode.parityOf(mode.slotStartMs(now))
    val secondsLeft = ((mode.slotMs - mode.positionInSlotMs(now)) + 999) / 1000
    val engine = session.timeEngine
    val state = rig.state

    // Fixed cells, measured in the phone's font: its "monospace" is proportional, so
    // padded text moved everything left of it with every digit.
    // Minimum widths, so a clock over 10 s off grows its cell rather than being cut.
    val clockStyle = TextStyle(fontSize = Tokens.Strip.LabelSize, fontFamily = FontFamily.Monospace)
    val slotStyle = TextStyle(fontSize = Tokens.Strip.FrequencySize, fontFamily = FontFamily.Monospace)
    val even = stringResource(R.string.top_even)
    val odd = stringResource(R.string.top_odd)
    val phone = stringResource(R.string.top_clock_phone)
    val widestTx = listOf(8.88, -8.88).map { stringResource(R.string.top_tx_shift, it) }
    val widestBand = listOf(8.88, -8.88).map { stringResource(R.string.top_band_offset, it) }
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val cells = remember(measurer, density, even, odd, phone, widestTx, widestBand) {
        fun widthOf(values: List<String>, style: TextStyle) =
            with(density) { values.maxOf { measurer.measure(it, style).size.width }.toDp() }
        val digits = (0..9).map { "$it" }
        TopBarCells(
            txShift = widthOf(widestTx, clockStyle),
            band = widthOf(widestBand, clockStyle),
            clock = widthOf(digits.flatMap { listOf("+$it.$it${it}s", "-$it.$it${it}s") } + phone, clockStyle),
            parity = widthOf(listOf(even, odd), slotStyle),
            seconds = widthOf(digits.map { "$it$it" }, slotStyle)
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Tokens.StripColors.Background)
            .padding(horizontal = Tokens.Strip.Padding, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // No callsign here: a long or compound call pushed the slot
        // countdown off narrow screens.
        FrequencyMenu(rig, settings, onAddCustom)
        Spacer(Modifier.weight(1f))
        // How far our transmission is moved from band time for the partner (TxClock),
        // while there is one. Leftmost, so its coming and going moves nothing else.
        if (autoTx.qso?.partner != null) {
            Text(
                text = stringResource(R.string.top_tx_shift, autoTx.txShiftMs() / 1000.0),
                color = Tokens.StripColors.Label,
                style = clockStyle,
                textAlign = TextAlign.End,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.widthIn(min = cells.txShift)
            )
        }
        // The band against our clock in the newest slot that gave a consensus, before
        // that slot's correction. Blank unless fresh; the cell stays so nothing moves.
        val bandResidual = engine.lastEstimate?.residualSec?.takeIf { engine.sync == TimeEngine.Sync.SYNCED }
        Text(
            text = bandResidual?.let { stringResource(R.string.top_band_offset, it) } ?: "",
            color = Tokens.StripColors.Label,
            style = clockStyle,
            textAlign = TextAlign.End,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.widthIn(min = cells.band)
        )
        Box(
            modifier = Modifier
                .size(7.dp)
                .background(
                    when (engine.sync) {
                        TimeEngine.Sync.SYNCED -> Tokens.StripColors.Marker
                        TimeEngine.Sync.HOLDING, TimeEngine.Sync.TENTATIVE -> Tokens.StripColors.Strong
                        TimeEngine.Sync.NONE -> Tokens.StripColors.TxBand
                    },
                    CircleShape
                )
        )
        Text(
            text = if (engine.sync == TimeEngine.Sync.NONE) stringResource(R.string.top_clock_phone)
            else "%+.2fs".format(engine.offsetMs / 1000.0),
            color = Tokens.StripColors.Label,
            style = clockStyle,
            textAlign = TextAlign.End,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.widthIn(min = cells.clock)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Strip.CountdownGap)) {
            Text(
                text = if (parity == SlotParity.EVEN) even else odd,
                color = Tokens.StripColors.Frequency,
                style = slotStyle,
                textAlign = TextAlign.End,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.width(cells.parity)
            )
            Text(
                text = secondsLeft.toString(),
                color = Tokens.StripColors.Frequency,
                style = slotStyle,
                textAlign = TextAlign.End,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.width(cells.seconds)
            )
        }
    }
}

/** Widths of the top bar's number cells, measured once from their widest values. */
private data class TopBarCells(val txShift: Dp, val band: Dp, val clock: Dp, val parity: Dp, val seconds: Dp)

/** Dial to the kHz for the top bar, e.g. 7.074; the full Hz reading is in Settings. */
internal fun shortMegahertz(hz: Long): String = "%d.%03d".format(hz / 1_000_000, (hz / 1_000) % 1_000)

@Composable
private fun BottomNav(selected: Tab, onSelect: (Tab) -> Unit) {
    HorizontalDivider(thickness = 1.dp, color = Tokens.StripColors.OtherLaneBackground)
    Row(modifier = Modifier.fillMaxWidth().background(Tokens.StripColors.Screen)) {
        for (tab in Tab.entries) {
            val on = tab == selected
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier
                    .weight(1f)
                    .height(Tokens.Nav.Height)
                    .clickable { onSelect(tab) }
            ) {
                Box(
                    modifier = Modifier
                        .width(18.dp)
                        .height(3.dp)
                        .background(
                            if (on) Tokens.StripColors.Marker else Tokens.StripColors.Screen,
                            RoundedCornerShape(2.dp)
                        )
                )
                Text(
                    text = stringResource(
                        when (tab) {
                            Tab.LIVE -> R.string.nav_live
                            Tab.ROSTER -> R.string.nav_roster
                            Tab.MAP -> R.string.nav_map
                            Tab.LOG -> R.string.nav_log
                            Tab.SETTINGS -> R.string.nav_settings
                        }
                    ),
                    color = if (on) Tokens.StripColors.Frequency else Tokens.StripColors.Label,
                    fontSize = Tokens.Strip.LabelSize,
                    fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}
