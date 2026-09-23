package de.mm20.launcher2.orb.board

import kotlinx.coroutines.flow.Flow

/**
 * Kanban-style task board. Columns are user-editable and tasks can be moved between
 * them. This is the single mutation point for board state.
 */
interface BoardRepository {
    fun columns(): Flow<List<BoardColumnEntity>>
    fun tasks(): Flow<List<BoardTaskEntity>>
    fun tasksInColumn(columnId: Long): Flow<List<BoardTaskEntity>>

    suspend fun ensureDefaultColumns()

    suspend fun createTask(
        title: String,
        notes: String? = null,
        columnId: Long? = null,
        columnLabel: String? = null,
        dueAt: Long? = null,
    ): Long

    suspend fun moveTask(taskId: Long, columnId: Long? = null, columnLabel: String? = null): Boolean

    suspend fun completeTask(taskId: Long, completed: Boolean = true): Boolean

    suspend fun updateTask(taskId: Long, title: String? = null, notes: String? = null, dueAt: Long? = null): Boolean

    suspend fun deleteTask(taskId: Long): Boolean

    suspend fun resolveColumn(columnId: Long? = null, columnLabel: String? = null): BoardColumnEntity?
}

internal class BoardRepositoryImpl(
    private val columnDao: BoardColumnDao,
    private val taskDao: BoardTaskDao,
) : BoardRepository {

    override fun columns(): Flow<List<BoardColumnEntity>> = columnDao.columns()

    override fun tasks(): Flow<List<BoardTaskEntity>> = taskDao.allTasks()

    override fun tasksInColumn(columnId: Long): Flow<List<BoardTaskEntity>> =
        taskDao.tasksInColumn(columnId)

    override suspend fun ensureDefaultColumns() {
        if (columnDao.count() > 0) return
        columnDao.insertColumn(BoardColumnEntity(label = DefaultColumnBacklog, position = 0))
        columnDao.insertColumn(BoardColumnEntity(label = DefaultColumnDoing, position = 1))
        columnDao.insertColumn(BoardColumnEntity(label = DefaultColumnDone, position = 2))
    }

    override suspend fun createTask(
        title: String,
        notes: String?,
        columnId: Long?,
        columnLabel: String?,
        dueAt: Long?,
    ): Long {
        ensureDefaultColumns()
        val column = resolveColumn(columnId, columnLabel) ?: return -1L
        val position = taskDao.nextPosition(column.id)
        return taskDao.insertTask(
            BoardTaskEntity(
                title = title,
                notes = notes,
                columnId = column.id,
                position = position,
                dueAt = dueAt,
            )
        )
    }

    override suspend fun moveTask(taskId: Long, columnId: Long?, columnLabel: String?): Boolean {
        val task = taskDao.task(taskId) ?: return false
        val column = resolveColumn(columnId, columnLabel) ?: return false
        val position = taskDao.nextPosition(column.id)
        val completed = task.completed || column.label.equals(DefaultColumnDone, ignoreCase = true)
        taskDao.updateTask(task.copy(columnId = column.id, position = position, completed = completed))
        return true
    }

    override suspend fun completeTask(taskId: Long, completed: Boolean): Boolean {
        val task = taskDao.task(taskId) ?: return false
        if (completed) {
            val done = resolveColumn(columnLabel = DefaultColumnDone)
            if (done != null) {
                val position = taskDao.nextPosition(done.id)
                taskDao.updateTask(task.copy(completed = true, columnId = done.id, position = position))
                return true
            }
        }
        taskDao.updateTask(task.copy(completed = completed))
        return true
    }

    override suspend fun updateTask(
        taskId: Long,
        title: String?,
        notes: String?,
        dueAt: Long?,
    ): Boolean {
        val task = taskDao.task(taskId) ?: return false
        taskDao.updateTask(
            task.copy(
                title = title ?: task.title,
                notes = notes ?: task.notes,
                dueAt = dueAt ?: task.dueAt,
            )
        )
        return true
    }

    override suspend fun deleteTask(taskId: Long): Boolean {
        if (taskDao.task(taskId) == null) return false
        taskDao.deleteTask(taskId)
        return true
    }

    override suspend fun resolveColumn(columnId: Long?, columnLabel: String?): BoardColumnEntity? {
        ensureDefaultColumns()
        if (columnId != null) {
            return columnDao.columnsOnce().firstOrNull { it.id == columnId }
        }
        if (!columnLabel.isNullOrBlank()) {
            columnDao.columnByLabel(columnLabel)?.let { return it }
            // Fall back to a case-insensitive contains match.
            columnDao.columnsOnce().firstOrNull {
                it.label.contains(columnLabel, ignoreCase = true)
            }?.let { return it }
        }
        return columnDao.columnsOnce().firstOrNull()
    }
}
