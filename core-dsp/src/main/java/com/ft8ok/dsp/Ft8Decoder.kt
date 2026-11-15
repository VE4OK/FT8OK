package com.ft8ok.dsp

/**
 * One decoded FT8 message.
 *
 * @param score the decoder's Costas sync correlation score. **This is not an SNR.**
 *   `ft8_lib`'s demo prints `score * 0.5` labelled as SNR, carrying its own
 *   "TODO: compute better approximation of SNR" — it measures how strongly the
 *   sync pattern correlated, not signal-to-noise, so no calibration offset turns
 *   one into the other. Useful for ranking candidates by
 *   confidence; **must never be sent as an on-air signal report**, which would put
 *   a wrong number in the other operator's log as well as ours.
 * @param dtSeconds the message's time offset within the slot. This is the input to
 *   the band-consensus clock estimator.
 * @param frequencyHz audio frequency within the passband, not the dial frequency.
 * @param message the unpacked message text, e.g. `CQ W3XAA EM13`.
 * @param fromReplyList found by testing the partner's possible replies, not by the
 *   decoder: marked on the feed, and [dtSeconds] is where the partner
 *   was last heard, not a measurement, so it must not reach the band clock.
 */
data class Decode(
    val score: Int,
    val dtSeconds: Double,
    val frequencyHz: Int,
    val message: String,
    val fromReplyList: Boolean = false
)

/**
 * How close the audio came to a partner's possible replies: fewer
 * hard errors is closer, out of 174. `ReplyWatch.accept` decides whether it counts as heard.
 *
 * @param message the closest of the list.
 * @param decoyHardErrors the same test with a call nobody sends to in place of ours.
 */
data class ReplyMatch(val hardErrors: Int, val message: String, val decoyHardErrors: Int)

/**
 * What a second look found once the decoded signals were taken out.
 *
 * @param removed how many signals were taken out.
 * @param cutShort true when time ran out before it had finished.
 * @param looks the same decodes, by the audio each was found in.
 */
data class DeepDecode(
    val decodes: List<Decode> = emptyList(),
    val removed: Int = 0,
    val cutShort: Boolean = false,
    val looks: List<Look> = emptyList()
) {
    /**
     * One look: [audio] has what was found earlier taken out and [decodes] still in it.
     * Their reports belong here. On the untouched audio a neighbour's power counts as
     * theirs, and read up to 10 dB high on air.
     */
    class Look(val audio: FloatArray, val decodes: List<Decode>)
}

/**
 * The FT8 decoder: audio window in, decodes out. That narrow interface is what
 * ADR 0004 requires so the underlying library stays swappable.
 *
 * Deliberately free of Android framework types, so the surrounding logic can be
 * unit-tested on the JVM and the only Android dependency is the JNI loader.
 */
object Ft8Decoder {

    /** ft8_lib accepts **only** 12 kHz and rejects other rates outright. */
    const val REQUIRED_SAMPLE_RATE = 12_000

    /**
     * How much higher this decoder reports DT than the signal's true offset from the
     * start of the buffer it was given, in seconds.
     *
     * **A property of the decoder, not of FT8**, which is why it lives here and not
     * on `Mode`. Measured two independent ways once:
     *
     *  - Known input: `ft8_lib`'s own `gen_ft8` pads 1.18 s of silence and its own
     *    `decode_ft8` reports +1.36. Our synthesis padded 0.50 / 1.00 / 2.00 s
     *    reported +0.64 / +1.12 / +2.16.
     *  - Against a reference decoder over 936 shared decodes of the same audio, ours
     *    reads +0.657 s with a standard deviation of 0.038 - a hard constant. That
     *    closes the arithmetic: the reference is reporting `true - 0.5`, which is
     *    the documented convention of subtracting the nominal transmission start.
     *
     * **It matters because the band-consensus clock compares against it.** Treating
     * a correctly-timed station as reading 0.50 when this decoder reports 0.65 drives
     * our whole slot grid about 0.15 s late, and every transmission with it.
     *
     * Swap the decoder and this number changes or disappears, which is the other
     * reason it is not a protocol constant.
     */
    const val DT_BIAS_SEC = 0.15

