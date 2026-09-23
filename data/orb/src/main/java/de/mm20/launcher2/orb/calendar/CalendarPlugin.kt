package de.mm20.launcher2.orb.calendar

import de.mm20.launcher2.agent.AgentPlugin
import de.mm20.launcher2.agent.Schema
import de.mm20.launcher2.agent.ToolResult
import de.mm20.launcher2.agent.ToolSpec
import de.mm20.launcher2.agent.stringOrNull
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Agent-facing calendar capability.
 *
 * Events live in the launcher's local calendar ([CalendarRepository]). Natural-language
 * date/time resolution is the job of the voice layer; by the time a tool is invoked the
 * arguments are concrete epoch millis or ISO-8601 strings.
 */
class CalendarPlugin(
    private val repository: CalendarRepository,
) : AgentPlugin {

    override val id: String = "orb.calendar"
    override val label: String = "Calendar"

    override fun tools(): List<ToolSpec> = listOf(
        ToolSpec(
            name = "orb.calendar.list_events",
            description = "List calendar events in a time range. Defaults to the next 7 days.",
            parameters = Schema.obj(
                "from" to Schema.string("Start of the range, ISO-8601 or epoch millis. Defaults to now."),
                "to" to Schema.string("End of the range, ISO-8601 or epoch millis. Defaults to 7 days from now."),
                "query" to Schema.string("Optional text to filter event titles."),
                "limit" to Schema.integer("Maximum number of events to return. Defaults to 20."),
            ),
        ),
        ToolSpec(
            name = "orb.calendar.create_event",
            description = "Create a calendar event.",
            parameters = Schema.obj(
                "title" to Schema.string("Event title.", required = true),
                "start" to Schema.string("Start time, ISO-8601 or epoch millis.", required = true),
                "end" to Schema.string("End time, ISO-8601 or epoch millis. Defaults to one hour after start."),
                "description" to Schema.string("Optional description."),
                "location" to Schema.string("Optional location."),
                "allDay" to Schema.boolean("Whether this is an all-day event."),
            ),
            requiresConfirmation = true,
        ),
        ToolSpec(
            name = "orb.calendar.update_event",
            description = "Update an existing calendar event by id.",
            parameters = Schema.obj(
                "id" to Schema.integer("Id of the event to update.", required = true),
                "title" to Schema.string("New title."),
                "start" to Schema.string("New start time."),
                "end" to Schema.string("New end time."),
                "description" to Schema.string("New description."),
                "location" to Schema.string("New location."),
            ),
            requiresConfirmation = true,
        ),
        ToolSpec(
            name = "orb.calendar.delete_event",
            description = "Delete a calendar event by id.",
            parameters = Schema.obj(
                "id" to Schema.integer("Id of the event to delete.", required = true),
            ),
            requiresConfirmation = true,
        ),
    )

    override suspend fun invoke(name: String, args: JsonObject): ToolResult = when (name) {
        "orb.calendar.list_events" -> listEvents(args)
        "orb.calendar.create_event" -> createEvent(args)
        "orb.calendar.update_event" -> updateEvent(args)
        "orb.calendar.delete_event" -> deleteEvent(args)
        else -> ToolResult.Error("Unknown tool: $name")
    }

    private suspend fun listEvents(args: JsonObject): ToolResult {
        val from = parseTime(args["from"]) ?: System.currentTimeMillis()
        val to = parseTime(args["to"]) ?: (from + DEFAULT_RANGE_MILLIS)
        val limit = (args["limit"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 20
        val query = args.stringOrNull("query")

        val events = if (query.isNullOrBlank()) {
            repository.events(from, to).first()
        } else {
            repository.search(query, limit).filter { it.startTime <= to && it.endTime >= from }
        }
        val trimmed = events.take(limit)
        return ToolResult.Success(
            data = buildJsonObject {
                put("count", JsonPrimitive(trimmed.size))
                put("events", buildJsonArray { trimmed.forEach { add(it.toJson()) } })
            },
            message = if (trimmed.isEmpty()) {
                "You have no events in that range."
            } else {
                trimmed.joinToString(separator = "; ") { it.spoken() }
            },
        )
    }

    private suspend fun createEvent(args: JsonObject): ToolResult {
        val title = args.stringOrNull("title")?.trim().orEmpty()
        if (title.isEmpty()) return ToolResult.Error("An event needs a title.")
        val start = parseTime(args["start"]) ?: return ToolResult.Error("I need a start time for the event.")
        val end = parseTime(args["end"]) ?: (start + DEFAULT_DURATION_MILLIS)
        if (end < start) return ToolResult.Error("The event ends before it starts.")
        val allDay = (args["allDay"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: false

        if (!args.isConfirmed()) {
            return ToolResult.ConfirmationRequired(
                "Add \"$title\" ${spokenRange(start, end, allDay)}?"
            )
        }
        val id = repository.create(
            title = title,
            startTime = start,
            endTime = end,
            description = args.stringOrNull("description"),
            location = args.stringOrNull("location"),
            allDay = allDay,
        )
        return ToolResult.Success(
            data = buildJsonObject { put("id", JsonPrimitive(id)) },
            message = "Added \"$title\" ${spokenRange(start, end, allDay)}.",
        )
    }

    private suspend fun updateEvent(args: JsonObject): ToolResult {
        val id = (args["id"] as? JsonPrimitive)?.content?.toLongOrNull()
            ?: return ToolResult.Error("Which event should I update?")
        val existing = repository.get(id) ?: return ToolResult.Error("I couldn't find that event.")
        if (!args.isConfirmed()) {
            return ToolResult.ConfirmationRequired("Update \"${existing.title}\"?")
        }
        val updated = repository.update(
            id = id,
            title = args.stringOrNull("title"),
            startTime = parseTime(args["start"]),
            endTime = parseTime(args["end"]),
            description = args.stringOrNull("description"),
            location = args.stringOrNull("location"),
        )
        return if (updated) {
            ToolResult.Success(message = "Updated \"${args.stringOrNull("title") ?: existing.title}\".")
        } else {
            ToolResult.Error("I couldn't update that event.")
        }
    }

    private suspend fun deleteEvent(args: JsonObject): ToolResult {
        val id = (args["id"] as? JsonPrimitive)?.content?.toLongOrNull()
            ?: return ToolResult.Error("Which event should I delete?")
        val existing = repository.get(id) ?: return ToolResult.Error("I couldn't find that event.")
        if (!args.isConfirmed()) {
            return ToolResult.ConfirmationRequired("Delete \"${existing.title}\"?")
        }
        return if (repository.delete(id)) {
            ToolResult.Success(message = "Deleted \"${existing.title}\".")
        } else {
            ToolResult.Error("I couldn't delete that event.")
        }
    }

    private fun EventEntity.toJson(): JsonObject = buildJsonObject {
        put("id", JsonPrimitive(id))
        put("title", JsonPrimitive(title))
        put("start", JsonPrimitive(startTime))
        put("end", JsonPrimitive(endTime))
        put("allDay", JsonPrimitive(allDay))
        description?.let { put("description", JsonPrimitive(it)) }
        location?.let { put("location", JsonPrimitive(it)) }
    }

    private fun EventEntity.spoken(): String =
        "$title ${spokenRange(startTime, endTime, allDay)}"

    private companion object {
        const val DEFAULT_DURATION_MILLIS = 60L * 60L * 1000L
        const val DEFAULT_RANGE_MILLIS = 7L * 24L * 60L * 60L * 1000L

        fun spokenRange(start: Long, end: Long, allDay: Boolean): String {
            val zone = ZoneId.systemDefault()
            val startTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(start), zone)
            val endTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(end), zone)
            val dayFmt = DateTimeFormatter.ofPattern("EEE d MMM")
            val timeFmt = DateTimeFormatter.ofPattern("HH:mm")
            return if (allDay) {
                "on ${startTime.format(dayFmt)}"
            } else if (startTime.toLocalDate() == endTime.toLocalDate()) {
                "on ${startTime.format(dayFmt)} from ${startTime.format(timeFmt)} to ${endTime.format(timeFmt)}"
            } else {
                "from ${startTime.format(dayFmt)} ${startTime.format(timeFmt)} " +
                    "to ${endTime.format(dayFmt)} ${endTime.format(timeFmt)}"
            }
        }
    }
}

/** Returns true when the caller has confirmed a previously requested action. */
internal fun JsonObject.isConfirmed(): Boolean =
    (this["confirmed"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: false

/**
 * Parses a time argument that may be epoch millis (number or numeric string) or an
 * ISO-8601 date/time. Returns null when it can't be understood.
 */
internal fun parseTime(element: kotlinx.serialization.json.JsonElement?): Long? {
    if (element == null) return null
    val primitive = element as? JsonPrimitive ?: return null
    primitive.content.toLongOrNull()?.let { return it }
    val text = primitive.content
    val zone = ZoneId.systemDefault()
    return try {
        OffsetDateTime.parse(text).toInstant().toEpochMilli()
    } catch (e: Exception) {
        try {
            LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli()
        } catch (e: Exception) {
            try {
                LocalDate.parse(text).atStartOfDay(zone).toInstant().toEpochMilli()
            } catch (e: Exception) {
                null
            }
        }
    }
}
