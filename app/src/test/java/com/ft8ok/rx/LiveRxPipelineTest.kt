package com.ft8ok.rx

import com.ft8ok.dsp.Decode
import com.ft8ok.dsp.DeepDecode
import com.ft8ok.model.Mode
import com.ft8ok.model.SlotParity
import com.ft8ok.time.BandConsensus
import com.ft8ok.time.TimeEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The receive loop on the JVM: no phone, no radio, no audio hardware.
 *
 * The test that matters most is [the slice is taken in host time, not corrected
 * time] — that is the bug which once made band sync look broken while the estimate
 * was right all along, and it is invisible from the outside because every number
 * involved stays plausible.
 */
class LiveRxPipelineTest {

    private class FakeClock(var wall: Long, var mono: Long = 0) {
        fun advanceTo(w: Long) { mono += w - wall; wall = w }
    }

    /** A slot boundary a long way from the epoch, so nothing passes by coincidence. */
    private val slotStart = 1_760_000_010_000L / 15_000 * 15_000

    private class Harness(
        offsetMs: Long = 0,
        deeper: ((FloatArray, Long) -> DeepDecode)? = null,
        measureOn: ((FloatArray, List<Decode>, Double?) -> Map<String, Double>)? = null,
        listen: ((FloatArray, Long, SlotParity, List<Decode>) -> List<Decode>)? = null
    ) {
        val clock = FakeClock(0)
        val engine: TimeEngine
        val sliceRequests = mutableListOf<Pair<Long, Long>>()
        val updates = mutableListOf<LiveRxPipeline.SlotUpdate>()
        var decodes: List<Decode> = emptyList()
        var audio: FloatArray? = FloatArray(12_000 * 15)
        var occupancyCalls = 0
        // What each analysis call was asked to measure, and a report it can be told apart by.
        val measured = mutableListOf<List<String>>()
        val pending = ArrayDeque<Runnable>()
        var deferPasses = false
        val pipeline: LiveRxPipeline

        init {
            clock.wall = 1_760_000_000_000L
            engine = TimeEngine(Mode.FT8, { clock.wall }, { clock.mono })
            if (offsetMs != 0L) {
                // Drive the engine to the offset we want through its own front door,
                // so the test uses the real acquisition path rather than a back door.
                val dt = Mode.FT8.nominalTxStartMs / 1000.0 - offsetMs / 1000.0
                engine.observe(List(5) { BandConsensus.DtSample(dt, 1.0) })
            }
            pipeline = LiveRxPipeline(
                mode = Mode.FT8,
                timeEngine = engine,
                slice = { start, duration ->
                    sliceRequests += start to duration
                    audio
                },
                decode = { decodes },
                decodeDeeper = deeper,
                measureOn = measureOn,
                analyseSlot = { _, measure, occupancyOf ->
                    if (occupancyOf != null) occupancyCalls++
                    measured += measure.map { it.message }
                    LiveRxPipeline.SlotAnalysis(
                        null,
                        measure.associate { LiveRxPipeline.dedupKey(it) to -it.frequencyHz / 100.0 }
                    )
                },
                onUpdate = { updates += it },
                listen = listen,
                decodeOn = { r -> if (deferPasses) pending.addLast(r) else r.run() },
                nowMs = { clock.wall }
            )
        }

        /** Put the clock at [positionMs] into the slot, in corrected time, and pump. */
        fun pumpAt(slotStartCorrected: Long, positionMs: Long) {
            clock.advanceTo(slotStartCorrected + positionMs - engine.offsetMs)
            pipeline.onAudio()
        }
    }

    private fun decode(message: String, hz: Int, dt: Double = 0.5, score: Int = 10) =
        Decode(score = score, dtSeconds = dt, frequencyHz = hz, message = message)

    // --- a clock the normal path cannot correct -------------------

