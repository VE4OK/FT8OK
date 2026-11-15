package com.ft8ok.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ft8ok.R
import com.ft8ok.settings.SettingsStore
import com.ft8ok.tx.AutoTx

/**
 * Settings › CQ PREFERENCES: in a CQ run, move on after Off or 1 to 5 missed
 * replies. One bar, like Contest's; the help line under it says what the choice does.
 * Search and pounce is not affected.
 */
@Composable
internal fun CqPreferences(settings: SettingsStore) {
    Column(modifier = Modifier.fillMaxWidth()) {
        SettingRow(label = stringResource(R.string.cq_miss_label))
        val off = stringResource(R.string.cq_miss_off)
        Segmented(
            options = (0..AutoTx.MAX_CQ_MISS_LIMIT).map { n ->
                (if (n == 0) off else n.toString()) to (settings.cqMissLimit == n)
            },
            onPick = { settings.chooseCqMissLimit(it) }
        )
        val limit = settings.cqMissLimit
        Help(
            if (limit == 0) stringResource(R.string.cq_miss_note_off)
            else pluralStringResource(R.plurals.cq_miss_note, limit, limit),
            modifier = Modifier.padding(bottom = 8.dp)
        )

        // Stop CQ after unanswered CQs, the same bar.
        SettingRow(label = stringResource(R.string.cq_stop_label))
        Segmented(
            options = AutoTx.CQ_STOP_CHOICES.map { n ->
                (if (n == 0) off else n.toString()) to (settings.cqStopLimit == n)
            },
            onPick = { settings.chooseCqStopLimit(AutoTx.CQ_STOP_CHOICES[it]) }
        )
        val stop = settings.cqStopLimit
        Help(
            if (stop == 0) stringResource(R.string.cq_stop_note_off)
            else pluralStringResource(R.plurals.cq_stop_note, stop, stop),
            modifier = Modifier.padding(bottom = 8.dp)
        )
    }
}
