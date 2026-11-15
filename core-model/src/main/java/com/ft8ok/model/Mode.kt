package com.ft8ok.model

/**
 * Which half of the alternating slot pattern a slot belongs to.
 *
 * FT8 stations transmit in alternate slots, so parity is not a detail of the UI —
 * it decides which spectrum is relevant to our transmission (the previous slot of
 * the **same** parity, 30 s ago rather than 15 s) and which slot we must answer in
 * (the opposite of our partner's). [Mode.parityOf] derives it from the slot's start
 * time, so nothing has to track it by counting.
 */
enum class SlotParity {
    /** Slots beginning at :00 and :30 of each minute. */
    EVEN,

    /** Slots beginning at :15 and :45. */
    ODD;

    val other: SlotParity get() = if (this == EVEN) ODD else EVEN
}

/**
 * Everything about a mode that is a number (ADR 0006).
 *
 * **This is the only place FT8 and FT4 constants may appear.** The rule exists
 * because FT8's 3.5 s late-start limit is longer than an entire FT4 transmission,
 * so anything hard-coded against FT8 is not merely untidy, it is wrong the moment
 * FT4 is switched on. If a timing number is needed anywhere else in the app, it is
 * read from here.
 *
 * Pure Kotlin, no Android types: the whole timing layer above it is then testable
 * on the JVM in about a second with no phone and no radio.
 *
 * v1 ships FT8 only (ADR 0011), but [FT4] is defined and unit-tested now. That is
 * what makes FT4 a later switch-on rather than a rewrite.
 */
