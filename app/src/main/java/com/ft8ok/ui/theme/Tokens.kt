package com.ft8ok.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Every layout metric and colour the UI uses, in one place.
 *
 * Two reasons this is not spread across the composables. The app is tuned for
 * **direct sunlight on a hillside**, so contrast will be retuned as a set once it
 * has been seen outdoors, and ADR 0010 locks portrait for v1 while requiring that
 * landscape stay a change rather than a rewrite — which only holds if no composable
 * hard-codes a size.
 */
object Tokens {

    /** Rule 9: nothing interactive is smaller than this. */
    val MinTouchTarget: Dp = 48.dp

    /** The decode feed. */
    object Feed {
        /**
         * Row text. Larger than the strip's labels; the rows are under
         * [MinTouchTarget] on purpose, so more of them fit.
         */
        val RowTextSize = 12.sp
        val RowPadding: Dp = 4.dp

        /** Row tint for our transmissions (amber) and rows mentioning us (red). */
        const val TintAlpha = 0.22f

        /**
         * Rows that mention our callsign are this many times a normal row's height,
         * so they are quicker to hit. The others stay thin.
         */
        const val MentionRowScale = 1.5f

        /** Rows from the station we are calling or working: subtle, green. */
        const val PartnerTintAlpha = 0.10f

        /** Rows heard on another band than the radio is on now. */
        const val OtherBandAlpha = 0.45f

        /**
         * Exchanges between other stations - not a CQ, not to us, not our partner -
         * dimmed so the rows worth a tap stand out. A chosen default.
         */
        const val QuietAlpha = 0.6f

        /** The CQ word and its modifier: the Map's CQ green. */
        val CqWord = StripColors.Marker

        /** A sender already in the logbook on this band, and the country/distance note. */
        val WorkedCall = StripColors.Label
        val Note = StripColors.Label

        /** The DUPE mark that takes the country note's place in contest mode. A chosen default. */
        val Dupe = StripColors.Hot

        /** Between the SNR, DT, frequency and message columns. */
        val ColumnGap: Dp = 6.dp

        /** At least this between the message and the country note. */
        val NoteGap: Dp = 12.dp

        /** The filter chips: the Roster's chip size, so the two tabs match. */
        val ChipHeight: Dp = Roster.ChipHeight

        /** The gap above each slot header, between one 15 s cycle and the next. */
        val SlotDividerThickness: Dp = 1.dp

        /** "Back to live", shown while the feed is scrolled back: a full tap target. */
        val BackToLiveHeight: Dp = 48.dp
    }

    /** The bottom nav. A full tap target: it is pressed often. */
    object Nav {
        val Height: Dp = 52.dp

        /** How far a sideways swipe must travel to change tab. A chosen default. */
        val SwipeDistance: Dp = 72.dp
    }

    /** The Roster tab. */
    object Roster {
        /**
         * The feed's density: the Roster is a long list built up over minutes, and
         * as many rows in view as fit matters most.
         */
        val RowHeight: Dp = 24.dp
        val HeaderHeight: Dp = 18.dp

        /** Wide enough for a six-character call; a longer one (with a /) is cut. */
        val CallWidth: Dp = 76.dp
        val ChipHeight: Dp = 30.dp
    }

    /** The Map tab. Colours sit with the strip's dark palette. */
    object Map {
        val Land = Color(0xFF141C24)
        val Border = Color(0xFF34495C)

        /** State and province lines: fainter than a country border. A chosen default. */
        val AdminLine = Color(0xFF263646)
        val FieldLine = Color(0xFF3A4E60)
        val SquareLine = Color(0xFF1C2833)
        val GridLabel = Color(0xFF5E7487)

        /**
         * A heard square is never drawn smaller than this, in pixels, so a busy one is
         * still a dot at World. Kept tiny on purpose, so squares keep their
         * true size as the map zooms out (6 dp was tried and reverted).
         */
        const val MinSquarePx = 5f

        /** Heard within this long reads bright; older, dim. Two slot pairs. A chosen default. */
        const val FreshWithinMs = 60_000L
        const val FreshAlpha = 0.70f
        const val StaleAlpha = 0.35f

