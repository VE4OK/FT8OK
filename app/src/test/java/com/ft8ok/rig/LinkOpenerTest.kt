package com.ft8ok.rig

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkOpenerTest {

    /** The worker and main thread as queues, so a test decides what runs when. */
    private val workerJobs = ArrayDeque<Runnable>()
    private val mainJobs = ArrayDeque<() -> Unit>()
    private val calls = mutableListOf<String>()
    private var openResult: String? = null

    private val opener = LinkOpener<String>(
        worker = { workerJobs.addLast(it) },
        onMain = { mainJobs.addLast(it) },
        open = { calls += "open $it"; openResult },
        cancelOpen = { calls += "cancel $it" },
        close = { calls += "close $it" }
    )

    private fun runWorker() { while (workerJobs.isNotEmpty()) workerJobs.removeFirst().run() }
    private fun runMain() { while (mainJobs.isNotEmpty()) mainJobs.removeFirst()() }

    @Test
    fun `a good open reports success and is no longer opening`() {
        var done = "never"
        opener.start("A", opener.nextAttempt()) { done = it ?: "ok" }
        assertTrue(opener.opening)

        runWorker()
        assertTrue("the result arrives on the main thread", opener.opening)
        runMain()

        assertEquals("ok", done)
        assertFalse(opener.opening)
    }

    @Test
    fun `a failed open reports why`() {
        openResult = "no answer"
        var done: String? = null
        opener.start("A", opener.nextAttempt()) { done = it }
        runWorker()
        runMain()
        assertEquals("no answer", done)
    }

    @Test
    fun `an open that throws is a failure, not a crash on the worker`() {
        val throwing = LinkOpener<String>(
            worker = { it.run() },
            onMain = { it() },
            open = { error("boom") },
            cancelOpen = {},
            close = {}
        )
        var done: String? = null
        throwing.start("A", throwing.nextAttempt()) { done = it }
        assertEquals("IllegalStateException: boom", done)
    }

    @Test
    fun `leaving during an open cancels it and closes it after it has finished`() {
        var done = false
        opener.start("A", opener.nextAttempt()) { done = true }

        opener.abandon()
        assertFalse(opener.opening)
        runWorker()
        runMain()

        assertEquals(listOf("cancel A", "open A", "close A"), calls)
        assertFalse("an abandoned attempt has no say", done)
    }

    @Test
    fun `a new open waits for the old link to close, so it can have the port`() {
        opener.start("A", opener.nextAttempt()) { }
        opener.abandon()
        opener.start("B", opener.nextAttempt()) { }

        runWorker()

        assertEquals(listOf("cancel A", "open A", "close A", "open B"), calls)
    }

    @Test
    fun `an open that finished just before the abandon is still closed`() {
        var done = false
        opener.start("A", opener.nextAttempt()) { done = true }
        runWorker() // open has returned; its result is still waiting on the main thread

        opener.abandon()
        runWorker()
        runMain()

        assertEquals(listOf("open A", "cancel A", "close A"), calls)
        assertFalse(done)
    }

    @Test
    fun `what an old link says is dropped once it is abandoned`() {
        val id = opener.nextAttempt()
        opener.start("A", id) { }
        assertTrue(opener.isCurrent(id))
        opener.abandon()
        assertFalse(opener.isCurrent(id))
    }

    @Test
    fun `an open link is left for its owner to close`() {
        val id = opener.nextAttempt()
        opener.start("A", id) { }
        runWorker()
        runMain()

        opener.abandon()
        runWorker()

        assertEquals("the owner closes a link it was handed", listOf("open A"), calls)
        assertFalse("but what it says from here on is dropped", opener.isCurrent(id))
        assertNull(workerJobs.firstOrNull())
    }
}
