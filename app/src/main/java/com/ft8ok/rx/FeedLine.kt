package com.ft8ok.rx

import com.ft8ok.dsp.Ft8Decoder
import com.ft8ok.dsp.SnrEstimator
import com.ft8ok.model.Ft8Message
import com.ft8ok.model.Mode
import kotlin.math.abs

/**
 * One feed row's columns and the parts of its message drawn in another colour.
 * Pure, so it is tested on the JVM.
 *
 * The row is `SNR  DT  FREQ  message   note`, and the three number columns are
 * separate fixed-width cells on screen, right-aligned. **Not one monospace string:**
 * the test phone's `FontFamily.Monospace` is proportional (seen: `-5`
 * narrower than `-15`), so columns built from padded text did not line up.
 *
 * @property cq the `CQ` word, and the word after it when there is one (`CQ POTA`),
 *   as a range in the message.
 * @property sender the sending station's call in the message, for the worked-before mark.
 */
object FeedLine {

    data class Line(
        val snr: String,
        val dt: String,
        val frequency: String,
        val message: String,
        val cq: IntRange? = null,
        val sender: IntRange? = null
    )

    /**
     * The widest each number column can be, for sizing them once per screen: both
     * signs, since `+` is wider than `-` in the test phone's font (it clipped `+1.8`).
     */
    val WIDEST_SNR = listOf("-88", "+88")
    val WIDEST_DT = listOf("-8.8", "+8.8")
    val WIDEST_FREQUENCY = listOf("8888")

    /**
     * A decode's DT as the feed shows it: seconds from an on-time start, so a station
     * on the band's time reads 0.0, as in WSJT-X. Display only. The decoder's raw
     * figure (nominal start plus its bias, 0.65 s for an on-time FT8 station) is what
     * the clocks work from, so `Decode.dtSeconds` stays as it is.
     */
    fun dtFromOnTime(rawDtSeconds: Double, mode: Mode): Double =
        rawDtSeconds - (mode.nominalTxStartMs / 1000.0 + Ft8Decoder.dtBiasSec(mode.name))

    /**
     * Our measured report (`--` when it could not be measured), DT to one decimal
     * and the audio frequency. Never the sync score.
     *
     * @param dtSeconds from an on-time start ([dtFromOnTime]), not the decoder's raw DT.
     */
    fun build(snrDb: Double?, dtSeconds: Double, frequencyHz: Int, message: String, parsed: Ft8Message): Line =
        Line(
            snr = snrDb?.let { "%+d".format(SnrEstimator.toReport(it)) } ?: "--",
            // No sign on zero: decoder steps put on-time stations at -0.01, which
            // would otherwise read "-0.0".
            dt = if (abs(dtSeconds) < 0.05) "%.1f".format(0.0) else "%+.1f".format(dtSeconds),
            frequency = frequencyHz.toString(),
            message = message,
            cq = cqRange(message, parsed),
            sender = senderRange(message, parsed)
        )

    /** Our transmission: TX where the report goes, nothing for DT, so the message lines up. */
    fun buildTx(frequencyHz: Int, message: String): Line =
        Line("TX", "", frequencyHz.toString(), message)

    private fun cqRange(message: String, parsed: Ft8Message): IntRange? {
        val cq = parsed as? Ft8Message.Cq ?: return null
        val words = words(message)
        if (words.isEmpty() || message.substring(words[0]) != "CQ") return null
        val last = if (cq.directedTo != null && words.size > 1) 1 else 0
        return words[0].first..words[last].last
    }

    private fun senderRange(message: String, parsed: Ft8Message): IntRange? {
        val call = when (parsed) {
            is Ft8Message.Cq -> parsed.caller
            is Ft8Message.Reply -> parsed.from
            is Ft8Message.Report -> parsed.from
            is Ft8Message.Acknowledge -> parsed.from
            is Ft8Message.Dxpedition -> parsed.from
            is Ft8Message.Contest -> parsed.from
            is Ft8Message.Unknown -> null
        }?.takeIf { it != Ft8Message.HASHED } ?: return null
        // A resolved hash is written `<VE3XAA/QRP>` but parsed without its brackets.
        return words(message).firstOrNull {
            message.substring(it).removeSurrounding("<", ">").equals(call, ignoreCase = true)
        }
    }

    /** Each space-separated word of [message] as its character range. */
    private fun words(message: String): List<IntRange> {
        val out = ArrayList<IntRange>(6)
        var i = 0
        while (i < message.length) {
            if (message[i] == ' ') { i++; continue }
            val from = i
            while (i < message.length && message[i] != ' ') i++
            out += from until i
        }
        return out
    }
}