    /**
     * A band with a real grid, and a phone whose clock is [clockErrorMs] from it. The
     * fake decoder hears what the real one does: a transmission starting between 1.6 s
     * before and 3 s after the start of the audio it is given (`decode.c`).
     */
    private class Band(
        clockErrorMs: Long,
        private val stations: List<Pair<Int, Int>>,   // frequency, parity
        private val mayGridSearch: () -> Boolean = { true }
    ) {
        val clock = FakeClock(1_794_000_000_000L + clockErrorMs)
        private val trueMinusWall = -clockErrorMs
        val engine = TimeEngine(Mode.FT8, { clock.wall }, { clock.mono })
        val updates = mutableListOf<LiveRxPipeline.SlotUpdate>()
        val reports = mutableListOf<LiveRxPipeline.GridSearchReport>()
        var slices = 0
        private var lastSliceStart = 0L

        private val pipeline = LiveRxPipeline(
            mode = Mode.FT8,
            timeEngine = engine,
            slice = { start, _ -> slices++; lastSliceStart = start; FloatArray(1) },
            decode = { heardFrom(lastSliceStart) },
            analyseSlot = { _, _, _ -> LiveRxPipeline.SlotAnalysis(null) },
            onUpdate = { updates += it },
            nowMs = { clock.wall },
            mayGridSearch = mayGridSearch,
            onGridSearch = { reports += it }
        )

        private fun heardFrom(startHostMs: Long): List<Decode> {
            val startTrue = startHostMs + trueMinusWall
            val decodes = mutableListOf<Decode>()
            val slot = Mode.FT8.slotMs
            for ((hz, parity) in stations) {
                var s = Math.floorDiv(startTrue - 2 * slot, slot) * slot
                while (s < startTrue + 2 * slot) {
                    val tx = s + Mode.FT8.nominalTxStartMs
                    val fits = tx + Mode.FT8.txDurationMs <= clock.wall + trueMinusWall
                    if (Math.floorMod(s / slot, 2L).toInt() == parity && fits && tx - startTrue in -1_600..3_000) {
                        decodes += Decode(10, (tx - startTrue) / 1000.0, hz, "STATION $hz")
                    }
                    s += slot
                }
            }
            return decodes
        }

        fun run(seconds: Int) {
            repeat(seconds * 10) {
                clock.advanceTo(clock.wall + 100)
                pipeline.onAudio()
            }
        }

        /** How far our slot boundary is from the band's, ms. */
        val gridErrorMs: Long
            get() = Math.floorMod(engine.offsetMs + -trueMinusWall + 7_500, 15_000L) - 7_500
    }

    private val threeStations = listOf(800 to 0, 1400 to 1, 2000 to 0)

    @Test
    fun `a clock 6 s out, which decodes nothing, finds the band and then decodes`() {
        val band = Band(6_200, threeStations)
        band.run(20)
        assertTrue("the wrong clock should hear nothing", band.updates.flatMap { it.allDecodes }.isEmpty())

        band.run(70)
        assertEquals(TimeEngine.Sync.SYNCED, band.engine.sync)
        assertTrue("grid is ${band.gridErrorMs} ms out", abs(band.gridErrorMs) <= 50)
        assertTrue("the normal passes hear the band again", band.updates.takeLast(3).any { it.allDecodes.isNotEmpty() })
    }

    @Test
    fun `the search stops once the clock has its consensus`() {
        val band = Band(6_200, threeStations)
        band.run(90)
        val searches = band.reports.size
        band.run(60)
        assertEquals(searches, band.reports.size)
    }

    @Test
    fun `a good clock on a working band never searches`() {
        // Two stations a slot, so every slot's own consensus comes from the normal path.
        val band = Band(0, listOf(800 to 0, 1000 to 0, 1200 to 0, 1400 to 1, 1600 to 1, 1800 to 1))
        band.run(120)
        assertEquals(TimeEngine.Sync.SYNCED, band.engine.sync)
        assertTrue("searched ${band.reports.size} times", band.reports.size <= 1)
    }

