package com.ft8ok.radio

/**
 * Icom CI-V framing and decoding. Pure Kotlin, no Android imports, so the fiddly
 * parts — BCD and frame scanning — are unit-tested on the JVM rather than against a
 * radio.
 *
 * Frame layout, verified on the hardware:
 *
 *     FE FE <to> <from> <cmd> [sub] [data...] FD
 *
 * `FB` is an OK acknowledgement, `FA` is NG.
 *
 * Sources (protocol details are never taken from memory):
 *  - Frame structure and FB/FA: Icom's own CI-V reference guides.
 *  - Command and mode constants: Hamlib `rigs/icom/icom_defs.h`, the reference
 *    implementation WSJT-X itself drives Icom radios through.
 *  - **BCD order: Hamlib `src/misc.c`, `to_bcd()`** — "packed two digits per octet,
 *    in little-endian order (e.g. byte order 90 78 56 34 12 for 1234567890 Hz)".
 *    A web summary claimed the opposite; it is wrong, and trusting it would garble
 *    every frequency.
 */
object Civ {

    const val PREAMBLE = 0xFE
    const val END = 0xFD
    const val ACK_OK = 0xFB
    const val ACK_NG = 0xFA

    /** Icom's documented default address for the IC-7300 (menu default 94h). */
    const val ADDR_IC7300 = 0x94

    /** Conventional controller ("computer") address. */
    const val ADDR_CONTROLLER = 0xE0

    /** Broadcast: every radio on the bus answers. Used when the address is unknown. */
    const val ADDR_BROADCAST = 0x00

    /**
     * Unsolicited "transceive" broadcasts. The IC-7300 announces every VFO and mode
     * change on its own, addressed to 00, using these commands — Hamlib notes
     * "transceive mode does not ack". Observed continuously while turning the dial.
     *
     * The payload layout is identical to the 03/04 replies, so the same decoders
     * work. **Consuming these is better than polling**: updates are instant and the
     * bus stays quiet. Ignoring them means discarding a flood of valid data and
     * calling it a read failure, which is what the first test build did.
     */
    const val CMD_SEND_FREQ = 0x00
    const val CMD_SEND_MODE = 0x01

    const val CMD_READ_FREQ = 0x03
    const val CMD_READ_MODE = 0x04

    /** Either an answer to our request, or an unsolicited transceive broadcast. */
    val FREQUENCY_COMMANDS = setOf(CMD_SEND_FREQ, CMD_READ_FREQ)
    val MODE_COMMANDS = setOf(CMD_SEND_MODE, CMD_READ_MODE)

    /**
     * Meters, Hamlib `C_RD_SQSM` with `S_*` subcommands.
     *
     * Read **while transmitting** - they are the only way the app can tell whether
     * a transmission actually produced RF, which it could not do when three silent
     * TX failures in a row all looked like successes from the inside.
     */
    const val CMD_METER = 0x15
    const val SUB_METER_RFPOWER = 0x11
    const val SUB_METER_SWR = 0x12
    const val SUB_METER_ALC = 0x13

    /**
     * Decode a meter reading: **3-digit BCD in BIG-endian order**, 0000..0255.
     *
     * Note the trap: this is the *opposite* byte order from frequency, which is
     * little-endian. Hamlib's `icom.c` spells out the contrast in a comment next to
     * `from_bcd_be` ("from_bcd is little endian"). Getting it backwards would
     * silently misreport SWR, which this app uses as a safety abort - so a wrong
     * byte order here does not produce a wrong number on a screen, it produces a
     * transmission that should have been stopped and was not.
     */
    fun decodeBcdMeter(bytes: List<Int>): Int? {
        if (bytes.size < 2) return null
        val digits = bytes.take(2).flatMap { listOf((it shr 4) and 0x0F, it and 0x0F) }
        if (digits.any { it > 9 }) return null
        return digits[0] * 1000 + digits[1] * 100 + digits[2] * 10 + digits[3]
    }

