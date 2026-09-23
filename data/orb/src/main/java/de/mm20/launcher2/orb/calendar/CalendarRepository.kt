package de.mm20.launcher2.orb.calendar

import kotlinx.coroutines.flow.Flow

/**
 * Read/write access to the launcher's local calendar.
 *
 * This is the single mutation point for local events. Voice, UI and future sync
 * implementations all go through here so behaviour stays consistent.
 */
interface CalendarRepository {
    fun events(from: Long, to: Long): Flow<List<EventEntity>>
    fun upcoming(from: Long, limit: Int): Flow<List<EventEntity>>
    suspend fun get(id: Long): EventEntity?
    suspend fun search(query: String, limit: Int = 20): List<EventEntity>
    suspend fun create(
        title: String,
        startTime: Long,
        endTime: Long,
        description: String? = null,
        location: String? = null,
        allDay: Boolean = false,
    ): Long

    suspend fun update(
        id: Long,
        title: String? = null,
        startTime: Long? = null,
        endTime: Long? = null,
        description: String? = null,
        location: String? = null,
    ): Boolean

    suspend fun delete(id: Long): Boolean

    /** Idempotently creates the default local calendar and returns its id. */
    suspend fun ensureDefaultCalendar(): Long
}

internal class CalendarRepositoryImpl(
    private val dao: CalendarDao,
) : CalendarRepository {

    override fun events(from: Long, to: Long): Flow<List<EventEntity>> =
        dao.eventsInRange(from, to)

    override fun upcoming(from: Long, limit: Int): Flow<List<EventEntity>> =
        dao.upcoming(from, limit)

    override suspend fun get(id: Long): EventEntity? = dao.event(id)

    override suspend fun search(query: String, limit: Int): List<EventEntity> =
        dao.search(query, limit)

    override suspend fun create(
        title: String,
        startTime: Long,
        endTime: Long,
        description: String?,
        location: String?,
        allDay: Boolean,
    ): Long {
        val calendarId = ensureDefaultCalendar()
        return dao.insertEvent(
            EventEntity(
                title = title,
                description = description,
                location = location,
                startTime = startTime,
                endTime = endTime,
                allDay = allDay,
                calendarId = calendarId,
            )
        )
    }

    override suspend fun update(
        id: Long,
        title: String?,
        startTime: Long?,
        endTime: Long?,
        description: String?,
        location: String?,
    ): Boolean {
        val existing = dao.event(id) ?: return false
        dao.updateEvent(
            existing.copy(
                title = title ?: existing.title,
                startTime = startTime ?: existing.startTime,
                endTime = endTime ?: existing.endTime,
                description = description ?: existing.description,
                location = location ?: existing.location,
            )
        )
        return true
    }

    override suspend fun delete(id: Long): Boolean {
        if (dao.event(id) == null) return false
        dao.deleteEvent(id)
        return true
    }

    override suspend fun ensureDefaultCalendar(): Long {
        dao.firstCalendarId()?.let { return it }
        return dao.insertCalendar(CalendarEntity(name = DEFAULT_CALENDAR_NAME))
    }

    private companion object {
        const val DEFAULT_CALENDAR_NAME = "Personal"
    }
}
