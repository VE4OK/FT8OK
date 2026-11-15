package com.ft8ok.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.ft8ok.ui.theme.Tokens

/*
 * The pieces every Settings card is built from: a card with its
 * heading, a sub-group line inside a card, a row with a label on the left and a
 * control on the right, dim help text, and one button style. Three text sizes only
 * (Tokens.Settings), so the page reads as one thing.
 */

/** One section: a slightly lighter panel with its heading inside. */
@Composable
internal fun SettingsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(Tokens.Settings.CardRadius)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Tokens.Settings.Card, shape)
            .border(1.dp, Tokens.Settings.CardEdge, shape)
            .padding(bottom = 4.dp)
    ) {
        Text(
            text = title,
            color = Tokens.Settings.Heading,
            fontSize = Tokens.Settings.HeadingSize,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.08.em,
            modifier = Modifier.padding(
                start = Tokens.Settings.Inner, end = Tokens.Settings.Inner, top = 12.dp, bottom = 6.dp
            )
        )
        content()
    }
}

/** A group inside a card (Radio › Transmit, Radio › Receive): a hairline and a small grey label. */
@Composable
internal fun SubGroup(title: String) {
    CardDivider(Modifier.padding(top = 8.dp))
    Text(
        text = title,
        color = Tokens.Settings.SubHeading,
        fontSize = Tokens.Settings.SubHeadingSize,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.1.em,
        modifier = Modifier.padding(start = Tokens.Settings.Inner, end = Tokens.Settings.Inner, top = 12.dp)
    )
}

@Composable
internal fun CardDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(modifier = modifier, thickness = 1.dp, color = Tokens.Settings.CardEdge)
}

/** Dim explanation under a setting. */
@Composable
internal fun Help(text: String, colour: Color = Tokens.Settings.Help, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = colour,
        fontSize = Tokens.Settings.HelpSize,
        lineHeight = Tokens.Settings.HelpSize * 1.35f,
        modifier = modifier.padding(horizontal = Tokens.Settings.Inner, vertical = 2.dp)
    )
}

/**
 * Label on the left (with optional help under it), whatever control on the right.
 * At least a tap target tall, so the control in it can be one.
 */
@Composable
internal fun SettingRow(
    label: String,
    help: String? = null,
    modifier: Modifier = Modifier,
    trailing: @Composable RowScope.() -> Unit = {}
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = Tokens.MinTouchTarget)
            .padding(horizontal = Tokens.Settings.Inner, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = Tokens.Settings.ControlGap)) {
            Text(text = label, color = Tokens.Settings.Text, fontSize = Tokens.Settings.TextSize)
            help?.let {
                Text(
                    text = it,
                    color = Tokens.Settings.Help,
                    fontSize = Tokens.Settings.HelpSize,
                    lineHeight = Tokens.Settings.HelpSize * 1.35f
                )
            }
        }
        trailing()
    }
}

/**
 * The one button style. Grey for everything ordinary; disabled is an outline with
 * readable text, so a button that cannot be pressed now is still findable.
 */
@Composable
internal fun SettingsButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(Tokens.Settings.ButtonRadius),
        colors = ButtonDefaults.buttonColors(
            containerColor = Tokens.Settings.Button,
            contentColor = Color.White,
            disabledContainerColor = Color.Transparent,
            disabledContentColor = Tokens.Settings.DisabledText
        ),
        border = if (enabled) null else BorderStroke(1.dp, Tokens.Settings.DisabledEdge),
        modifier = modifier.heightIn(min = Tokens.Settings.ButtonHeight)
    ) {
        Text(text, fontSize = Tokens.Settings.ButtonTextSize, fontWeight = FontWeight.Medium)
    }
}

/** A plain ✕ that removes something: grey glyph, full tap target, no filled block. */
@Composable
internal fun RemoveButton(description: String, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(Tokens.MinTouchTarget)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description }
    ) {
        Text("✕", color = Tokens.Settings.Help, fontSize = Tokens.Settings.TextSize)
    }
}

/** A row of mutually exclusive choices drawn as one bar (contest sub-mode, grid CQ word). */
@Composable
internal fun Segmented(options: List<Pair<String, Boolean>>, onPick: (Int) -> Unit) {
    val shape = RoundedCornerShape(Tokens.Settings.SegmentRadius)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Tokens.Settings.Inner, vertical = 4.dp)
            .border(1.dp, Tokens.Settings.SegmentEdge, shape)
    ) {
        options.forEachIndexed { i, (label, on) ->
            if (i > 0) {
                Box(
                    Modifier
                        .size(width = 1.dp, height = Tokens.MinTouchTarget)
                        .background(Tokens.Settings.SegmentEdge)
                )
            }
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = Tokens.MinTouchTarget)
                    .background(
                        if (on) Tokens.Settings.Selected else Color.Transparent,
                        when {
                            options.size == 1 -> shape
                            i == 0 -> RoundedCornerShape(topStart = Tokens.Settings.SegmentRadius, bottomStart = Tokens.Settings.SegmentRadius)
                            i == options.lastIndex -> RoundedCornerShape(topEnd = Tokens.Settings.SegmentRadius, bottomEnd = Tokens.Settings.SegmentRadius)
                            else -> RoundedCornerShape(0.dp)
                        }
                    )
                    .clickable(role = Role.Tab) { onPick(i) }
                    .semantics { selected = on }
            ) {
                Text(
                    text = label,
                    color = if (on) Color.White else Tokens.Settings.Text,
                    fontSize = Tokens.Settings.TextSize,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

/** The app's blue when on, grey when off: never Material's purple. */
@Composable
internal fun settingsSwitchColors(): SwitchColors = SwitchDefaults.colors(
    checkedThumbColor = Color.White,
    checkedTrackColor = Tokens.Settings.Selected,
    checkedBorderColor = Tokens.Settings.Selected,
    uncheckedThumbColor = Tokens.Settings.Help,
    uncheckedTrackColor = Tokens.StripColors.Background,
    uncheckedBorderColor = Tokens.Settings.Button
)
