package com.ft8ok.radio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The CI-V protocol on the JVM.
 *
 * Every test here corresponds to something that actually went wrong, or could, with
 * a radio attached. The byte sequences are the shapes the IC-7300 really produces.
 */
class CivTest {

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    /** A frequency broadcast: FE FE 00 94 00 <5 BCD> FD. */
    private fun frequencyFrame(vararg bcd: Int) =
        bytes(0xFE, 0xFE, 0x00, 0x94, 0x00, *bcd.toTypedArray().toIntArray(), 0xFD)

    // --- BCD ---------------------------------------------------------------------

    @Test
    fun `the hamlib test vector pins the byte order`() {
        // The one case that proves little-endian. A reversed codec round-trips its
        // own error perfectly and looks healthy, so decoding what we encoded would
        // prove nothing; this vector is external.
        assertEquals(
            listOf(0x90, 0x78, 0x56, 0x34, 0x12),
            Civ.encodeBcdFrequencyHz(1_234_567_890L)
        )
        assertEquals(
            1_234_567_890L,
            Civ.decodeBcdFrequencyHz(listOf(0x90, 0x78, 0x56, 0x34, 0x12))
        )
    }

    @Test
    fun `the frequencies this app actually uses round-trip`() {
        for (hz in listOf(1_840_000L, 7_074_000L, 14_074_000L, 18_100_000L, 28_074_000L, 7_047_500L)) {
            assertEquals(hz, Civ.decodeBcdFrequencyHz(Civ.encodeBcdFrequencyHz(hz)))
        }
    }

    @Test
    fun `20m FT8 encodes to its known byte pattern`() {
        assertEquals(
            listOf(0x00, 0x40, 0x07, 0x14, 0x00),
            Civ.encodeBcdFrequencyHz(14_074_000L)
        )
    }

    @Test
    fun `a payload that is not BCD decodes to nothing, not to a plausible number`() {
        // 0x0A is not a decimal digit. Returning null is what stops a mis-framed
        // buffer being read as a frequency and acted on.
        assertNull(Civ.decodeBcdFrequencyHz(listOf(0x00, 0x40, 0x0A, 0x14, 0x00)))
        assertNull(Civ.decodeBcdFrequencyHz(listOf(0x00, 0x40)))
        assertNull(Civ.decodeBcdFrequencyHz(emptyList()))
    }

    // --- Framing -----------------------------------------------------------------

    @Test
    fun `a frame split across two reads is reassembled`() {
        // The exact shape seen once: the split fell inside the preamble,
        // so neither read contained a parseable frame on its own.
        val reader = CivReader()
        reader.onBytes(bytes(0xFD, 0xFE))
        assertNull("nothing decodable yet", reader.state.dialHz)

        reader.onBytes(bytes(0xFE, 0x00, 0x94, 0x00, 0x00, 0x40, 0x07, 0x14, 0x00, 0xFD))
        assertEquals(14_074_000L, reader.state.dialHz)
    }

    @Test
    fun `a run of more than two preamble bytes is handled`() {
        // A connect reply began "FE FE FE 94 E0 03 FD" - three preamble bytes, so a
        // parser that assumes exactly two reads the address as a preamble.
        val frames = Civ.parseFrames(bytes(0xFE, 0xFE, 0xFE, 0x94, 0xE0, 0x03, 0xFD))
        assertEquals(1, frames.size)
        assertEquals(0x94, frames[0].to)
        assertEquals(0xE0, frames[0].from)
    }

    @Test
    fun `leading rubbish is discarded rather than blocking the stream`() {
        val reader = CivReader()
        reader.onBytes(bytes(0x11, 0x22, 0x33))
        reader.onBytes(frequencyFrame(0x00, 0x40, 0x07, 0x14, 0x00))
        assertEquals(14_074_000L, reader.state.dialHz)
    }

    @Test
    fun `several frames in one read are all seen, and the newest wins`() {
        // While the dial turns, the buffer fills with superseded values.
        val reader = CivReader()
        val burst = frequencyFrame(0x00, 0x40, 0x07, 0x14, 0x00) +
            frequencyFrame(0x00, 0x50, 0x07, 0x14, 0x00) +
            frequencyFrame(0x00, 0x60, 0x07, 0x14, 0x00)
        reader.onBytes(burst)
        assertEquals(14_076_000L, reader.state.dialHz)
    }