        /** A tap picks the nearest heard square within this distance (gloves). */
        val TapRadius: Dp = 28.dp

        /** The tapped square's station list, under the map. */
        val PanelMaxHeight: Dp = 160.dp
    }

    /** The Log tab. */
    object Log {
        /** The feed's density ("tightened up a bit"). Was 40 dp. */
        val RowHeight: Dp = 24.dp

        /** A not-logged row carries a LOG button, so it keeps the 48 dp tap target (rule 9). */
        val NotLoggedRowHeight: Dp = 48.dp
    }

    /** The TX dock above the bottom of the screen. */
    object Dock {
        /**
         * The message picker row. Thin on purpose, so below [MinTouchTarget]: a deliberate exception like the feed
         * rows. Its height is fixed and it is always present, disabled with no
         * partner, so nothing below the feed changes height (see TxReport).
         */
        val PickHeight: Dp = 34.dp
        val PickGap: Dp = 4.dp

        /** The ⋯ joined to CQ: a slice of CQ's width, full button height. */
        val MoreWidth: Dp = 40.dp

        /**
         * The `Contest` line under the CQ button's label while contest mode is on:
         * the strip marker's green. About 5.3:1 on the grey
         * button, about 3:1 on the blue one while CQ is lit; another colour may be
         * needed if it washes out in sun.
         */
        val ContestLine = StripColors.Marker
        val ContestLineSize = 9.sp
    }

    /** The CQ ⋯ panel above the dock. Everything in it meets [MinTouchTarget]. */
    object More {
        val TabHeight: Dp = 48.dp
        val ChipHeight: Dp = 48.dp
        val FieldHeight: Dp = 52.dp
        val SegmentWidth: Dp = 64.dp
        val Gap: Dp = 8.dp
        val Padding: Dp = 10.dp
        val FieldTextSize = 16.sp
        val PreviewTextSize = 13.sp
        val KindTextSize = 11.sp
    }

    /**
     * The Settings tab: each section a card, three text sizes.
     */
    object Settings {
        /** Around the cards, and between one card and the next. */
        val ScreenPadding: Dp = 8.dp
        val CardGap: Dp = 10.dp
        val CardRadius: Dp = 10.dp

        /** Inside a card: left and right of every row, and the gap between controls. */
        val Inner: Dp = 12.dp
        val ControlGap: Dp = 8.dp

        /** A button's height: the full tap target (rule 9). */
        val ButtonHeight: Dp = MinTouchTarget
        val ButtonRadius: Dp = 6.dp

        /** Add beside the frequency field; the segmented bars' corners. */
        val SmallButtonWidth: Dp = 72.dp
        val SegmentRadius: Dp = 8.dp

        /** The only three text sizes on the page, plus the sub-group label. */
        val HeadingSize = 12.5.sp
        val SubHeadingSize = 10.5.sp
        val TextSize = 14.sp
        val HelpSize = 11.5.sp
        val ButtonTextSize = 13.sp

        /** Between the card and the screen behind it: a step up from [StripColors.Screen]. */
        val Card = Color(0xFF0C1116)
        val CardEdge = Color(0xFF18212A)

        /** Section headings: the app's blue. Sub-group labels and help: the label grey. */
        val Heading = StripColors.Occupied
        val SubHeading = StripColors.Label
        val Help = StripColors.Label
        val Text = StripColors.Frequency

        /** Ordinary buttons are grey; only HOLD TO TX is orange, with dark ink on it. */
        val Button = StripColors.TxBand
        val TransmitButton = StripColors.Strong
        val TransmitInk = Color(0xFF1B1206)

        /** A disabled button is an outline with readable text, not a dark ghost. */
        val DisabledEdge = StripColors.Faint
        val DisabledText = Color(0xFF5E7487)

        /** Selected: the app's blue, as the Roster's chips (switch track, segment). */
        val Selected = StripColors.Occupied
        val SegmentEdge = StripColors.TxBand
    }

