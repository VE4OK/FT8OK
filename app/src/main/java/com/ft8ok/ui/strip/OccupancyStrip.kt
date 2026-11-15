package com.ft8ok.ui.strip

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import com.ft8ok.R
import com.ft8ok.dsp.Occupancy
import com.ft8ok.dsp.TxFrequency
import com.ft8ok.model.SlotParity
import com.ft8ok.ui.theme.Tokens
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * What one parity's lane knows.
 *
 * @param map the occupancy for this parity, or null before anything is heard.
 * @param heardAgoMs how long ago this parity was last heard **while listening**.
 *   Not decoration. We are deaf in every slot we transmit in, so the lane for our
 *   own TX parity can be minutes old, and ADR 0013 requires that age to be visible:
 *   a confidently-drawn bar chart of stale data is worse than no chart.
 */
data class LaneState(
    val map: Occupancy.Map?,
    val heardAgoMs: Long?,
    /**
     * Where this lane would transmit (one pick per lane). Drawn on
     * both lanes, bright on the lane we transmit in and dim on the other.
     */
    val pickHz: Int? = null
)

/**
 * The occupancy strip.
 *
 * Two stacked thin lanes, one per slot parity, the parity we would transmit in
 * emphasised and the other dimmed — no toggle, because FT8 stations alternate slots
 * and the spectrum relevant to our TX is the *same* parity as our TX, 30 s ago
 * rather than 15 s. Averaging the two would blur two different populations of
 * stations into one and hide the gaps.
 *
 * It is deliberately not a waterfall. Its only job is to answer "where can I
 * transmit without sitting on someone", and to make the answer checkable at a glance
 * by someone who is cold, in a hurry, and in direct sunlight.
 *
 * **The lanes are labelled `ODD` and `EVEN`**.
 * They were `TX` and `RX`, which reads as "this lane is my transmission" when in
 * fact both lanes are received spectra and the top one is merely the parity we
 * happen to transmit in. Naming the parity outright says what the lane is, and stays
 * true when our TX parity changes. **Emphasis carries which parity is ours**, and
 * the row order never changes, so a glance always finds the same lane in the same
 * place.
 *
 * **Every row is laid out to the same horizontal insets**, so both lanes share one
 * frequency scale. They did not in the first version — the trailing labels differed
 * in width, which silently gave the two lanes different scales and made comparing
 * parities meaningless.
 *
 * A tap on either lane snaps to a clear gap in that lane and sets the frequency by
 * hand, for both lanes. The TX
 * frequency shows under the lane's name and Auto/Set under its age, on the lane we
 * transmit in; while it reads Set, a tap on either side cell goes back to Auto.
 */
