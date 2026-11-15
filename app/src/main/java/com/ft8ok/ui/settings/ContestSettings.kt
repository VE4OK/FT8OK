package com.ft8ok.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.ft8ok.R
import com.ft8ok.model.ContestSetup
import com.ft8ok.settings.SettingsStore
import com.ft8ok.ui.theme.Tokens

/**
 * Settings › Contest: the sub-mode and our exchange in it.
 * Set once before a contest; nothing on the operating screens opens this, and the CQ
 * button's `Contest` line says it is on.
 *
 * A sub-mode is switched on only when the exchange for it passes (the class and
 * section against WSJT-X's lists, a plain call, a grid); otherwise it stays as it
 * was and the reason shows here.
 */
@Composable
internal fun ContestSettings(settings: SettingsStore) {
    // The sub-mode whose values are on show: the one in use, or the one last tapped.
    var shown by remember { mutableStateOf(settings.contestKind) }
    var refused by remember { mutableStateOf<ContestSetup.Problem?>(null) }

    fun choose(kind: ContestSetup.Kind?) {
        shown = kind
        refused = settings.chooseContest(kind)
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        // One bar, not pills. Lit is the mode in use, as before.
        val kinds = listOf(null, ContestSetup.Kind.GRID, ContestSetup.Kind.FIELD_DAY, ContestSetup.Kind.REPORT_GRID)
        Segmented(
            options = listOf(
                stringResource(R.string.contest_off) to (settings.contestKind == null),
                stringResource(R.string.contest_grid) to (settings.contestKind == ContestSetup.Kind.GRID),
                stringResource(R.string.contest_field_day) to (settings.contestKind == ContestSetup.Kind.FIELD_DAY),
                stringResource(R.string.contest_report_grid) to (settings.contestKind == ContestSetup.Kind.REPORT_GRID)
            ),
            onPick = { choose(kinds[it]) }
        )

        when (shown) {
            ContestSetup.Kind.GRID -> {
                ContestNote(stringResource(R.string.contest_grid_note))
                ContestNote(stringResource(R.string.contest_grid_word))
                val choices = ContestSetup.GRID_CQ_WORDS + SettingsStore.CONTEST_WORD_CUSTOM
                Segmented(
                    options = choices.map {
                        val name = if (it == SettingsStore.CONTEST_WORD_CUSTOM) stringResource(R.string.contest_word_custom) else it
                        name to (settings.contestGridChoice == it)
                    },
                    onPick = { settings.chooseContestGridWord(choices[it]) }
                )
                if (settings.contestGridChoice == SettingsStore.CONTEST_WORD_CUSTOM) {
                    ExchangeField(
                        value = settings.contestGridCustomWord,
                        label = stringResource(R.string.contest_word_custom_label),
                        onValue = { settings.chooseContestGridCustomWord(it.uppercase().filter { c -> c in 'A'..'Z' || c in '0'..'9' }.take(4)) },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = Tokens.Settings.Inner, vertical = 4.dp)
                    )
                }
            }
            ContestSetup.Kind.FIELD_DAY -> {
                ContestNote(stringResource(R.string.contest_fd_note))
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Tokens.Settings.Inner, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(Tokens.Settings.ControlGap)
                ) {
                    ExchangeField(
                        value = settings.contestFdClass,
                        label = stringResource(R.string.contest_fd_class),
                        onValue = { settings.chooseContestFdClass(it.uppercase().filter { c -> c.isLetterOrDigit() }.take(3)) },
                        modifier = Modifier.weight(1f)
                    )
                    ExchangeField(
                        value = settings.contestFdSection,
                        label = stringResource(R.string.contest_fd_section),
                        onValue = { settings.chooseContestFdSection(it.uppercase().filter { c -> c in 'A'..'Z' }.take(3)) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            ContestSetup.Kind.REPORT_GRID -> {
                // Standard messages, contest mode for the rest.
                ContestNote(stringResource(R.string.contest_report_grid_note))
                ContestNote(stringResource(R.string.contest_grid_word))
                val choices = ContestSetup.REPORT_GRID_CQ_WORDS + SettingsStore.CONTEST_WORD_CUSTOM
                Segmented(
                    options = choices.map {
                        val name = when (it) {
                            SettingsStore.CONTEST_WORD_CUSTOM -> stringResource(R.string.contest_word_custom)
                            "" -> stringResource(R.string.contest_word_none)
                            else -> it
                        }
                        name to (settings.contestReportGridChoice == it)
                    },
                    onPick = { settings.chooseContestReportGridWord(choices[it]) }
                )
                if (settings.contestReportGridChoice == SettingsStore.CONTEST_WORD_CUSTOM) {
                    ExchangeField(
                        value = settings.contestGridCustomWord,
                        label = stringResource(R.string.contest_word_custom_label),
                        onValue = { settings.chooseContestGridCustomWord(it.uppercase().filter { c -> c in 'A'..'Z' || c in '0'..'9' }.take(4)) },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = Tokens.Settings.Inner, vertical = 4.dp)
                    )
                }
            }
            null -> ContestNote(stringResource(R.string.contest_off_note))
        }

        // Why the mode is not on as shown: refused when tapped, or the values (or the
        // station) changed since and no longer pass - then nothing is sent in its format.
        val kind = shown
        val problem = kind?.let { (settings.contestFor(it) as? ContestSetup.Result.Refused)?.problem }
        when {
            problem != null -> ContestNote(problemText(problem), warn = true)
            kind != null && settings.contestKind != kind && refused != null ->
                ContestNote(stringResource(R.string.contest_now_valid), warn = false)
            kind != null && settings.contest?.sendsExchange == false ->
                ContestNote(stringResource(R.string.contest_sending_standard, cqLabel(settings.contest!!.cqWord)))
            kind != null && settings.contest != null ->
                ContestNote(stringResource(R.string.contest_sending, settings.contest!!.cqWord, settings.contest!!.exchange))
        }
    }
}

/** `CQ` or `CQ TEST`: what our CQ starts with. */
private fun cqLabel(word: String): String = if (word.isEmpty()) "CQ" else "CQ $word"

@Composable
private fun problemText(problem: ContestSetup.Problem): String = stringResource(
    when (problem) {
        ContestSetup.Problem.CALL_NOT_PLAIN -> R.string.contest_problem_call
        ContestSetup.Problem.NO_GRID -> R.string.contest_problem_grid
        ContestSetup.Problem.BAD_CLASS -> R.string.contest_problem_class
        ContestSetup.Problem.BAD_SECTION -> R.string.contest_problem_section
        ContestSetup.Problem.BAD_CQ_WORD -> R.string.contest_problem_word
    }
)

@Composable
private fun ExchangeField(value: String, label: String, onValue: (String) -> Unit, modifier: Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        label = { Text(label, fontSize = Tokens.Settings.HelpSize) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
        textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, color = Tokens.StripColors.Frequency),
        modifier = modifier
    )
}

@Composable
private fun ContestNote(text: String, warn: Boolean = false) {
    Help(text, if (warn) Tokens.StripColors.Strong else Tokens.Settings.Help)
}