data class Mode(
    /**
     * Short name, as it appears on air. **Not** always the ADIF MODE: ADIF files FT4
     * as `MODE=MFSK SUBMODE=FT4`, so the mapping lives in `core-log`, not here.
     */
    val name: String,

    /** Length of one transmit/receive slot. */
    val slotMs: Long,

    /**
     * How far into the slot a correctly-synchronised station starts transmitting.
     *
     * Also the reference that converts a decoded DT into a clock error — the band
     * consensus measures `DT - nominalTxStart`, so an error here biases every
     * correction by the same amount.
     */
    val nominalTxStartMs: Long,

    /** How long the transmission itself lasts, once started. */
    val txDurationMs: Long,

    /**
     * When to run decode passes, measured from the slot boundary.
     *
     * More than one because strong signals resolve before the transmission has
     * finished, and the operator's decision budget after the final pass is under a
     * second. Ascending.
     */
    val decodePassesMs: List<Long>,

    /**
     * How late a transmission may still be started, past [nominalTxStartMs].
     *
     * A single named constant per mode, with no user setting, by decision:
     * if a decision lands later than this the slot is simply
     * skipped and the queue carries to the next slot of the same parity. It will be
     * tuned after on-air tests.
     */
    val lateStartMs: Long,

    /** When transmission must cease regardless, measured from the slot boundary. */
    val hardStopMs: Long,

    /**
     * How long past the slot boundary to wait for the decodes of the slot that
     * just ended (the partner's) before preparing a transmission without them.
     *
     * Their final pass normally lands before the boundary; FT8's latest in 3,123
     * slots on the test phone was 105 ms after it (events.log). A slot
     * whose audio was lost never decodes at all, and waiting longer for it only
     * made us late: the old limit, half the late-start window, started a call 2.2 s
     * late once.
     */
    val decodeWaitMs: Long,

    /**
     * How far behind its target a late transmission may start with the whole
     * message. Later than that, the start of the message is left out, so receivers
     * see it this late and no later. 0 is WSJT-X's own late
     * start: always on time, everything missed left out.
     */
    val maxLateShiftMs: Long,

    /**
     * How far apart the windows of a grid search start. Must be
     * under the span one window's decoder can search, or an offset falls between two.
     */
    val gridSearchStepMs: Long,

    /** Windows per grid search, each one slot long. */
    val gridSearchWindows: Int,

    /** How long a grid search remembers what it heard. */
    val gridHistoryMs: Long,

    /**
     * How wide one signal is: its tone count times its tone spacing. Where a report
     * adds up a signal's power. `Occupancy` still uses FT8's 50 Hz
     * for its gaps; FT4's transmit side is not built.
     */
    val signalWidthHz: Double
) {

    /** When a transmission started at the nominal time would end. */
    val nominalTxEndMs: Long get() = nominalTxStartMs + txDurationMs

    /**
     * When subtraction must stop, from the slot's start: inside
     * [decodeWaitMs], with room for a look already under way to finish. It is what
     * replaces a Fast/Normal/Deep setting.
     */
    val deepDecodeEndMs: Long get() = slotMs + decodeWaitMs - DEEP_DECODE_MARGIN_MS

    /** The latest moment a transmission may begin. */
    val latestTxStartMs: Long get() = nominalTxStartMs + lateStartMs

    /**
     * May a transmission still be started this far into the slot?
     *
     * **A refusal costs nothing but the transmission**. If the
     * answer is no the slot is simply not transmitted in: receive carries on, the
     * current slot goes on decoding, and the queue carries to the next slot of the
     * same parity. Nothing is torn down and no slot is given up.
     *
     * That last part is the whole point and is a deliberate difference from other
     * software, where a late TX click takes the receive channel away for a
     * transmission too late to be decoded — losing the transmission *and* the slot
     * it was sent in, and usually the answer that would have come back. Refusing
     * early is strictly better: we keep hearing the band, and the operator loses one
     * cycle instead of two.
     */
    fun mayStartTxAt(positionInSlotMs: Long): Boolean =
        positionInSlotMs in nominalTxStartMs..latestTxStartMs

    /** The slot boundary at or before [timeMs], in the same timebase as [timeMs]. */
    fun slotStartMs(timeMs: Long): Long = Math.floorDiv(timeMs, slotMs) * slotMs

    /** How far into its slot [timeMs] falls. */
    fun positionInSlotMs(timeMs: Long): Long = Math.floorMod(timeMs, slotMs)

    /**
     * Parity of the slot beginning at [slotStartMs].
     *
     * Defined against the UTC epoch rather than against an arbitrary first slot, so
     * two devices agree without having to talk to each other. The epoch is midnight
     * UTC, so slot 0 is :00 — an even slot — and the pattern holds indefinitely.
     */
    fun parityOf(slotStartMs: Long): SlotParity =
        if (Math.floorMod(Math.floorDiv(slotStartMs, slotMs), 2L) == 0L) SlotParity.EVEN
        else SlotParity.ODD

    companion object {

        /**
         * Kept back from [decodeWaitMs] for a look already under way, and for a final
         * pass that started late: the latest seen was 105 ms past the boundary.
         */
        private const val DEEP_DECODE_MARGIN_MS = 100L

        /**
         * FT8. Every number here is from the protocol as WSJT-X implements it.
         *
         * The transmission is 79 symbols at 1920 samples per symbol at 12 kHz =
         * 12.64 s, which is why a message that starts at 0.5 s finishes around
         * 13.15 s — the fact the decode schedule is built around.
         */
        val FT8 = Mode(
            name = "FT8",
            slotMs = 15_000,
            nominalTxStartMs = 500,
            txDurationMs = 12_640,
            // WSJT-X's own schedule. The first pass lands before the transmission
            // has finished and is expected to find only the strong signals; that is
            // the point of having it, since it buys the operator seconds.
            decodePassesMs = listOf(11_800, 13_500, 14_700),
            lateStartMs = 3_500,
            hardStopMs = 14_700,
            // Covers the latest final pass seen (+105 ms) and still leaves the warm
            // output path (110-130 ms) time to start the signal at +500 ms.
            decodeWaitMs = 300,
            // Measured best on the host decoder, and
            // about 1 s inside WSJT-X's ±2.5 s search for clock errors at both ends.
            maxLateShiftMs = 1_500,
            // Measured on IC-7300 recordings: the decoder searches
            // about 4.6 s of one window, and these five cover 27 s of audio. 60 s of
            // votes is where the pooled vote beat a shorter history.
            gridSearchStepMs = 3_000,
            gridSearchWindows = 5,
            gridHistoryMs = 60_000,
            // 8 tones, 6.25 Hz apart.
            signalWidthHz = 50.0
        )

        /**
         * FT4, used by the FT4OK build.
         *
         * Slot length, transmission length and the decode passes are the protocol's.
         * [lateStartMs] comes from the late-start bench below. It is deliberately not
         * FT8's 3.5 s, which would run into the next slot.
         */
        val FT4 = Mode(
            name = "FT4",
            slotMs = 7_500,
            // Verified from WSJT-X: DT 0 puts the first
            // SYNC symbol at 0.5 s, and the waveform starts one 48 ms ramp symbol
            // before it (ft4sim.f90: k = nint((xdt+0.5)/dt) - NSPS). This is the
            // waveform's start, as FT8's 500 is, so the decoder's bias
            // (Ft8Decoder.DT_BIAS_SEC_FT4, measured from the waveform start) adds to it.
            nominalTxStartMs = 452,
            // 105 symbols x 576 samples at 12 kHz (ft4_params.f90: NZ2 = 60480),
            // ramps included. It used to say 4480, which matched no FT4 arithmetic.
            txDurationMs = 5_040,
            decodePassesMs = listOf(5_600, 6_050),
            // Host bench: with the
            // 0.6 s whole-message cap below, a message started 1.3 s late still decoded
            // 54% of the time at -12.5 dB and 99% at -10 dB (ft8_lib, 80 trials a
            // point); at the old 1.75 s placeholder it was about 5% at -12.5 dB.
            lateStartMs = 1_300,
            hardStopMs = 7_200,
            // No wait: FT4's final pass,
            // subtraction included, finished at +6.07..+6.29 s on a test phone, over a
            // second before the boundary, so TX decides at the boundary, 452 ms before
            // our start (300 left only 152 ms against a 110-130 ms output delay).
            // May need adjusting on slower phones: a final pass landing
            // after the boundary is not waited for, and our message is then chosen
            // without the partner's last slot.
            decodeWaitMs = 0,
            // A late message goes out whole up to 0.6 s late, its start left out beyond
            // Bench: whole-message shifts
            // decoded as well as on time up to 0.9 s (but for an ft8_lib dip at
            // 0.30-0.45 s, which is ft8_lib's own), and 0% from
            // 1.1 s, the edge of the start search - WSJT-X's own is DT +1.0 s
            // (ft4_decode.f90, verified). 0.6 leaves 0.4 s of that for clock error.
            maxLateShiftMs = 600,
            // Under the span one FT4 window searches: -0.53..+1.54 s, about 2.0 s,
            // since ft8_lib's FT4 range was widened to WSJT-X's.
            // Five windows 1.5 s apart cover a whole 7.5 s slot. Not yet measured
            // live on FT4; the history is scaled from FT8 with the slot.
            gridSearchStepMs = 1_500,
            gridSearchWindows = 5,
            gridHistoryMs = 30_000,
            // 4 tones, 12000/576 = 20.83 Hz apart.
            signalWidthHz = 4 * 12_000.0 / 576
        )

        /** Every mode the app knows, whether or not v1 exposes it. */
        val ALL = listOf(FT8, FT4)
    }
}
