package com.ft8ok.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.ft8ok.log.Adif
import com.ft8ok.log.LoggedQso

/**
 * A logbook: one activation, contest or general session, with its own ADIF file.
 * New contacts go into the active one; "worked before" looks
 * across all of them.
 */
@Entity(tableName = "logbook")
data class LogbookEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** `GENERAL`, `POTA`, `SOTA` or `CONTEST`. Only GENERAL is used in v1 (ADR 0011). */
    val type: String = TYPE_GENERAL,
    val createdAtMs: Long
) {
    companion object {
        const val TYPE_GENERAL = "GENERAL"
    }
}

/**
 * One logged contact. One column per field [LoggedQso] models, so the log can be
 * searched and sorted without parsing; everything else an import carried is kept
 * as ADIF text in [extraAdif].
 */
@Entity(
    tableName = "qso",
    foreignKeys = [ForeignKey(
        entity = LogbookEntity::class,
        parentColumns = ["id"],
        childColumns = ["logbookId"],
        // A logbook with contacts in it cannot be deleted by accident: clearing one
        // archives its ADIF first and then deletes the rows itself.
        onDelete = ForeignKey.RESTRICT
    )],
    indices = [Index("logbookId", "timeOnMs"), Index("call")]
)
data class QsoEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val logbookId: Long,
    val call: String,
    val timeOnMs: Long,
    val timeOffMs: Long?,
    val freqHz: Long?,
    val band: String?,
    val mode: String,
    val submode: String?,
    val stationCallsign: String?,
    val myGrid: String?,
    val grid: String?,
    val rstSent: String?,
    val rstRcvd: String?,
    val state: String?,
    val dxcc: Int?,
    val cqZone: Int?,
    val ituZone: Int?,
    val continent: String?,
    val sig: String?,
    val sigInfo: String?,
    val mySig: String?,
    val mySigInfo: String?,
    val myState: String?,
    val comment: String?,
    val extraAdif: String
) {

    fun toLoggedQso() = LoggedQso(
        call = call, timeOnMs = timeOnMs, timeOffMs = timeOffMs, freqHz = freqHz, band = band,
        mode = mode, submode = submode, stationCallsign = stationCallsign, myGrid = myGrid,
        grid = grid, rstSent = rstSent, rstRcvd = rstRcvd, state = state, dxcc = dxcc,
        cqZone = cqZone, ituZone = ituZone, continent = continent, sig = sig, sigInfo = sigInfo,
        mySig = mySig, mySigInfo = mySigInfo, myState = myState, comment = comment,
        extra = Adif.decodeFields(extraAdif)
    )

    companion object {
        fun of(q: LoggedQso, logbookId: Long) = QsoEntity(
            logbookId = logbookId, call = q.call, timeOnMs = q.timeOnMs, timeOffMs = q.timeOffMs,
            freqHz = q.freqHz, band = q.band, mode = q.mode, submode = q.submode,
            stationCallsign = q.stationCallsign, myGrid = q.myGrid, grid = q.grid,
            rstSent = q.rstSent, rstRcvd = q.rstRcvd, state = q.state, dxcc = q.dxcc,
            cqZone = q.cqZone, ituZone = q.ituZone, continent = q.continent, sig = q.sig,
            sigInfo = q.sigInfo, mySig = q.mySig, mySigInfo = q.mySigInfo, myState = q.myState,
            comment = q.comment, extraAdif = Adif.encodeFields(q.extra)
        )
    }
}
