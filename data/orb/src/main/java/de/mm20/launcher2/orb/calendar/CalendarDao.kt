package de.mm20.launcher2.orb.calendar

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

const val SourceLocal = "local"

/**
 * A local calendar. `source` distinguishes locally created calendars from calendars that
 * will later be mirrored from an external provider (Google, CalDAV, …).
 */
@Entity(tableName = "orb_calendar")
data class CalendarEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val color: Int? = null,
    val source: String = SourceLocal,
)

@Entity(tableName = "orb_event")
data class EventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val description: String? = null,
    val location: String? = null,
    /** Start of the event, epoch millis. */
    val startTime: Long,
    /** End of the event, epoch millis. */
    val endTime: Long,
    val allDay: Boolean = false,
    val calendarId: Long = 0,
    val source: String = SourceLocal,
)

@Dao
interface CalendarDao {

    @Query("SELECT * FROM orb_calendar ORDER BY id ASC")
    fun calendars(): Flow<List<CalendarEntity>>

    @Query("SELECT * FROM orb_calendar WHERE id = :id LIMIT 1")
    suspend fun calendar(id: Long): CalendarEntity?

    @Insert
    suspend fun insertCalendar(calendar: CalendarEntity): Long

    @Query("SELECT COUNT(*) FROM orb_calendar")
    suspend fun calendarCount(): Int

    @Query("SELECT id FROM orb_calendar ORDER BY id ASC LIMIT 1")
    suspend fun firstCalendarId(): Long?

    @Query(
        "SELECT * FROM orb_event WHERE endTime >= :from AND startTime <= :to " +
            "ORDER BY startTime ASC"
    )
    fun eventsInRange(from: Long, to: Long): Flow<List<EventEntity>>

    @Query(
        "SELECT * FROM orb_event WHERE startTime >= :from " +
            "ORDER BY startTime ASC LIMIT :limit"
    )
    fun upcoming(from: Long, limit: Int): Flow<List<EventEntity>>

    @Query("SELECT * FROM orb_event WHERE id = :id LIMIT 1")
    suspend fun event(id: Long): EventEntity?

    @Query(
        "SELECT * FROM orb_event WHERE title LIKE '%' || :query || '%' " +
            "ORDER BY startTime ASC LIMIT :limit"
    )
    suspend fun search(query: String, limit: Int): List<EventEntity>

    @Insert
    suspend fun insertEvent(event: EventEntity): Long

    @Update
    suspend fun updateEvent(event: EventEntity)

    @Query("DELETE FROM orb_event WHERE id = :id")
    suspend fun deleteEvent(id: Long)
}