    /**
     * IC-7300 SWR from a raw meter reading, interpolated from Hamlib's
     * `IC7300_SWR_CAL`: 0 to 1.0, 48 to 1.5, 80 to 2.0, 120 to 3.0, 240 to 6.0.
     */
    fun swrFromRaw(raw: Int): Double = interpolate(
        raw, listOf(0 to 1.0, 48 to 1.5, 80 to 2.0, 120 to 3.0, 240 to 6.0)
    )

    /**
     * ALC as a fraction of full scale, from Hamlib's `IC7300_ALC_CAL`: raw 0 = 0.0,
     * raw 120 = 1.0. So the meaningful range is 0..120, **not** 0..255.
     *
     * Measured on this radio: ALC reads 0% across the
     * entire usable audio range, so it is **not** a usable calibration signal -
     * informational only, and any ALC movement at all means the drive is too high.
     */
    fun alcFractionFromRaw(raw: Int): Double = (raw / 120.0).coerceIn(0.0, 1.0)

    /**
     * Watts from a raw RF power reading, interpolated from Hamlib's
     * `IC7300_RFPOWER_METER_CAL`. Approximate by nature - it is a meter, not a
     * wattmeter - but far more useful than a raw number when judging drive.
     */
    fun wattsFromPowerRaw(raw: Int): Double = interpolate(
        raw,
        listOf(
            0 to 0.0, 21 to 5.0, 43 to 10.0, 65 to 15.0, 83 to 20.0, 95 to 25.0,
            105 to 30.0, 114 to 35.0, 124 to 40.0, 143 to 50.0, 183 to 75.0,
            213 to 100.0, 255 to 120.0
        )
    )

    /**
     * The raw meter reading corresponding to an SWR of [swr] - the inverse of
     * [swrFromRaw], so an operator-set SWR limit can be compared against what the
     * radio actually reports.
     *
     * Converting the threshold INTO raw once, rather than converting every reading
     * out of raw, keeps the comparison exact at the calibration points: raw 80 is
     * SWR 2.0 by definition, and a limit of 2.0 must trip at exactly 80.
     */
    fun rawFromSwr(swr: Double): Int {
        val points = listOf(0 to 1.0, 48 to 1.5, 80 to 2.0, 120 to 3.0, 240 to 6.0)
        if (swr <= points.first().second) return points.first().first
        if (swr >= points.last().second) return points.last().first
        for (i in 0 until points.size - 1) {
            val (x0, y0) = points[i]
            val (x1, y1) = points[i + 1]
            if (swr in y0..y1) {
                val t = (swr - y0) / (y1 - y0)
                return Math.round(x0 + t * (x1 - x0)).toInt()
            }
        }
        return points.last().first
    }

    /** Raw SWR reading above which transmission must stop by default. 80 raw = SWR 2.0. */
    const val SWR_ABORT_RAW = 80

    private fun interpolate(raw: Int, points: List<Pair<Int, Double>>): Double {
        if (raw <= points.first().first) return points.first().second
        if (raw >= points.last().first) return points.last().second
        for (i in 0 until points.size - 1) {
            val (x0, y0) = points[i]
            val (x1, y1) = points[i + 1]
            if (raw in x0..x1) {
                val t = (raw - x0).toDouble() / (x1 - x0)
                return y0 + t * (y1 - y0)
            }
        }
        return points.last().second
    }

    /*
     * Known and deliberately not sent:
     *
     *   CMD_LEVEL     = 0x14, 0x0A the RF POWER control setting (not the meter)
     */