@Composable
fun OccupancyStrip(
    evenLane: LaneState,
    oddLane: LaneState,
    /** The parity we would transmit in. Decides emphasis, nothing else. */
    txParity: SlotParity,
    txFrequencyHz: Int?,
    mode: TxFrequency.Mode,
    onTapFrequency: (Int) -> Unit,
    /** Back to Auto. The bottom line takes it only while the frequency is set by hand. */
    onTapAuto: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val frequencyLabel = txFrequencyHz
        ?.let { stringResource(R.string.strip_frequency, it) }
        ?: stringResource(R.string.strip_no_frequency)
    // Resolved here rather than inside `semantics { }`, which is not a composable
    // scope and cannot call stringResource.
    val description = stringResource(R.string.strip_a11y, frequencyLabel)
    // Digits only under the lane label: "Hz" does not fit the 36 dp column.
    val frequencyDigits = txFrequencyHz
        ?.let { stringResource(R.string.strip_frequency_digits, it) }
        ?: stringResource(R.string.strip_no_frequency)
    val modeWord = stringResource(
        if (mode == TxFrequency.Mode.FULL_AUTO) R.string.strip_mode_auto
        else R.string.strip_mode_user
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Tokens.Strip.Padding)
            .semantics { contentDescription = description }
    ) {
        for (parity in LANE_ORDER) {
            val isTxParity = parity == txParity
            val lane = if (parity == SlotParity.EVEN) evenLane else oddLane
            val name = stringResource(
                if (parity == SlotParity.EVEN) R.string.strip_lane_even else R.string.strip_lane_odd
            )

            // The TX frequency and its mode ride in the lane we transmit in: the
            // frequency under the parity name, Auto/Set under the age. Both side cells of that lane are the tap back
            // to Auto while the frequency is set by hand.
            val backToAuto = if (isTxParity && onTapAuto != null && mode == TxFrequency.Mode.USER_SET) {
                onTapAuto
            } else {
                null
            }
            AlignedRow(
                leading = {
                    LaneLabel(
                        name,
                        emphasised = isTxParity,
                        frequency = if (isTxParity) frequencyDigits else null
                    )
                },
                trailing = {
                    LaneAge(
                        lane.heardAgoMs,
                        modeWord = if (isTxParity) modeWord else null
                    )
                },
                onTapSides = backToAuto
            ) {
                Lane(
                    state = lane,
                    background = if (isTxParity) Tokens.StripColors.Background
                    else Tokens.StripColors.OtherLaneBackground,
                    dimmed = !isTxParity,
                    // Each lane's own pick, on the spectrum it was chosen from.
                    // The lane we transmit in shows the frequency we would use.
                    txFrequencyHz = if (isTxParity) txFrequencyHz else lane.pickHz,
                    mode = mode,
                    description = stringResource(
                        if (parity == SlotParity.EVEN) R.string.strip_a11y_lane_even
                        else R.string.strip_a11y_lane_odd,
                        ageLabel(lane.heardAgoMs)
                    ),
                    // Either lane takes a tap: a hand-set
                    // frequency applies to both lanes, so the tap snaps to a gap in the
                    // lane tapped. It was the lit lane only, and a partner held after a
                    // disarm kept ODD lit, so EVEN could never be tapped.
                    onTapFrequency = onTapFrequency
                )
            }
        }
    }
}

/**
 * Row order, top to bottom, and it does not change.
 *
 * Even before odd because that is the order they occur in within a minute (:00
 * then :15). Fixed rather than "ours on top": our TX parity alternates, and a strip
 * whose rows swap underneath a glance is a strip that gets misread. The labels say
 * which parity is which and the emphasis says which one is ours.
 */
private val LANE_ORDER = listOf(SlotParity.EVEN, SlotParity.ODD)

/**
 * One row of the strip: a fixed-width label, the plotted area, a fixed-width
 * trailing slot.
 *
 * The fixed widths are the point. Letting the trailing text size itself gives each
 * row a different plot width, and a frequency scale that changes between rows is
 * not a frequency scale.
 */
@Composable
private fun AlignedRow(
    leading: @Composable () -> Unit,
    trailing: @Composable () -> Unit,
    /** When set, both side cells (their full row height) take this tap. */
    onTapSides: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val sideTap = if (onTapSides != null) Modifier.clickable(onClick = onTapSides) else Modifier
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Tokens.Strip.LaneGap)
            .height(Tokens.Strip.LaneRowHeight),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.width(Tokens.Strip.LabelWidth).fillMaxHeight().then(sideTap),
            contentAlignment = Alignment.CenterStart
        ) { leading() }
        Box(Modifier.weight(1f)) { content() }
        Box(
            modifier = Modifier.width(Tokens.Strip.TrailingWidth).fillMaxHeight().then(sideTap),
            contentAlignment = Alignment.CenterEnd
        ) { trailing() }
    }
}

