package com.ft8ok.radio

/**
 * Keying the transmitter.
 *
 * **This is the only interface in the app that can put a signal on the air.** It is
 * deliberately tiny, deliberately separate from everything that decides *whether* to
 * transmit, and deliberately has a watchdog built into its contract rather than
 * bolted on above it.
 *
 * Rule 3, in full: TX arm defaults off; Halt releases PTT immediately and disarms;
 * a watchdog releases PTT if nothing refreshes it; the setup TX test is
 * hold-to-transmit only and cannot latch. The first three live here or just above;
 * the fourth is a UI shape.
 */
interface Ptt {

    /** True while the transmitter is keyed, as far as we know. */
    val isKeyed: Boolean

    /**
     * Key the transmitter.
     *
     * @param watchdogMs release automatically after this long without a [refresh].
     *   Not optional, and not a large number: the failure this guards against is our
     *   own process stalling or dying with the radio transmitting, which is the
     *   worst thing this app can do. A stalled app that cannot call [release] also
     *   cannot call [refresh].
     * @return null on success, or a reason.
     */
    fun key(watchdogMs: Long): String?

    /** Tell the watchdog we are still here and still mean it. */
    fun refresh()

    /** Unkey. Must be safe to call when not keyed, and must never throw. */
    fun release()
}

/**
 * PTT over CI-V, for a radio whose CAT link is already open.
 *
 * The command is `1C 00` with `01` to transmit and `00` to receive (Hamlib
 * `C_CTL_PTT` / `S_PTT`). Sending it needs the same serial port the rig link reads
 * from, so this is given a writer rather than opening its own.
 *
 * **The watchdog runs on its own thread and is armed before the key command is
 * sent**, not after. If the write succeeds and then this process stalls, the
 * watchdog still fires; if the write itself hangs, the watchdog is already running.
 */
class CivPtt(
    /** Writes bytes to the open CAT port. Returns false if the write failed. */
    private val write: (ByteArray) -> Boolean,
    private val radioAddress: Int = Civ.ADDR_IC7300,
    private val log: (String) -> Unit = {},
    private val nowMs: () -> Long = System::currentTimeMillis
) : Ptt {

    @Volatile
    private var keyed = false

    @Volatile
    private var deadlineMs = 0L

    private var watchdog: Thread? = null

    override val isKeyed: Boolean get() = keyed

    @Synchronized
    override fun key(watchdogMs: Long): String? {
        require(watchdogMs in 1..MAX_WATCHDOG_MS) {
            "watchdog must be between 1 ms and ${MAX_WATCHDOG_MS} ms, was $watchdogMs"
        }
        if (keyed) {
            refresh()
            return null
        }

        // Armed BEFORE the radio is keyed. If the write succeeds and we then stall,
        // this thread is already running and will release.
        deadlineMs = nowMs() + watchdogMs
        keyed = true
        startWatchdog()

        if (!write(command(transmit = true))) {
            keyed = false
            stopWatchdog()
            return "the radio did not accept the transmit command"
        }
        return null
    }

    @Synchronized
    override fun refresh() {
        if (keyed) deadlineMs = nowMs() + REFRESH_EXTENSION_MS
    }

    @Synchronized
    override fun release() {
        if (!keyed && watchdog == null) return
        keyed = false
        stopWatchdog()
        // Sent even if we believe we are not keyed: an unkey command to an unkeyed
        // radio costs nothing, and the state we are wrong about is the dangerous one.
        try {
            if (!write(command(transmit = false))) {
                log("PTT: the radio did not acknowledge receive - CHECK THE RADIO")
            }
        } catch (t: Throwable) {
            log("PTT: release failed: ${t.javaClass.simpleName} ${t.message} - CHECK THE RADIO")
        }
    }

    private fun startWatchdog() {
        stopWatchdog()
        watchdog = Thread {
            try {
                while (keyed) {
                    val left = deadlineMs - nowMs()
                    if (left <= 0) {
                        log("PTT WATCHDOG FIRED - releasing")
                        release()
                        return@Thread
                    }
                    Thread.sleep(minOf(left, WATCHDOG_POLL_MS))
                }
            } catch (_: InterruptedException) {
                // Released normally.
            }
        }.apply {
            name = "ft8ok-ptt-watchdog"
            isDaemon = false   // must not be killed while the radio is keyed
            start()
        }
    }

    private fun stopWatchdog() {
        watchdog?.interrupt()
        watchdog = null
    }

    private fun command(transmit: Boolean): ByteArray = Civ.request(
        radioAddress,
        Civ.ADDR_CONTROLLER,
        CMD_PTT,
        SUB_PTT,
        if (transmit) 0x01 else 0x00
    )

    companion object {
        /** Hamlib `C_CTL_PTT` / `S_PTT`. */
        const val CMD_PTT = 0x1C
        const val SUB_PTT = 0x00

        /**
         * The longest watchdog anything may ask for.
         *
         * An FT8 transmission is 12.64 s and the hard stop is at 14.7 s into the
         * slot, so nothing legitimate needs longer than a slot. Capping it means a
         * caller cannot accidentally request a watchdog that would let the radio
         * transmit indefinitely.
         */
        const val MAX_WATCHDOG_MS = 20_000L

        /** How long a [refresh] buys. Short: it is meant to be called often. */
        const val REFRESH_EXTENSION_MS = 2_000L

        private const val WATCHDOG_POLL_MS = 100L
    }
}
