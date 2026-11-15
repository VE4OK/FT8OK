package com.ft8ok.rig

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The radio changes band only when the operator picks a frequency in the top bar.
 * Never from a tap on a station, a Roster or Map entry,
 * or anything automatic: the antenna, tuner and amplifier are set for the band the
 * operator chose, and transmitting on another one is how a PA gets hurt.
 *
 * A source check rather than a behaviour test, because the rule is about what may
 * call [RigSession.tune] at all, and a new caller is exactly what must not slip in.
 */
class OnlyThePickerTunesTest {

    @Test
    fun `nothing but the frequency picker tunes the radio`() {
        val sources = File("src/main/java")
        assertTrue("run from the app module: ${sources.absolutePath}", sources.isDirectory)
        val callers = sources.walk()
            .filter { it.isFile && it.extension == "kt" }
            .filter { file -> file.readLines().any { TUNE_CALL.containsMatchIn(it) } }
            .map { it.name }
            .toSortedSet()
        // RigSession hands the call on to the link; FrequencyMenu is the picker.
        assertEquals(sortedSetOf("FrequencyMenu.kt", "RigSession.kt"), callers)
    }

    private companion object {
        val TUNE_CALL = Regex("""\.tune\(""")
    }
}