    @Test
    fun `one station is enough to get decoding, and the lock stays tentative`() {
        val band = Band(6_200, listOf(1000 to 0))
        band.run(90)
        assertEquals(TimeEngine.Sync.TENTATIVE, band.engine.sync)
        assertTrue("grid is ${band.gridErrorMs} ms out", abs(band.gridErrorMs) <= 50)
        assertTrue(band.updates.takeLast(4).any { it.allDecodes.isNotEmpty() })
    }

    @Test
    fun `an empty band gives no lock and leaves the clock alone`() {
        val band = Band(6_200, emptyList())
        band.run(90)
        assertEquals(TimeEngine.Sync.NONE, band.engine.sync)
        assertEquals(0L, band.engine.offsetMs)
        assertTrue(band.reports.size >= 4)
        assertTrue(band.reports.all { it.lock == null })
    }

    @Test
    fun `no search runs while it may not, such as just after our own transmission`() {
        val band = Band(6_200, threeStations, mayGridSearch = { false })
        band.run(90)
        assertTrue(band.reports.isEmpty())
        assertEquals(0L, band.engine.offsetMs)
    }

    @Test
    fun `a search adds nothing to the feed`() {
        val band = Band(6_200, threeStations)
        while (band.reports.none { it.lock != null }) band.run(1)
        assertTrue(band.updates.flatMap { it.newDecodes }.isEmpty())
    }

    @Test
    fun `the slice is taken in host time, not corrected time`() {
        // The clock is 1.66 s fast, exactly as the test phone was measured.
        val h = Harness(offsetMs = -1660)
        assertEquals(-1660L, h.engine.offsetMs)

        h.pumpAt(slotStart, 11_900)

        val (requestedStart, duration) = h.sliceRequests.single()
        // The audio buffer is anchored in host time, so the corrected boundary must
        // be converted back before it indexes anything. Asking for `slotStart` here
        // would slice 1.66 s away from where the slot actually is, and every DT
        // would then be wrong by exactly the correction we just applied.
        assertEquals(slotStart - h.engine.offsetMs, requestedStart)
        assertEquals(Mode.FT8.slotMs, duration)
    }

    @Test
    fun `with no correction applied the two timebases coincide`() {
        val h = Harness()
        h.pumpAt(slotStart, 11_900)
        assertEquals(slotStart, h.sliceRequests.single().first)
    }

    @Test
    fun `each pass fires once, at its scheduled time`() {
        val h = Harness()
        h.decodes = listOf(decode("CQ W3XAA EM13", 1000))

        h.pumpAt(slotStart, 1_000)      // too early for anything
        assertTrue(h.updates.isEmpty())

        h.pumpAt(slotStart, 11_900)     // pass 0 at 11.8 s
        h.pumpAt(slotStart, 12_000)     // still pass 0's territory: no repeat
        assertEquals(1, h.updates.size)
        assertEquals(0, h.updates[0].pass)

        h.pumpAt(slotStart, 13_600)     // pass 1 at 13.5 s
        h.pumpAt(slotStart, 14_800)     // pass 2 at 14.7 s
        h.pumpAt(slotStart, 14_900)     // nothing left to run
        assertEquals(3, h.updates.size)
        assertEquals(listOf(0, 1, 2), h.updates.map { it.pass })
        assertEquals(listOf(false, false, true), h.updates.map { it.isFinal })
    }

    @Test
    fun `a message resolved in an early pass is not repeated by a later one`() {
        val h = Harness()
        val early = decode("CQ W3XAA EM13", 1000)
        h.decodes = listOf(early)
        h.pumpAt(slotStart, 11_900)

        // The later pass re-decodes the same audio, finds the same message again,
        // and also a weaker one that has now resolved.
        val late = decode("K1ABC W3XAA -12", 1500)
        h.decodes = listOf(early, late)
        h.pumpAt(slotStart, 13_600)

        assertEquals(listOf(early), h.updates[0].newDecodes)
        assertEquals(listOf(late), h.updates[1].newDecodes)
        assertEquals(listOf(early, late), h.updates[1].allDecodes)
    }

