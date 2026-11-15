package com.ft8ok.radio

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber

/**
 * The CAT link over the one USB cable.
 *
 * **Subscribes, does not poll.** The IC-7300 broadcasts every frequency and mode
 * change unsolicited (CI-V Transceive, on by default), so this opens the port and
 * listens. One explicit request is sent at connect time, and only to learn where the
 * radio is before it next changes anything; after that the radio does the talking.
 *
 * **It moves the radio only when asked.** [tune] and [setFt8Mode] are called from an
 * operator tap (the app never puts the radio back on its own), and
 * PTT is reached through [ptt]. Nothing here runs while the transmitter is keyed.
 *
 * All the stream handling lives in [CivReader], which is pure and tested. What is
 * left here is opening the port, a thread, and the baud/address search.
 */
class Ic7300Rig(
    private val usbManager: UsbManager,
    private val device: UsbDevice,
    /** Called on the reader thread whenever the state changes. Keep it quick. */
    private val onState: (RigState) -> Unit = {},
    private val log: (String) -> Unit = {}
) : Rig {

    private val reader = CivReader()
    private var port: UsbSerialPort? = null
    private var thread: Thread? = null

    @Volatile
    private var running = false

    /** The baud rate and radio address that answered, once known. */
    @Volatile
    var baud: Int = 0
        private set

    @Volatile
    var radioAddress: Int = Civ.ADDR_IC7300
        private set

    override val state: RigState get() = reader.state

    override val isConnected: Boolean get() = running

    /**
     * PTT over this link, available only while it is open.
     *
     * Created here because keying needs the port this class owns, and nothing else
     * should be opening a second one. Null when not connected — which is itself a
     * safety property: no CAT link, no way to key the transmitter.
     */
    var ptt: Ptt? = null
        private set

    /**
     * Write a CI-V frame to the radio.
     *
     * Synchronised against the probe and anything else that writes, because the bus
     * is half-duplex and two writers interleaving produce frames neither of them
     * meant to send.
     */
    @Synchronized
    private fun writeFrame(bytes: ByteArray): Boolean {
        val p = port ?: return false
        return try {
            p.write(bytes, WRITE_TIMEOUT_MS)
            true
        } catch (t: Throwable) {
            log("CAT write failed: ${t.javaClass.simpleName} ${t.message}")
            false
        }
    }

    override fun connect(): String? {
        if (running) return null

        val driver = driverFor(device)
            ?: return "No USB-serial driver matches this device " +
                "(${"%04X:%04X".format(device.vendorId, device.productId)})."

        if (!usbManager.hasPermission(device)) return "USB permission has not been granted."

        val connection = usbManager.openDevice(device)
            ?: return "Could not open the USB device. Unplug and replug the cable."

        val serialPort = driver.ports.firstOrNull()
            ?: return "The USB-serial driver reported no ports."

        try {
            serialPort.open(connection)
            serialPort.setParameters(
                BAUD_CANDIDATES.first(), 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE
            )
        } catch (t: Throwable) {
            return "Could not open the serial port: ${t.javaClass.simpleName} ${t.message}"
        }

        val found = probe(serialPort)
        if (found == null) {
            try { serialPort.close() } catch (_: Throwable) { }
            return "The radio did not answer at any baud rate. Check the radio's menu: " +
                "CI-V Baud Rate, CI-V Address (94h), CI-V Transceive ON, and " +
                "CI-V USB Port set to 'Unlink from [REMOTE]'."
        }

        baud = found.first
        radioAddress = found.second
        log("CAT: $baud baud, address %02X".format(radioAddress))

        port = serialPort
        running = true
        ptt = CivPtt(
            write = ::writeFrame,
            radioAddress = radioAddress,
            log = log
        )
        thread = Thread { readLoop(serialPort) }.apply {
            name = "ft8ok-cat"
            start()
        }
        Thread { askLoop() }.apply {
            name = "ft8ok-cat-ask"
            isDaemon = true
            start()
        }
        return null
    }

    /** Stop a running [connect] at its next baud or address. Any thread. */
    fun cancelConnect() {
        connectCancelled = true
    }

    @Volatile
    private var connectCancelled = false

    override fun disconnect() {
        // Unkey BEFORE closing the port. Closing first would leave a keyed
        // transmitter with no way left to tell it to stop.
        try { ptt?.release() } catch (_: Throwable) { }
        ptt = null
        running = false
        synchronized(askLock) { askLock.notifyAll() }
        try { thread?.join(STOP_TIMEOUT_MS) } catch (_: Throwable) { }
        thread = null
        try { port?.close() } catch (_: Throwable) { }
        port = null
        reader.reset()
    }

    /**
     * Find the baud rate and address by asking, rather than by configuration.
     *
     * The radio's CI-V baud rate is a menu setting the operator may have changed, and
     * its address likewise. Asking costs a second at connect time and removes a whole
     * class of "it does not work" from the field.
     *
     * The address of a reply is taken from its `from` byte, never from what we
     * guessed — a broadcast request answered by a radio at a different address still
     * tells us where it is.
     */
    private fun probe(serialPort: UsbSerialPort): Pair<Int, Int>? {
        val buffer = ByteArray(512)
        for (candidateBaud in BAUD_CANDIDATES) {
            try {
                serialPort.setParameters(
                    candidateBaud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE
                )
            } catch (_: Throwable) {
                continue
            }

            for (candidateAddress in listOf(Civ.ADDR_IC7300, Civ.ADDR_BROADCAST)) {
                if (connectCancelled) return null
                try {
                    // Drain anything buffered at the previous rate: at the wrong
                    // baud it is meaningless noise that would be parsed as frames.
                    while (serialPort.read(buffer, 50) > 0) { /* discard */ }

                    serialPort.write(
                        Civ.request(candidateAddress, Civ.ADDR_CONTROLLER, Civ.CMD_READ_FREQ),
                        WRITE_TIMEOUT_MS
                    )
                    val n = serialPort.read(buffer, PROBE_READ_TIMEOUT_MS)
                    if (n <= 0) continue

                    val frames = Civ.parseFrames(buffer, n)
                        .filterNot { Civ.isEcho(it, Civ.ADDR_CONTROLLER) }
                    val answer = frames.firstOrNull { Civ.newestFrequencyHz(listOf(it)) != null }
                        ?: continue

                    // Its own `from` byte is where the radio really is.
                    return candidateBaud to answer.from
                } catch (_: Throwable) {
                    // Try the next combination; a failure here is information, not
                    // an error worth surfacing.
                }
            }
        }
        return null
    }

    /**
     * One meter reading, while transmitting.
     *
     * Request/response on a half-duplex bus, so **one question at a time** - the
     * same rule that cost us the mode reading at connect time. The reader thread is
     * consuming the port concurrently, which is why the reply is taken from the
     * shared [CivReader] rather than by reading the port here.
     *
     *  the raw 0..255 reading, or null if the radio did not answer in time.
     */
    fun readMeter(sub: Int, timeoutMs: Long = METER_TIMEOUT_MS): Int? {
        val p = port ?: return null
        synchronized(meterLock) {
            meterSub = sub
            meterValue = null
            if (!writeFrame(Civ.request(radioAddress, Civ.ADDR_CONTROLLER, Civ.CMD_METER, sub))) {
                return null
            }
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                meterValue?.let { return it }
                try { Thread.sleep(5) } catch (_: InterruptedException) { return null }
            }
            return null
        }
    }

    private val meterLock = Any()

    @Volatile
    private var meterSub: Int? = null

    @Volatile
    private var meterValue: Int? = null

    /**
     * Pick a meter reply out of the inbound stream.
     *
     * Selected by decodable content and by the subcommand we asked for, never by
     * position - the bus echoes our own request, and the echo carries the same
     * command byte with no payload.
     */
    private fun absorbMeter(frames: List<Civ.Frame>) {
        val want = meterSub ?: return
        for (frame in frames) {
            if (frame.command != Civ.CMD_METER) continue
            if (frame.data.getOrNull(1) != want) continue
            val value = Civ.decodeBcdMeter(frame.data.drop(2)) ?: continue
            meterValue = value
            return
        }
    }

    /**
     * Put the radio on [hz] in USB-D. Blocks for about a second: call it off the main
     * thread. Returns null on success, or what went wrong.
     *
     * Three commands, one at a time, each waiting for the radio's OK. The mode, data
     * mode and then the frequency are asked for, so [state] reflects what the radio
     * says it did, not what was requested.
     *
     * The frequency has to be asked: the radio did **not** announce a change made
     * over CI-V. Seen once - a pick of 18.100 MHz landed, the top bar stayed on
     * 14.074 for the rest of the session and the contact was logged on 20 m, while a
     * mode change on the front panel minutes later was announced as usual.
     */
    fun tune(hz: Long): String? {
        refusal()?.let { return it }
        synchronized(commandLock) {
            transact(Civ.setFrequency(radioAddress, Civ.ADDR_CONTROLLER, hz), "the frequency")
                ?.let { return it }
            val failure = setFt8ModeLocked()
            log("CAT: tuned to ${Civ.formatHz(hz)}, ${failure ?: "USB-D"}")
            if (!confirmFrequencyLocked(hz)) {
                log("CAT: the radio did not confirm ${Civ.formatHz(hz)} after $READBACK_TRIES questions")
            }
            return failure
        }
    }

    /**
     * Ask for the frequency until the radio reports [hz], a few times at most.
     *
     * One question was not enough: once a tune to 18.100 MHz
     * was never answered and the app stayed on 14.074, while the same pick 20 s
     * later was answered in 250 ms. Why the answer was lost is not known.
     */
    private fun confirmFrequencyLocked(hz: Long): Boolean =
        askUntilLocked(Civ.request(radioAddress, Civ.ADDR_CONTROLLER, Civ.CMD_READ_FREQ)) {
            reader.state.dialHz == hz
        }

    /**
     * Send [question] until [answered] holds, [READBACK_TRIES] times at most, each
     * waited on for [ACK_TIMEOUT_MS]. Returns whether it ever held.
     */
    private fun askUntilLocked(
        question: ByteArray,
        tries: Int = READBACK_TRIES,
        answered: () -> Boolean
    ): Boolean {
        repeat(tries) {
            // One question at a time: let the previous answer clear the bus first.
            try { Thread.sleep(REQUEST_GAP_MS) } catch (_: InterruptedException) { return false }
            if (!writeFrame(question)) return false
            val deadline = System.currentTimeMillis() + ACK_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                if (answered()) return true
                try { Thread.sleep(5) } catch (_: InterruptedException) { return false }
            }
        }
        return false
    }

    /** Put the radio in USB-D without moving the dial. Blocks; null on success. */
    fun setFt8Mode(): String? {
        refusal()?.let { return it }
        synchronized(commandLock) {
            return setFt8ModeLocked().also { log("CAT: set USB-D: ${it ?: "done"}") }
        }
    }

    private fun refusal(): String? = when {
        !running -> "No CAT link."
        // Moving a keyed radio is the one thing this must never do.
        ptt?.isKeyed == true -> "Transmitting - the radio was not changed."
        else -> null
    }

    private fun setFt8ModeLocked(): String? {
        // Keep the operator's filter choice; Hamlib does the same.
        val filter = Civ.filterCode(state.filter)
        transact(Civ.setMode(radioAddress, Civ.ADDR_CONTROLLER, Civ.MODE_USB, filter), "USB")
            ?.let { return it }
        transact(Civ.setDataMode(radioAddress, Civ.ADDR_CONTROLLER, true, filter), "data mode")
            ?.let { return it }
        // The mode has to be asked, like the frequency: the radio did not announce a
        // mode set over CI-V. Seen once - a pick of 40 m from LSB put the radio
        // in USB-D, the app kept the LSB it read at connect and showed LSB-D, and the
        // banner's tap could not clear it. Asked before data mode, because a mode
        // reply that changes the mode makes data mode unknown again (CivReader).
        val usb = Civ.modeName(Civ.MODE_USB)
        if (!askUntilLocked(Civ.request(radioAddress, Civ.ADDR_CONTROLLER, Civ.CMD_READ_MODE)) {
                reader.state.mode == usb
            }) {
            log("CAT: the radio did not confirm USB after $READBACK_TRIES questions")
        }
        try { Thread.sleep(REQUEST_GAP_MS) } catch (_: InterruptedException) { return "Interrupted." }
        writeFrame(Civ.readDataMode(radioAddress, Civ.ADDR_CONTROLLER))
        return null
    }

    /** Serialises everything that writes and then waits for an answer. */
    private val commandLock = Any()

    @Volatile
    private var awaitingAck = false

    @Volatile
    private var ack: Boolean? = null

    /**
     * Send one set command and wait for `FB` or `FA`. Then pause, because the radio
     * follows a change with its own transceive broadcast, and talking over it on a
     * half-duplex bus loses one or the other.
     */
    private fun transact(frame: ByteArray, what: String): String? {
        ack = null
        awaitingAck = true
        try {
            if (!writeFrame(frame)) return "Could not write $what to the radio."
            val deadline = System.currentTimeMillis() + ACK_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                val answer = ack
                if (answer != null) {
                    try { Thread.sleep(REQUEST_GAP_MS) } catch (_: InterruptedException) { }
                    return if (answer) null else "The radio refused $what."
                }
                try { Thread.sleep(5) } catch (_: InterruptedException) { return "Interrupted." }
            }
            return "The radio did not answer when setting $what."
        } finally {
            awaitingAck = false
        }
    }

    private fun absorbAck(frames: List<Civ.Frame>) {
        if (!awaitingAck) return
        frames.firstNotNullOfOrNull { Civ.ackOf(it, radioAddress, Civ.ADDR_CONTROLLER) }?.let { ack = it }
    }

    private val askLock = Object()

    @Volatile
    private var askSoon = false

    private fun askDataModeSoon() {
        synchronized(askLock) {
            askSoon = true
            askLock.notifyAll()
        }
    }

    /**
     * Keep data mode known. Asked after every mode broadcast, and every
     * [DATA_POLL_MS] besides, because it is not verified that the radio announces a
     * press of its DATA key. Never while keyed: the meter polling
     * owns the bus then.
     *
     * The frequency is asked on the same timer, as the safety net under everything
     * that trusts it (the band filter, the logged band, the disarm on a band change,
     * the TX guard): a lost answer or Transceive switched off then leaves the app
     * wrong for [DATA_POLL_MS] at most, not for the session.
     *
     * So is the mode, first, for the same reason: a mode set over CI-V was not
     * announced. Only on the timer - after a mode broadcast the mode is
     * already known, and asking then would answer with another mode frame and
     * prompt this loop again.
     */
    private fun askLoop() {
        confirmPassbandAtConnect()
        while (running) {
            val prompted: Boolean
            try {
                synchronized(askLock) {
                    if (!askSoon) askLock.wait(DATA_POLL_MS)
                    prompted = askSoon
                    askSoon = false
                }
                // Let whatever the radio is saying finish first.
                Thread.sleep(REQUEST_GAP_MS)
            } catch (_: InterruptedException) {
                return
            }
            if (!running || ptt?.isKeyed == true) continue
            synchronized(commandLock) {
                if (!prompted) {
                    writeFrame(Civ.request(radioAddress, Civ.ADDR_CONTROLLER, Civ.CMD_READ_MODE))
                    try { Thread.sleep(REQUEST_GAP_MS) } catch (_: InterruptedException) { return }
                    if (!running || ptt?.isKeyed == true) return@synchronized
                }
                writeFrame(Civ.readDataMode(radioAddress, Civ.ADDR_CONTROLLER))
                // The mode reply asked for above prompts a data-mode question of its
                // own; this one already covers it.
                if (!prompted) synchronized(askLock) { askSoon = false }
                try { Thread.sleep(REQUEST_GAP_MS) } catch (_: InterruptedException) { return }
                if (running && ptt?.isKeyed != true) {
                    writeFrame(Civ.request(radioAddress, Civ.ADDR_CONTROLLER, Civ.CMD_READ_FREQ))
                }
            }
        }
    }

    /**
     * Make sure the SSB-D transmit passband was read at connect, asking again
     * [READBACK_TRIES] - 1 more times if the first question ([requestInitialState]) was
     * not answered. Not on the timer: it is a menu setting most operators set once or
     * never, so a change made in the radio's menu is picked up at the next connect or
     * app restart.
     *
     * A refusal (`FA`, firmware before 1.30) ends the retries; the app then keeps its
     * default range, as it does when nothing answers.
     */
    private fun confirmPassbandAtConnect() {
        // Let the connect questions and their answers clear the bus first.
        try { Thread.sleep(CONNECT_SETTLE_MS) } catch (_: InterruptedException) { return }
        if (!running || reader.state.txPassbandHz != null) return
        synchronized(commandLock) {
            if (!running || ptt?.isKeyed == true) return
            ack = null
            awaitingAck = true
            try {
                askUntilLocked(
                    Civ.readSsbdTxPassband(radioAddress, Civ.ADDR_CONTROLLER),
                    tries = READBACK_TRIES - 1
                ) { reader.state.txPassbandHz != null || ack == false }
                when {
                    reader.state.txPassbandHz != null -> Unit
                    ack == false -> log("CAT: the radio refused the SSB-D TX passband read; using the default range")
                    else -> log("CAT: the radio did not report its SSB-D TX passband after $READBACK_TRIES questions; using the default range")
                }
            } finally {
                awaitingAck = false
            }
        }
    }

    /**
     * Ask once for what the radio will never volunteer: where it already is.
     *
     * Transceive broadcasts announce *changes*. A radio sitting still has nothing to
     * announce, so a listener that only subscribes learns the frequency when the
     * operator next touches the dial — and until then shows no radio while being
     * perfectly well connected. Caught on the phone, where the probe
     * identified the baud rate from a reply and then discarded the frequency in it.
     */
    private fun requestInitialState(serialPort: UsbSerialPort) {
        try {
            serialPort.write(
                Civ.request(radioAddress, Civ.ADDR_CONTROLLER, Civ.CMD_READ_FREQ),
                WRITE_TIMEOUT_MS
            )
            // One question at a time. The bus is half-duplex and shared: sending the
            // second request immediately puts our bytes on the wire while the radio
            // is still answering the first, and the answer that gets lost is the
            // second one. Seen on the phone - the frequency arrived and
            // the mode never did.
            Thread.sleep(REQUEST_GAP_MS)
            serialPort.write(
                Civ.request(radioAddress, Civ.ADDR_CONTROLLER, Civ.CMD_READ_MODE),
                WRITE_TIMEOUT_MS
            )
            // The SSB-D transmit passband, which limits where we may transmit.
            // Asked only at connect; an unanswered question is
            // asked again by [confirmPassbandAtConnect]. Firmware before 1.30 refuses
            // it; the app then keeps its default range.
            Thread.sleep(REQUEST_GAP_MS)
            serialPort.write(
                Civ.readSsbdTxPassband(radioAddress, Civ.ADDR_CONTROLLER),
                WRITE_TIMEOUT_MS
            )
        } catch (t: Throwable) {
            log("CAT: could not ask for the current state: ${t.message}")
        }
    }

    private fun readLoop(serialPort: UsbSerialPort) {
        val buffer = ByteArray(READ_BUFFER_BYTES)
        var last = reader.state
        requestInitialState(serialPort)
        try {
            while (running) {
                val n = try {
                    serialPort.read(buffer, READ_TIMEOUT_MS)
                } catch (t: Throwable) {
                    log("CAT read failed: ${t.javaClass.simpleName} ${t.message}")
                    break
                }
                if (n <= 0) continue

                // Meter replies are pulled out before the reader digests the rest,
                // because they are request/response and someone is waiting.
                absorbMeter(Civ.parseFrames(buffer, n))
                val now = reader.onBytes(buffer, n)
                absorbAck(reader.lastFrames)
                // The mode broadcast does not carry data mode: ask for it after every one.
                if (reader.lastFrames.any { it.from != Civ.ADDR_CONTROLLER && Civ.newestMode(listOf(it)) != null }) {
                    askDataModeSoon()
                }
                if (now != last) {
                    last = now
                    onState(now)
                }
            }
        } finally {
            running = false
            try { serialPort.close() } catch (_: Throwable) { }
        }
    }

    companion object {
        /**
         * Tried in this order. 115200 first because it is the IC-7300's fastest and
         * the most likely setting on a radio used with a computer; the rest descend
         * to the old defaults.
         */
        val BAUD_CANDIDATES = intArrayOf(115200, 19200, 38400, 57600, 9600, 4800)

        private const val PROBE_READ_TIMEOUT_MS = 400
        private const val READ_TIMEOUT_MS = 250
        private const val WRITE_TIMEOUT_MS = 500
        private const val STOP_TIMEOUT_MS = 1_000L
        private const val READ_BUFFER_BYTES = 1024

        /** How long to wait for a meter reply. Short: it is polled during a transmission. */
        private const val METER_TIMEOUT_MS = 300L

        /** Gap between two consecutive requests on a half-duplex, shared bus. */
        private const val REQUEST_GAP_MS = 250L

        /**
         * The top of what the IC-7300 tunes: Hamlib `ic7300.c` lists its ranges up
         * to 74.8 MHz. The frequency picker offers nothing above it.
         */
        const val TOP_HZ = 74_800_000L

        /** How long a set command may take to be acknowledged; also how long a frequency answer is waited for. */
        private const val ACK_TIMEOUT_MS = 500L

        /** How many times a tune asks for the frequency before giving up; also the passband questions at connect. */
        private const val READBACK_TRIES = 3

        /**
         * How long after the connect questions the passband is checked for an answer:
         * the three questions are 250 ms apart and each answer is waited on 500 ms.
         */
        private const val CONNECT_SETTLE_MS = 1_500L

        /** How often data mode is asked for when nothing has prompted it. A chosen default. */
        private const val DATA_POLL_MS = 10_000L

        /** The driver for a device, or null if this is not a CAT interface we know. */
        fun driverFor(device: UsbDevice): UsbSerialDriver? =
            UsbSerialProber.getDefaultProber().probeDevice(device)

        /**
         * The attached CAT device, if there is one.
         *
         * The IC-7300's CAT interface enumerates as a vendor-specific CP2102
         * (verified by enumeration), which the bundled CP210x driver
         * matches. Anything else the prober recognises is accepted too — the
         * architecture is radio-agnostic.
         */
        fun findDevice(usbManager: UsbManager): UsbDevice? =
            usbManager.deviceList.values.firstOrNull { driverFor(it) != null }
    }
}
