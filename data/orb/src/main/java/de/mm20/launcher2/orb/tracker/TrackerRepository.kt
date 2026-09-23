package de.mm20.launcher2.orb.tracker

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

data class TrackerSummaryEntry(
    val label: String,
    val totalMillis: Long,
    val sessionCount: Int,
)

/**
 * Simple, single-running-session time tracker. Optionally linked to a board task or a
 * calendar event so agents can answer "how long did I work on X".
 */
interface TrackerRepository {
    fun running(): Flow<TimeSessionEntity?>
    fun sessions(from: Long, to: Long): Flow<List<TimeSessionEntity>>

    suspend fun start(label: String, taskId: Long? = null, eventId: Long? = null): Long

    /** Stops the currently running session and returns it, or null if none was running. */
    suspend fun stop(): TimeSessionEntity?

    suspend fun summary(from: Long, to: Long): List<TrackerSummaryEntry>
}

internal class TrackerRepositoryImpl(
    private val dao: TimeSessionDao,
) : TrackerRepository {

    override fun running(): Flow<TimeSessionEntity?> = dao.running()

    override fun sessions(from: Long, to: Long): Flow<List<TimeSessionEntity>> =
        dao.sessionsInRange(from, to)

    override suspend fun start(label: String, taskId: Long?, eventId: Long?): Long {
        // Ensure only one session is running at a time.
        dao.runningOnce()?.let { running ->
            dao.update(running.copy(endedAt = System.currentTimeMillis()))
        }
        return dao.insert(
            TimeSessionEntity(
                label = label,
                taskId = taskId,
                eventId = eventId,
                startedAt = System.currentTimeMillis(),
            )
        )
    }

    override suspend fun stop(): TimeSessionEntity? {
        val running = dao.runningOnce() ?: return null
        val stopped = running.copy(endedAt = System.currentTimeMillis())
        dao.update(stopped)
        return stopped
    }

    override suspend fun summary(from: Long, to: Long): List<TrackerSummaryEntry> {
        val sessions = dao.sessionsInRange(from, to).first()
        return sessions
            .groupBy { it.label }
            .map { (label, list) ->
                TrackerSummaryEntry(
                    label = label,
                    totalMillis = list.sumOf {
                        (it.endedAt ?: System.currentTimeMillis()) - it.startedAt
                    },
                    sessionCount = list.size,
                )
            }
            .sortedByDescending { it.totalMillis }
    }
}
