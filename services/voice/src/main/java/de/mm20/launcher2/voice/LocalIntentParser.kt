package de.mm20.launcher2.voice

import de.mm20.launcher2.agent.ToolArgs

private val completeOrFinish = Regex("^(?:complete|finish)\\s+(.+)$")
private val markAsDone = Regex("^mark\\s+(.+?)\\s+(?:as\\s+)?(?:done|complete[d]?)$")
private val moveTask = Regex("^move\\s+(.+?)\\s+to\\s+(backlog|doing|done)$")
private val addExplicitTask = Regex("^add\\s+(?:a\\s+)?task\\s+(.+?)(?:\\s+to\\s+(.+))?$")
private val addTaskToColumn = Regex("^add\\s+(.+?)\\s+to\\s+(backlog|doing|done|to-?do|tasks?|my tasks?)$")
private val createEvent = Regex("^(?:add\\s+(?:an?\\s+)?(?:event|meeting|appointment)\\s+|schedule\\s+)(.+)$")
private val tomorrowWord = Regex("\\btomorrow\\b")
private val todayWord = Regex("\\btoday\\b")
private val inDuration = Regex("\\bin\\s+(\\d+)\\s+(hours?|minutes?|mins?|h|m)\\b")
private val atTime = Regex("\\bat\\s+(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?")
private val whitespace = Regex("\\s+")
private val startTracking = Regex("^(start|begin)\\s+tracking(\\s+(for\\s+)?(.+))?$")
private val trackLabel = Regex("^track\\s+(.+)$")
private val stopTracking = Regex("^(stop|end|finish)\\s+tracking$")
private val trackingStatus = Regex("^(what('s| is)?\\s+)?(am i\\s+)?tracking(\\s+now)?$")
private val timeTrackingSummary = Regex("^time\\s+tracking(\\s+summary)?$")
private val torchOnOff = Regex("^(turn\\s+)?(on|off)\\s+(the\\s+)?(flashlight|torch|light)$")
private val torchLabel = Regex("^(flashlight|torch|light)\\s+(on|off)$")
private val setTimer = Regex("^(set\\s+)?(a\\s+)?timer\\s+(for\\s+)?(.+)$")
private val setAlarm = Regex("^(set\\s+)?(an?\\s+)?alarm\\s+(for\\s+)?(\\d{1,2})(:(\\d{2}))?\\s*(am|pm)?$")
private val searchQuery = Regex("^(search|google|look\\s+up)\\s+(for\\s+)?(.+)$")
private val openApp = Regex("^(open|launch|start|run)\\s+(.+)$")
private val durationSpec = Regex("(\\d+|[a-z]+)\\s*(hours?|hrs?|h|minutes?|mins?|m|seconds?|secs?|s)\\b")

private val numberWords = mapOf(
    "a" to 1L, "an" to 1L, "one" to 1L, "two" to 2L, "three" to 3L, "four" to 4L,
    "five" to 5L, "six" to 6L, "seven" to 7L, "eight" to 8L, "nine" to 9L,
    "ten" to 10L, "fifteen" to 15L, "twenty" to 20L, "thirty" to 30L,
    "forty" to 40L, "fifty" to 50L, "sixty" to 60L,
)

private val affirmativeReplies = setOf(
    "yes", "yeah", "yep", "yup", "sure", "ok", "okay", "do it", "go ahead",
    "confirm", "confirmed", "please do", "affirmative", "correct",
)

private val negativeReplies = setOf("no", "nope", "nah", "cancel", "stop", "don't", "dont", "never mind", "nevermind")

/**
 * Deterministic, fully offline parser for the most common commands.
 *
 * This runs first so that frequent, latency-sensitive actions (open an app, set a timer,
 * toggle the flashlight) never depend on the network or an API key.
 */
class LocalIntentParser {

    fun parse(raw: String): ToolInvocation? {
        val text = raw.trim().lowercase().trimEnd('.', '!', '?', ',')
        if (text.isEmpty()) return null

        parseTracker(text)?.let { return it }
        parseTorch(text)?.let { return it }
        parseTimer(text)?.let { return it }
        parseAlarm(text)?.let { return it }
        parseSearch(text)?.let { return it }
        parseExplicitTask(text)?.let { return it }
        parseTaskWithColumn(text)?.let { return it }
        parseCompleteTask(text)?.let { return it }
        parseMoveTask(text)?.let { return it }
        parseEvent(text)?.let { return it }
        parseOpenApp(text)?.let { return it }
        return null
    }

