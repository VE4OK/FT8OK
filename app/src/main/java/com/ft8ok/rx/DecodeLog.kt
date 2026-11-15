package com.ft8ok.rx

import com.ft8ok.dsp.ReplyMatch
import com.ft8ok.model.SlotParity
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The lines of `files/decodes.log`: every message heard or sent, for reading over adb
 * after a session. `events.log` keeps no message text, and the feed
 * lives only in memory, so "did we hear their reply?" had no record. Pure, so it is
 * tested on the JVM.
 *
 * Tab-separated, after the stamp the log adds:
 *
 *     RX  slot  parity  band  pass  hz  dt  snr  message
 *     TX  slot  parity  band  -     hz  -   -    message
 *     SLOT  slot  parity  band  decodes  ok|dropped|clipped
 *     LIST  slot  parity  band  partner  armed|disarmed  to_us|to_other|not_heard
 *           hz  dt  hard  message  decoy_hard  ms  grid  heard|-    (see [reply])
 *
 * `dt` is the decoder's raw figure, so an on-time FT8 station reads about 0.65
 * ([FeedLine.dtFromOnTime]). `snr` is our measured report in dB, `-` when it could not
 * be measured. `pass` counts from 0, and is `list` for a reply the reply list found
 * (its `dt` is where the partner was last heard). A slot with no `SLOT` line had no final pass, so
 * nothing was listened to; `decodes` 0 means it was listened to and nothing was found.
 */
object DecodeLog {

    private val slotFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.US).withZone(ZoneOffset.UTC)

    private fun slot(ms: Long) = slotFormat.format(Instant.ofEpochMilli(ms))

    /** One decode, or one of our transmissions; [pass] is ignored for those. */
    fun row(row: RxSession.DecodeRow, pass: Int): String {
        val start = listOf(
            if (row.tx) "TX" else "RX",
            slot(row.slotStartMs),
            row.parity.name.lowercase(),
            row.band ?: "-"
        )
        val rest = if (row.tx) {
            listOf("-", row.decode.frequencyHz.toString(), "-", "-")
        } else {
            listOf(
                if (row.decode.fromReplyList) "list" else pass.toString(),
                row.decode.frequencyHz.toString(),
                "%.2f".format(Locale.US, row.decode.dtSeconds),
                row.snrDb?.let { "%.1f".format(Locale.US, it) } ?: "-"
            )
        }
        return (start + rest + row.decode.message).joinToString("\t")
    }

    /**
     * One test of a partner's slot against their possible replies.
     * `hard` counts errors out of 174, lower is closer; `decoy_hard` is the same with a
     * call nobody sends to in place of ours, so a low `hard` with a high `decoy_hard` is
     * a reply, and both low is their traffic. The last column is `heard` when
     * [ReplyWatch.accept] let it through to the feed and the QSO engine.
     */
    fun reply(
        slotMs: Long,
        parity: SlotParity,
        band: String?,
        plan: ReplyWatch.Plan,
        match: ReplyMatch,
        millis: Long,
        accepted: Boolean = false
    ): String = listOf(
        "LIST",
        slot(slotMs),
        parity.name.lowercase(),
        band ?: "-",
        plan.partner,
        if (plan.armed) "armed" else "disarmed",
        plan.heard.name.lowercase(),
        plan.frequencyHz.toString(),
        "%.2f".format(Locale.US, plan.dtSeconds),
        match.hardErrors.toString(),
        match.message,
        match.decoyHardErrors.toString(),
        millis.toString(),
        plan.partnerGrid ?: "-",
        if (accepted) "heard" else "-"
    ).joinToString("\t")

    /** The end of a slot: how many messages it held and whether they count. */
    fun slotEnd(update: LiveRxPipeline.SlotUpdate, band: String?, dropped: Boolean): String =
        listOf(
            "SLOT",
            slot(update.slotStartMs),
            update.parity.name.lowercase(),
            band ?: "-",
            update.allDecodes.size.toString(),
            when {
                dropped -> "dropped"
                update.clipped -> "clipped"
                else -> "ok"
            }
        ).joinToString("\t")
}