    // --- Echo --------------------------------------------------------------------

    @Test
    fun `our own echoed request is not mistaken for an answer`() {
        // The bus echoes everything we send. The echo carries the same command byte
        // as the reply but an empty payload, so selecting by command alone reads our
        // own question as an answer.
        val reader = CivReader()
        reader.onBytes(frequencyFrame(0x00, 0x40, 0x07, 0x14, 0x00))
        assertEquals(14_074_000L, reader.state.dialHz)

        // Our request for the frequency, echoed back: FE FE 94 E0 03 FD.
        reader.onBytes(bytes(0xFE, 0xFE, 0x94, 0xE0, 0x03, 0xFD))
        assertEquals("the echo must not disturb the known state", 14_074_000L, reader.state.dialHz)
        assertTrue(reader.echoesDropped > 0)
    }

    // --- Mode --------------------------------------------------------------------

    @Test
    fun `mode and filter come from a transceive broadcast`() {
        val reader = CivReader()
        // FE FE 00 94 01 01 02 FD - mode USB, filter FIL2.
        reader.onBytes(bytes(0xFE, 0xFE, 0x00, 0x94, 0x01, 0x01, 0x02, 0xFD))
        assertEquals("USB", reader.state.mode)
        assertEquals("FIL2", reader.state.filter)
    }

    @Test
    fun `a mode update does not wipe the known frequency`() {
        val reader = CivReader()
        reader.onBytes(frequencyFrame(0x00, 0x00, 0x10, 0x18, 0x00))   // 18.100 MHz
        reader.onBytes(bytes(0xFE, 0xFE, 0x00, 0x94, 0x01, 0x01, 0x01, 0xFD))
        assertEquals(18_100_000L, reader.state.dialHz)
        assertEquals("USB", reader.state.mode)
    }

    // --- State -------------------------------------------------------------------

    @Test
    fun `the band comes from the verified table, and is null off it`() {
        val reader = CivReader()
        reader.onBytes(frequencyFrame(0x00, 0x00, 0x10, 0x18, 0x00))
        assertEquals("17m", reader.state.band)
        assertTrue(reader.state.onFt8Frequency)

        // Tuned away to the phone band: no band label, and not an FT8 frequency.
        reader.onBytes(frequencyFrame(0x00, 0x50, 0x24, 0x14, 0x00))   // 14.245 MHz
        assertNull(reader.state.band)
        assertTrue(!reader.state.onFt8Frequency)
    }

    @Test
    fun `nothing is known before the radio has said anything`() {
        val reader = CivReader()
        assertNull(reader.state.dialHz)
        assertNull(reader.state.mode)
        assertNull(reader.state.band)
        assertNull(reader.state.lastHeardMs)
        // Crucially not false-but-confident: "not on an FT8 frequency" and "we have
        // not been told the frequency" must not look the same to a guard.
        assertTrue(!reader.state.onFt8Frequency)
    }

    @Test
    fun `the buffer does not grow without bound on a stream that never parses`() {
        // A wrong baud rate produces endless plausible-looking noise. Holding all of
        // it would be a slow leak on a link that never stops talking.
        val reader = CivReader()
        val noise = ByteArray(1000) { 0x55 }
        repeat(100) { reader.onBytes(noise) }
        // Still functional afterwards: the real test is that a good frame now works.
        reader.onBytes(frequencyFrame(0x00, 0x40, 0x07, 0x14, 0x00))
        assertEquals(14_074_000L, reader.state.dialHz)
    }

    @Test
    fun `a partial frame is held, not discarded, however it is chopped up`() {
        // Feed one valid frame one byte at a time. Nothing may be lost.
        val reader = CivReader()
        val frame = frequencyFrame(0x00, 0x40, 0x07, 0x14, 0x00)
        for (b in frame) reader.onBytes(byteArrayOf(b))
        assertEquals(14_074_000L, reader.state.dialHz)
    }

