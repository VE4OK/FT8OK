package com.ft8ok.ui.radio

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.ft8ok.R
import com.ft8ok.dsp.Ft8Decoder
import com.ft8ok.model.SlotParity
import com.ft8ok.model.TxText
import com.ft8ok.rx.RxSession
import com.ft8ok.settings.SettingsStore
import com.ft8ok.tx.AutoTx
import com.ft8ok.ui.theme.Tokens

/** The panel's tabs. */
enum class MoreTab { CQ, CALL, TEXT }

/** The encoder's own answer for [message]; null if the native library is not loaded. */
internal fun encodingOf(message: String): TxText.Encoding? = when (Ft8Decoder.encoding(message)) {
    null -> null
    Ft8Decoder.ENCODING_FREE_TEXT -> TxText.Encoding.FREE_TEXT
    Ft8Decoder.ENCODING_STANDARD -> TxText.Encoding.STANDARD
    Ft8Decoder.ENCODING_NONSTANDARD_CALL -> TxText.Encoding.NONSTANDARD_CALL
    Ft8Decoder.ENCODING_OTHER -> TxText.Encoding.OTHER
    else -> TxText.Encoding.NONE
}

/**
 * What the panel holds between visits. Kept by the shell, not by a tab, so switching
 * tabs or closing the panel loses nothing (a typed callsign was gone
 * after a look at the CQ tab). [tab] is also what the dock button does.
 */
@Stable
class MoreDraft {
    var tab by mutableStateOf(MoreTab.CQ)
    var call by mutableStateOf("")
    /** The operator's EVEN/ODD pick for [call]; null follows the default. Reset when [call] changes. */
    var callSide by mutableStateOf<SlotParity?>(null)
    /** Our side as the CALL tab last showed it, for a station never heard. */
    var ourSide by mutableStateOf<SlotParity?>(null)
    var text by mutableStateOf("")
    var otherWord by mutableStateOf("")
    var otherOpen by mutableStateOf(false)
}

/** The call the dock button would make: [call] in [txIn], with what goes out first. */
data class CallPlan(val call: String, val message: String, val txIn: SlotParity, val heard: RxSession.DecodeRow?)

/**
 * The call in [draft], if it is one FT8 can send as an addressed message; null
 * otherwise. Their side defaults to the opposite of where they were last heard on
 * this band, or ours if never.
 */
internal fun callPlan(draft: MoreDraft, session: RxSession, callMessage: (String) -> String?): CallPlan? {
    val call = draft.call
    if (!TxText.isCallsignShape(call)) return null
    val message = callMessage(call) ?: return null
    val encoding = encodingOf(message) ?: return null
    if (!TxText.sendsAsBuilt(message, encoding, Ft8Decoder.decodedAs(message))) return null
    val heard = session.lastHeard(call)
    val side = draft.callSide ?: heard?.parity?.other ?: draft.ourSide ?: session.txParity()
    return CallPlan(call, message, side, heard)
}

/** The encoder's verdict on typed text; null if the native library is not loaded. */
internal fun textVerdict(typed: String): TxText.Verdict? {
    val text = TxText.normalise(typed)
    val auto = encodingOf(text) ?: return null
    val decodedAs = Ft8Decoder.decodedAs(text) ?: return null
    val fits = Ft8Decoder.fitsFreeText(text) ?: return null
    return TxText.judgeText(text, auto, decodedAs, fits)
}

/** The text to queue for a verdict, or null for a refusal. */
internal fun TxText.Verdict?.toQueued(): AutoTx.QueuedText? = when (this) {
    is TxText.Verdict.Standard -> AutoTx.QueuedText(text, freeText = false)
    is TxText.Verdict.FreeText -> AutoTx.QueuedText(text, freeText = true)
    else -> null
}

/** How a preview line is coloured: fine, sendable with a caveat, or refused. */
private enum class Tone { GOOD, CAUTION, REFUSED }

/** The line under each tab saying exactly what will go out, and as what. */
private data class Preview(val message: String?, val kind: String, val tone: Tone)

