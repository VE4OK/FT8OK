package com.ft8ok.rig

import java.util.concurrent.Executor

/**
 * Opens a link off the main thread, one at a time, so a slow USB open never freezes the screen.
 *
 * A radio that does not answer takes about 5 s to give up on, which froze the screen
 * while it ran on the main thread. Everything here except [worker]'s work runs on the
 * main thread.
 *
 * Two rules, both of which exist because the operator can leave and come back while
 * an open is still running:
 *  - **An abandoned link is closed on [worker]**, so the close always runs after its
 *    own open and before any later one. Android gives a USB device to one opener at a
 *    time; the next open would fail if the old one still held it.
 *  - **An abandoned attempt has no say any more.** Its result is dropped, and
 *    [isCurrent] lets the caller drop what its link reports afterwards, so a stale
 *    frequency cannot land after the link has gone.
 */
internal class LinkOpener<L : Any>(
    private val worker: Executor,
    private val onMain: (() -> Unit) -> Unit,
    /** Runs on [worker]. Null when open, else why not. */
    private val open: (L) -> String?,
    /** Asks a running [open] to give up early. Any thread. */
    private val cancelOpen: (L) -> Unit,
    /** Runs on [worker]. Must be safe on a link that never opened. */
    private val close: (L) -> Unit
) {
    private var attempt = 0
    private var pending: L? = null

    /** True from [open] until its result arrives or it is abandoned. */
    val opening: Boolean get() = pending != null

    /** A new attempt's id, to hand to [start] and to check with [isCurrent]. */
    fun nextAttempt(): Int = ++attempt

    /** False once the attempt has been abandoned, or a later one has begun. */
    fun isCurrent(id: Int): Boolean = id == attempt

    /** Open [link] on the worker; [onDone] gets the result, unless it is abandoned first. */
    fun start(link: L, id: Int, onDone: (failure: String?) -> Unit) {
        pending = link
        worker.execute {
            val failure = try {
                open(link)
            } catch (t: Throwable) {
                "${t.javaClass.simpleName}: ${t.message}"
            }
            onMain {
                if (isCurrent(id)) {
                    pending = null
                    onDone(failure)
                }
            }
        }
    }

    /** Give up on any attempt in progress and ignore whatever the current link says next. */
    fun abandon() {
        attempt++
        val link = pending ?: return
        pending = null
        cancelOpen(link)
        worker.execute {
            try {
                close(link)
            } catch (_: Throwable) {
                // The link is being thrown away; there is nobody left to tell.
            }
        }
    }
}
