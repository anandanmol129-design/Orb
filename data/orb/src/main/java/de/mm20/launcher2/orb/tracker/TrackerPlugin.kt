package de.mm20.launcher2.orb.tracker

import de.mm20.launcher2.agent.AgentPlugin
import de.mm20.launcher2.agent.Schema
import de.mm20.launcher2.agent.ToolResult
import de.mm20.launcher2.agent.ToolSpec
import de.mm20.launcher2.orb.calendar.parseTime
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Agent-facing time tracking capability. Exactly one session can run at a time; starting
 * a new one closes the previous one.
 */
class TrackerPlugin(
    private val repository: TrackerRepository,
) : AgentPlugin {

    override val id: String = "orb.tracker"
    override val label: String = "Time tracker"

    override fun tools(): List<ToolSpec> = listOf(
        ToolSpec(
            name = "orb.tracker.start",
            description = "Start tracking time for an activity. Stops any running session first.",
            parameters = Schema.obj(
                "label" to Schema.string("What to track, e.g. 'writing' or 'project X'.", required = true),
                "taskId" to Schema.integer("Optional board task id to link this session to."),
            ),
        ),
        ToolSpec(
            name = "orb.tracker.stop",
            description = "Stop the currently running time tracking session.",
            parameters = Schema.obj(),
        ),
        ToolSpec(
            name = "orb.tracker.status",
            description = "Report which activity is currently being tracked, and for how long.",
            parameters = Schema.obj(),
        ),
        ToolSpec(
            name = "orb.tracker.summary",
            description = "Summarise tracked time per activity in a time range. Defaults to today.",
            parameters = Schema.obj(
                "from" to Schema.string("Start of range, ISO-8601 or epoch millis."),
                "to" to Schema.string("End of range, ISO-8601 or epoch millis."),
            ),
        ),
    )

    override suspend fun invoke(name: String, args: JsonObject): ToolResult = when (name) {
        "orb.tracker.start" -> start(args)
        "orb.tracker.stop" -> stop()
        "orb.tracker.status" -> status()
        "orb.tracker.summary" -> summary(args)
        else -> ToolResult.Error("Unknown tool: $name")
    }

    private suspend fun start(args: JsonObject): ToolResult {
        val label = (args["label"] as? JsonPrimitive)?.content?.trim().orEmpty()
        if (label.isEmpty()) return ToolResult.Error("What should I track time for?")
        val taskId = (args["taskId"] as? JsonPrimitive)?.content?.toLongOrNull()
        repository.start(label, taskId)
        return ToolResult.Success(
            data = buildJsonObject { put("label", JsonPrimitive(label)) },
            message = "Started tracking \"$label\".",
        )
    }

    private suspend fun stop(): ToolResult {
        val session = repository.stop()
            ?: return ToolResult.Success(message = "Nothing is being tracked right now.")
        val duration = (session.endedAt ?: System.currentTimeMillis()) - session.startedAt
        return ToolResult.Success(
            data = buildJsonObject {
                put("label", JsonPrimitive(session.label))
                put("durationMillis", JsonPrimitive(duration))
            },
            message = "Stopped tracking \"${session.label}\" after ${formatDuration(duration)}.",
        )
    }

    private suspend fun status(): ToolResult {
        val running = repository.running().first()
            ?: return ToolResult.Success(message = "Nothing is being tracked right now.")
        val duration = System.currentTimeMillis() - running.startedAt
        return ToolResult.Success(
            data = buildJsonObject {
                put("label", JsonPrimitive(running.label))
                put("elapsedMillis", JsonPrimitive(duration))
            },
            message = "Tracking \"${running.label}\" for ${formatDuration(duration)}.",
        )
    }

    private suspend fun summary(args: JsonObject): ToolResult {
        val now = System.currentTimeMillis()
        val from = parseTime(args["from"]) ?: startOfToday()
        val to = parseTime(args["to"]) ?: now
        val entries = repository.summary(from, to)
        return ToolResult.Success(
            data = buildJsonObject {
                put(
                    "entries",
                    buildJsonArray {
                        entries.forEach { entry ->
                            add(
                                buildJsonObject {
                                    put("label", JsonPrimitive(entry.label))
                                    put("totalMillis", JsonPrimitive(entry.totalMillis))
                                    put("sessions", JsonPrimitive(entry.sessionCount))
                                }
                            )
                        }
                    }
                )
            },
            message = if (entries.isEmpty()) {
                "I haven't tracked anything in that range."
            } else {
                "Today: " + entries.joinToString(", ") {
                    "${it.label} ${formatDuration(it.totalMillis)}"
                }
            },
        )
    }

    private companion object {
        fun startOfToday(): Long = java.time.LocalDate.now()
            .atStartOfDay(java.time.ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

        fun formatDuration(millis: Long): String {
            val totalMinutes = millis / 60000
            val hours = totalMinutes / 60
            val minutes = totalMinutes % 60
            return when {
                hours > 0 && minutes > 0 -> "${hours}h ${minutes}m"
                hours > 0 -> "${hours}h"
                else -> "${minutes}m"
            }
        }
    }
}