/**
 * The CQ ⋯ panel: the word after CQ, a callsign to call, free text.
 *
 * **It only sets things up.** Nothing in it queues or transmits:
 * the tab it is left on decides what the button beside ⋯ does ([DockButton]) - `CQ POTA`, `CALL W1AW` or `TEXT` - that button queues it, ARM transmits, and
 * HALT stays in the dock below. Every tab shows the message and how FT8 will carry
 * it, from the encoder itself ([encodingOf]).
 *
 * It sits above the dock, outside the tab, so it is there on every tab like the dock
 * is. With the keyboard up the whole frame shrinks above it ([AppShell]'s
 * safe-drawing padding), so HALT moves up but is never covered (rule 3).
 */
@Composable
internal fun MorePanel(
    draft: MoreDraft,
    settings: SettingsStore,
    session: RxSession,
    /** The CQ the engine would send with a word, or without one for null. */
    cqMessage: (String?) -> String,
    /** The first call the engine would send to a station, or null if it would not. */
    callMessage: (String) -> String?,
    onCqWord: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tab = draft.tab
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Tokens.StripColors.Background)
            .padding(bottom = Tokens.More.Gap),
        verticalArrangement = Arrangement.spacedBy(Tokens.More.Gap)
    ) {
        HorizontalDivider(thickness = 2.dp, color = Tokens.StripColors.Occupied)
        Tabs(tab, onTab = { draft.tab = it }, onClose = onClose)
        when (tab) {
            MoreTab.CQ -> CqTab(draft, settings, cqMessage, onCqWord)
            MoreTab.CALL -> CallTab(draft, settings, session, callMessage, onClose)
            MoreTab.TEXT -> TextTab(draft, settings, onClose)
        }
    }
}

@Composable
private fun Tabs(tab: MoreTab, onTab: (MoreTab) -> Unit, onClose: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().height(Tokens.More.TabHeight)) {
        for (each in MoreTab.entries) {
            val on = each == tab
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1f)
                    .height(Tokens.More.TabHeight)
                    .clickable { onTab(each) }
            ) {
                Text(
                    text = stringResource(
                        when (each) {
                            MoreTab.CQ -> R.string.more_tab_cq
                            MoreTab.CALL -> R.string.more_tab_call
                            MoreTab.TEXT -> R.string.more_tab_text
                        }
                    ),
                    color = if (on) Tokens.StripColors.Frequency else Tokens.StripColors.Label,
                    fontSize = Tokens.Strip.LabelSize,
                    fontWeight = FontWeight.Bold
                )
                if (on) {
                    HorizontalDivider(
                        thickness = 2.dp,
                        color = Tokens.StripColors.Occupied,
                        modifier = Modifier.align(Alignment.BottomCenter)
                    )
                }
            }
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .width(Tokens.MinTouchTarget)
                .height(Tokens.More.TabHeight)
                .clickable(onClick = onClose)
        ) {
            Text("✕", color = Tokens.StripColors.Label, fontSize = Tokens.Strip.FrequencySize)
        }
    }
}

// --- CQ ----------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CqTab(
    draft: MoreDraft,
    settings: SettingsStore,
    cqMessage: (String?) -> String,
    onCqWord: (String) -> Unit
) {
    val word = settings.cqWord
    val other = draft.otherWord

    Label(stringResource(R.string.more_cq_label))
    // Contest mode sets the word: shown, locked, with where to change it.
    val contest = settings.contest
    if (contest != null) {
        // Report+grid may call with a plain CQ: the chip shows the panel's "none".
        Chips { Chip(contest.cqWord.ifEmpty { stringResource(R.string.more_cq_none) }, on = true) {} }
        Hint(stringResource(R.string.more_cq_contest_locked))
    } else Chips {
        Chip(stringResource(R.string.more_cq_none), on = word.isEmpty()) { draft.otherOpen = false; onCqWord("") }
        for (each in TxText.CQ_WORDS) {
            Chip(each, on = word == each) { draft.otherOpen = false; onCqWord(each) }
        }
        // A typed word stays on show while it is the one in use.
        if (word.isNotEmpty() && word !in TxText.CQ_WORDS) Chip(word, on = true) {}
        Chip(stringResource(R.string.more_cq_other), on = false, quiet = true) { draft.otherOpen = true }
    }
    if (contest == null && draft.otherOpen) {
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { focus.requestFocus() }
        Field(
            value = other,
            // Only what a CQ word can be made of; the length rule is judged below.
            onValue = { draft.otherWord = it.uppercase().filter { c -> c in 'A'..'Z' || c in '0'..'9' }.take(4) },
            onDone = {
                if (TxText.isCqWord(other)) {
                    onCqWord(other)
                    draft.otherOpen = false
                }
            },
            placeholder = stringResource(R.string.more_cq_other_hint),
            focus = focus
        )
        if (other.isNotEmpty() && !TxText.isCqWord(other)) {
            PreviewLine(Preview(null, stringResource(R.string.more_cq_bad_word), Tone.REFUSED))
        }
    }
    val message = cqMessage(word.ifEmpty { null })
    PreviewLine(
        when {
            !settings.profile.canTransmit -> Preview(null, stringResource(R.string.more_no_profile), Tone.REFUSED)
            else -> when (val encoding = encodingOf(message)) {
                null -> Preview(message, stringResource(R.string.more_no_encoder), Tone.REFUSED)
                else -> {
                    val decodedAs = Ft8Decoder.decodedAs(message)
                    if (TxText.sendsAsBuilt(message, encoding, decodedAs)) {
                        Preview(message, stringResource(R.string.more_kind_cq), Tone.GOOD)
                    } else {
                        Preview(message, garbled(decodedAs), Tone.REFUSED)
                    }
                }
            }
        }
    )
    Hint(stringResource(R.string.more_cq_hint))
}

