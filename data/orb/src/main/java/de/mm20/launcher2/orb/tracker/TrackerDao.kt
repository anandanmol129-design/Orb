package de.mm20.launcher2.orb.tracker

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "orb_time_session")
data class TimeSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val label: String,
    /** Optional link to a board task. */
    val taskId: Long? = null,
    /** Optional link to a calendar event. */
    val eventId: Long? = null,
    val startedAt: Long,
    /** null while the session is running. */
    val endedAt: Long? = null,
)

@Dao
interface TimeSessionDao {

    @Query("SELECT * FROM orb_time_session WHERE endedAt IS NULL ORDER BY startedAt DESC LIMIT 1")
    fun running(): Flow<TimeSessionEntity?>

    @Query("SELECT * FROM orb_time_session WHERE endedAt IS NULL ORDER BY startedAt DESC LIMIT 1")
    suspend fun runningOnce(): TimeSessionEntity?

    @Query(
        "SELECT * FROM orb_time_session WHERE startedAt >= :from AND startedAt <= :to " +
            "ORDER BY startedAt DESC"
    )
    fun sessionsInRange(from: Long, to: Long): Flow<List<TimeSessionEntity>>

    @Query("SELECT * FROM orb_time_session WHERE id = :id LIMIT 1")
    suspend fun session(id: Long): TimeSessionEntity?

    @Insert
    suspend fun insert(session: TimeSessionEntity): Long

    @Update
    suspend fun update(session: TimeSessionEntity)

    @Query("DELETE FROM orb_time_session WHERE id = :id")
    suspend fun delete(id: Long)
}
