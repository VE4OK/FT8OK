package com.ft8ok.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ft8ok.R
import com.ft8ok.logbook.LogSession
import com.ft8ok.rig.RigSession
import com.ft8ok.rx.RxSession
import com.ft8ok.settings.SettingsStore
import com.ft8ok.tx.TxSession
import com.ft8ok.ui.radio.LogFolderRow
import com.ft8ok.ui.radio.RigLine
import com.ft8ok.ui.radio.StationSetup
import com.ft8ok.ui.radio.StatusLine
import com.ft8ok.ui.radio.SwrLimitRow
import com.ft8ok.ui.radio.TxReport
import com.ft8ok.ui.radio.TxTestRow
import com.ft8ok.ui.theme.Tokens

/**
 * Settings: everything that left the Live screen, laid out as
 * cards:
 * Station, Radio (with Transmit and Receive inside it), Your frequencies, CQ
 * preferences, Contest, Log, Diagnostics.
 *
 * The TX test stays hold-to-transmit (rule 3) and moved here rather than being
 * deleted: it is how drive level is set.
 */
@Composable
fun SettingsScreen(
    session: RxSession,
    rig: RigSession,
    tx: TxSession,
    settings: SettingsStore,
    log: LogSession,
    onConnectRadio: () -> Unit,
    onDisconnectRadio: () -> Unit,
    onReceiveOn: (Boolean) -> Unit,
    onDetailedLogs: (Boolean) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Tokens.Settings.ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(Tokens.Settings.CardGap)
    ) {
        SettingsCard(stringResource(R.string.settings_station)) {
            StationSetup(settings, log = null, collapsible = false, modifier = Modifier.padding(bottom = 8.dp))
        }

        SettingsCard(stringResource(R.string.settings_radio)) {
            RigLine(rig, onConnectRadio)
            RadioControls(rig, onConnect = onConnectRadio, onDisconnect = onDisconnectRadio)

            SubGroup(stringResource(R.string.settings_transmit))
            SwrLimitRow(settings)
            SettingRow(
                label = stringResource(R.string.settings_tx_test),
                help = stringResource(R.string.settings_tx_test_note)
            )
            TxTestRow(tx = tx, rig = rig, profile = settings.profile, modifier = Modifier.padding(bottom = 8.dp))

            // No TX frequency here: a tap on the strip pins one, and a tap on the Set
            // line under the strip goes back to Auto.
            SubGroup(stringResource(R.string.settings_receive_group))
            SettingRow(
                label = stringResource(R.string.settings_receive),
                help = stringResource(R.string.settings_receive_note)
            ) {
                Switch(
                    checked = settings.receiveOn,
                    onCheckedChange = onReceiveOn,
                    colors = settingsSwitchColors()
                )
            }
            Column(Modifier.padding(top = 2.dp, bottom = 8.dp)) { StatusLine(session) }
        }

        SettingsCard(stringResource(R.string.settings_frequencies)) {
            CustomFrequencies(settings)
        }

        SettingsCard(stringResource(R.string.settings_cq_prefs)) {
            CqPreferences(settings)
        }

        SettingsCard(stringResource(R.string.settings_contest)) {
            ContestSettings(settings)
            Spacer(Modifier.height(8.dp))
        }

        SettingsCard(stringResource(R.string.settings_log)) {
            LogFolderRow(log)
        }

        SettingsCard(stringResource(R.string.settings_diagnostics)) {
            if (tx.report == null) Help(stringResource(R.string.settings_no_tx_report)) else TxReport(tx)
            SettingRow(
                label = stringResource(R.string.settings_detailed_logs),
                help = stringResource(R.string.settings_detailed_logs_note)
            ) {
                Switch(
                    checked = settings.detailedLogs,
                    onCheckedChange = onDetailedLogs,
                    colors = settingsSwitchColors()
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}