    /**
     * The same for FT4, and **not** FT8's: +0.040..+0.050 s on WSJT-X-shaped FT4
     * messages placed at four known starts (a host test). Using 0.15 would put FT4OK's slot grid about 0.1 s late and
     * leave subtraction outside its time search.
     */
    const val DT_BIAS_SEC_FT4 = 0.045

    /** The bias for a mode by its on-air name (`Mode.name`): FT4's, else FT8's. */
    fun dtBiasSec(modeName: String): Double = if (modeName == "FT4") DT_BIAS_SEC_FT4 else DT_BIAS_SEC

    @Volatile
    private var loaded = false

    @Volatile
    var loadError: String? = null
        private set

    /**
     * Load the native library. Safe to call repeatedly. Returns false rather than
     * throwing so a UI can report the problem instead of crashing.
     */
    fun ensureLoaded(): Boolean {
        if (loaded) return true
        return try {
            System.loadLibrary("ft8ok_dsp")
            loaded = true
            loadError = null
            true
        } catch (t: Throwable) {
            loadError = "${t.javaClass.simpleName}: ${t.message}"
            false
        }
    }

    /**
     * Our own callsign, so a message that names us only by a hash reads `<VE4OK>`
     * rather than `<...>`: a station with a call FT8 cannot carry in full (VE3XAA/QRP)
     * sends its RR73 and 73 that way. Set it whenever the profile
     * may have changed; it reaches the native side at the next [decode].
     */
    @Volatile
    var myCall: String = ""

    private var myCallApplied: String? = null

    /** What [decode] looks for. One per app, fixed at build time. */
    enum class Protocol { FT8, FT4 }

    /**
     * Set once at start-up from the app's mode, before any decode. [decode] and
     * [decodeDeeper] (subtraction, in FT4's own waveform) follow it. **[bestReply] does
     * nothing with FT4** (the a priori list is FT8 only). [encode]
     * follows it too; whether FT4OK may key the radio is `Flavor.TRANSMIT_BUILT`.
     */
    @Volatile
    var protocol: Protocol = Protocol.FT8

    private var protocolApplied: Protocol? = null

    /**
     * Decode one slot of audio.
     *
     * @param samples mono float samples, nominally ±1.0, at [REQUIRED_SAMPLE_RATE].
     *   A full FT8 slot is 15 s = 180 000 samples; slightly less is fine and is
     *   what live capture provides, since the useful audio ends around 13.5 s.
     *
     * One at a time: the native side keeps its callsign tables for the whole session
     * (every call heard in full, for the messages that name a call only by its hash),
     * and the setup self test can decode while receive is running.
     */
    @Synchronized
    fun decode(samples: FloatArray, sampleRate: Int = REQUIRED_SAMPLE_RATE): List<Decode> {
        require(sampleRate == REQUIRED_SAMPLE_RATE) {
            "decoder needs $REQUIRED_SAMPLE_RATE Hz, got $sampleRate - decimate first"
        }
        if (!ensureLoaded()) return emptyList()

        val call = myCall.trim().uppercase()
        if (call != myCallApplied) {
            nativeSetMyCall(call)
            myCallApplied = call
        }
        applyProtocol()

        return nativeDecode(samples, sampleRate).mapNotNull(::parseLine)
    }

    /** Hand [protocol] to the native side if it changed. Before a decode or an encode. */
    @Synchronized
    private fun applyProtocol() {
        val wanted = protocol
        if (wanted != protocolApplied) {
            nativeSetProtocol(wanted == Protocol.FT4)
            protocolApplied = wanted
        }
    }

