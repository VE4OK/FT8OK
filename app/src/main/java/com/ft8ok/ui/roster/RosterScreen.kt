package com.ft8ok.ui.roster

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.remember
import com.ft8ok.R
import com.ft8ok.data.LogRepository
import com.ft8ok.log.ContestLog
import com.ft8ok.log.EntityLookup
import com.ft8ok.rx.Roster
import com.ft8ok.rx.RxSession
import com.ft8ok.settings.SettingsStore
import com.ft8ok.ui.shell.noTabSwipe
import com.ft8ok.ui.theme.Tokens
import com.ft8ok.ui.theme.appSliderColors

/**
 * Stations heard in the last 30 s to 5 min, one row each. Tapping a
 * row works the station exactly as tapping its latest decode on Live does.
 */
@Composable
fun RosterScreen(
    session: RxSession,
    settings: SettingsStore,
    onCall: (RxSession.DecodeRow) -> Unit,
    /** Callsign to country (cty.dat, ADR 0012); knows nothing until it has loaded. */
    entities: EntityLookup = EntityLookup.NONE,
    /** The logbook in use, for the contest dupe mark. */
    contacts: List<LogRepository.Stored> = emptyList()
) {
    val sort = settings.rosterSort
    val listState = rememberLazyListState()
    // A new sort starts at the top. Rows are keyed by call, so without this the list
    // follows whichever station was first and the head of the new order is off screen.
    LaunchedEffect(sort) { listState.scrollToItem(0) }
    val now = session.clockTick.takeIf { it > 0 } ?: System.currentTimeMillis()
    val windowSec = settings.rosterWindowSec
    val entries = Roster.select(session.heard, session.band, now, windowSec * 1000L, sort) { entities.entityOf(it)?.name }
    // In contest mode, calls already worked in this contest on this band: marked
    // DUPE where the country goes, never hidden.
    val contest = settings.contest?.kind
    val band = session.band
    val dupes = remember(contacts, contest, band) {
        contest?.let { k ->
            ContestLog.inContest(contacts.map { it.qso }, k, System.currentTimeMillis())
                .filter { it.band.equals(band, ignoreCase = true) }
                .mapTo(HashSet()) { it.call.uppercase() }
        } ?: emptySet()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Tokens.StripColors.Background)
                .padding(horizontal = Tokens.Strip.Padding, vertical = 6.dp)
        ) {
            // A drag on this row moves the slider, never the tab.
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.noTabSwipe()) {
                Text(
                    text = stringResource(R.string.roster_window),
                    color = Tokens.StripColors.Label,
                    fontSize = Tokens.Strip.LabelSize
                )
                Slider(
                    value = windowSec.toFloat(),
                    onValueChange = { settings.chooseRosterWindowSec(it.toInt()) },
                    valueRange = SettingsStore.MIN_ROSTER_WINDOW_SEC.toFloat()..SettingsStore.MAX_ROSTER_WINDOW_SEC.toFloat(),
                    // 15 s steps: one per slot.
                    steps = (SettingsStore.MAX_ROSTER_WINDOW_SEC - SettingsStore.MIN_ROSTER_WINDOW_SEC) / 15 - 1,
                    colors = appSliderColors(),
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                )
                Text(
                    text = "%d:%02d".format(windowSec / 60, windowSec % 60),
                    color = Tokens.StripColors.Frequency,
                    fontSize = Tokens.Strip.FrequencySize,
                    fontFamily = FontFamily.Monospace
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (s in Roster.Sort.entries) {
                    val on = s == sort
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .height(Tokens.Roster.ChipHeight)
                            .background(
                                if (on) Tokens.StripColors.Faint else Tokens.StripColors.Background,
                                RoundedCornerShape(14.dp)
                            )
                            .border(
                                1.dp,
                                if (on) Tokens.StripColors.Occupied else Tokens.StripColors.TxBand,
                                RoundedCornerShape(14.dp)
                            )
                            .clickable { settings.chooseRosterSort(s) }
                            .padding(horizontal = 12.dp)
                    ) {
                        Text(
                            text = stringResource(
                                when (s) {
                                    Roster.Sort.COUNTRY -> R.string.roster_sort_country
                                    Roster.Sort.RECENT -> R.string.roster_sort_recent
                                    Roster.Sort.SNR -> R.string.roster_sort_snr
                                    Roster.Sort.GRID -> R.string.roster_sort_grid
                                }
                            ),
                            color = if (on) Tokens.StripColors.Frequency else Tokens.StripColors.Label,
                            fontSize = Tokens.Strip.LabelSize
                        )
                    }
                }
            }
        }

        RosterLine(
            call = stringResource(R.string.roster_col_call),
            country = stringResource(R.string.roster_col_country),
            grid = stringResource(R.string.roster_col_grid),
            report = stringResource(R.string.roster_col_snr),
            ago = stringResource(R.string.roster_col_ago),
            header = true
        )

        if (entries.isEmpty()) {
            Text(
                text = stringResource(R.string.roster_empty),
                color = Tokens.StripColors.Label,
                fontSize = Tokens.Strip.LabelSize,
                modifier = Modifier.padding(Tokens.Strip.Padding)
            )
        }
        LazyColumn(modifier = Modifier.weight(1f), state = listState) {
            items(entries, key = { it.call }) { e ->
                val agoSec = ((now - e.lastHeardMs) / 1000).coerceAtLeast(0)
                RosterLine(
                    call = e.call,
                    country = entities.entityOf(e.call)?.name ?: "",
                    dupe = e.call.uppercase() in dupes,
                    grid = e.grid ?: "",
                    report = e.report?.let { "%+d".format(it) } ?: "--",
                    ago = "%d:%02d".format(agoSec / 60, agoSec % 60),
                    calling = e.calling,
                    modifier = Modifier.clickable { onCall(e.row) }
                )
            }
        }
    }
}

@Composable
private fun RosterLine(
    call: String,
    country: String,
    grid: String,
    report: String,
    ago: String,
    modifier: Modifier = Modifier,
    dupe: Boolean = false,
    calling: Boolean = false,
    header: Boolean = false
) {
    val colour = if (header) Tokens.StripColors.Label else Tokens.StripColors.Frequency
    val size = if (header) Tokens.Strip.LabelSize else Tokens.Feed.RowTextSize
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(if (header) Tokens.Roster.HeaderHeight else Tokens.Roster.RowHeight)
            .padding(horizontal = Tokens.Strip.Padding),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(call, color = colour, fontSize = size, fontFamily = FontFamily.Monospace,
            maxLines = 1, modifier = Modifier.width(Tokens.Roster.CallWidth))
        Text(
            text = if (calling) stringResource(R.string.roster_cq) else "",
            color = Tokens.StripColors.Marker,
            fontSize = Tokens.Strip.LabelSize,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(22.dp)
        )
        // The flexible column: country names run long ("Fed. Rep. of Germany"), so
        // it takes what is left and ends in an ellipsis rather than pushing the
        // numbers off the row.
        Text(if (dupe) stringResource(R.string.feed_dupe) else country,
            color = if (header) colour else if (dupe) Tokens.Feed.Dupe else Tokens.StripColors.Label, fontSize = Tokens.Strip.LabelSize,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(grid, color = Tokens.StripColors.Label, fontSize = size, fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.End, modifier = Modifier.width(40.dp))
        Text(report, color = Tokens.StripColors.Label, fontSize = size, fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.End, modifier = Modifier.width(32.dp))
        Text(ago, color = Tokens.StripColors.Label, fontSize = size, fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.End, modifier = Modifier.width(40.dp))
    }
}
