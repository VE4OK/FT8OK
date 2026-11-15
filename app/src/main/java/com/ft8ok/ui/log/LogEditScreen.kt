package com.ft8ok.ui.log

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.ft8ok.R
import com.ft8ok.data.LogRepository
import com.ft8ok.log.LogEdit
import com.ft8ok.log.LoggedQso
import com.ft8ok.ui.shell.noTabSwipe
import com.ft8ok.ui.theme.Tokens

/**
 * One contact, edited by hand. A full page in place of the list,
 * not a pop-up (rule 9). Nothing is saved until SAVE, and SAVE refuses what would not
 * make a valid ADIF record, saying which field and why, under that field.
 *
 * Delete lives here and not on a swipe: a swipe would fight the tab swipe and is easy
 * to do by accident. It takes effect at once, with the Logged row's UNDO to take it back.
 */
@Composable
fun LogEditScreen(
    entry: LogRepository.Stored,
    onSave: (LoggedQso) -> Unit,
    onDelete: () -> Unit,
    onClose: () -> Unit
) {
    BackHandler(onBack = onClose)
    var form by remember(entry.id) { mutableStateOf(LogEdit.formOf(entry.qso)) }
    var problems by remember(entry.id) { mutableStateOf<Map<LogEdit.Field, LogEdit.Problem>>(emptyMap()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            // A sideways drag here (selecting text, a slipped thumb) must not change
            // tab and throw the edit away.
            .noTabSwipe()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Tokens.Strip.Padding, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = stringResource(R.string.log_edit_title, entry.qso.call),
            color = Tokens.StripColors.Frequency,
            fontSize = Tokens.Strip.FrequencySize,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = stringResource(R.string.log_edit_mode, listOfNotNull(entry.qso.mode, entry.qso.submode).joinToString(" "), entry.qso.band ?: "--"),
            color = Tokens.StripColors.Label,
            fontSize = Tokens.Strip.LabelSize
        )

        EditField(R.string.log_field_call, form.call, problems[LogEdit.Field.CALL], caps = true) { form = form.copy(call = it) }
        EditField(R.string.log_field_time, form.timeOn, problems[LogEdit.Field.TIME_ON], type = KeyboardType.Number) { form = form.copy(timeOn = it) }
        EditField(R.string.log_field_freq, form.freqMhz, problems[LogEdit.Field.FREQ], type = KeyboardType.Decimal) { form = form.copy(freqMhz = it) }
        EditField(R.string.log_field_grid, form.grid, problems[LogEdit.Field.GRID], caps = true) { form = form.copy(grid = it) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) {
                EditField(R.string.log_field_sent, form.rstSent, problems[LogEdit.Field.RST_SENT]) { form = form.copy(rstSent = it) }
            }
            Box(Modifier.weight(1f)) {
                EditField(R.string.log_field_rcvd, form.rstRcvd, problems[LogEdit.Field.RST_RCVD]) { form = form.copy(rstRcvd = it) }
            }
        }
        EditField(R.string.log_field_comment, form.comment, problems[LogEdit.Field.COMMENT]) { form = form.copy(comment = it) }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            PageButton(R.string.log_save, Tokens.StripColors.Occupied, Modifier.weight(1f)) {
                when (val outcome = LogEdit.apply(entry.qso, form)) {
                    is LogEdit.Outcome.Ok -> {
                        if (outcome.qso != entry.qso) onSave(outcome.qso)
                        onClose()
                    }
                    is LogEdit.Outcome.Invalid -> problems = outcome.problems
                }
            }
            PageButton(R.string.log_cancel, Tokens.StripColors.Label, Modifier.weight(1f), onClick = onClose)
        }
        // Set apart from SAVE, so it is not hit on the way to it.
        Spacer(Modifier.heightIn(min = 32.dp))
        PageButton(R.string.log_delete, Tokens.StripColors.Strong, Modifier.fillMaxWidth()) {
            onDelete()
            onClose()
        }
    }
}

@Composable
private fun EditField(
    label: Int,
    value: String,
    problem: LogEdit.Problem?,
    type: KeyboardType = KeyboardType.Ascii,
    caps: Boolean = false,
    onChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(label), fontSize = Tokens.Strip.LabelSize) },
        // One line throughout: a log field cannot hold a line break (LogEdit refuses it).
        singleLine = true,
        isError = problem != null,
        supportingText = problem?.let { { Text(stringResource(problemText(it)), fontSize = Tokens.Strip.LabelSize) } },
        keyboardOptions = KeyboardOptions(
            keyboardType = type,
            capitalization = if (caps) KeyboardCapitalization.Characters else KeyboardCapitalization.None,
            autoCorrectEnabled = false
        ),
        textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, color = Tokens.StripColors.Frequency),
        modifier = Modifier.fillMaxWidth()
    )
}

private fun problemText(p: LogEdit.Problem): Int = when (p) {
    LogEdit.Problem.BAD_CALL -> R.string.log_bad_call
    LogEdit.Problem.BAD_TIME -> R.string.log_bad_time
    LogEdit.Problem.BAD_FREQ -> R.string.log_bad_freq
    LogEdit.Problem.BAD_GRID -> R.string.log_bad_grid
    LogEdit.Problem.BAD_REPORT -> R.string.log_bad_report
    LogEdit.Problem.NOT_ASCII -> R.string.log_not_ascii
}

@Composable
private fun PageButton(label: Int, color: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .heightIn(min = Tokens.MinTouchTarget)
            .background(color.copy(alpha = 0.18f), RoundedCornerShape(4.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(stringResource(label), color = color, fontSize = Tokens.Strip.LabelSize, fontWeight = FontWeight.Bold)
    }
}
