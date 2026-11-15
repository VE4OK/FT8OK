package com.ft8ok.ui.log

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ft8ok.R
import com.ft8ok.log.AdifBand
import com.ft8ok.log.LoggedQso
import com.ft8ok.logbook.LogSession
import com.ft8ok.logbook.NotLogged
import com.ft8ok.tx.AutoTx
import com.ft8ok.ui.radio.LoggedRow
import com.ft8ok.ui.theme.Tokens

/**
 * The Log tab: the active logbook, newest contact first.
 *
 * Above it, the contacts that got both reports through but were never logged, each
 * with a LOG button - the operator's "log anyway", kept off Live.
 * A tap on a logged contact opens it for editing or deleting ([LogEditScreen]).
 */
@Composable
fun LogScreen(
    log: LogSession,
    notLogged: List<NotLogged.Entry> = emptyList(),
    onForceLog: (NotLogged.Entry) -> Unit = {}
) {
    val contacts = log.contacts
    // The contact open for editing, by id: the list refreshes under it after a save.
    var editingId by remember { mutableStateOf<Long?>(null) }
    contacts.firstOrNull { it.id == editingId }?.let { entry ->
        LogEditScreen(
            entry = entry,
            onSave = { log.update(entry.id, it) },
            onDelete = { log.delete(entry.id) },
            onClose = { editingId = null }
        )
        return
    }
    // The logbooks page: switch, new, import.
    var booksOpen by remember { mutableStateOf(false) }
    if (booksOpen) {
        LogbooksScreen(log, onClose = { booksOpen = false })
        return
    }
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Tokens.StripColors.Background)
                .padding(horizontal = Tokens.Strip.Padding),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // The logbook's name opens the Logbooks page; the ▾ says it does.
            Box(
                modifier = Modifier
                    .heightIn(min = Tokens.MinTouchTarget)
                    .clickable { booksOpen = true },
                contentAlignment = Alignment.CenterStart
            ) {
                Text(
                    text = stringResource(R.string.log_book_open, log.logbookName ?: ""),
                    color = Tokens.StripColors.Frequency,
                    fontSize = Tokens.Strip.FrequencySize,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                text = pluralStringResource(R.plurals.log_count, contacts.size, contacts.size),
                color = Tokens.StripColors.Label,
                fontSize = Tokens.Strip.LabelSize,
                modifier = Modifier.padding(start = 8.dp).weight(1f)
            )
            // Through the system Save dialog: one file, no lasting folder access.
            // The type keeps the name as given rather than
            // letting a picker add .txt (inferred, not checked on every provider).
            val save = rememberLauncherForActivityResult(
                ActivityResultContracts.CreateDocument("application/octet-stream")
            ) { uri -> uri?.let(log::saveCopy) }
            Box(
                modifier = Modifier
                    .heightIn(min = Tokens.MinTouchTarget)
                    .clickable { save.launch(log.copyFileName(System.currentTimeMillis())) }
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.log_save_copy),
                    color = Tokens.StripColors.Occupied,
                    fontSize = Tokens.Strip.LabelSize,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        log.copyResult?.let { result ->
            Text(
                text = when (result) {
                    is LogSession.CopyResult.Saved ->
                        pluralStringResource(R.plurals.log_copy_saved, result.count, result.count, result.name)
                    is LogSession.CopyResult.Failed -> stringResource(R.string.log_copy_failed, result.reason)
                },
                color = if (result is LogSession.CopyResult.Failed) Tokens.StripColors.Strong else Tokens.StripColors.Label,
                fontSize = Tokens.Strip.LabelSize,
                modifier = Modifier.padding(horizontal = Tokens.Strip.Padding, vertical = 2.dp)
            )
        }

        // Undo here too: a contact logged from this tab is undone from this tab.
        log.lastLogged?.let { LoggedRow(it, onUndo = log::undo, onDismiss = log::dismissLogged) }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            if (notLogged.isNotEmpty()) {
                item(key = "not-logged-header") {
                    Text(
                        text = stringResource(R.string.log_not_logged),
                        color = Tokens.StripColors.Occupied,
                        fontSize = Tokens.Strip.LabelSize,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = Tokens.Strip.Padding, vertical = 4.dp)
                    )
                }
                items(notLogged, key = { "n:" + AutoTx.keyOf(it.qso) }) { entry ->
                    NotLoggedRow(entry, onForceLog)
                }
            }

            if (contacts.isEmpty()) {
                item(key = "empty") {
                    Text(
                        text = stringResource(R.string.log_empty),
                        color = Tokens.StripColors.Label,
                        fontSize = Tokens.Strip.LabelSize,
                        modifier = Modifier.padding(Tokens.Strip.Padding)
                    )
                }
            }

            items(contacts, key = { it.id }) { entry -> ContactRow(entry.qso) { editingId = entry.id } }
        }
    }
}

