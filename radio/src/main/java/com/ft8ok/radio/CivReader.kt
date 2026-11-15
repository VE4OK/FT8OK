package com.ft8ok.radio

/**
 * Turns a CI-V byte stream into [RigState].
 *
 * Pure Kotlin and stateful by necessity: the buffer **must** persist across reads.
 * CI-V is a stream, not a sequence of messages, and a frame routinely straddles two
 * reads — once one arrived as "...FD FE" followed by "FE E0 94 03 ..." and
 * parsing each read on its own found nothing in either, because the frame was split
 * through its own preamble. Keeping the tail reassembles it.
 *
 * Everything the transport has to get right lives here, where it can be tested
 * against scripted byte sequences instead of against a radio.
 */
class CivReader(
    /** Our own address, so our echoed requests can be recognised and dropped. */
    private val controllerAddress: Int = Civ.ADDR_CONTROLLER,
    private val nowMs: () -> Long = System::currentTimeMillis
) {

    /** The latest understood state. Volatile: a tune waits on it from another thread. */
    @Volatile
    var state: RigState = RigState()
        private set

    private var buffer = ByteArray(INITIAL_CAPACITY)
    private var length = 0

    /** How many frames were understood, for diagnostics. */
    var framesUnderstood = 0L
        private set

    /**
     * Every frame from the last read, echoes and acknowledgements included, for the
     * request/response paths (acks are one byte and [Civ.isEcho] drops them).
     */
    var lastFrames: List<Civ.Frame> = emptyList()
        private set

    /** How many frames were dropped as our own echo. */
    var echoesDropped = 0L
        private set

    /**
     * Feed bytes just read from the port. Returns the state after absorbing them.
     *
     * Safe to call with a partial frame, with several frames at once, with our own
     * echo mixed in, or with leading rubbish.
     */
    fun onBytes(chunk: ByteArray, count: Int = chunk.size): RigState {
        append(chunk, count)

        val scan = Civ.scan(buffer, length)
        if (scan.consumed > 0) compact(scan.consumed)
        lastFrames = scan.frames

        val usable = scan.frames.filterNot { frame ->
            val echo = Civ.isEcho(frame, controllerAddress)
            if (echo) echoesDropped++
            echo
        }
        if (usable.isEmpty()) return state

        // Newest wins. While the dial is turning the buffer holds a run of
        // superseded values, and the one we want is the last.
        val frequency = Civ.newestFrequencyHz(usable)
        val mode = Civ.newestMode(usable)
        val data = Civ.newestDataMode(usable)
        val passband = Civ.newestSsbdTxPassband(usable)
        if (frequency == null && mode == null && data == null && passband == null) return state

        // A mode frame says nothing about data mode. If the mode itself changed and no
        // data reply came after it, data mode is unknown again until the radio is asked.
        val lastModeAt = usable.indexOfLast { Civ.newestMode(listOf(it)) != null }
        val lastDataAt = usable.indexOfLast { Civ.newestDataMode(listOf(it)) != null }
        val dataMode = when {
            lastDataAt > lastModeAt -> data
            mode != null && mode.first != state.mode -> null
            else -> data ?: state.dataMode
        }

        framesUnderstood += usable.size
        state = state.copy(
            dialHz = frequency ?: state.dialHz,
            mode = mode?.first ?: state.mode,
            filter = mode?.second ?: state.filter,
            dataMode = dataMode,
            txPassbandHz = passband ?: state.txPassbandHz,
            lastHeardMs = nowMs()
        )
        return state
    }

    /** Forget everything, including the buffered tail. */
    fun reset() {
        length = 0
        state = RigState()
        framesUnderstood = 0
        echoesDropped = 0
    }

    private fun append(chunk: ByteArray, count: Int) {
        if (count <= 0) return
        if (length + count > buffer.size) grow(length + count)
        System.arraycopy(chunk, 0, buffer, length, count)
        length += count
    }

    private fun compact(consumed: Int) {
        val remaining = length - consumed
        if (remaining > 0) System.arraycopy(buffer, consumed, buffer, 0, remaining)
        length = remaining
    }

    private fun grow(needed: Int) {
        // A buffer that only ever grows would be a slow leak on a bus that never
        // stops talking. If nothing is parseable by the time we reach the cap, the
        // stream is not CI-V and holding more of it will not help.
        if (needed > MAX_CAPACITY) {
            length = 0
            return
        }
        var size = buffer.size
        while (size < needed) size *= 2
        buffer = buffer.copyOf(minOf(size, MAX_CAPACITY))
    }

    companion object {
        private const val INITIAL_CAPACITY = 1024

        /**
         * Far more than any real frame needs. A frequency frame is 11 bytes; this
         * holds hundreds, so a burst of transceive broadcasts while the dial spins
         * is absorbed without ever approaching the cap.
         */
        private const val MAX_CAPACITY = 8192
    }
}