    @Test
    fun `a message that drifts a few Hz between passes is still the same message`() {
        val h = Harness()
        h.decodes = listOf(decode("CQ W3XAA EM13", 1000))
        h.pumpAt(slotStart, 11_900)
        h.decodes = listOf(decode("CQ W3XAA EM13", 1004))
        h.pumpAt(slotStart, 13_600)
        assertTrue(h.updates[1].newDecodes.isEmpty())
    }

    @Test
    fun `two stations sending the same text on different frequencies are both kept`() {
        val h = Harness()
        h.decodes = listOf(decode("CQ W3XAA EM13", 1000), decode("CQ W3XAA EM13", 2000))
        h.pumpAt(slotStart, 11_900)
        assertEquals(2, h.updates[0].newDecodes.size)
    }

    @Test
    fun `the clock hears only the final pass, and hears all of it`() {
        val h = Harness()
        h.decodes = List(5) { decode("CQ W$it EM13", 1000 + it * 100, dt = 1.0) }

        h.pumpAt(slotStart, 11_900)
        assertNull("an early pass must not touch the clock", h.updates[0].clock)
        assertEquals(0L, h.engine.offsetMs)

        h.pumpAt(slotStart, 13_600)
        h.pumpAt(slotStart, 14_800)
        val final = h.updates.last()
        assertNotNull(final.clock)
        // Five stations all 500 ms late: the clock acquires and corrects by -500.
        assertEquals(5, final.clock!!.estimate!!.stationsUsed)
        assertEquals(-500L, h.engine.offsetMs)
    }

    @Test
    fun `occupancy is computed once, on the complete slot`() {
        val h = Harness()
        h.decodes = listOf(decode("CQ W3XAA EM13", 1000))
        h.pumpAt(slotStart, 11_900)
        h.pumpAt(slotStart, 13_600)
        assertEquals(0, h.occupancyCalls)
        h.pumpAt(slotStart, 14_800)
        assertEquals(1, h.occupancyCalls)
    }

    @Test
    fun `a new slot starts clean`() {
        val h = Harness()
        val message = decode("CQ W3XAA EM13", 1000)
        h.decodes = listOf(message)
        h.pumpAt(slotStart, 11_900)
        h.pumpAt(slotStart, 14_800)

        // Same station, next slot. It is news again - the call list shows it anew.
        h.pumpAt(slotStart + 15_000, 11_900)
        val next = h.updates.last()
        assertEquals(listOf(message), next.newDecodes)
        assertEquals(0, next.pass)
        assertEquals(slotStart + 15_000, next.slotStartMs)
    }

    @Test
    fun `parity alternates with the slot and is carried on the update`() {
        val h = Harness()
        h.decodes = listOf(decode("CQ W3XAA EM13", 1000))
        h.pumpAt(slotStart, 11_900)
        h.pumpAt(slotStart + 15_000, 11_900)
        assertEquals(
            h.updates[0].parity.other,
            h.updates[1].parity
        )
        assertEquals(Mode.FT8.parityOf(slotStart), h.updates[0].parity)
    }

    @Test
    fun `audio that is not buffered yet produces no update at all`() {
        val h = Harness()
        h.audio = null
        h.pumpAt(slotStart, 11_900)
        assertTrue("a silent update would look like a dead band", h.updates.isEmpty())
    }

    @Test
    fun `clipping is reported, because clipped audio looks healthy and decodes badly`() {
        val h = Harness()
        h.audio = FloatArray(12_000 * 15).also { it[42] = 1.0f }
        h.pumpAt(slotStart, 11_900)
        assertTrue(h.updates.single().clipped)
    }

    @Test
    fun `a partial slice is reported with the audio it actually had`() {
        val h = Harness()
        h.audio = FloatArray((12_000 * 11.8).toInt())
        h.pumpAt(slotStart, 11_900)
        assertEquals(11_800, h.updates.single().audioMs)
    }