@Composable
private fun ContactRow(q: LoggedQso, onTap: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(Tokens.Log.RowHeight)
            .clickable(onClick = onTap)
            .padding(horizontal = Tokens.Strip.Padding),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            utcDateTime(q.timeOnMs), color = Tokens.StripColors.Label,
            fontSize = Tokens.Strip.LabelSize, fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(84.dp)
        )
        Text(
            q.call, color = Tokens.StripColors.Frequency,
            fontSize = Tokens.Feed.RowTextSize, fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)
        )
        Text(
            q.band ?: "", color = Tokens.StripColors.Label,
            fontSize = Tokens.Strip.LabelSize, fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(36.dp)
        )
        Text(
            q.grid ?: "", color = Tokens.StripColors.Label,
            fontSize = Tokens.Strip.LabelSize, fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(40.dp)
        )
        // Sent, then received: the order a paper log and WSJT-X use.
        Text(
            "%s %s".format(q.rstSent ?: "--", q.rstRcvd ?: "--"),
            color = Tokens.StripColors.Label,
            fontSize = Tokens.Strip.LabelSize, fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.End, modifier = Modifier.width(60.dp)
        )
    }
}

/** A contact with both reports and no entry: the same columns as the log, then LOG. */
@Composable
private fun NotLoggedRow(entry: NotLogged.Entry, onForceLog: (NotLogged.Entry) -> Unit) {
    val q = entry.qso
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(Tokens.Log.NotLoggedRowHeight)
            .background(Tokens.StripColors.Occupied.copy(alpha = 0.10f))
            .padding(start = Tokens.Strip.Padding),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            if (entry.current) stringResource(R.string.log_not_logged_current) else utcDateTime(q.startedAtMs),
            color = Tokens.StripColors.Label,
            fontSize = Tokens.Strip.LabelSize, fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(84.dp)
        )
        Text(
            q.partner, color = Tokens.StripColors.Frequency,
            fontSize = Tokens.Feed.RowTextSize, fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)
        )
        Text(
            entry.dialHz?.let(AdifBand::forHz) ?: "", color = Tokens.StripColors.Label,
            fontSize = Tokens.Strip.LabelSize, fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(36.dp)
        )
        Text(
            "%s %s".format(
                q.reportSentDb?.let(LoggedQso::formatReport) ?: "--",
                q.reportReceivedDb?.let(LoggedQso::formatReport) ?: "--"
            ),
            color = Tokens.StripColors.Label,
            fontSize = Tokens.Strip.LabelSize, fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.End, modifier = Modifier.width(60.dp)
        )
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .clickable { onForceLog(entry) }
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stringResource(R.string.log_force),
                color = Tokens.StripColors.Strong,
                fontSize = Tokens.Strip.LabelSize,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/** UTC "MM-dd HH:mm". The offset is explicit, so the device time zone never enters. */
private fun utcDateTime(epochMs: Long): String {
    val instant = java.time.Instant.ofEpochMilli(epochMs).atOffset(java.time.ZoneOffset.UTC)
    return "%02d-%02d %02d:%02d".format(instant.monthValue, instant.dayOfMonth, instant.hour, instant.minute)
}
