package com.ft8ok.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update

@Dao
interface LogDao {

    @Insert
    suspend fun insertLogbook(logbook: LogbookEntity): Long

    @Query("SELECT * FROM logbook ORDER BY id")
    suspend fun logbooks(): List<LogbookEntity>

    @Query("SELECT * FROM logbook WHERE id = :id")
    suspend fun logbook(id: Long): LogbookEntity?

    @Insert
    suspend fun insertQso(qso: QsoEntity): Long

    @Insert
    suspend fun insertQsos(qsos: List<QsoEntity>)

    @Query("SELECT * FROM qso WHERE id = :id")
    suspend fun qso(id: Long): QsoEntity?

    @Query("DELETE FROM qso WHERE id = :id")
    suspend fun deleteQso(id: Long): Int

    @Update
    suspend fun updateQso(qso: QsoEntity): Int

    /** Oldest first: the order a log file is read in. */
    @Query("SELECT * FROM qso WHERE logbookId = :logbookId ORDER BY timeOnMs, id")
    suspend fun qsosIn(logbookId: Long): List<QsoEntity>

    /** How many contacts each logbook holds; a logbook with none is absent. */
    @Query("SELECT logbookId, COUNT(*) AS n FROM qso GROUP BY logbookId")
    suspend fun counts(): List<LogbookCount>

    /** Across every logbook, as "worked before" requires. */
    @Query("SELECT COUNT(*) FROM qso WHERE call = :call")
    suspend fun countWith(call: String): Int

    @Query("SELECT COUNT(*) FROM qso WHERE call = :call AND band = :band")
    suspend fun countWith(call: String, band: String): Int
}

/** A row of [LogDao.counts]. */
data class LogbookCount(val logbookId: Long, val n: Int)

/**
 * The logbook database.
 *
 * **Version 1.** Every later version needs a written migration and a test that
 * upgrades a real version-N database, using the schema committed under
 * `data/schemas`. Never `fallbackToDestructiveMigration`: that is the one line of
 * code that could silently delete somebody's log (rule 8).
 */
@Database(entities = [LogbookEntity::class, QsoEntity::class], version = 1, exportSchema = true)
abstract class LogDatabase : RoomDatabase() {

    abstract fun logDao(): LogDao

    companion object {
        const val FILE_NAME = "ft8ok-log.db"

        @Volatile private var instance: LogDatabase? = null

        /** One instance per process: Room must not have two open on the same file. */
        fun get(context: Context): LogDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(context.applicationContext, LogDatabase::class.java, FILE_NAME)
                    .build().also { instance = it }
            }
    }
}