    @Test
    fun `a decoder that throws does not stop the loop`() {
        val h = Harness()
        val pipeline = LiveRxPipeline(
            mode = Mode.FT8,
            timeEngine = h.engine,
            slice = { _, _ -> FloatArray(12_000 * 15) },
            decode = { error("decoder exploded") },
            analyseSlot = { _, _, _ -> LiveRxPipeline.SlotAnalysis(null) },
            onUpdate = { h.updates += it },
            nowMs = { h.clock.wall }
        )
        h.clock.advanceTo(slotStart + 11_900)
        pipeline.onAudio()
        // The slot still reports, with nothing in it, and the next slot can run.
        assertEquals(1, h.updates.size)
        assertTrue(h.updates.single().allDecodes.isEmpty())
    }

    @Test
    fun `parity of consecutive slots matches the protocol's even-odd pattern`() {
        // :00 and :30 are even, :15 and :45 odd - what the strip's two lanes mean.
        val minute = 1_760_000_040_000L / 60_000 * 60_000
        assertEquals(SlotParity.EVEN, Mode.FT8.parityOf(minute))
        assertEquals(SlotParity.ODD, Mode.FT8.parityOf(minute + 15_000))
        assertEquals(SlotParity.EVEN, Mode.FT8.parityOf(minute + 30_000))
    }

    @Test
    fun `each message's report comes with the pass that first decodes it`() {
        val h = Harness()
        val early = decode("CQ W3XAA EM13", 1000)
        val late = decode("CQ K1ABC FN42", 1500)
        h.decodes = listOf(early)
        h.pumpAt(slotStart, 11_900)
        assertEquals(-10.0, h.updates[0].snrByMessage[LiveRxPipeline.dedupKey(early)]!!, 0.0)

        h.decodes = listOf(early, late)
        h.pumpAt(slotStart, 13_600)
        // Only the new message is measured; the early one is not measured again.
        assertEquals(listOf(listOf("CQ W3XAA EM13"), listOf("CQ K1ABC FN42")), h.measured)
        assertEquals(-15.0, h.updates[1].snrByMessage[LiveRxPipeline.dedupKey(late)]!!, 0.0)
    }

    @Test
    fun `a later pass never revises a report, and the final pass carries the whole slot`() {
        val h = Harness()
        val first = decode("CQ W3XAA EM13", 1000)
        h.decodes = listOf(first)
        h.pumpAt(slotStart, 11_900)
        // The same message a few Hz off in a later pass: the same message, not re-measured.
        h.decodes = listOf(decode("CQ W3XAA EM13", 1003))
        h.pumpAt(slotStart, 13_600)
        h.pumpAt(slotStart, 14_800)
        val final = h.updates.last()
        assertTrue(final.isFinal)
        assertEquals(mapOf(LiveRxPipeline.dedupKey(first) to -10.0), final.snrByMessage)
    }

    @Test
    fun `a pass with nothing new skips the spectrum, except the final one`() {
        val h = Harness()
        h.pumpAt(slotStart, 11_900)
        h.pumpAt(slotStart, 13_600)
        assertTrue(h.measured.isEmpty())
        h.pumpAt(slotStart, 14_800)
        assertEquals(listOf(emptyList<String>()), h.measured)
        assertEquals(1, h.occupancyCalls)
    }

    @Test
    fun `a final pass that finishes after the boundary keeps to its own slot`() {
        // +15.1 s was measured on the phone: the next slot's first
        // audio arrives while the final pass is still decoding.
        val h = Harness()
        val cq = decode("CQ W3XAA EM13", 1000)
        h.decodes = listOf(cq)
        h.pumpAt(slotStart, 11_900)
        h.pumpAt(slotStart, 13_600)
        h.deferPasses = true
        h.pumpAt(slotStart, 14_800)          // the final pass is queued...
        h.pumpAt(slotStart + 15_000, 100)    // ...and the next slot begins first
        h.pending.removeFirst().run()
        val final = h.updates.last()
        assertTrue(final.isFinal)
        assertEquals(slotStart, final.slotStartMs)
        assertEquals(listOf(cq), final.allDecodes)

        // The next slot did not inherit the old one's messages: a repeated CQ is new there.
        h.deferPasses = false
        h.pumpAt(slotStart + 15_000, 11_900)
        assertEquals(listOf(cq), h.updates.last().newDecodes)
    }

