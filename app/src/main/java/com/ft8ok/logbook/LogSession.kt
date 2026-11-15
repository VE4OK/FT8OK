package com.ft8ok.logbook

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.net.toUri
import com.ft8ok.data.LogDatabase
import com.ft8ok.data.LogbookEntity
import com.ft8ok.log.Adif
import com.ft8ok.data.LogRepository
import com.ft8ok.data.SafAdifSink
import com.ft8ok.log.LoggedQso
import com.ft8ok.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The logbook as the screen sees it: log a contact, undo it, choose the folder.
 *
 * Database and file work run on a background dispatcher, one change at a time
 * (`limitedParallelism(1)`), so an Undo can never overtake the log it undoes. The
 * results come back through [onMain] as Compose state, matching how the RX, rig and
 * TX sessions report.
 */
class LogSession(
    context: Context,
    private val settings: SettingsStore,
    private val onMain: (() -> Unit) -> Unit
) {
    private val appContext = context.applicationContext
    private val db = LogDatabase.get(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    /**
     * The contact just logged, while its Undo is offered. [onUndone] runs on the main
     * thread after an Undo: a contact logged by hand goes back on the not-logged list.
     * With [deleted] set this is a deletion instead, and Undo puts the entry back.
     */
    data class Logged(
        val id: Long,
        val call: String,
        val onUndone: (() -> Unit)? = null,
        val deleted: LogRepository.Stored? = null
    )

    var lastLogged by mutableStateOf<Logged?>(null)
        private set

    /** Why the ADIF file is out of date, or null. The database copy is safe either way. */
    var fileError by mutableStateOf<String?>(null)
        private set

    /** The repository with today's folder: the operator can change it at any time. */
    private fun repo() = LogRepository(
        db, settings.logFolder?.let { SafAdifSink(appContext, it.toUri()) }
    )

    /** The active logbook's contacts with their ids, newest first, for the Log tab. */
    var contacts by mutableStateOf<List<LogRepository.Stored>>(emptyList())
        private set

    /** The active logbook's name, for the Log tab's header. */
    var logbookName by mutableStateOf<String?>(null)
        private set

    /** Every logbook with its count, for the Logbooks page. */
    var books by mutableStateOf<List<LogRepository.Book>>(emptyList())
        private set

    /** The logbook in use, by id, as last read from the database. */
    var activeId by mutableStateOf<Long?>(null)
        private set

    init {
        refreshContacts()
    }

    // Each action reads settings.activeLogbookId on the main thread when it is asked
    // for, so a switch a moment later cannot move a contact into a logbook that was
    // not in use when it was logged.

    /** Re-read the list from the database, which is the source of truth (rule 8). */
    private fun refreshContacts() {
        val chosen = settings.activeLogbookId
        scope.launch {
            val repo = repo()
            val book = repo.logbook(chosen)
            val list = repo.entries(book.id).asReversed()
            val all = repo.books()
            onMain {
                contacts = list
                logbookName = book.name
                activeId = book.id
                books = all
            }
        }
    }

    /** Use another logbook from now on: new contacts, the list and Save a copy follow it. */
    fun switchTo(id: Long) {
        settings.chooseLogbook(id)
        refreshContacts()
    }

    /** Why the last New logbook was refused, or null. */
    var nameProblem by mutableStateOf<LogRepository.NameProblem?>(null)
        private set

    /** Create a logbook and switch to it, or say why not in [nameProblem]. */
    fun create(name: String, onCreated: () -> Unit) {
        scope.launch {
            val (book, problem) = repo().createLogbook(name)
            onMain {
                nameProblem = problem
                if (book != null) {
                    switchTo(book.id)
                    onCreated()
                }
            }
        }
    }

    fun clearNameProblem() {
        nameProblem = null
    }

    /** How the last import went, or why it could not; null before the first. */
    sealed interface ImportOutcome {
        data class Done(val result: LogRepository.Imported, val logbook: String) : ImportOutcome
        data class Failed(val reason: String) : ImportOutcome
    }

    var importOutcome by mutableStateOf<ImportOutcome?>(null)
        private set

    /**
     * Add the contacts of an ADIF file the operator picked to the logbook in use.
     * Read one character per byte (ISO-8859-1): ADIF counts field
     * lengths in bytes, and a UTF-8 decode would shift every length after an accent.
     */
    fun importFile(uri: Uri) {
        val chosen = settings.activeLogbookId
        scope.launch {
            val outcome = try {
                val text = appContext.contentResolver.openInputStream(uri)
                    ?.use { it.readBytes().toString(Charsets.ISO_8859_1) }
                    ?: throw java.io.IOException("no file")
                val repo = repo()
                val book = repo.logbook(chosen)
                ImportOutcome.Done(repo.import(text, book.id), book.name)
            } catch (e: IllegalArgumentException) {
                ImportOutcome.Failed(e.message ?: "not an ADIF file")
            } catch (e: java.io.IOException) {
                ImportOutcome.Failed(e.message ?: e.javaClass.simpleName)
            } catch (e: SecurityException) {
                ImportOutcome.Failed(e.message ?: "no permission")
            }
            onMain {
                importOutcome = outcome
                (outcome as? ImportOutcome.Done)?.let { fileError = it.result.fileError }
            }
            refreshContacts()
        }
    }

    fun log(qso: LoggedQso, onUndone: (() -> Unit)? = null) {
        val chosen = settings.activeLogbookId
        scope.launch {
            val repo = repo()
            val result = repo.log(qso, repo.logbook(chosen).id)
            onMain {
                lastLogged = Logged(result.id, qso.call, onUndone)
                fileError = result.fileError
            }
            refreshContacts()
        }
    }

    fun undo() {
        val logged = lastLogged ?: return
        lastLogged = null
        scope.launch {
            val result = logged.deleted?.let { repo().restore(it) } ?: repo().undo(logged.id)
            onMain {
                fileError = result.fileError
                logged.onUndone?.invoke()
            }
            refreshContacts()
        }
    }

    /** How the last Save a copy went, for one line on the Log tab; null before the first. */
    sealed interface CopyResult {
        data class Saved(val count: Int, val name: String) : CopyResult
        data class Failed(val reason: String) : CopyResult
    }

    var copyResult by mutableStateOf<CopyResult?>(null)
        private set

    /** What Save a copy offers as the file name: `FT8OK-General-20261023.adi`, UTC date. */
    fun copyFileName(nowMs: Long): String {
        val d = java.time.Instant.ofEpochMilli(nowMs).atOffset(java.time.ZoneOffset.UTC)
        val book = LogbookEntity(name = logbookName ?: LogRepository.DEFAULT_NAME, createdAtMs = 0)
        return LogRepository.fileNameFor(book).removeSuffix(".adi") +
            "-%04d%02d%02d.adi".format(d.year, d.monthValue, d.dayOfMonth)
    }

    /** The name the operator gave the file, as its provider reports it. */
    private fun displayName(uri: Uri): String? =
        appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }

    /**
     * Write the active logbook as ADIF to [uri], a file the operator just named in the
     * system Save dialog. That one file only: no lasting access to
     * anything, and the auto-saved file in the log folder is untouched.
     */
    fun saveCopy(uri: Uri) {
        val chosen = settings.activeLogbookId
        scope.launch {
            val repo = repo()
            val list = repo.qsos(repo.logbook(chosen).id)
            val result = try {
                val out = appContext.contentResolver.openOutputStream(uri, "wt")
                    ?: throw java.io.IOException("no file")
                out.use { it.write(Adif.write(list).toByteArray(Charsets.US_ASCII)) }
                CopyResult.Saved(list.size, displayName(uri) ?: "")
            } catch (e: IllegalArgumentException) {
                // Adif.write refuses text an .adi file cannot carry.
                CopyResult.Failed(e.message ?: "not ASCII")
            } catch (e: java.io.IOException) {
                CopyResult.Failed(e.message ?: e.javaClass.simpleName)
            } catch (e: SecurityException) {
                CopyResult.Failed(e.message ?: "no permission")
            }
            onMain { copyResult = result }
        }
    }

    /** Save an edited contact. The database first, then the file. */
    fun update(id: Long, qso: LoggedQso) {
        scope.launch {
            val result = repo().update(id, qso)
            onMain { fileError = result.fileError }
            refreshContacts()
        }
    }

    /** Delete a contact, with the same Undo row as logging one. */
    fun delete(id: Long) {
        scope.launch {
            val (deleted, result) = repo().delete(id) ?: return@launch
            onMain {
                lastLogged = Logged(id, deleted.qso.call, deleted = deleted)
                fileError = result.fileError
            }
            refreshContacts()
        }
    }

    /** Stop offering Undo for the last contact. It stays logged. */
    fun dismissLogged() {
        lastLogged = null
    }

    /**
     * The operator picked a folder. Keep the grant across restarts, then write the
     * file straight away so a bad folder shows up now rather than at the next QSO.
     */
    fun setFolder(uri: Uri) {
        appContext.contentResolver.takePersistableUriPermission(
            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        settings.chooseLogFolder(uri.toString())
        // Every logbook's file, so the new folder holds all of them, not just one.
        scope.launch {
            val repo = repo()
            val error = repo.books().firstNotNullOfOrNull { repo.refresh(it.logbook.id) }
            onMain { fileError = error }
        }
    }

    /**
     * Where the folder picker opens: the chosen folder, or Download while none is set.
     * A folder made inside Download (e.g. `Download/FT8OK`) is one tap away in a
     * browser's file picker, so a website upload finds the ADIF there. Android 11+
     * refuses Download itself, only a folder inside it. Only a hint: a picker may
     * ignore it, and `primary:Download` is the stock storage provider's naming,
     * not a documented constant (inferred to hold on most phones).
     */
    val pickerStart: Uri
        get() = settings.logFolder?.toUri()?.let { tree ->
            runCatching {
                DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            }.getOrNull()
        } ?: DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Download")

    /** The chosen folder as the picker names it, e.g. `primary:Documents/FT8`, or null. */
    val folderLabel: String?
        get() = settings.logFolder?.let { it.toUri().lastPathSegment }

    companion object {
        /** How long Undo is offered after a contact is logged. */
        const val UNDO_WINDOW_MS = 20_000L
    }
}