    @Test
    fun `last heard advances only when something is understood`() {
        var clock = 1_000L
        val reader = CivReader(nowMs = { clock })
        reader.onBytes(frequencyFrame(0x00, 0x40, 0x07, 0x14, 0x00))
        assertEquals(1_000L, reader.state.lastHeardMs)

        clock = 2_000L
        reader.onBytes(bytes(0x11, 0x22))          // rubbish
        assertEquals("rubbish is not news", 1_000L, reader.state.lastHeardMs)

        clock = 3_000L
        reader.onBytes(frequencyFrame(0x00, 0x40, 0x07, 0x14, 0x00))
        assertEquals(3_000L, reader.state.lastHeardMs)
    }

    // --- meters ------------------------------------------------------------------

    @Test
    fun `meter readings are BIG-endian BCD, the opposite of frequency`() {
        // The trap. Frequency is little-endian; meters are not. Reversing this does
        // not produce a wrong number on a screen - it produces an SWR abort that
        // does not fire.
        assertEquals(0, Civ.decodeBcdMeter(listOf(0x00, 0x00)))
        assertEquals(56, Civ.decodeBcdMeter(listOf(0x00, 0x56)))
        assertEquals(120, Civ.decodeBcdMeter(listOf(0x01, 0x20)))
        assertEquals(255, Civ.decodeBcdMeter(listOf(0x02, 0x55)))

        // Read the other way round, 0x01 0x20 would be 2001 rather than 120.
        assertTrue(Civ.decodeBcdMeter(listOf(0x01, 0x20)) != 2001)
    }

    @Test
    fun `a payload that is not BCD gives no reading rather than a wrong one`() {
        assertNull(Civ.decodeBcdMeter(listOf(0x0A, 0x20)))
        assertNull(Civ.decodeBcdMeter(listOf(0x01)))
        assertNull(Civ.decodeBcdMeter(emptyList()))
    }

    @Test
    fun `SWR crosses the abort threshold where Hamlib's calibration says it should`() {
        // 80 raw is SWR 2.0, and 2.0 is where we stop transmitting.
        assertEquals(1.0, Civ.swrFromRaw(0), 0.01)
        assertEquals(1.5, Civ.swrFromRaw(48), 0.01)
        assertEquals(2.0, Civ.swrFromRaw(Civ.SWR_ABORT_RAW), 0.01)
        assertEquals(3.0, Civ.swrFromRaw(120), 0.01)
        assertEquals(6.0, Civ.swrFromRaw(240), 0.01)

        // Monotonic, and clamped outside the calibration points.
        assertTrue(Civ.swrFromRaw(100) > Civ.swrFromRaw(90))
        assertEquals(6.0, Civ.swrFromRaw(255), 0.01)
    }

    @Test
    fun `power in watts matches the calibration points`() {
        assertEquals(0.0, Civ.wattsFromPowerRaw(0), 0.01)
        assertEquals(10.0, Civ.wattsFromPowerRaw(43), 0.01)
        // The 13 W measured into a dummy load at -11 dBFS read raw 56.
        assertEquals(13.0, Civ.wattsFromPowerRaw(56), 0.5)
        assertEquals(100.0, Civ.wattsFromPowerRaw(213), 0.01)
        assertEquals(120.0, Civ.wattsFromPowerRaw(255), 0.01)
    }

    @Test
    fun `ALC is a fraction of 120, not of 255`() {
        // The meaningful range is 0..120 (Hamlib IC7300_ALC_CAL). Treating it as
        // 0..255 would report half the ALC that is really there.
        assertEquals(0.0, Civ.alcFractionFromRaw(0), 0.001)
        assertEquals(0.5, Civ.alcFractionFromRaw(60), 0.001)
        assertEquals(1.0, Civ.alcFractionFromRaw(120), 0.001)
        assertEquals(1.0, Civ.alcFractionFromRaw(255), 0.001)
    }

