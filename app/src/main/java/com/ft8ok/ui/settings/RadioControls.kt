package com.ft8ok.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.ft8ok.R
import com.ft8ok.radio.Ic7300Rig
import com.ft8ok.rig.RigSession
import com.ft8ok.settings.SettingsStore
import com.ft8ok.ui.shell.fullMegahertz
import com.ft8ok.ui.theme.Tokens

/**
 * Settings > Radio below the rig line: connect
 * and disconnect, put the radio in USB-D, and the link details. The frequency itself
 * is picked from the top bar.
 */
@Composable
internal fun RadioControls(
    rig: RigSession,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Tokens.Settings.Inner, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(Tokens.Settings.ControlGap)
    ) {
        SettingsButton(
            text = stringResource(if (rig.connected) R.string.rig_disconnect else R.string.rig_connect),
            enabled = !rig.busy && !rig.connecting,
            onClick = if (rig.connected) onDisconnect else onConnect,
            modifier = Modifier.weight(1f)
        )
        SettingsButton(
            text = stringResource(R.string.rig_set_usbd),
            enabled = rig.connected && !rig.busy && !rig.state.inFt8Mode,
            onClick = rig::setFt8Mode,
            modifier = Modifier.weight(1f)
        )
    }
    if (rig.connected) Help(stringResource(R.string.rig_link_detail, rig.address, rig.baud))
    if (rig.busy) Help(stringResource(R.string.rig_setting))
    rig.commandError?.let { Help(stringResource(R.string.rig_command_failed, it), Tokens.StripColors.Hot) }
}

/**
 * Settings > Your frequencies, its own card:
 * the operator's own dials, on which transmit is allowed, and the field to add one.
 */
@Composable
internal fun CustomFrequencies(settings: SettingsStore) {
    Help(stringResource(R.string.rig_custom_help), modifier = Modifier.padding(bottom = 6.dp))
    if (settings.customDialsHz.isEmpty()) {
        CardDivider()
        Text(
            text = stringResource(R.string.rig_custom_none),
            color = Tokens.Settings.Help,
            fontSize = Tokens.Settings.TextSize,
            modifier = Modifier
                .heightIn(min = Tokens.MinTouchTarget)
                .padding(horizontal = Tokens.Settings.Inner, vertical = 14.dp)
        )
    }
    for (hz in settings.customDialsHz) {
        CardDivider()
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = Tokens.Settings.Inner, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${fullMegahertz(hz)} MHz",
                color = Tokens.Settings.Text,
                fontSize = Tokens.Settings.TextSize,
                modifier = Modifier.weight(1f)
            )
            RemoveButton(
                description = stringResource(R.string.rig_custom_remove, fullMegahertz(hz)),
                onClick = { settings.removeCustomDial(hz) }
            )
        }
    }

    CardDivider()
    var entry by rememberSaveable { mutableStateOf("") }
    val parsed = parseKilohertz(entry)
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Tokens.Settings.Inner, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(Tokens.Settings.ControlGap),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = entry,
            onValueChange = { entry = it },
            placeholder = { Text(stringResource(R.string.rig_custom_hint), fontSize = Tokens.Settings.TextSize) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            textStyle = LocalTextStyle.current.copy(
                fontFamily = FontFamily.Monospace, color = Tokens.Settings.Text
            ),
            modifier = Modifier.weight(1f)
        )
        SettingsButton(
            text = stringResource(R.string.rig_custom_add),
            enabled = parsed != null,
            onClick = {
                parsed?.let(settings::addCustomDial)
                entry = ""
            },
            modifier = Modifier.widthIn(min = Tokens.Settings.SmallButtonWidth)
        )
    }
}

/** kHz as typed ("7075.5", "7075,5") to Hz, or null if it is not a dial the radio can tune. */
internal fun parseKilohertz(text: String): Long? {
    val khz = text.trim().replace(',', '.').toBigDecimalOrNull() ?: return null
    val hz = khz.movePointRight(3).setScale(0, java.math.RoundingMode.HALF_UP).toLong()
    return hz.takeIf { it in 1..Ic7300Rig.TOP_HZ }
}