    /**
     * Moving the radio. From Hamlib `icom_defs.h` and the IC-7300
     * path of `icom.c` (`icom_set_mode_without_data`, `icom_set_mode`,
     * `icom_get_mode`):
     *
     *  - `05` + the 5-byte frequency: set the dial.
     *  - `06` + mode [+ filter]: set the mode, `01` = USB.
     *  - `1A 06` + data [+ filter]: data mode, `01` on / `00` off. Hamlib sends the
     *    filter with it on this radio, and `00` as the filter when switching off.
     *  - `1A 06` alone asks; the reply is `1A 06 <d> [filter]`, any non-zero `d` on.
     *
     * Each set is answered `FB` (done) or `FA` (refused), addressed to us.
     */
    const val CMD_SET_FREQ = 0x05
    const val CMD_SET_MODE = 0x06
    const val CMD_CTL_MEM = 0x1A
    const val SUB_DATA_MODE = 0x06
    const val MODE_USB = 0x01

    fun setFrequency(to: Int, from: Int, hz: Long): ByteArray =
        request(to, from, CMD_SET_FREQ, *encodeBcdFrequencyHz(hz).toIntArray())

    fun setMode(to: Int, from: Int, mode: Int, filter: Int): ByteArray =
        request(to, from, CMD_SET_MODE, mode, filter)

    fun setDataMode(to: Int, from: Int, on: Boolean, filter: Int): ByteArray =
        request(to, from, CMD_CTL_MEM, SUB_DATA_MODE, if (on) 0x01 else 0x00, if (on) filter else 0x00)

    fun readDataMode(to: Int, from: Int): ByteArray =
        request(to, from, CMD_CTL_MEM, SUB_DATA_MODE)

    /** Data mode from the newest `1A 06` reply, or null if there is none. */
    fun newestDataMode(frames: List<Frame>): Boolean? =
        frames.mapNotNull { frame ->
            if (frame.data.size >= 3 && frame.data[0] == CMD_CTL_MEM && frame.data[1] == SUB_DATA_MODE) {
                frame.data[2] != 0
            } else {
                null
            }
        }.lastOrNull()

    /**
     * The radio's SSB-D transmit passband. In USB-D the IC-7300
     * passes only audio between these edges to the transmitter: anything outside is
     * removed before the modulator, so the radio keys with no power (two `NO RF`
     * transmissions at 2838 Hz).
     *
     * From Icom's IC-7300 firmware notes, V1.30 (in the V1.40 information sheet):
     * `MENU » SET > Tone Control/TBW > TX > SSB-D`, TBW, default 300 – 2700; CI-V
     * `1A 05 01 96` sends or reads one byte, the high digit the higher edge
     * (0 = 2500, 1 = 2700, 2 = 2800, 3 = 2900 Hz) and the low digit the lower edge
     * (0 = 100, 1 = 200, 2 = 300, 3 = 500 Hz). Read only; we never set it.
     */
    val SSBD_TBW_ITEM = intArrayOf(0x05, 0x01, 0x96)
    private val TBW_LOWER_HZ = intArrayOf(100, 200, 300, 500)
    private val TBW_HIGHER_HZ = intArrayOf(2500, 2700, 2800, 2900)

    fun readSsbdTxPassband(to: Int, from: Int): ByteArray =
        request(to, from, CMD_CTL_MEM, *SSBD_TBW_ITEM)

    /** The edges, in Hz, from a `1A 05 01 96` setting byte; null for a code not in Icom's table. */
    fun decodeSsbdTxPassband(setting: Int): IntRange? {
        val higher = (setting shr 4) and 0x0F
        val lower = setting and 0x0F
        if (higher !in TBW_HIGHER_HZ.indices || lower !in TBW_LOWER_HZ.indices) return null
        return TBW_LOWER_HZ[lower]..TBW_HIGHER_HZ[higher]
    }

    /** The SSB-D transmit passband from the newest `1A 05 01 96` reply, or null. */
    fun newestSsbdTxPassband(frames: List<Frame>): IntRange? =
        frames.mapNotNull { frame ->
            val d = frame.data
            if (d.size >= 5 && d[0] == CMD_CTL_MEM && d[1] == SSBD_TBW_ITEM[0] &&
                d[2] == SSBD_TBW_ITEM[1] && d[3] == SSBD_TBW_ITEM[2]
            ) {
                decodeSsbdTxPassband(d[4])
            } else {
                null
            }
        }.lastOrNull()