    object Strip {
        /**
         * One lane of bars, and **the same for both lanes**. Thin on purpose: the
         * strip is not a waterfall. The dimmed parity used to be shorter, which
         * made the two rows swap heights every slot as the TX parity alternated and
         * lurched the whole page. Emphasis is colour, not size.
         */
        val LaneHeight: Dp = 26.dp

        /**
         * The row each lane sits in, and so the lane's tap target. Below
         * [MinTouchTarget] on purpose: at 48 dp the two charts sat
         * 25 dp apart; at 34 dp they are 11 dp apart. Tall enough for the parity name
         * over the TX frequency.
         */
        val LaneRowHeight: Dp = 34.dp

        val LaneGap: Dp = 3.dp
        /** Fits the longest lane label ("EVEN") at [LabelSize] without wrapping. */
        val LabelWidth: Dp = 36.dp

        /**
         * Fixed width for whatever sits at the right of each row. Fixed, not
         * wrapped: letting the trailing text size itself gave each row a different
         * plot width, and a frequency scale that changes between rows is not a
         * frequency scale.
         */
        val TrailingWidth: Dp = 46.dp


        /**
         * Target width of one bar. The real count is derived from the available
         * width, so the strip keeps its texture on any screen instead of stretching
         * a fixed number of bars.
         */
        val BarWidth: Dp = 5.dp
        val BarGap: Dp = 1.dp

        val MarkerWidth: Dp = 2.dp
        val MarkerCapHeight: Dp = 5.dp

        val Padding: Dp = 8.dp
        val LabelSize = 10.sp
        val FrequencySize = 13.sp

        /** Between EVEN/ODD and the countdown in the top bar. */
        val CountdownGap: Dp = 4.dp

        /**
         * Set explicitly: Material's default line height (24 sp) would make the two
         * stacked lines in a lane's side cell taller than [LaneRowHeight].
         */
        val LabelLineHeight = 12.sp
        val FrequencyLineHeight = 15.sp

        /**
         * Level in dB above the slot's noise floor that draws a full-height bar.
         * Beyond this the bar is clipped rather than scaled, since the difference
         * between a strong signal and a very strong one does not change any decision.
         */
        const val FullScaleDb = 24f
    }

    /**
     * Colours tiered to the **same dB thresholds the decision logic uses**, so the
     * display cannot disagree with the policy. In particular
     * [com.ft8ok.dsp.TxFrequency.LOUD_DB] (12 dB) gets its own visibly
     * distinct tier: what Auto treats as loud when keeping clear of neighbours. Full
     * Auto moves from 9 dB on trial, so a move can now come from a blue bar.
     */
    object StripColors {
        /**
         * The page behind the strip. Distinct from [Background] on purpose: when the
         * two matched, an emphasised lane with no bars in it was invisible - the
         * lane was drawn, in exactly the colour of the screen it sat on (seen on the
         * phone).
         */
        val Screen = Color(0xFF02040A)

        /** The emphasised lane's own background. */
        val Background = Color(0xFF07090C)
        val OtherLaneBackground = Color(0xFF101418)

        /** Below the picking threshold: present, not in the way. */
        val Faint = Color(0xFF28465C)

        /** At or above 6 dB: occupied, avoid when choosing. */
        val Occupied = Color(0xFF2E86C1)

        /** At or above 12 dB: strong enough to abandon a held frequency. */
        val Strong = Color(0xFFE8A33D)

        /** At or above 18 dB: loud. */
        val Hot = Color(0xFFE05C4B)

        val Marker = Color(0xFF39FF6A)
        val MarkerUserSet = Color(0xFFFFD23F)
        val TxBand = Color(0xFF4A5A68)
        val Label = Color(0xFF8FA3B4)
        val LabelStale = Color(0xFFE8A33D)
        val Frequency = Color(0xFFE8EEF4)

        /** Tier thresholds in dB above the floor, ascending. */
        const val FaintDb = 3f
        const val OccupiedDb = 6f
        const val StrongDb = 12f
        const val HotDb = 18f
    }
}