    private fun parseCompleteTask(text: String): ToolInvocation? {
        completeOrFinish.find(text)?.let {
            return ToolInvocation(
                "orb.board.complete_task",
                ToolArgs.of("title" to it.groupValues[1].trim()),
            )
        }
        markAsDone.find(text)?.let {
            return ToolInvocation(
                "orb.board.complete_task",
                ToolArgs.of("title" to it.groupValues[1].trim()),
            )
        }
        return null
    }

    private fun parseMoveTask(text: String): ToolInvocation? {
        val match = moveTask.find(text)
            ?: return null
        return ToolInvocation(
            "orb.board.move_task",
            ToolArgs.of(
                "title" to match.groupValues[1].trim(),
                "column" to match.groupValues[2],
            ),
        )
    }

    private fun parseExplicitTask(text: String): ToolInvocation? {
        val match = addExplicitTask.find(text)
            ?: return null
        val title = match.groupValues[1].trim()
        if (title.isEmpty()) return null
        val column = match.groupValues[2].trim().takeIf { it.isNotEmpty() }
        val args = if (column != null) {
            ToolArgs.of("title" to title, "column" to column)
        } else {
            ToolArgs.of("title" to title)
        }
        return ToolInvocation("orb.board.create_task", args)
    }

    private fun parseTaskWithColumn(text: String): ToolInvocation? {
        val match = addTaskToColumn.find(text) ?: return null
        val title = match.groupValues[1].trim()
        if (title.isEmpty()) return null
        val rawColumn = match.groupValues[2]
        val column = when {
            rawColumn.startsWith("do") -> "Done"
            rawColumn.startsWith("to") -> "Backlog"
            else -> "Backlog"
        }
        return ToolInvocation(
            "orb.board.create_task",
            ToolArgs.of("title" to title, "column" to column),
        )
    }

    private fun parseEvent(text: String): ToolInvocation? {
        val match = createEvent.find(text) ?: return null
        val spec = parseEventSpec(match.groupValues[1]) ?: return null
        return ToolInvocation(
            "orb.calendar.create_event",
            ToolArgs.of(
                "title" to spec.first,
                "start" to spec.second,
                "end" to (spec.second + 60L * 60_000),
            ),
        )
    }

    /**
     * Extracts a title and a concrete start time from phrases like
     * "team sync tomorrow at 3pm" or "call in 30 minutes".
     */
    private fun parseEventSpec(raw: String): Pair<String, Long>? {
        var working = raw
        val zone = java.time.ZoneId.systemDefault()

        var dayOffset = 0L
        if (tomorrowWord.containsMatchIn(working)) {
            dayOffset = 1L
            working = working.replace(tomorrowWord, " ")
        } else if (todayWord.containsMatchIn(working)) {
            working = working.replace(todayWord, " ")
        }

        var start: Long? = null

        inDuration.find(working)?.let { inMatch ->
            val amount = inMatch.groupValues[1].toLongOrNull() ?: return@let
            val unit = inMatch.groupValues[2]
            val millis = if (unit.startsWith("h")) amount * 3_600_000 else amount * 60_000
            start = System.currentTimeMillis() + millis
            working = working.removeRange(inMatch.range)
        }

        if (start == null) {
            atTime.find(working)?.let { timeMatch ->
                var hour = timeMatch.groupValues[1].toIntOrNull() ?: return@let
                val minute = timeMatch.groupValues[2].toIntOrNull() ?: 0
                when (timeMatch.groupValues[3]) {
                    "pm" -> if (hour < 12) hour += 12
                    "am" -> if (hour == 12) hour = 0
                }
                if (hour in 0..23 && minute in 0..59) {
                    start = java.time.LocalDate.now()
                        .plusDays(dayOffset)
                        .atStartOfDay(zone)
                        .plusHours(hour.toLong())
                        .plusMinutes(minute.toLong())
                        .toInstant()
                        .toEpochMilli()
                    working = working.removeRange(timeMatch.range)
                }
            }
        }

        if (start == null && dayOffset == 1L) {
            start = java.time.LocalDate.now()
                .plusDays(1)
                .atTime(9, 0)
                .atZone(zone)
                .toInstant()
                .toEpochMilli()
        }

        val title = working.replace(whitespace, " ").trim().trim(',', '.', ' ')
        val startTime = start ?: return null
        if (title.isEmpty()) return null
        return title to startTime
    }

