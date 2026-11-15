package com.ft8ok.dev

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ft8ok.dsp.TxFrequency
import com.ft8ok.model.SlotParity
import com.ft8ok.ui.strip.LaneState
import com.ft8ok.ui.strip.OccupancyStrip
import com.ft8ok.ui.theme.Tokens

/**
 * Dev harness for the occupancy strip, with no radio and no decoder running.
 *
 * It exists because the alternative is judging the strip during a live test on the
 * phone, and each of those costs a trip to the radio. Here the strip is
 * driven by spectra actually recorded on 18.100 MHz, in every state it has to
 * survive: fresh, ageing, stale, no data, Full Auto, User-set.
 *
 * Throwaway. The real screen replaces it.
 */
class StripPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { StripGallery() }
    }
}

@Composable
private fun StripGallery() {
    val odd = remember { SampleSpectra.mapFrom(SampleSpectra.ODD_SLOT) }
    val even = remember { SampleSpectra.mapFrom(SampleSpectra.EVEN_SLOT) }

    // A tap has to actually do something, or the snapping is untested.
    var tapped by remember { mutableStateOf<Int?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Tokens.StripColors.Screen)
            .verticalScroll(rememberScrollState())
            .padding(top = 40.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Case("Full Auto, fresh reading - odd is our TX parity") {
            OccupancyStrip(
                evenLane = LaneState(even, heardAgoMs = 15_000),
                oddLane = LaneState(odd, heardAgoMs = 0),
                txParity = SlotParity.ODD,
                txFrequencyHz = odd.candidates.firstOrNull(),
                mode = TxFrequency.Mode.FULL_AUTO,
                onTapFrequency = { tapped = it }
            )
        }

        Case("Full Auto, 45 s old - we have transmitted since") {
            OccupancyStrip(
                evenLane = LaneState(even, heardAgoMs = 45_000),
                oddLane = LaneState(odd, heardAgoMs = 15_000),
                txParity = SlotParity.EVEN,
                txFrequencyHz = even.candidates.firstOrNull(),
                mode = TxFrequency.Mode.FULL_AUTO,
                onTapFrequency = { tapped = it }
            )
        }

        Case("Full Auto, STALE - CQing every cycle, deaf for 2:30") {
            OccupancyStrip(
                evenLane = LaneState(even, heardAgoMs = 150_000),
                oddLane = LaneState(odd, heardAgoMs = 15_000),
                txParity = SlotParity.EVEN,
                txFrequencyHz = 1728,
                mode = TxFrequency.Mode.FULL_AUTO,
                onTapFrequency = { tapped = it }
            )
        }

        Case("User-set, marker on a busy spot - the app will not move it") {
            OccupancyStrip(
                evenLane = LaneState(even, heardAgoMs = 15_000),
                oddLane = LaneState(odd, heardAgoMs = 0),
                txParity = SlotParity.ODD,
                txFrequencyHz = 1900,
                mode = TxFrequency.Mode.USER_SET,
                onTapFrequency = { tapped = it }
            )
        }

        Case("Nothing heard yet - no bars, no frequency, no false confidence") {
            OccupancyStrip(
                evenLane = LaneState(null, heardAgoMs = null),
                oddLane = LaneState(null, heardAgoMs = null),
                txParity = SlotParity.ODD,
                txFrequencyHz = null,
                mode = TxFrequency.Mode.FULL_AUTO,
                onTapFrequency = { tapped = it }
            )
        }

        val tapLabel = tapped?.let { "$it Hz (snapped)" } ?: "tap a lane above"
        Case("tap result: $tapLabel") {
            OccupancyStrip(
                evenLane = LaneState(even, heardAgoMs = 15_000),
                oddLane = LaneState(odd, heardAgoMs = 0),
                txParity = SlotParity.ODD,
                txFrequencyHz = tapped ?: odd.candidates.firstOrNull(),
                mode = if (tapped == null) TxFrequency.Mode.FULL_AUTO else TxFrequency.Mode.USER_SET,
                onTapFrequency = { tapped = it }
            )
        }

        Spacer(Modifier.height(4.dp))
        Text(
            text = "odd gaps: " + odd.gaps.joinToString { it.fromHz.toString() + "-" + it.toHz } +
                "\nodd candidates: " + odd.candidates.joinToString() +
                "\neven candidates: " + even.candidates.joinToString(),
            color = Color(0xFF6E8396),
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(horizontal = 8.dp)
        )
    }
}

@Composable
private fun Case(label: String, content: @Composable () -> Unit) {
    Column {
        Text(
            text = label,
            color = Color(0xFF9FB4C6),
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(start = 8.dp, bottom = 3.dp)
        )
        content()
    }
}