    @Test
    fun `an operator-set SWR limit converts back to the raw reading exactly`() {
        // The threshold is converted INTO raw once rather than every reading out of
        // it, so a limit of 2.0 trips at exactly the 80 the calibration defines.
        assertEquals(80, Civ.rawFromSwr(2.0))
        assertEquals(48, Civ.rawFromSwr(1.5))
        assertEquals(120, Civ.rawFromSwr(3.0))

        // Round trip through both directions at the points that matter.
        for (swr in listOf(1.5, 2.0, 2.5, 3.0)) {
            assertEquals(swr, Civ.swrFromRaw(Civ.rawFromSwr(swr)), 0.05)
        }
        // Monotonic between them.
        assertTrue(Civ.rawFromSwr(2.5) > Civ.rawFromSwr(2.0))
    }

    // --- Setting the radio ------------------------------------

    private fun hex(b: ByteArray) = b.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }

    @Test
    fun `set commands have the hamlib shapes`() {
        assertEquals("FE FE 94 E0 05 00 30 57 03 00 FD", hex(Civ.setFrequency(0x94, 0xE0, 3_573_000)))
        assertEquals("FE FE 94 E0 06 01 01 FD", hex(Civ.setMode(0x94, 0xE0, Civ.MODE_USB, 1)))
        assertEquals("FE FE 94 E0 1A 06 01 02 FD", hex(Civ.setDataMode(0x94, 0xE0, true, 2)))
        // Off sends 00 as the filter, as Hamlib does.
        assertEquals("FE FE 94 E0 1A 06 00 00 FD", hex(Civ.setDataMode(0x94, 0xE0, false, 2)))
        assertEquals("FE FE 94 E0 1A 06 FD", hex(Civ.readDataMode(0x94, 0xE0)))
    }

    @Test
    fun `acks count only from the radio to us`() {
        val ok = Civ.parseFrames(bytes(0xFE, 0xFE, 0xE0, 0x94, 0xFB, 0xFD)).single()
        val ng = Civ.parseFrames(bytes(0xFE, 0xFE, 0xE0, 0x94, 0xFA, 0xFD)).single()
        val wrongWay = Civ.parseFrames(bytes(0xFE, 0xFE, 0x94, 0xE0, 0xFB, 0xFD)).single()
        assertEquals(true, Civ.ackOf(ok, 0x94, 0xE0))
        assertEquals(false, Civ.ackOf(ng, 0x94, 0xE0))
        assertNull(Civ.ackOf(wrongWay, 0x94, 0xE0))
    }

    @Test
    fun `the reader learns data mode from its reply, not from our echo`() {
        val reader = CivReader()
        // Our own question echoed back carries no answer.
        reader.onBytes(bytes(0xFE, 0xFE, 0x94, 0xE0, 0x1A, 0x06, 0xFD))
        assertNull(reader.state.dataMode)
        reader.onBytes(bytes(0xFE, 0xFE, 0xE0, 0x94, 0x1A, 0x06, 0x01, 0x01, 0xFD))
        assertEquals(true, reader.state.dataMode)
        reader.onBytes(bytes(0xFE, 0xFE, 0xE0, 0x94, 0x1A, 0x06, 0x00, 0x00, 0xFD))
        assertEquals(false, reader.state.dataMode)
    }

    @Test
    fun `a new mode makes data mode unknown, the same mode does not`() {
        val reader = CivReader()
        reader.onBytes(bytes(0xFE, 0xFE, 0x00, 0x94, 0x01, 0x01, 0x01, 0xFD)) // USB
        reader.onBytes(bytes(0xFE, 0xFE, 0xE0, 0x94, 0x1A, 0x06, 0x01, 0x01, 0xFD))
        assertTrue(reader.state.inFt8Mode)
        assertEquals("USB-D", reader.state.modeLabel)

        reader.onBytes(bytes(0xFE, 0xFE, 0x00, 0x94, 0x01, 0x01, 0x02, 0xFD)) // USB, filter 2
        assertEquals(true, reader.state.dataMode)

        reader.onBytes(bytes(0xFE, 0xFE, 0x00, 0x94, 0x01, 0x00, 0x01, 0xFD)) // LSB
        assertNull(reader.state.dataMode)
        assertEquals("LSB", reader.state.modeLabel)
    }

    /**
     * Seen once: from LSB, a tune set USB and data on, but only data mode was asked,
     * and the app showed LSB-D until restarted. The tune now asks the mode (04) and
     * then data mode; this is the order of answers that must end in USB-D.
     */
    @Test
    fun `a mode answer then a data answer after a tune from LSB gives USB-D`() {
        val reader = CivReader()
        reader.onBytes(bytes(0xFE, 0xFE, 0xE0, 0x94, 0x04, 0x00, 0x01, 0xFD)) // LSB at connect
        reader.onBytes(bytes(0xFE, 0xFE, 0xE0, 0x94, 0x1A, 0x06, 0x00, 0x00, 0xFD))
        assertEquals("LSB", reader.state.modeLabel)

        // What the old tune left behind: data asked, mode never asked.
        val stale = CivReader()
        stale.onBytes(bytes(0xFE, 0xFE, 0xE0, 0x94, 0x04, 0x00, 0x01, 0xFD))
        stale.onBytes(bytes(0xFE, 0xFE, 0xE0, 0x94, 0x1A, 0x06, 0x01, 0x01, 0xFD))
        assertEquals("LSB-D", stale.state.modeLabel)

        reader.onBytes(bytes(0xFE, 0xFE, 0xE0, 0x94, 0x04, 0x01, 0x01, 0xFD)) // USB
        assertNull(reader.state.dataMode)
        reader.onBytes(bytes(0xFE, 0xFE, 0xE0, 0x94, 0x1A, 0x06, 0x01, 0x01, 0xFD))
        assertTrue(reader.state.inFt8Mode)
        assertEquals("USB-D", reader.state.modeLabel)
    }

    @Test
    fun `acks reach lastFrames although the state ignores them`() {
        val reader = CivReader()
        reader.onBytes(bytes(0xFE, 0xFE, 0xE0, 0x94, 0xFB, 0xFD))
        assertEquals(true, reader.lastFrames.firstNotNullOfOrNull { Civ.ackOf(it, 0x94, 0xE0) })
    }

    // --- SSB-D transmit passband, 1A 05 01 96 ---------------

    @Test
    fun `the SSB-D passband byte decodes by Icom's table`() {
        // High digit the higher edge, low digit the lower edge (V1.30 notes).
        assertEquals(300..2700, Civ.decodeSsbdTxPassband(0x12))   // the radio's default
        assertEquals(100..2500, Civ.decodeSsbdTxPassband(0x00))
        assertEquals(500..2900, Civ.decodeSsbdTxPassband(0x33))
        assertNull("a code not in the table", Civ.decodeSsbdTxPassband(0x41))
    }

    @Test
    fun `the read request is 1A 05 01 96`() {
        assertEquals(
            listOf(0xFE, 0xFE, 0x94, 0xE0, 0x1A, 0x05, 0x01, 0x96, 0xFD),
            Civ.readSsbdTxPassband(0x94, 0xE0).map { it.toInt() and 0xFF }
        )
    }

    @Test
    fun `a passband reply lands in the state, and our echoed request does not`() {
        val reader = CivReader()
        // Our own request echoed back: no setting byte, so nothing is learned.
        reader.onBytes(bytes(0xFE, 0xFE, 0x94, 0xE0, 0x1A, 0x05, 0x01, 0x96, 0xFD))
        assertNull(reader.state.txPassbandHz)
        reader.onBytes(bytes(0xFE, 0xFE, 0xE0, 0x94, 0x1A, 0x05, 0x01, 0x96, 0x12, 0xFD))
        assertEquals(300..2700, reader.state.txPassbandHz)
    }

    @Test
    fun `a refused passband read leaves it unknown`() {
        // Firmware before 1.30 has no such item and answers FA.
        val reader = CivReader()
        reader.onBytes(bytes(0xFE, 0xFE, 0xE0, 0x94, 0xFA, 0xFD))
        assertNull(reader.state.txPassbandHz)
    }

    @Test
    fun `a mode broadcast does not forget the passband`() {
        val reader = CivReader()
        reader.onBytes(bytes(0xFE, 0xFE, 0xE0, 0x94, 0x1A, 0x05, 0x01, 0x96, 0x12, 0xFD))
        reader.onBytes(bytes(0xFE, 0xFE, 0x00, 0x94, 0x01, 0x01, 0x01, 0xFD))
        assertEquals(300..2700, reader.state.txPassbandHz)
    }
}