    /**
     * How well the audio near [frequencyHz] and [dtSeconds] matches anything [from]
     * could send [to] next, and the same for [decoy] in [to]'s place.
     * [cleaned]: use what the last [decodeDeeper] left of the slot, not [samples].
     * [grid]: [from]'s grid if known, which puts their two grid messages in the list.
     */
    @Synchronized
    fun bestReply(
        samples: FloatArray,
        cleaned: Boolean,
        to: String,
        decoy: String,
        from: String,
        grid: String?,
        frequencyHz: Int,
        dtSeconds: Double
    ): ReplyMatch? {
        if (protocol != Protocol.FT8) return null // the list is FT8 tones
        if (!ensureLoaded()) return null
        val line = nativeBestReply(
            samples, REQUIRED_SAMPLE_RATE, cleaned, to, decoy, from, grid.orEmpty(), frequencyHz.toFloat(), dtSeconds.toFloat()
        ) ?: return null
        val parts = line.split('\t')
        if (parts.size < 3) return null
        return ReplyMatch(parts[0].toIntOrNull() ?: return null, parts[1], parts[2].toIntOrNull() ?: return null)
    }

    /**
     * Look again at the [samples] of the last [decode], with what it found taken out,
     * until nothing new appears or [budgetMs] has gone. Strong signals hide what lies
     * beside them, and this uncovers it. Nothing found by [decode] is lost: this only
     * adds. If another decode came between the two, it finds nothing and says cut short.
     */
    @Synchronized
    fun decodeDeeper(samples: FloatArray, budgetMs: Long): DeepDecode {
        if (!ensureLoaded()) return DeepDecode()
        val audio = Array(MAX_LOOKS) { FloatArray(samples.size) }
        val lines = nativeDecodeDeeper(samples, REQUIRED_SAMPLE_RATE, budgetMs, audio)
        val status = lines.firstOrNull()?.split('\t') ?: return DeepDecode()
        val byLook = lines.drop(1).groupBy { it.substringAfterLast('\t').toIntOrNull() ?: 0 }
        val looks = byLook.map { (look, found) ->
            DeepDecode.Look(audio[look.coerceIn(0, MAX_LOOKS - 1)], found.mapNotNull(::parseLine))
        }
        return DeepDecode(
            decodes = looks.flatMap { it.decodes },
            removed = status.getOrNull(0)?.toIntOrNull() ?: 0,
            cutShort = status.getOrNull(1) == "1",
            looks = looks
        )
    }

    /**
     * The JNI boundary carries tab-separated text rather than structured objects:
     * constructing Java objects from C is a lot of fragile boilerplate for no gain
     * at this volume (tens of messages per 15 s slot), and a text protocol is
     * trivially inspectable when something goes wrong.
     */
    private fun parseLine(line: String): Decode? {
        val parts = line.split('\t')
        if (parts.size < 4) return null
        val score = parts[0].toIntOrNull() ?: return null
        val dt = parts[1].toDoubleOrNull() ?: return null
        val hz = parts[2].toDoubleOrNull()?.toInt() ?: return null
        return Decode(score = score, dtSeconds = dt, frequencyHz = hz, message = parts[3])
    }

    /**
     * Average power spectrum of a slot, in dB per bin from DC to Nyquist.
     *
     * Feeds the occupancy map (see [Occupancy]). Separate from [decode] because the
     * strip must show energy that is present but not decodable — a signal too weak
     * for us still collides with our transmission.
     *
     * @param nfft FFT size; bin width is [REQUIRED_SAMPLE_RATE] / nfft. 2048 gives
     *   ~5.9 Hz bins, comfortably finer than FT8's 50 Hz signal width.
     */
    fun spectrum(samples: FloatArray, nfft: Int = 2048): FloatArray {
        if (!ensureLoaded()) return FloatArray(0)
        if (samples.size < nfft) return FloatArray(0)
        return nativeSpectrum(samples, nfft)
    }

    /** Bin width for a given FFT size, as [spectrum] produces. */
    fun binHz(nfft: Int = 2048): Double = REQUIRED_SAMPLE_RATE.toDouble() / nfft

