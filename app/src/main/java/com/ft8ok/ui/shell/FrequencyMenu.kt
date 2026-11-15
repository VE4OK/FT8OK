package com.ft8ok.ui.shell

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.ft8ok.R
import com.ft8ok.log.AdifBand
import com.ft8ok.model.DialFrequencies
import com.ft8ok.model.DialFrequency
import com.ft8ok.radio.Ic7300Rig
import com.ft8ok.rig.RigSession
import com.ft8ok.rig.TxGuard
import com.ft8ok.settings.SettingsStore
import com.ft8ok.ui.theme.Tokens

/**
 * The band and dial in the top bar, and a tap on it opens the frequency list:
 * every FT8 frequency the radio can reach,
 * and the operator's own, all in frequency order, then "Add custom".
 * Picking one sets the dial and USB-D over CI-V; the radio's own report then
 * updates the label, so it shows what the radio did rather than what was asked.
 */
@Composable
internal fun FrequencyMenu(rig: RigSession, settings: SettingsStore, onAddCustom: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    val state = rig.state
    val dial = state.dialHz
    val allowed = dial != null && TxGuard.allowedDial(dial, settings.customDialsHz, com.ft8ok.Flavor.MODE)
    val description = stringResource(R.string.freq_menu_a11y)

    Box {
        Text(
            text = when {
                rig.connecting -> stringResource(R.string.rig_connecting)
                !rig.connected -> stringResource(R.string.rig_none)
                dial == null -> stringResource(R.string.rig_linked)
                rig.busy -> stringResource(R.string.rig_setting)
                else -> "%s %s".format(
                    // A custom dial shows its band, not "custom", so the label is never
                    // wider than a table one and fits narrow screens.
                    state.band ?: AdifBand.forHz(dial) ?: stringResource(R.string.rig_off_band),
                    shortMegahertz(dial)
                ) + " ▾"
            },
            color = when {
                rig.connected && dial != null && allowed -> Tokens.StripColors.Label
                rig.connected && dial != null -> Tokens.StripColors.LabelStale
                else -> Tokens.StripColors.Label
            },
            fontSize = Tokens.Strip.FrequencySize,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .heightIn(min = Tokens.MinTouchTarget)
                .semantics { contentDescription = description }
                .clickable(enabled = rig.connected && !rig.busy) { open = true }
                .wrapContentHeight(Alignment.CenterVertically)
        )
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            containerColor = Tokens.StripColors.Background
        ) {
            for (row in pickerRows(pickerEntries(), settings.customDialsHz)) {
                val entry = row.entry
                val here = dial != null && kotlin.math.abs(row.hz - dial) <= DialFrequencies.MATCH_TOLERANCE_HZ
                MenuRow(
                    text = if (entry != null) {
                        "%-5s %s%s".format(
                            entry.band,
                            shortMegahertz(entry.hz),
                            if (entry.preferred) "" else " " + stringResource(R.string.freq_alt)
                        )
                    } else {
                        "%-5s %s".format(stringResource(R.string.freq_custom), fullMegahertz(row.hz))
                    },
                    here = here,
                    bold = entry?.preferred == true
                ) {
                    open = false
                    rig.tune(row.hz)
                }
            }
            MenuRow(stringResource(R.string.freq_add_custom), here = false, bold = false) {
                open = false
                onAddCustom()
            }
        }
    }
}

@Composable
private fun MenuRow(text: String, here: Boolean, bold: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = {
            Text(
                text = text,
                color = if (here) Tokens.StripColors.Marker else Tokens.StripColors.Frequency,
                fontSize = Tokens.Strip.FrequencySize,
                fontFamily = FontFamily.Monospace,
                fontWeight = if (bold || here) FontWeight.Bold else FontWeight.Normal
            )
        },
        onClick = onClick
    )
}

/**
 * This build's table (FT8 or FT4), limited to what an IC-7300
 * can tune ([Ic7300Rig.TOP_HZ]). Region-specific entries (4 m is Region 1 only) are
 * kept; the app has no region setting and does not guess one.
 */
private fun pickerEntries(): List<DialFrequency> =
    DialFrequencies.ALL.filter { it.mode == com.ft8ok.Flavor.MODE.name && it.hz <= Ic7300Rig.TOP_HZ }

/** One line of the picker: a table [entry], or a custom dial when that is null. */
internal data class PickerRow(val hz: Long, val entry: DialFrequency?)

/**
 * The table and the custom dials as one list in frequency order,
 * so a custom dial sits beside the band it is on. The table row comes first at the
 * same frequency.
 */
internal fun pickerRows(entries: List<DialFrequency>, customHz: List<Long>): List<PickerRow> =
    (entries.map { PickerRow(it.hz, it) } + customHz.map { PickerRow(it, null) }).sortedBy { it.hz }

/** A custom dial to the Hz, trailing zeros dropped but kHz kept: 7.0755, 7.074. */
internal fun fullMegahertz(hz: Long): String {
    val text = "%d.%06d".format(hz / 1_000_000, hz % 1_000_000).trimEnd('0')
    val decimals = text.length - text.indexOf('.') - 1
    return if (decimals < 3) text + "0".repeat(3 - decimals) else text
}
