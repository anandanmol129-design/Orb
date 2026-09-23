package de.mm20.launcher2.orb.board

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

const val DefaultColumnBacklog = "Backlog"
const val DefaultColumnDoing = "Doing"
const val DefaultColumnDone = "Done"

@Entity(tableName = "orb_board_column")
data class BoardColumnEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val label: String,
    val position: Int,
    val source: String = "local",
)

@Entity(tableName = "orb_task")
data class BoardTaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val notes: String? = null,
    val columnId: Long,
    val position: Int,
    val dueAt: Long? = null,
    val completed: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val source: String = "local",
)

@Dao
interface BoardColumnDao {

    @Query("SELECT * FROM orb_board_column ORDER BY position ASC")
    fun columns(): Flow<List<BoardColumnEntity>>

    @Query("SELECT * FROM orb_board_column ORDER BY position ASC")
    suspend fun columnsOnce(): List<BoardColumnEntity>

    @Query("SELECT * FROM orb_board_column WHERE label LIKE :label LIMIT 1")
    suspend fun columnByLabel(label: String): BoardColumnEntity?

    @Insert
    suspend fun insertColumn(column: BoardColumnEntity): Long

    @Query("SELECT COUNT(*) FROM orb_board_column")
    suspend fun count(): Int
}

@Dao
interface BoardTaskDao {

    @Query("SELECT * FROM orb_task ORDER BY columnId ASC, position ASC")
    fun allTasks(): Flow<List<BoardTaskEntity>>

    @Query("SELECT * FROM orb_task WHERE columnId = :columnId ORDER BY position ASC")
    fun tasksInColumn(columnId: Long): Flow<List<BoardTaskEntity>>

    @Query("SELECT * FROM orb_task WHERE id = :id LIMIT 1")
    suspend fun task(id: Long): BoardTaskEntity?

    @Query(
        "SELECT * FROM orb_task WHERE title LIKE '%' || :query || '%' " +
            "ORDER BY completed ASC, position ASC LIMIT :limit"
    )
    suspend fun search(query: String, limit: Int): List<BoardTaskEntity>

    @Insert
    suspend fun insertTask(task: BoardTaskEntity): Long

    @Update
    suspend fun updateTask(task: BoardTaskEntity)

    @Query("DELETE FROM orb_task WHERE id = :id")
    suspend fun deleteTask(id: Long)

    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM orb_task WHERE columnId = :columnId")
    suspend fun nextPosition(columnId: Long): Int
}