    /**
     * Encode a message into its tones (79 for FT8, 105 for FT4, by [protocol]), or
     * null if the 77-bit protocol cannot represent it.
     *
     * **Null means do not transmit.** A callsign or grid the protocol cannot carry
     * is a refusal, not a warning to ignore - transmitting something else, or
     * nothing, both beat transmitting a message the other end will read as garbage.
     *
     * @param freeText send [message] as free text and nothing else - for text the
     *   operator typed as text. Otherwise the message must encode
     *   as an addressed message: the library's own fall-back to free text, when a
     *   standard message fails, is refused here rather than sent.
     */
    fun encode(message: String, freeText: Boolean = false): ByteArray? {
        if (!ensureLoaded()) return null
        // FT8's 79 tones or FT4's 105, by [protocol].
        applyProtocol()
        val tones = if (freeText) {
            nativeEncodeFree(message)
        } else {
            val encoding = nativeEncoding(message)
            if (encoding == ENCODING_FREE_TEXT) return null
            // A contest exchange (Field Day) or any other type the
            // engine builds goes out only if it decodes back exactly. Hashed calls
            // (the DXpedition message's `<VE4OK>`) cannot come back without a call
            // table, and keep their own checks.
            if (encoding == ENCODING_OTHER && '<' !in message && nativeDecodedAs(message) != message) return null
            nativeEncode(message)
        }
        return if (tones.isEmpty()) null else tones
    }

    /**
     * How the encoder would carry [message] if left to choose: one of the
     * `ENCODING_` codes, or null if the native library is not loaded. Cheap - no tones
     * are made - so the panel asks on every keystroke.
     */
    fun encoding(message: String): Int? = if (ensureLoaded()) nativeEncoding(message) else null

    /**
     * What other stations' decoders will show for [message] if it is sent as the
     * encoder chooses: encoded and decoded straight back. Empty if it cannot be
     * encoded; null if the library is not loaded. The encoder garbles rather than
     * refuses - `CQ HIHI TEST` comes back as `CQ HIHI <...>` - so this,
     * not [encoding], says whether a message survives.
     */
    fun decodedAs(message: String): String? = if (ensureLoaded()) nativeDecodedAs(message) else null

    /** Whether [message] fits as free text: 13 characters at most, FT8's alphabet. Null if not loaded. */
    fun fitsFreeText(message: String): Boolean? =
        if (ensureLoaded()) nativeEncodeFree(message).isNotEmpty() else null

    /** [encoding] results, as `ft8ok_decoder.c` defines them. */
    const val ENCODING_NONE = 0
    const val ENCODING_FREE_TEXT = 1
    const val ENCODING_STANDARD = 2
    const val ENCODING_NONSTANDARD_CALL = 3
    const val ENCODING_OTHER = 4

    @JvmStatic
    private external fun nativeEncode(message: String): ByteArray

    @JvmStatic
    private external fun nativeEncodeFree(message: String): ByteArray

    @JvmStatic
    private external fun nativeEncoding(message: String): Int

    @JvmStatic
    private external fun nativeDecodedAs(message: String): String

    @JvmStatic
    private external fun nativeDecode(samples: FloatArray, sampleRate: Int): Array<String>

    /** `hard<TAB>message<TAB>decoyHard`, or null if the calls will not pack. */
    private external fun nativeBestReply(
        samples: FloatArray,
        sampleRate: Int,
        cleaned: Boolean,
        to: String,
        decoy: String,
        from: String,
        grid: String,
        frequencyHz: Float,
        dtSeconds: Float
    ): String?

    /** The most looks [decodeDeeper] takes: `kMaxSubtractionRounds` in `ft8ok_decoder.c`. */
    private const val MAX_LOOKS = 3

    /**
     * First entry `removed<TAB>cutShort`, then decodes as [nativeDecode] gives them with
     * `<TAB>look` added; [looks] gets the audio of each look that found something.
     */
    private external fun nativeDecodeDeeper(
        samples: FloatArray,
        sampleRate: Int,
        budgetMs: Long,
        looks: Array<FloatArray>
    ): Array<String>

    @JvmStatic
    private external fun nativeSetMyCall(call: String)

    private external fun nativeSetProtocol(ft4: Boolean)

    @JvmStatic
    private external fun nativeSpectrum(samples: FloatArray, nfft: Int): FloatArray
}