@Composable
private fun LaneLabel(text: String, emphasised: Boolean, frequency: String?) {
    Column {
        Text(
            text = text,
            color = if (emphasised) Tokens.StripColors.Frequency else Tokens.StripColors.Label,
            fontSize = Tokens.Strip.LabelSize,
            lineHeight = Tokens.Strip.LabelLineHeight,
            fontFamily = FontFamily.Monospace,
            fontWeight = if (emphasised) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
            softWrap = false
        )
        if (frequency != null) {
            Text(
                text = frequency,
                color = Tokens.StripColors.Frequency,
                fontSize = Tokens.Strip.FrequencySize,
                lineHeight = Tokens.Strip.FrequencyLineHeight,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                softWrap = false
            )
        }
    }
}

/**
 * One parity's bars.
 *
 * **Both lanes are the same size, always, whichever one is emphasised**. They were not: the emphasised lane was taller, so when the TX parity
 * alternated — which it does every slot — the two rows swapped heights and the whole
 * page below them lurched up and down. Emphasis is carried by brightness and the
 * label instead, which costs nothing and moves nothing. Equal heights also mean both
 * lanes plot to the same vertical scale, so a bar in one is directly comparable with
 * a bar in the other.
 *
 * The row is [Tokens.Strip.LaneRowHeight] tall while the bars are drawn at
 * [Tokens.Strip.LaneHeight] inside it, so the tap target is a little taller than the
 * bars. It is below [Tokens.MinTouchTarget] on purpose, traded for a smaller gap
 * between the lanes: 34 dp, full width. Chaining
 * `.height(h).defaultMinSize(...)` does not achieve this, because `height` fixes both
 * constraints and the minimum never applies. The non-tappable lane keeps the same row
 * height regardless — that is the point.
 *
 * [description] carries the lane's **age** as well as its name, because a canvas of
 * bars conveys nothing to a screen reader and the one fact that cannot be inferred
 * from the rest of the screen is how old this parity's reading is (ADR 0013).
 */
@Composable
private fun Lane(
    state: LaneState,
    background: Color,
    dimmed: Boolean,
    txFrequencyHz: Int?,
    mode: TxFrequency.Mode,
    description: String,
    onTapFrequency: ((Int) -> Unit)?
) {
    val density = LocalDensity.current
    val barPitchPx = remember(density) {
        with(density) { (Tokens.Strip.BarWidth + Tokens.Strip.BarGap).toPx() }
    }
    val barGapPx = remember(density) { with(density) { Tokens.Strip.BarGap.toPx() } }
    val markerWidthPx = remember(density) { with(density) { Tokens.Strip.MarkerWidth.toPx() } }
    val markerCapPx = remember(density) { with(density) { Tokens.Strip.MarkerCapHeight.toPx() } }

    // One height for every lane, tappable or not, emphasised or not.
    val rowHeight = Dp(max(Tokens.Strip.LaneRowHeight.value, Tokens.Strip.LaneHeight.value))

    val touchable = if (onTapFrequency != null) {
        Modifier.pointerInput(state.map) {
            detectTapGestures { offset ->
                val fraction = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                onTapFrequency(TxFrequency.snap(Occupancy.hzAt(fraction), state.map))
            }
        }
    } else {
        Modifier
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(rowHeight)
            .then(touchable)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxWidth().height(Tokens.Strip.LaneHeight)) {
            drawRect(color = background, size = size)

            val columnCount = max(1, (size.width / barPitchPx).roundToInt())
            val levels = state.map?.let { Occupancy.columns(it, columnCount) }
            val barWidth = size.width / columnCount - barGapPx

            if (levels != null) {
                for (col in 0 until columnCount) {
                    val db = levels[col]
                    if (db < Tokens.StripColors.FaintDb) continue
                    // Height carries the level as well as the colour: the same
                    // reading encoded twice survives sunlight washing out the hues.
                    val fraction = (db / Tokens.Strip.FullScaleDb).coerceIn(0.12f, 1f)
                    val barHeight = size.height * fraction
                    drawRect(
                        color = tierColour(db, dimmed),
                        topLeft = Offset(col * (barWidth + barGapPx), size.height - barHeight),
                        size = Size(barWidth, barHeight)
                    )
                }
            }

            // The marker goes on last, so a loud neighbour never buries it.
            if (txFrequencyHz != null) {
                val x = Occupancy.fractionOf(txFrequencyHz) * size.width
                val base =
                    if (mode == TxFrequency.Mode.USER_SET) Tokens.StripColors.MarkerUserSet
                    else Tokens.StripColors.Marker
                // The other lane's pick is dim, as its bars are: not where we transmit next.
                val colour = if (dimmed) base.copy(alpha = DIM_ALPHA) else base
                drawRect(
                    color = colour,
                    topLeft = Offset(x - markerWidthPx / 2f, 0f),
                    size = Size(markerWidthPx, size.height)
                )
                // A cap at the top, so the marker reads as "here" rather than as one
                // more bar in a chart already made of vertical lines.
                drawRect(
                    color = colour,
                    topLeft = Offset(x - markerCapPx, 0f),
                    size = Size(markerCapPx * 2f, markerWidthPx * 1.5f)
                )
            }
        }
    }
}