// --- CALL --------------------------------------------------------------------------

@Composable
private fun CallTab(
    draft: MoreDraft,
    settings: SettingsStore,
    session: RxSession,
    callMessage: (String) -> String?,
    onDone: () -> Unit
) {
    val call = draft.call
    // Our side for a station never heard: the contact's or CQ run's, else the next
    // slot as the tab opened. Taken once per opening, because the next slot alternates
    // and a default that flipped every 15 s under the operator's eyes would be wrong
    // half the time they looked away.
    LaunchedEffect(Unit) { draft.ourSide = session.txParity() }
    val heard = if (TxText.isCallsignShape(call)) session.lastHeard(call) else null
    val plan = callPlan(draft, session, callMessage)
    val side = plan?.txIn ?: draft.callSide ?: heard?.parity?.other ?: draft.ourSide ?: session.txParity()

    val message = if (TxText.isCallsignShape(call)) callMessage(call) else null
    val encoding = message?.let(::encodingOf)
    val sendable = settings.profile.canTransmit && plan != null

    val focus = remember { FocusRequester() }
    // The keyboard only when there is nothing typed yet: a callsign kept from before
    // is shown, not edited, so the button can be pressed without it in the way.
    LaunchedEffect(Unit) { if (call.isEmpty()) focus.requestFocus() }

    Label(stringResource(R.string.more_call_label))
    Field(
        value = call,
        // A callsign is A-Z, 0-9 and '/'; anything else cannot be part of one. A new
        // callsign gets the default side again.
        onValue = { typed ->
            val next = typed.uppercase().filter { c -> c in 'A'..'Z' || c in '0'..'9' || c == '/' }.take(11)
            if (next != draft.call) draft.callSide = null
            draft.call = next
        },
        // ✓ only puts the keyboard and panel away; the button beside ⋯ calls.
        onDone = onDone,
        focus = focus
    )
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Tokens.More.Padding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Tokens.More.Gap)
    ) {
        Text(stringResource(R.string.more_call_tx_in), color = Tokens.StripColors.Label, fontSize = Tokens.More.KindTextSize)
        Row(
            modifier = Modifier.border(1.dp, Tokens.StripColors.TxBand, RoundedCornerShape(6.dp))
        ) {
            for (parity in listOf(SlotParity.EVEN, SlotParity.ODD)) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .width(Tokens.More.SegmentWidth)
                        .height(Tokens.MinTouchTarget)
                        .background(if (parity == side) Tokens.StripColors.Faint else Color.Transparent)
                        .clickable { draft.callSide = parity }
                ) {
                    Text(
                        text = parity.name,
                        color = if (parity == side) Tokens.StripColors.Frequency else Tokens.StripColors.Label,
                        fontSize = Tokens.Strip.LabelSize,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
        Text(
            text = heard?.let {
                stringResource(R.string.more_call_last_heard, it.parity.name, utcTime(it.slotStartMs))
            } ?: stringResource(R.string.more_call_not_heard),
            color = Tokens.StripColors.Label,
            fontSize = Tokens.More.KindTextSize,
            fontFamily = FontFamily.Monospace
        )
    }
    if (call.isNotEmpty()) {
        PreviewLine(
            when {
                !settings.profile.canTransmit -> Preview(null, stringResource(R.string.more_no_profile), Tone.REFUSED)
                message != null && encoding == null -> Preview(message, stringResource(R.string.more_no_encoder), Tone.REFUSED)
                sendable -> Preview(message, stringResource(R.string.more_kind_call), Tone.GOOD)
                // Say what others would see instead, when the encoder would send something.
                else -> Preview(
                    message,
                    message?.let(Ft8Decoder::decodedAs)?.takeIf { it.isNotEmpty() }?.let { garbled(it) }
                        ?: stringResource(R.string.more_call_bad),
                    Tone.REFUSED
                )
            }
        )
    }
    Hint(stringResource(R.string.more_call_hint))
}

// --- TEXT --------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TextTab(draft: MoreDraft, settings: SettingsStore, onDone: () -> Unit) {
    val text = TxText.normalise(draft.text)
    val verdict = textVerdict(text)

    // Removing saved texts is a mode, not a gesture (a way to delete
    // them was needed; holding a chip was not found). In it every saved text shows ✕
    // and a tap removes it, so an ordinary tap can never delete one by accident.
    var editing by remember { mutableStateOf(false) }

    Label(stringResource(R.string.more_text_saved))
    Chips {
        for (saved in settings.savedTexts) {
            if (editing) {
                Chip("✕ $saved", on = false, warn = true) { settings.removeText(saved) }
            } else {
                // A tap puts it in the field, ready for the button: typing in the cold
                // is slow.
                Chip(saved, on = saved == text) { draft.text = saved }
            }
        }
        if (editing) {
            Chip(stringResource(R.string.more_text_edit_done), on = true) { editing = false }
        } else {
            Chip(stringResource(R.string.more_text_save), on = false, quiet = true) {
                if (verdict.toQueued() != null) settings.saveText(text)
            }
            if (settings.savedTexts.isNotEmpty()) {
                Chip(stringResource(R.string.more_text_edit), on = false, quiet = true) { editing = true }
            }
        }
    }
    Field(
        value = draft.text,
        // Upper case as typed, and no longer than free text can be. Nothing else is changed or dropped: a character FT8 cannot
        // carry is named below, never silently swapped. A standard message longer
        // than 13 characters is a job for CALL, not TEXT.
        onValue = { draft.text = it.uppercase().take(TxText.FREE_TEXT_MAX) },
        // ✓ only puts the keyboard and panel away; the button beside ⋯ queues.
        onDone = onDone,
        counter = "${text.length}/${TxText.FREE_TEXT_MAX}",
        counterWarn = text.length == TxText.FREE_TEXT_MAX
    )
    PreviewLine(
        when {
            !settings.profile.canTransmit -> Preview(null, stringResource(R.string.more_no_profile), Tone.REFUSED)
            verdict == null -> Preview(text.ifEmpty { null }, stringResource(R.string.more_no_encoder), Tone.REFUSED)
            else -> when (verdict) {
                is TxText.Verdict.Standard -> Preview(verdict.text, stringResource(R.string.more_kind_standard), Tone.GOOD)
                is TxText.Verdict.FreeText -> Preview(
                    verdict.text,
                    stringResource(if (verdict.looksLikeCall) R.string.more_kind_free_call else R.string.more_kind_free),
                    Tone.CAUTION
                )
                is TxText.Verdict.Refused -> Preview(
                    text.ifEmpty { null },
                    when (verdict.reason) {
                        TxText.Reason.EMPTY -> stringResource(R.string.more_refused_empty)
                        TxText.Reason.BAD_CHARACTER -> stringResource(R.string.more_refused_char, verdict.char.toString())
                        TxText.Reason.TOO_LONG -> stringResource(R.string.more_refused_long, verdict.length)
                        TxText.Reason.UNSENDABLE -> stringResource(R.string.more_refused_unsendable)
                    },
                    Tone.REFUSED
                )
            }
        }
    )
    Hint(stringResource(R.string.more_text_hint))
}