    private fun parseTracker(text: String): ToolInvocation? {
        startTracking.matchEntire(text)?.let { match ->
            val label = match.groupValues.getOrNull(4)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: "work"
            return ToolInvocation("orb.tracker.start", ToolArgs.of("label" to label))
        }
        trackLabel.matchEntire(text)?.let { match ->
            val label = match.groupValues[1].trim()
            return ToolInvocation("orb.tracker.start", ToolArgs.of("label" to label))
        }
        stopTracking.matchEntire(text)?.let {
            return ToolInvocation("orb.tracker.stop", ToolArgs.of())
        }
        trackingStatus.matchEntire(text)?.let {
            return ToolInvocation("orb.tracker.status", ToolArgs.of())
        }
        timeTrackingSummary.matchEntire(text)?.let {
            return ToolInvocation("orb.tracker.summary", ToolArgs.of())
        }
        return null
    }

    private fun parseTorch(text: String): ToolInvocation? {
        torchOnOff.find(text)?.let {
            return ToolInvocation(
                "orb.system.torch",
                ToolArgs.of("on" to (it.groupValues[2] == "on")),
            )
        }
        torchLabel.find(text)?.let {
            return ToolInvocation(
                "orb.system.torch",
                ToolArgs.of("on" to (it.groupValues[2] == "on")),
            )
        }
        return null
    }

    private fun parseTimer(text: String): ToolInvocation? {
        val match = setTimer.find(text) ?: return null
        val seconds = parseDurationSeconds(match.groupValues[4]) ?: return null
        return ToolInvocation("orb.system.set_timer", ToolArgs.of("seconds" to seconds))
    }

    private fun parseAlarm(text: String): ToolInvocation? {
        val match = setAlarm.find(text) ?: return null
        var hour = match.groupValues[4].toIntOrNull() ?: return null
        val minute = match.groupValues[6].toIntOrNull() ?: 0
        val meridiem = match.groupValues[7]
        if (meridiem == "pm" && hour < 12) hour += 12
        if (meridiem == "am" && hour == 12) hour = 0
        if (hour !in 0..23 || minute !in 0..59) return null
        return ToolInvocation("orb.system.set_alarm", ToolArgs.of("hour" to hour, "minute" to minute))
    }

    private fun parseSearch(text: String): ToolInvocation? {
        val match = searchQuery.find(text) ?: return null
        val query = match.groupValues[3].trim()
        if (query.isEmpty()) return null
        return ToolInvocation("orb.search.query", ToolArgs.of("query" to query))
    }

    private fun parseOpenApp(text: String): ToolInvocation? {
        val match = openApp.find(text) ?: return null
        val app = match.groupValues[2].trim()
        if (app.isEmpty()) return null
        return ToolInvocation("orb.apps.open", ToolArgs.of("appName" to app))
    }

    private fun parseDurationSeconds(text: String): Long? {
        var total = 0L
        var found = false
        durationSpec.findAll(text).forEach { match ->
            val value = match.groupValues[1].toLongOrNull()
                ?: numberWords[match.groupValues[1]]
                ?: return@forEach
            val unit = match.groupValues[2]
            val multiplier = when {
                unit.startsWith("h") -> 3600L
                unit.startsWith("m") -> 60L
                else -> 1L
            }
            total += value * multiplier
            found = true
        }
        return if (found && total > 0) total else null
    }
}

/** Returns true when a spoken reply is affirmative. */
fun isAffirmative(text: String): Boolean {
    val t = text.trim().lowercase().trimEnd('.', '!', '?', ',')
    return t in affirmativeReplies
}

/** Returns true when a spoken reply is negative. */
fun isNegative(text: String): Boolean {
    val t = text.trim().lowercase().trimEnd('.', '!', '?', ',')
    return t in negativeReplies
}