    // --- the second look ------------------------------------------

    private val heard = decode("CQ K1ABC FN42", 1500)
    private val hidden = decode("W1AW K2DEF EM12", 1530)

    private fun Harness.runThroughFinalPass() {
        decodes = listOf(heard)
        pumpAt(slotStart, 11_800)
        pumpAt(slotStart, 13_500)
        pumpAt(slotStart, 14_700)
    }

    @Test
    fun `the untouched decode goes out first and the second look closes the slot`() {
        val h = Harness(deeper = { _, _ -> DeepDecode(listOf(hidden), removed = 1) })
        h.runThroughFinalPass()

        val untouched = h.updates[h.updates.size - 2]
        val closing = h.updates.last()
        assertEquals(false, untouched.isFinal)
        assertEquals(listOf(hidden), closing.newDecodes)
        assertEquals(listOf(heard, hidden), closing.allDecodes)
        assertEquals(true, closing.isFinal)
        assertEquals(1, h.updates.count { it.isFinal })
        assertEquals(LiveRxPipeline.Deeper(1, 1, 0, false), closing.deeper)
        assertEquals(3, closing.pass)
    }

    @Test
    fun `the second look gets the time left before TX must decide`() {
        var budget = 0L
        val h = Harness(deeper = { _, ms -> budget = ms; DeepDecode() })
        h.runThroughFinalPass()
        assertEquals(Mode.FT8.deepDecodeEndMs - 14_700, budget)
    }

    @Test
    fun `a final pass with no time left closes the slot without a second look`() {
        var ran = false
        val h = Harness(deeper = { _, _ -> ran = true; DeepDecode() })
        h.deferPasses = true
        h.runThroughFinalPass()
        // The pass was held up: the budget has gone by the time it runs.
        h.clock.advanceTo(slotStart + Mode.FT8.deepDecodeEndMs - h.engine.offsetMs)
        while (h.pending.isNotEmpty()) h.pending.removeFirst().run()
        assertEquals(false, ran)
        assertEquals(1, h.updates.count { it.isFinal })
        assertEquals(true, h.updates.last().deeper?.cutShort)
    }

    @Test
    fun `a second look that throws still closes the slot with what was heard`() {
        val h = Harness(deeper = { _, _ -> error("subtraction exploded") })
        h.runThroughFinalPass()
        assertEquals(listOf(heard), h.updates.last().allDecodes)
        assertEquals(true, h.updates.last().isFinal)
    }

    @Test
    fun `a message the second look finds again is not repeated`() {
        val h = Harness(deeper = { _, _ -> DeepDecode(listOf(heard)) })
        h.runThroughFinalPass()
        assertTrue(h.updates.last().newDecodes.isEmpty())
        assertEquals(0, h.updates.last().deeper?.found)
    }

    @Test
    fun `the map is made once, from the whole slot`() {
        val h = Harness(deeper = { _, _ -> DeepDecode(listOf(hidden)) })
        h.runThroughFinalPass()
        assertEquals(1, h.occupancyCalls)
    }

    @Test
    fun `a hidden station's report comes from the audio it was found in`() {
        val cleaned = FloatArray(4)
        var measuredOn: FloatArray? = null
        var floor: Double? = null
        val h = Harness(
            deeper = { _, _ -> DeepDecode(listOf(hidden), looks = listOf(DeepDecode.Look(cleaned, listOf(hidden)))) },
            measureOn = { audio, measure, noise ->
                measuredOn = audio
                floor = noise
                measure.associate { LiveRxPipeline.dedupKey(it) to -7.0 }
            }
        )
        h.runThroughFinalPass()
        assertTrue(measuredOn === cleaned)
        assertEquals(-7.0, h.updates.last().snrByMessage.getValue(LiveRxPipeline.dedupKey(hidden)), 0.0)
        assertTrue("not measured on the untouched audio too", h.measured.none { "W1AW K2DEF EM12" in it })
        assertEquals(null, floor) // the harness's analysis gives no floor; it is handed on as given
    }

