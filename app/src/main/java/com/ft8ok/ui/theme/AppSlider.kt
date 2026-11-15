package com.ft8ok.ui.theme

import androidx.compose.material3.SliderColors
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Every slider's colours: the app's grey and white, never Material's default purple.
 */
@Composable
fun appSliderColors(
    /** False hides the step dots (Settings' SWR slider); the steps still apply. */
    ticks: Boolean = true
): SliderColors = SliderDefaults.colors(
    thumbColor = Tokens.StripColors.Frequency,
    activeTrackColor = Tokens.StripColors.Label,
    inactiveTrackColor = Tokens.StripColors.TxBand,
    activeTickColor = if (ticks) Tokens.StripColors.TxBand else Color.Transparent,
    inactiveTickColor = if (ticks) Tokens.StripColors.Label else Color.Transparent
)