    /**
     * True for `FB`, false for `FA`, null if [frame] is not an acknowledgement from
     * [radioAddress] to [controllerAddress]. The bus echoes our own frames, so the
     * direction is checked, not just the byte.
     */
    fun ackOf(frame: Frame, radioAddress: Int, controllerAddress: Int): Boolean? {
        if (frame.from != radioAddress || frame.to != controllerAddress || frame.data.size != 1) return null
        return when (frame.data[0]) {
            ACK_OK -> true
            ACK_NG -> false
            else -> null
        }
    }

    /** The filter number for a [filterName], 1 when unknown (the IC-7300's FIL1). */
    fun filterCode(name: String?): Int = when (name) {
        "FIL2" -> 0x02
        "FIL3" -> 0x03
        else -> 0x01
    }

    data class Frame(val to: Int, val from: Int, val data: List<Int>) {
        val command: Int? get() = data.firstOrNull()
        override fun toString(): String =
            "to=%02X from=%02X data=%s".format(to, from, data.joinToString(" ") { "%02X".format(it) })
    }

    /** Build a request frame. Bytes are given as ints for readability at call sites. */
    fun request(to: Int, from: Int, vararg payload: Int): ByteArray {
        val out = ArrayList<Int>(payload.size + 5)
        out += PREAMBLE
        out += PREAMBLE
        out += to
        out += from
        payload.forEach { out += it }
        out += END
        return ByteArray(out.size) { out[it].toByte() }
    }

    /**
     * Result of scanning a stream buffer: the complete frames found, and how many
     * leading bytes may now be discarded. Anything after [consumed] is an incomplete
     * tail that must be kept and prepended to the next read.
     */
    data class Scan(val frames: List<Frame>, val consumed: Int)

    /**
     * Incremental stream parser. **Call this on a buffer that persists across reads.**
     *
     * CI-V is a byte stream, so a frame can straddle two reads: once a reply
     * arrived as "...FD FE" followed by "FE E0 94 03 ..." in the next read, and
     * parsing each read in isolation found nothing in either — the frame was split
     * through its own preamble. Retaining the tail reassembles it.
     */
    fun scan(buffer: ByteArray, length: Int): Scan {
        fun at(index: Int) = buffer[index].toInt() and 0xFF

        val frames = mutableListOf<Frame>()
        var i = 0
        var consumed = 0

        while (i < length - 1) {
            if (at(i) != PREAMBLE || at(i + 1) != PREAMBLE) {
                // Not a frame start: this byte is unusable, so let it be discarded.
                i++
                consumed = i
                continue
            }
            // Runs of more than two FE occur in practice — a connect reply began
            // "FE FE FE 94 E0 03 FD". Consume the whole run so the byte after it is
            // the address, not another preamble byte.
            var addr = i
            while (addr < length && at(addr) == PREAMBLE) addr++
            if (addr + 2 > length) break // need `to` and `from`; keep the tail

            val end = (addr until length).firstOrNull { at(it) == END }
                ?: break // incomplete frame; keep the tail for the next read

            frames += Frame(at(addr), at(addr + 1), (addr + 2 until end).map { at(it) })
            i = end + 1
            consumed = i
        }
        return Scan(frames, consumed)
    }

    /** Convenience for a self-contained buffer; prefer [scan] for a live stream. */
    fun parseFrames(buffer: ByteArray, length: Int = buffer.size): List<Frame> =
        scan(buffer, length).frames