    // --- the reply list ---------------------------------------------

    private val listed = decode("VE4OK IV3XAA R-12", 1844)

    @Test
    fun `a reply the list found closes the slot with the rest, marked, with a report`() {
        val cleaned = FloatArray(4)
        var soFar: List<Decode>? = null
        var parity: SlotParity? = null
        val h = Harness(
            deeper = { _, _ -> DeepDecode(listOf(hidden), removed = 1, looks = listOf(DeepDecode.Look(cleaned, listOf(hidden)))) },
            // Nothing measurable left where the reply is.
            measureOn = { _, measure, _ -> measure.filter { it.message != listed.message }.associate { LiveRxPipeline.dedupKey(it) to -7.0 } },
            listen = { _, _, p, heardSoFar -> soFar = heardSoFar; parity = p; listOf(listed) }
        )
        h.runThroughFinalPass()

        val closing = h.updates.last()
        assertEquals(listOf(heard, hidden), soFar)
        assertEquals(Mode.FT8.parityOf(slotStart), parity)
        val found = closing.newDecodes.single { it.message == listed.message }
        assertEquals(true, found.fromReplyList)
        assertEquals(true, closing.allDecodes.contains(found))
        assertEquals(LiveRxPipeline.LISTED_REPORT_DB, closing.snrByMessage.getValue(LiveRxPipeline.dedupKey(found)), 0.0)
        // The second look's count is its own.
        assertEquals(1, closing.deeper?.found)
        assertEquals(1, h.updates.count { it.isFinal })
    }

    @Test
    fun `the list is not asked when the second look did not run`() {
        var asked = false
        val h = Harness(deeper = { _, _ -> DeepDecode() }, listen = { _, _, _, _ -> asked = true; listOf(listed) })
        h.deferPasses = true
        h.runThroughFinalPass()
        h.clock.advanceTo(slotStart + Mode.FT8.deepDecodeEndMs - h.engine.offsetMs)
        while (h.pending.isNotEmpty()) h.pending.removeFirst().run()
        assertEquals(false, asked)
        assertTrue(h.updates.last().allDecodes.none { it.fromReplyList })

        val failed = Harness(deeper = { _, _ -> error("subtraction exploded") }, listen = { _, _, _, _ -> asked = true; emptyList() })
        failed.runThroughFinalPass()
        assertEquals(false, asked)
    }

    @Test
    fun `a list that throws still closes the slot`() {
        val h = Harness(deeper = { _, _ -> DeepDecode(listOf(hidden)) }, listen = { _, _, _, _ -> error("list exploded") })
        h.runThroughFinalPass()
        assertEquals(listOf(heard, hidden), h.updates.last().allDecodes)
        assertEquals(true, h.updates.last().isFinal)
    }

    @Test
    fun `work after the slot sees the closing update, and an error there changes nothing`() {
        val h = Harness(deeper = { _, _ -> DeepDecode(listOf(hidden)) })
        var closed: LiveRxPipeline.SlotUpdate? = null
        val pipeline = LiveRxPipeline(
            mode = Mode.FT8,
            timeEngine = h.engine,
            slice = { _, _ -> FloatArray(12_000 * 15) },
            decode = { listOf(heard) },
            decodeDeeper = { _, _ -> DeepDecode(listOf(hidden)) },
            analyseSlot = { _, _, _ -> LiveRxPipeline.SlotAnalysis(null) },
            onUpdate = { h.updates += it },
            onSlotClosed = { _, update -> closed = update; error("after the slot exploded") },
            nowMs = { h.clock.wall }
        )
        for (position in listOf(11_800L, 13_500L, 14_700L)) {
            h.clock.advanceTo(slotStart + position)
            pipeline.onAudio()
        }
        assertEquals(h.updates.last(), closed)
        assertEquals(listOf(heard, hidden), closed?.allDecodes)
    }
}
