package com.ft8ok.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.ft8ok.log.Adif
import com.ft8ok.log.LoggedQso
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/** The logbook on a real Room database, on the JVM: no phone, no radio. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LogRepositoryTest {

    private lateinit var db: LogDatabase
    private val files = mutableMapOf<String, String>()
    private var failWrites = false

    private val sink = AdifSink { name, text ->
        if (failWrites) throw IOException("folder gone")
        files[name] = text
    }

    private val t0 = 1_791_999_015_000L

    private fun qso(call: String, band: String = "40m", extra: Map<String, String> = emptyMap()) =
        LoggedQso(call = call, timeOnMs = t0, timeOffMs = t0 + 60_000, freqHz = 7_074_000,
            band = band, mode = "FT8", stationCallsign = "VE4OK", rstSent = "-12", rstRcvd = "+05",
            extra = extra)

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), LogDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun close() = db.close()

    @Test
    fun `logging stores the contact and rewrites the logbook's file`() = runBlocking {
        val repo = LogRepository(db, sink) { t0 }
        val book = repo.defaultLogbook()
        val r = repo.log(qso("VE6XA", extra = mapOf("APP_X" to "1")), book.id)
        assertNull(r.fileError)
        assertEquals(listOf(qso("VE6XA", extra = mapOf("APP_X" to "1"))), repo.qsos(book.id))
        assertEquals(repo.qsos(book.id), Adif.read(files.getValue("FT8OK-General.adi")).qsos)
    }

    @Test
    fun `a failed file write never loses the contact, and the next write catches up`() = runBlocking {
        val repo = LogRepository(db, sink) { t0 }
        val book = repo.defaultLogbook()
        failWrites = true
        assertEquals("folder gone", repo.log(qso("VE6XA"), book.id).fileError)
        assertEquals(1, repo.qsos(book.id).size)
        failWrites = false
        repo.log(qso("K1ABC"), book.id)
        assertEquals(2, Adif.read(files.getValue("FT8OK-General.adi")).qsos.size)
    }

    @Test
    fun `undo removes the contact from the database and the file`() = runBlocking {
        val repo = LogRepository(db, sink) { t0 }
        val book = repo.defaultLogbook()
        repo.log(qso("VE6XA"), book.id)
        val id = repo.log(qso("K1ABC"), book.id).id
        repo.undo(id)
        assertEquals(listOf("VE6XA"), repo.qsos(book.id).map { it.call })
        assertEquals(listOf("VE6XA"), Adif.read(files.getValue("FT8OK-General.adi")).qsos.map { it.call })
    }

    @Test
    fun `the log works with no folder chosen yet`() = runBlocking {
        val repo = LogRepository(db, sink = null) { t0 }
        val book = repo.defaultLogbook()
        assertNull(repo.log(qso("VE6XA"), book.id).fileError)
        assertEquals(1, repo.qsos(book.id).size)
    }

    @Test
    fun `worked before, on any band or on this band`() = runBlocking {
        val repo = LogRepository(db, sink) { t0 }
        val book = repo.defaultLogbook()
        repo.log(qso("VE6XA", band = "40m"), book.id)
        assertTrue(repo.workedBefore("VE6XA"))
        assertTrue(repo.workedBefore("VE6XA", "40m"))
        assertFalse(repo.workedBefore("VE6XA", "20m"))
        assertFalse(repo.workedBefore("K1ABC"))
    }

    @Test
    fun `import keeps good records and hands back bad ones`() = runBlocking {
        val repo = LogRepository(db, sink) { t0 }
        val book = repo.defaultLogbook()
        val text = Adif.write(listOf(qso("VE6XA"))) + "<CALL:5>K1ABC <MODE:3>FT8 <EOR>\n"
        val result = repo.import(text, book.id)
        assertNull(result.fileError)
        assertEquals(1, result.added)
        assertEquals(1, result.rejected.size)
        assertEquals(listOf("VE6XA"), repo.qsos(book.id).map { it.call })
    }

    @Test
    fun `importing the same file twice adds nothing the second time`() = runBlocking {
        val repo = LogRepository(db, sink) { t0 }
        val book = repo.defaultLogbook()
        repo.log(qso("VE6XA"), book.id)
        val text = Adif.write(listOf(qso("VE6XA"), qso("K1ABC")))
        val first = repo.import(text, book.id)
        assertEquals("VE6XA was already logged", 1 to 1, first.added to first.skipped)
        val again = repo.import(text, book.id)
        assertEquals(0 to 2, again.added to again.skipped)
        assertEquals(2, repo.qsos(book.id).size)
    }

    @Test
    fun `a record the writer would refuse is rejected, not stored to break the file later`() = runBlocking {
        val repo = LogRepository(db, sink) { t0 }
        val book = repo.defaultLogbook()
        // Read as ISO-8859-1, one char per byte, as the app reads a file: "ü" is one byte.
        val text = Adif.write(listOf(qso("VE6XA"))) +
            "<CALL:5>K1ABC <QSO_DATE:8>20261015 <TIME_ON:4>1432 <MODE:3>FT8 <COMMENT:6>Zürich <EOR>\n"
        val result = repo.import(text, book.id)
        assertEquals(1, result.added)
        assertEquals(listOf("K1ABC"), result.rejected.map { it.fields["CALL"] })
        assertNull("the file still writes", repo.refresh(book.id))
    }

    @Test
    fun `logbook names that would share a file are refused`() = runBlocking {
        val repo = LogRepository(db, sink) { t0 }
        repo.defaultLogbook()
        val (cqww, ok) = repo.createLogbook("  CQWW 10/25 ")
        assertNull(ok)
        assertEquals("CQWW 10/25", cqww!!.name)
        assertEquals(LogRepository.NameProblem.TAKEN, repo.createLogbook("cqww_10_25").second)
        assertEquals(LogRepository.NameProblem.TAKEN, repo.createLogbook("general").second)
        assertEquals(LogRepository.NameProblem.EMPTY, repo.createLogbook("  ").second)
        assertEquals(listOf("General" to 0, "CQWW 10/25" to 0), repo.books().map { it.logbook.name to it.count })
    }

    @Test
    fun `contacts go to the logbook asked for, and a missing one falls back to the default`() = runBlocking {
        val repo = LogRepository(db, sink) { t0 }
        val general = repo.defaultLogbook()
        val pota = repo.createLogbook("POTA K-1234").first!!
        repo.log(qso("VE6XA"), repo.logbook(pota.id).id)
        assertEquals(listOf("General" to 0, "POTA K-1234" to 1), repo.books().map { it.logbook.name to it.count })
        assertEquals(general.id, repo.logbook(999).id)
        assertEquals(general.id, repo.logbook(null).id)
        assertTrue(files.containsKey("FT8OK-POTA_K-1234.adi"))
    }

    @Test
    fun `an edit replaces the contact in the database and the file, extras kept`() = runBlocking {
        val repo = LogRepository(db, sink) { t0 }
        val book = repo.defaultLogbook()
        val id = repo.log(qso("VE6XA", extra = mapOf("APP_X" to "1")), book.id).id
        val edited = qso("VE6XA/P", extra = mapOf("APP_X" to "1")).copy(grid = "DO33")
        assertNull(repo.update(id, edited).fileError)
        assertEquals(listOf(LogRepository.Stored(id, book.id, edited)), repo.entries(book.id))
        assertEquals(listOf(edited), Adif.read(files.getValue("FT8OK-General.adi")).qsos)
    }

    @Test
    fun `a deleted contact goes from the file, and Undo brings it back whole`() = runBlocking {
        val repo = LogRepository(db, sink) { t0 }
        val book = repo.defaultLogbook()
        repo.log(qso("VE6XA"), book.id)
        val id = repo.log(qso("K1ABC", extra = mapOf("APP_X" to "1")), book.id).id
        val (deleted, _) = repo.delete(id)!!
        assertEquals(listOf("VE6XA"), Adif.read(files.getValue("FT8OK-General.adi")).qsos.map { it.call })
        repo.restore(deleted)
        assertEquals(setOf(qso("VE6XA"), qso("K1ABC", extra = mapOf("APP_X" to "1"))), repo.qsos(book.id).toSet())
        assertEquals(2, Adif.read(files.getValue("FT8OK-General.adi")).qsos.size)
        assertNull("gone twice is not an error", repo.delete(id))
    }

    @Test
    fun `the default logbook is created once`() = runBlocking {
        val repo = LogRepository(db, sink) { t0 }
        assertEquals(repo.defaultLogbook().id, repo.defaultLogbook().id)
    }
}