// --- pieces ------------------------------------------------------------------------

@Composable
private fun Label(text: String) {
    Text(
        text = text.uppercase(),
        color = Tokens.StripColors.Label,
        fontSize = Tokens.Strip.LabelSize,
        modifier = Modifier.padding(horizontal = Tokens.More.Padding)
    )
}

@Composable
private fun Hint(text: String) {
    Text(
        text = text,
        color = Tokens.StripColors.Label,
        fontSize = Tokens.More.KindTextSize,
        modifier = Modifier.padding(horizontal = Tokens.More.Padding)
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun Chips(content: @Composable () -> Unit) {
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Tokens.More.Padding),
        horizontalArrangement = Arrangement.spacedBy(Tokens.More.Gap),
        verticalArrangement = Arrangement.spacedBy(Tokens.More.Gap)
    ) { content() }
}

@Composable
internal fun Chip(
    label: String,
    on: Boolean,
    quiet: Boolean = false,
    /** Drawn red: a tap removes something. */
    warn: Boolean = false,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(Tokens.More.ChipHeight / 2)
    val edge = when {
        on -> Tokens.StripColors.Occupied
        warn -> Tokens.StripColors.Hot
        else -> Tokens.StripColors.TxBand
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .heightIn(min = Tokens.More.ChipHeight)
            .background(if (on) Tokens.StripColors.Occupied else Color.Transparent, shape)
            .border(1.dp, edge, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp)
    ) {
        Text(
            text = label,
            color = when {
                on -> Color.White
                warn -> Tokens.StripColors.Hot
                quiet -> Tokens.StripColors.Label
                else -> Tokens.StripColors.Frequency
            },
            fontSize = Tokens.More.PreviewTextSize,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun Field(
    value: String,
    onValue: (String) -> Unit,
    onDone: () -> Unit,
    placeholder: String? = null,
    counter: String? = null,
    counterWarn: Boolean = false,
    focus: FocusRequester? = null
) {
    BasicTextField(
        value = value,
        onValueChange = onValue,
        singleLine = true,
        textStyle = TextStyle(
            fontFamily = FontFamily.Monospace,
            fontSize = Tokens.More.FieldTextSize,
            fontWeight = FontWeight.Medium,
            color = Tokens.StripColors.Frequency
        ),
        cursorBrush = SolidColor(Tokens.StripColors.Marker),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Characters,
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Ascii,
            imeAction = ImeAction.Done
        ),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Tokens.More.Padding)
            .then(if (focus != null) Modifier.focusRequester(focus) else Modifier),
        decorationBox = { inner ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(Tokens.More.FieldHeight)
                    .background(Tokens.StripColors.Screen, RoundedCornerShape(6.dp))
                    .border(1.dp, Tokens.StripColors.Occupied, RoundedCornerShape(6.dp))
                    .padding(horizontal = 10.dp)
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    if (value.isEmpty() && placeholder != null) {
                        Text(placeholder, color = Tokens.StripColors.TxBand, fontSize = Tokens.More.PreviewTextSize)
                    }
                    inner()
                }
                counter?.let {
                    Text(
                        text = it,
                        color = if (counterWarn) Tokens.StripColors.Strong else Tokens.StripColors.Label,
                        fontSize = Tokens.More.KindTextSize,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    )
}

@Composable
private fun PreviewLine(preview: Preview) {
    val colour = when (preview.tone) {
        Tone.GOOD -> Tokens.StripColors.Marker
        Tone.CAUTION -> Tokens.StripColors.Strong
        Tone.REFUSED -> Tokens.StripColors.Hot
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Tokens.More.Padding)
            .background(colour.copy(alpha = 0.12f), RoundedCornerShape(4.dp))
            .padding(horizontal = 9.dp, vertical = 6.dp)
    ) {
        preview.message?.let {
            Text(
                text = it,
                color = Tokens.StripColors.Frequency,
                fontSize = Tokens.More.PreviewTextSize,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium
            )
        }
        Text(text = preview.kind, color = colour, fontSize = Tokens.More.KindTextSize)
    }
}

/** A built message that would not reach others as built: what they would see instead. */
@Composable
private fun garbled(decodedAs: String?): String =
    if (decodedAs.isNullOrEmpty()) stringResource(R.string.more_refused_unsendable)
    else stringResource(R.string.more_garbled, decodedAs)

/** HH:mm:ss UTC, as the feed's slot headers show time. */
private fun utcTime(epochMs: Long): String =
    java.time.Instant.ofEpochMilli(epochMs).toString().substring(11, 19)