// No TX-band bar and no frequency axis under the lanes: the vertical space is worth
// more. The TX limit lives in core-dsp's Occupancy.TX_LOW_HZ/TX_HIGH_HZ.

/**
 * How old this lane's reading is, and under it, on the lane we transmit in, the TX
 * frequency's mode (Auto or Set).
 *
 * Shown per lane, and in amber past [STALE_AFTER_MS], rather than hidden: while
 * transmitting every cycle of our own parity this number is the only thing that
 * distinguishes a live reading from a four-minute-old one.
 */
@Composable
private fun LaneAge(ageMs: Long?, modeWord: String?) {
    val stale = ageMs != null && ageMs >= STALE_AFTER_MS
    Column(horizontalAlignment = Alignment.End) {
        Text(
            text = ageLabel(ageMs),
            color = if (stale) Tokens.StripColors.LabelStale else Tokens.StripColors.Label,
            fontSize = Tokens.Strip.LabelSize,
            lineHeight = Tokens.Strip.LabelLineHeight,
            fontFamily = FontFamily.Monospace,
            fontWeight = if (stale) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
            softWrap = false
        )
        if (modeWord != null) {
            Text(
                text = modeWord,
                color = Tokens.StripColors.Label,
                fontSize = Tokens.Strip.LabelSize,
                lineHeight = Tokens.Strip.FrequencyLineHeight,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                softWrap = false
            )
        }
    }
}

@Composable
private fun ageLabel(ageMs: Long?): String = when {
    ageMs == null -> stringResource(R.string.strip_no_data)
    ageMs < 1_000 -> stringResource(R.string.strip_age_now)
    ageMs < 60_000 -> stringResource(R.string.strip_age_seconds, ageMs / 1_000)
    else -> {
        val totalSeconds = ageMs / 1_000
        stringResource(
            R.string.strip_age_minutes,
            (totalSeconds / 60).toInt(),
            (totalSeconds % 60).toInt()
        )
    }
}

/**
 * When a lane's reading stops being worth trusting: four slots of its own parity, in
 * the app's mode (120 s FT8, 60 s FT4).
 *
 * A judgement call, not a derivation; it may change with more time on air.
 */
val STALE_AFTER_MS: Long = 4 * 2 * com.ft8ok.Flavor.MODE.slotMs

/** Tier a level, using the same dB thresholds the TX policy decides on. */
private fun tierColour(db: Float, dimmed: Boolean): Color {
    val base = when {
        db >= Tokens.StripColors.HotDb -> Tokens.StripColors.Hot
        db >= Tokens.StripColors.StrongDb -> Tokens.StripColors.Strong
        db >= Tokens.StripColors.OccupiedDb -> Tokens.StripColors.Occupied
        else -> Tokens.StripColors.Faint
    }
    return if (dimmed) base.copy(alpha = DIM_ALPHA) else base
}

/** How the lane we are not about to transmit in is dimmed: its bars and its marker. */
private const val DIM_ALPHA = 0.55f