    /**
     * Is this frame our own request coming back at us?
     *
     * The bus is half-duplex and echoes everything we send. An echo carries the same
     * command byte as the reply we are waiting for but an **empty payload**, so
     * selecting frames by command alone picks up our own question and decodes it as
     * an answer. Select by decodable content, never by position or command.
     */
    fun isEcho(frame: Frame, controllerAddress: Int): Boolean =
        frame.from == controllerAddress || frame.data.size <= 1

    /**
     * The **newest** decodable frequency among [frames], or null if there is none.
     *
     * Newest rather than first because while the dial is turning the buffer holds a
     * run of superseded values, and because the first frame is often our own echo.
     */
    fun newestFrequencyHz(frames: List<Frame>): Long? =
        frames.mapNotNull { frame ->
            if (frame.command in FREQUENCY_COMMANDS) decodeBcdFrequencyHz(frame.data.drop(1)) else null
        }.lastOrNull()

    /** Mode and filter from the newest mode reply or broadcast. */
    fun newestMode(frames: List<Frame>): Pair<String, String?>? =
        frames.mapNotNull { frame ->
            if (frame.command in MODE_COMMANDS && frame.data.size >= 2) {
                modeName(frame.data[1]) to frame.data.getOrNull(2)?.let { filterName(it) }
            } else {
                null
            }
        }.lastOrNull()

    /**
     * Decode Icom little-endian packed BCD into Hz.
     *
     * `bytes[0]` holds the 1 Hz and 10 Hz digits, `bytes[4]` the 100 MHz and 1 GHz
     * digits; each byte's high nibble is the more significant digit. Returns null if
     * any nibble is not a decimal digit, which is how a mis-framed or non-frequency
     * payload shows up — and is why an echo with an empty payload decodes to null
     * rather than to a plausible wrong number.
     */
    fun decodeBcdFrequencyHz(bytes: List<Int>): Long? {
        if (bytes.size < 5) return null
        var hz = 0L
        var scale = 1L
        for (i in 0 until 5) {
            val b = bytes[i]
            val high = (b shr 4) and 0x0F
            val low = b and 0x0F
            if (high > 9 || low > 9) return null
            hz += (high * 10 + low) * scale
            scale *= 100
        }
        return hz
    }

    /**
     * Encode Hz as Icom little-endian packed BCD, the inverse of
     * [decodeBcdFrequencyHz].
     *
     * Used to set the dial ([setFrequency]). The test vector it is checked against
     * is the thing that proves the byte order - a reversed codec round-trips its own
     * error and looks perfectly healthy.
     */
    fun encodeBcdFrequencyHz(hz: Long): List<Int> {
        require(hz >= 0) { "negative frequency" }
        var remaining = hz
        return (0 until 5).map {
            val pair = (remaining % 100).toInt()
            remaining /= 100
            ((pair / 10) shl 4) or (pair % 10)
        }
    }

    /** Mode codes from Hamlib `icom_defs.h`. */
    fun modeName(code: Int): String = when (code) {
        0x00 -> "LSB"
        0x01 -> "USB"
        0x02 -> "AM"
        0x03 -> "CW"
        0x04 -> "RTTY"
        0x05 -> "FM"
        0x06 -> "WFM"
        0x07 -> "CW-R"
        0x08 -> "RTTY-R"
        0x11 -> "AMS"
        0x12 -> "PSK"
        0x13 -> "PSK-R"
        else -> "unknown(0x%02X)".format(code)
    }

    /** The IC-7300 reports the selected IF filter alongside the mode. */
    fun filterName(code: Int): String = when (code) {
        0x01 -> "FIL1"
        0x02 -> "FIL2"
        0x03 -> "FIL3"
        else -> "filter(0x%02X)".format(code)
    }

    fun formatHz(hz: Long): String {
        val mhz = hz / 1_000_000
        val rest = hz % 1_000_000
        return "%d.%06d MHz".format(mhz, rest)
    }

    fun hex(bytes: ByteArray, length: Int = bytes.size): String =
        (0 until length).joinToString(" ") { "%02X".format(bytes[it].toInt() and 0xFF) }
}
