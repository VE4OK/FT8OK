package com.ft8ok.ui.log

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.ft8ok.R
import com.ft8ok.data.LogRepository
import com.ft8ok.logbook.LogSession
import com.ft8ok.ui.shell.noTabSwipe
import com.ft8ok.ui.theme.Tokens

/**
 * The logbooks, opened by a tap on the logbook's name in the Log
 * header. Tap one to use it; make a new one by name; add an ADIF file's contacts to
 * the one in use. No rename and no delete of a whole logbook: the one destructive
 * action is left out until the operator asks for it.
 */
@Composable
fun LogbooksScreen(log: LogSession, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    var name by remember { mutableStateOf("") }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(log::importFile)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .noTabSwipe()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Tokens.Strip.Padding, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.logbooks_title),
                color = Tokens.StripColors.Frequency,
                fontSize = Tokens.Strip.FrequencySize,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            Box(
                modifier = Modifier
                    .heightIn(min = Tokens.MinTouchTarget)
                    .clickable(onClick = onClose)
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(stringResource(R.string.logbooks_close), color = Tokens.StripColors.Label,
                    fontSize = Tokens.Strip.LabelSize, fontWeight = FontWeight.Bold)
            }
        }

        for (book in log.books) {
            val inUse = book.logbook.id == log.activeId
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = Tokens.MinTouchTarget)
                    .background(
                        if (inUse) Tokens.StripColors.Occupied.copy(alpha = 0.15f) else Color.Transparent,
                        RoundedCornerShape(4.dp)
                    )
                    .clickable {
                        if (!inUse) log.switchTo(book.logbook.id)
                        onClose()
                    }
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (inUse) "●" else "", color = Tokens.StripColors.Occupied,
                    fontSize = Tokens.Strip.LabelSize, modifier = Modifier.width(16.dp)
                )
                Text(
                    book.logbook.name, color = Tokens.StripColors.Frequency,
                    fontSize = Tokens.Feed.RowTextSize, fontFamily = FontFamily.Monospace,
                    fontWeight = if (inUse) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    pluralStringResource(R.plurals.log_count, book.count, book.count),
                    color = Tokens.StripColors.Label, fontSize = Tokens.Strip.LabelSize
                )
            }
        }

        // New logbook: a name, and it becomes the one in use.
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it; log.clearNameProblem() },
                label = { Text(stringResource(R.string.logbooks_new_name), fontSize = Tokens.Strip.LabelSize) },
                singleLine = true,
                isError = log.nameProblem != null,
                supportingText = log.nameProblem?.let { p -> { Text(stringResource(nameProblemText(p), LogRepository.MAX_NAME), fontSize = Tokens.Strip.LabelSize) } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false),
                textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, color = Tokens.StripColors.Frequency),
                modifier = Modifier.weight(1f)
            )
            PageAction(R.string.logbooks_create, Tokens.StripColors.Occupied) {
                log.create(name) { name = ""; onClose() }
            }
        }

        // Import: into the logbook in use. To keep a file's contacts apart, make a
        // new logbook first.
        PageAction(R.string.logbooks_import, Tokens.StripColors.Occupied, Modifier.fillMaxWidth().padding(top = 12.dp)) {
            // .adi has no registered type, so the picker cannot filter by it.
            pick.launch(arrayOf("*/*"))
        }
        Text(
            text = stringResource(R.string.logbooks_import_hint),
            color = Tokens.StripColors.Label,
            fontSize = Tokens.Strip.LabelSize
        )
        log.importOutcome?.let { outcome ->
            Text(
                text = when (outcome) {
                    is LogSession.ImportOutcome.Done -> importText(outcome)
                    is LogSession.ImportOutcome.Failed -> stringResource(R.string.logbooks_import_failed, outcome.reason)
                },
                color = if (outcome is LogSession.ImportOutcome.Failed) Tokens.StripColors.Strong else Tokens.StripColors.Frequency,
                fontSize = Tokens.Strip.LabelSize
            )
        }
    }
}

@Composable
private fun importText(outcome: LogSession.ImportOutcome.Done): String {
    val r = outcome.result
    val parts = mutableListOf(pluralStringResource(R.plurals.logbooks_import_added, r.added, r.added, outcome.logbook))
    if (r.skipped > 0) parts += pluralStringResource(R.plurals.logbooks_import_skipped, r.skipped, r.skipped)
    if (r.rejected.isNotEmpty()) {
        parts += pluralStringResource(R.plurals.logbooks_import_rejected, r.rejected.size, r.rejected.size)
    }
    return parts.joinToString(" · ")
}

private fun nameProblemText(p: LogRepository.NameProblem): Int = when (p) {
    LogRepository.NameProblem.EMPTY -> R.string.logbooks_name_empty
    LogRepository.NameProblem.TOO_LONG -> R.string.logbooks_name_long
    LogRepository.NameProblem.NOT_ASCII -> R.string.log_not_ascii
    LogRepository.NameProblem.TAKEN -> R.string.logbooks_name_taken
}

@Composable
private fun PageAction(label: Int, color: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .heightIn(min = Tokens.MinTouchTarget)
            .background(color.copy(alpha = 0.18f), RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(stringResource(label), color = color, fontSize = Tokens.Strip.LabelSize, fontWeight = FontWeight.Bold)
    }
}
