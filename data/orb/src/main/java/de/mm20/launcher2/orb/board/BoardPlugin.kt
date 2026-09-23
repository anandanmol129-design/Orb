package de.mm20.launcher2.orb.board

import de.mm20.launcher2.agent.AgentPlugin
import de.mm20.launcher2.agent.Schema
import de.mm20.launcher2.agent.ToolResult
import de.mm20.launcher2.agent.ToolSpec
import de.mm20.launcher2.orb.calendar.isConfirmed
import de.mm20.launcher2.orb.calendar.parseTime
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Agent-facing kanban board capability. Columns are discoverable so an agent can map a
 * spoken column name ("doing") to a real column without hard-coded assumptions.
 */
class BoardPlugin(
    private val repository: BoardRepository,
) : AgentPlugin {

    override val id: String = "orb.board"
    override val label: String = "Tasks"

    override fun tools(): List<ToolSpec> = listOf(
        ToolSpec(
            name = "orb.board.list_columns",
            description = "List the board columns (e.g. Backlog, Doing, Done) with their ids.",
            parameters = Schema.obj(),
        ),
        ToolSpec(
            name = "orb.board.list_tasks",
            description = "List tasks, optionally filtered by column or a title query.",
            parameters = Schema.obj(
                "column" to Schema.string("Column name or id to filter by."),
                "query" to Schema.string("Optional text to filter task titles."),
                "includeCompleted" to Schema.boolean("Include tasks from the Done column. Defaults to true."),
            ),
        ),
        ToolSpec(
            name = "orb.board.create_task",
            description = "Create a task on the board.",
            parameters = Schema.obj(
                "title" to Schema.string("Task title.", required = true),
                "column" to Schema.string("Column name to place the task in. Defaults to the first column."),
                "notes" to Schema.string("Optional notes."),
                "due" to Schema.string("Optional due date, ISO-8601 or epoch millis."),
            ),
        ),
        ToolSpec(
            name = "orb.board.move_task",
            description = "Move a task to another column. Identify it by id or title.",
            parameters = Schema.obj(
                "id" to Schema.integer("Id of the task to move."),
                "title" to Schema.string("Title of the task, if the id is unknown."),
                "column" to Schema.string("Destination column name.", required = true),
            ),
        ),
        ToolSpec(
            name = "orb.board.complete_task",
            description = "Mark a task as completed (moves it to the Done column).",
            parameters = Schema.obj(
                "id" to Schema.integer("Id of the task to complete."),
                "title" to Schema.string("Title of the task, if the id is unknown."),
            ),
        ),
        ToolSpec(
            name = "orb.board.delete_task",
            description = "Delete a task.",
            parameters = Schema.obj(
                "id" to Schema.integer("Id of the task to delete."),
                "title" to Schema.string("Title of the task, if the id is unknown."),
            ),
            requiresConfirmation = true,
        ),
    )

    override suspend fun invoke(name: String, args: JsonObject): ToolResult = when (name) {
        "orb.board.list_columns" -> listColumns()
        "orb.board.list_tasks" -> listTasks(args)
        "orb.board.create_task" -> createTask(args)
        "orb.board.move_task" -> moveTask(args)
        "orb.board.complete_task" -> completeTask(args)
        "orb.board.delete_task" -> deleteTask(args)
        else -> ToolResult.Error("Unknown tool: $name")
    }

    private suspend fun listColumns(): ToolResult {
        val columns = repository.columns().first()
        return ToolResult.Success(
            data = buildJsonObject {
                put("columns", buildJsonArray { columns.forEach { add(it.toJson()) } })
            },
            message = columns.joinToString(", ") { it.label },
        )
    }

    private suspend fun listTasks(args: JsonObject): ToolResult {
        val includeCompleted = (args["includeCompleted"] as? JsonPrimitive)
            ?.content?.toBooleanStrictOrNull() ?: true
        val columnArg = args["column"]?.let { (it as? JsonPrimitive)?.content }
        val query = args["query"]?.let { (it as? JsonPrimitive)?.content }

        val columns = repository.columns().first()
        val column = if (columnArg != null) {
            repository.resolveColumn(
                columnId = columnArg.toLongOrNull(),
                columnLabel = columnArg,
            )
        } else null

        var tasks = if (column != null) {
            repository.tasksInColumn(column.id).first()
        } else {
            repository.tasks().first()
        }
        if (!includeCompleted) tasks = tasks.filter { !it.completed }
        if (!query.isNullOrBlank()) {
            tasks = tasks.filter { it.title.contains(query, ignoreCase = true) }
        }
        val byColumn = columns.associateBy { it.id }
        return ToolResult.Success(
            data = buildJsonObject {
                put("count", JsonPrimitive(tasks.size))
                put(
                    "tasks",
                    buildJsonArray {
                        tasks.forEach { task -> add(task.toJson(byColumn[task.columnId]?.label)) }
                    }
                )
            },
            message = if (tasks.isEmpty()) {
                "There are no matching tasks."
            } else {
                tasks.joinToString(separator = "; ") {
                    "${it.title} (${byColumn[it.columnId]?.label ?: "unknown"})"
                }
            },
        )
    }

    private suspend fun createTask(args: JsonObject): ToolResult {
        val title = args["title"]?.let { (it as? JsonPrimitive)?.content }?.trim().orEmpty()
        if (title.isEmpty()) return ToolResult.Error("A task needs a title.")
        val columnArg = args["column"]?.let { (it as? JsonPrimitive)?.content }
        val notes = args["notes"]?.let { (it as? JsonPrimitive)?.content }
        val due = parseTime(args["due"])
        val id = repository.createTask(
            title = title,
            notes = notes,
            columnId = columnArg?.toLongOrNull(),
            columnLabel = columnArg,
            dueAt = due,
        )
        if (id < 0) return ToolResult.Error("I couldn't find that column.")
        val column = repository.resolveColumn(columnId = columnArg?.toLongOrNull(), columnLabel = columnArg)
        return ToolResult.Success(
            data = buildJsonObject {
                put("id", JsonPrimitive(id))
                column?.let { put("column", JsonPrimitive(it.label)) }
            },
            message = "Added \"$title\" to ${column?.label ?: "the board"}.",
        )
    }

    /**
     * Resolves the target task from an `id` or, failing that, a fuzzy `title` match, so
     * offline voice commands that only know a task's name can still act on it.
     */
    private suspend fun resolveTask(args: JsonObject): BoardTaskEntity? {
        val id = (args["id"] as? JsonPrimitive)?.content?.toLongOrNull()
        if (id != null) {
            repository.tasks().first().firstOrNull { it.id == id }?.let { return it }
        }
        val title = args["title"]?.let { (it as? JsonPrimitive)?.content }?.trim()
        if (title.isNullOrEmpty()) return null
        val tasks = repository.tasks().first()
        return tasks.firstOrNull { it.title.equals(title, ignoreCase = true) }
            ?: tasks.firstOrNull { it.title.startsWith(title, ignoreCase = true) }
            ?: tasks.firstOrNull { it.title.contains(title, ignoreCase = true) }
    }

    private suspend fun moveTask(args: JsonObject): ToolResult {
        val task = resolveTask(args) ?: return ToolResult.Error("I couldn't find that task.")
        val columnArg = args["column"]?.let { (it as? JsonPrimitive)?.content }
            ?: return ToolResult.Error("Which column should I move it to?")
        val moved = repository.moveTask(
            taskId = task.id,
            columnId = columnArg.toLongOrNull(),
            columnLabel = columnArg,
        )
        return if (moved) {
            val column = repository.resolveColumn(
                columnId = columnArg.toLongOrNull(),
                columnLabel = columnArg,
            )
            ToolResult.Success(message = "Moved \"${task.title}\" to ${column?.label ?: columnArg}.")
        } else {
            ToolResult.Error("I couldn't move that task.")
        }
    }

    private suspend fun completeTask(args: JsonObject): ToolResult {
        val task = resolveTask(args) ?: return ToolResult.Error("I couldn't find that task.")
        return if (repository.completeTask(task.id, true)) {
            ToolResult.Success(message = "Completed \"${task.title}\".")
        } else {
            ToolResult.Error("I couldn't complete that task.")
        }
    }

    private suspend fun deleteTask(args: JsonObject): ToolResult {
        val task = resolveTask(args) ?: return ToolResult.Error("I couldn't find that task.")
        if (!args.isConfirmed()) {
            return ToolResult.ConfirmationRequired("Delete \"${task.title}\"?")
        }
        return if (repository.deleteTask(task.id)) {
            ToolResult.Success(message = "Deleted \"${task.title}\".")
        } else {
            ToolResult.Error("I couldn't delete that task.")
        }
    }

    private fun BoardColumnEntity.toJson(): JsonObject = buildJsonObject {
        put("id", JsonPrimitive(id))
        put("label", JsonPrimitive(label))
        put("position", JsonPrimitive(position))
    }

    private fun BoardTaskEntity.toJson(columnLabel: String?): JsonObject = buildJsonObject {
        put("id", JsonPrimitive(id))
        put("title", JsonPrimitive(title))
        put("column", JsonPrimitive(columnLabel ?: ""))
        put("completed", JsonPrimitive(completed))
        notes?.let { put("notes", JsonPrimitive(it)) }
        dueAt?.let { put("due", JsonPrimitive(it)) }
    }
}
