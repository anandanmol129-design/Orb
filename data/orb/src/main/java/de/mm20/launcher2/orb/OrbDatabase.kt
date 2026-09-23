package de.mm20.launcher2.orb

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import de.mm20.launcher2.orb.board.BoardColumnDao
import de.mm20.launcher2.orb.board.BoardColumnEntity
import de.mm20.launcher2.orb.board.BoardTaskDao
import de.mm20.launcher2.orb.board.BoardTaskEntity
import de.mm20.launcher2.orb.calendar.CalendarDao
import de.mm20.launcher2.orb.calendar.CalendarEntity
import de.mm20.launcher2.orb.calendar.EventEntity
import de.mm20.launcher2.orb.tracker.TimeSessionDao
import de.mm20.launcher2.orb.tracker.TimeSessionEntity

/**
 * The launcher's own, self-contained feature database.
 *
 * Deliberately kept separate from Kvaesitso's `room` database so that:
 *  - first-party feature plugins can evolve their schema independently,
 *  - the launcher schema and its migration history remain untouched,
 *  - the whole file can later be swapped for a synced backend (see `source` columns).
 */
@Database(
    entities = [
        CalendarEntity::class,
        EventEntity::class,
        BoardColumnEntity::class,
        BoardTaskEntity::class,
        TimeSessionEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class OrbDatabase : RoomDatabase() {

    abstract fun calendarDao(): CalendarDao
    abstract fun boardColumnDao(): BoardColumnDao
    abstract fun boardTaskDao(): BoardTaskDao
    abstract fun timeSessionDao(): TimeSessionDao

    companion object {
        private const val NAME = "orb"

        @Volatile
        private var instance: OrbDatabase? = null

        fun getInstance(context: Context): OrbDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    OrbDatabase::class.java,
                    NAME,
                ).build().also { instance = it }
            }
        }
    }
}
