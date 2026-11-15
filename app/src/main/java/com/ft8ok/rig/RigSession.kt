package com.ft8ok.rig

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ft8ok.radio.Ic7300Rig
import com.ft8ok.radio.Ptt
import com.ft8ok.radio.Rig
import com.ft8ok.radio.RigState
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * The CAT link's lifetime and its state, as the screen sees it.
 *
 * The thin shell again: the protocol is in `radio`, the decision of what a frequency
 * means is in `core-model`, and what lives here is the Android lifetime and the
 * conversion into something a composable can read.
 */
class RigSession(
    private val usbManager: UsbManager,
    /** Runs a block on the main thread. Screen state is only written there. */
    private val onMain: (() -> Unit) -> Unit
) {

    /** Why the radio is not connected. A value, not a sentence: rule 9. */
    sealed interface Problem {
        /** Nothing that looks like a CAT interface is plugged in. */
        data object NoDevice : Problem

        /** Android has not granted us access to the USB device yet. */
        data object NeedsPermission : Problem

        /** The link could not be opened, and the radio layer said why. */
        data class CannotConnect(val detail: String) : Problem
    }

    var state by mutableStateOf(RigState())
        private set

    var connected by mutableStateOf(false)
        private set

    var problem by mutableStateOf<Problem?>(null)
        private set

    /** The baud rate that answered, once known — worth showing when it goes wrong. */
    var baud by mutableStateOf(0)
        private set

    /** The CI-V address that answered. */
    var address by mutableStateOf(0)
        private set

    private var rig: Rig? = null

    /** A connect is running on the worker: the screen says so rather than "no radio". */
    var connecting by mutableStateOf(false)
        private set

    private val opener = LinkOpener<Ic7300Rig>(
        worker = connectWorker,
        onMain = onMain,
        open = { it.connect() },
        cancelOpen = { it.cancelConnect() },
        close = { it.disconnect() }
    )

    /** The attached CAT device, if any. */
    fun device(): UsbDevice? = Ic7300Rig.findDevice(usbManager)

    /** True when a device is attached but Android has not let us open it. */
    fun needsPermission(): Boolean =
        device()?.let { !usbManager.hasPermission(it) } ?: false

    /**
     * Open the link, if there is something to open.
     *
     * Quiet about not finding a radio: the app is perfectly usable receiving audio
     * with no CAT link at all, and a banner shouting about it would be noise.
     *
     * Returns at once: finding the radio runs on [connectWorker], because a radio that
     * does not answer takes about 5 s to give up on. [connected] turns true later, so
     * nothing may rely on it straight after this call.
     */
    fun connect(byOperator: Boolean = false) {
        if (connected) return
        // The operator pressed Disconnect: stay off until they press Connect, rather
        // than reconnecting on the next resume or USB broadcast.
        if (operatorDisconnected && !byOperator) return
        operatorDisconnected = false
        if (opener.opening) return

        val usbDevice = device()
        if (usbDevice == null) {
            problem = Problem.NoDevice
            return
        }
        if (!usbManager.hasPermission(usbDevice)) {
            problem = Problem.NeedsPermission
            return
        }

        val attempt = opener.nextAttempt()
        val link = Ic7300Rig(
            usbManager = usbManager,
            device = usbDevice,
            onState = { fresh ->
                onMain {
                    // A link we have left must not put its frequency back on screen.
                    if (opener.isCurrent(attempt)) {
                        val before = state
                        state = fresh
                        onStateChanged(before, fresh)
                    }
                }
            },
            log = { line -> onMain { lastLog = line; onLog(line) } }
        )

        connecting = true
        problem = null
        opener.start(link, attempt) { failure ->
            connecting = false
            if (failure != null) {
                problem = Problem.CannotConnect(failure)
            } else {
                rig = link
                baud = link.baud
                address = link.radioAddress
                connected = true
                problem = null
            }
        }
    }

    /** Close the link and release the port. [byOperator]: keep it closed until Connect. */
    fun disconnect(byOperator: Boolean = false) {
        if (byOperator) operatorDisconnected = true
        opener.abandon()
        connecting = false
        rig?.disconnect()
        rig = null
        connected = false
        val before = state
        state = RigState()
        // The frequency is unknown from here: what was heard while disconnected must
        // not be filed under the band the radio was last on.
        onStateChanged(before, state)
    }

    var lastLog by mutableStateOf<String?>(null)
        private set

    /**
     * PTT over the open link, or null when there is none.
     *
     * Null is the safe answer and the common one: no CAT link means no way to key
     * the transmitter, which is a property worth having rather than working around.
     */
    fun ptt(): Ptt? = (rig as? Ic7300Rig)?.ptt

    /** True after the operator pressed Disconnect, until they press Connect. */
    var operatorDisconnected by mutableStateOf(false)
        private set

    /** A tune or mode change is in flight. */
    var busy by mutableStateOf(false)
        private set

    /** Why the last tune or mode change failed, for a banner. Null when it worked. */
    var commandError by mutableStateOf<String?>(null)
        private set

    /** Called on the main thread with the state before and after each change. */
    var onStateChanged: (before: RigState, after: RigState) -> Unit = { _, _ -> }

    /** CAT log lines, for the event log. */
    var onLog: (String) -> Unit = {}

    /** Put the radio on [hz] in USB-D. Returns at once. */
    fun tune(hz: Long) = command { it.tune(hz) }

    /** Put the radio in USB-D where it is: the banner's Fix. Returns at once. */
    fun setFt8Mode() = command { it.setFt8Mode() }

    /**
     * Run a radio command off the main thread: each one waits for the radio's OK,
     * about a second for a tune. One at a time; a tap while one is running is
     * ignored rather than queued.
     */
    private fun command(block: (Ic7300Rig) -> String?) {
        val link = rig as? Ic7300Rig
        if (link == null) {
            commandError = "No CAT link."
            return
        }
        if (busy) return
        busy = true
        commandError = null
        Thread {
            val failure = try { block(link) } catch (t: Throwable) { "${t.javaClass.simpleName}: ${t.message}" }
            onMain {
                busy = false
                commandError = failure
            }
        }.apply { name = "ft8ok-cat-cmd" }.start()
    }

    /** Reads the radio's meters, or null when there is no link to read them over. */
    fun meters(): ((Int) -> Int?)? = (rig as? Ic7300Rig)?.let { r -> { sub: Int -> r.readMeter(sub) } }

    private companion object {
        /**
         * One for the whole process, not one per session: a session replaced mid-connect
         * must still queue behind the old one's close, or the port is not free for it.
         */
        val connectWorker: Executor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "ft8ok-cat-connect").apply { isDaemon = true }
        }
    }
}
