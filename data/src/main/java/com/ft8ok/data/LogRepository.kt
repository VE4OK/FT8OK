package com.ft8ok.data

import com.ft8ok.log.Adif
import com.ft8ok.log.LoggedQso
import java.io.IOException

/** Where a logbook's ADIF file goes. The real one is [SafAdifSink]. */
fun interface AdifSink {
    /** Replace the whole file [fileName] with [text]. Blocking; throws [IOException]. */
    fun write(fileName: String, text: String)
}

/**
 * The log as the rest of the app sees it.
 *
 * **The database write is what matters; the file is a copy.** Every change stores
 * first and then rewrites the logbook's whole ADIF file. If the file write fails
 * (folder removed, card pulled, permission revoked) the contact is still logged,
 * the failure is returned for a banner, and the next successful write brings the
 * file back up to date - so the file can never lose a contact the database has.
 *
 * Blocking file I/O: call from a background dispatcher, never the main thread.
 */
class LogRepository(
    private val db: LogDatabase,
    /** Null until the operator has chosen a folder; the database works without one. */
    private val sink: AdifSink?,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val dao = db.logDao()

    /** The outcome of a change: the row it touched, and why the file is stale, if it is. */
    data class Result(val id: Long, val fileError: String?)

    /** The logbook new contacts go into. Creates "General" on first use. */
    suspend fun defaultLogbook(): LogbookEntity =
        dao.logbooks().firstOrNull()
            ?: LogbookEntity(name = DEFAULT_NAME, createdAtMs = clock()).let { it.copy(id = dao.insertLogbook(it)) }

    suspend fun log(qso: LoggedQso, logbookId: Long): Result {
        val id = dao.insertQso(QsoEntity.of(qso, logbookId))
        return Result(id, refresh(logbookId))
    }

    /** Undo a log entry, or delete one by hand. */
    suspend fun undo(id: Long): Result {
        val row = dao.qso(id) ?: return Result(id, null)
        dao.deleteQso(id)
        return Result(id, refresh(row.logbookId))
    }

    /** Delete an entry, returning it so an Undo can [restore] it. Null if it was not there. */
    suspend fun delete(id: Long): Pair<Stored, Result>? {
        val row = dao.qso(id) ?: return null
        dao.deleteQso(id)
        return Stored(id, row.logbookId, row.toLoggedQso()) to Result(id, refresh(row.logbookId))
    }

    /** Put a deleted entry back in its logbook. It gets a new id; nothing refers to the old one. */
    suspend fun restore(entry: Stored): Result = log(entry.qso, entry.logbookId)

    /** Replace an entry's fields with an edited copy; its logbook stays. */
    suspend fun update(id: Long, qso: LoggedQso): Result {
        val row = dao.qso(id) ?: return Result(id, "contact $id not found")
        dao.updateQso(QsoEntity.of(qso, row.logbookId).copy(id = id))
        return Result(id, refresh(row.logbookId))
    }

    /** A contact with its row id, which editing and deleting need. */
    data class Stored(val id: Long, val logbookId: Long, val qso: LoggedQso)

    /** The logbook's contacts with their ids, oldest first. */
    suspend fun entries(logbookId: Long): List<Stored> =
        dao.qsosIn(logbookId).map { Stored(it.id, it.logbookId, it.toLoggedQso()) }

    /** The logbook with [id], or the default one if there is none (never chosen, or gone). */
    suspend fun logbook(id: Long?): LogbookEntity = id?.let { dao.logbook(it) } ?: defaultLogbook()

    /** A logbook and how many contacts it holds. */
    data class Book(val logbook: LogbookEntity, val count: Int)

    /** Every logbook, oldest first, with its count. */
    suspend fun books(): List<Book> {
        defaultLogbook()
        val counts = dao.counts().associate { it.logbookId to it.n }
        return dao.logbooks().map { Book(it, counts[it.id] ?: 0) }
    }

    enum class NameProblem { EMPTY, TOO_LONG, NOT_ASCII, TAKEN }

    /**
     * A new, empty logbook, or why not. **Names that would share an
     * ADIF file are refused** - `CQWW 10/25` and `CQWW_10_25` are both
     * `FT8OK-CQWW_10_25.adi`, and two logbooks writing one file would overwrite each
     * other's contacts in it.
     */
    suspend fun createLogbook(name: String): Pair<LogbookEntity?, NameProblem?> {
        val n = name.trim()
        val problem = when {
            n.isEmpty() -> NameProblem.EMPTY
            n.length > MAX_NAME -> NameProblem.TOO_LONG
            !n.all { it in ' '..'~' } -> NameProblem.NOT_ASCII
            else -> {
                val file = fileNameFor(LogbookEntity(name = n, createdAtMs = 0))
                if (dao.logbooks().any { fileNameFor(it).equals(file, ignoreCase = true) }) NameProblem.TAKEN else null
            }
        }
        if (problem != null) return null to problem
        val book = LogbookEntity(name = n, createdAtMs = clock())
        return book.copy(id = dao.insertLogbook(book)) to null
    }

    /** What an import did. [rejected] stay in the operator's file; nothing here is lost. */
    data class Imported(
        val added: Int,
        val skipped: Int,
        val rejected: List<Adif.Rejected>,
        val fileError: String?
    )

    /**
     * Add an ADIF file's contacts to a logbook.
     *
     * - A contact already in the logbook - same call, band and start minute - is
     *   **skipped**, so importing the same file twice does not double the log.
     * - A record this app could not write back out (text outside ASCII, which the
     *   writer refuses) is **rejected** with the unreadable ones:
     *   stored, it would make every later rewrite of the logbook's file fail.
     *
     * @throws IllegalArgumentException if [text] is not an ADIF file at all.
     */
    suspend fun import(text: String, logbookId: Long): Imported {
        val read = Adif.read(text)
        val rejected = read.rejected.toMutableList()
        val seen = qsos(logbookId).mapTo(HashSet(), ::dupeKey)
        val add = mutableListOf<LoggedQso>()
        var skipped = 0
        for (q in read.qsos) {
            try {
                Adif.write(listOf(q))
            } catch (e: IllegalArgumentException) {
                rejected += Adif.Rejected(mapOf("CALL" to q.call), e.message ?: "not ASCII")
                continue
            }
            if (seen.add(dupeKey(q))) add += q else skipped++
        }
        dao.insertQsos(add.map { QsoEntity.of(it, logbookId) })
        return Imported(add.size, skipped, rejected, refresh(logbookId))
    }

    private fun dupeKey(q: LoggedQso) = Triple(q.call.uppercase(), q.band?.lowercase(), q.timeOnMs / 60_000)

    suspend fun qsos(logbookId: Long): List<LoggedQso> = dao.qsosIn(logbookId).map { it.toLoggedQso() }

    /** Worked this call before, in any logbook, on any band - or on [band] if given. */
    suspend fun workedBefore(call: String, band: String? = null): Boolean =
        (if (band == null) dao.countWith(call) else dao.countWith(call, band)) > 0

    /** Rewrite a logbook's file from the database. Returns why it failed, or null. */
    suspend fun refresh(logbookId: Long): String? {
        val sink = sink ?: return null
        val logbook = dao.logbook(logbookId) ?: return "no logbook $logbookId"
        return try {
            sink.write(fileNameFor(logbook), Adif.write(qsos(logbookId)))
            null
        } catch (e: IOException) {
            e.message ?: e.javaClass.simpleName
        } catch (e: SecurityException) {
            // The folder permission was revoked from outside the app.
            e.message ?: "folder permission lost"
        }
    }

    companion object {
        const val DEFAULT_NAME = "General"

        /** Long enough for `POTA K-1234 2026-07-04`; short enough for the Log header. */
        const val MAX_NAME = 32

        /**
         * `FT8OK-General.adi`. One file per logbook, kept stable so the operator always
         * finds the same file. POTA's `CALL@PARK-yyyymmdd` naming waits for the
         * activation profile (ADR 0011).
         */
        fun fileNameFor(logbook: LogbookEntity): String =
            "FT8OK-" + logbook.name.replace(Regex("[^A-Za-z0-9._-]+"), "_") + ".adi"
    }
}
