package de.mm20.launcher2.voice

import de.mm20.launcher2.agent.ToolArgs

private val torchOnOff = Regex("^(turn\\s+)?(on|off)\\s+(the\\s+)?(flashlight|torch|light)$")
private val torchLabel = Regex("^(flashlight|torch|light)\\s+(on|off)$")
private val setTimer = Regex("^(set\\s+)?(a\\s+)?timer\\s+(for\\s+)?(.+)$")
private val setAlarm = Regex("^(set\\s+)?(an?\\s+)?alarm\\s+(for\\s+)?(\\d{1,2})(:(\\d{2}))?\\s*(am|pm)?$")
private val searchQuery = Regex("^(search|google|look\\s+up)\\s+(for\\s+)?(.+)$")
private const val ON_WHATSAPP = "(?:\\s+(?:on|over|via|in)\\s+whats?\\s?app)"
private val whatsappVideoCall = Regex("^(?:make\\s+)?(?:a\\s+)?(?:whats?\\s?app\\s+)?video\\s+call\\s+(?:to\\s+|with\\s+)?(.+?)$ON_WHATSAPP?$")
private val whatsappVoiceCall = Regex(
    "^(?:(?:make|place)\\s+)?(?:a\\s+)?(?:" +
        // "whatsapp call x", "whatsapp voice call x"
        "whats?\\s?app\\s+(?:voice\\s+|audio\\s+)?call\\s+(?:to\\s+)?(.+?)$ON_WHATSAPP?" +
        // "call x on whatsapp", "voice call x on whatsapp"
        "|(?:voice\\s+|audio\\s+)?call\\s+(?:to\\s+)?(.+?)$ON_WHATSAPP" +
        ")$"
)
private val whatsappChat = Regex(
    "^(?:(?:send\\s+)?(?:a\\s+)?(?:message|text|msg|chat)\\s+(?:to\\s+|with\\s+)?(.+?)$ON_WHATSAPP" +
        "|(?:open\\s+)?whats?\\s?app\\s+(?:message|text|msg|chat)\\s+(?:to\\s+|with\\s+)?(.+?)" +
        "|whats?\\s?app\\s+(?!call\\b|video\\b|voice\\b|audio\\b)(.+?))$"
)
private val phoneCall = Regex("^(?:(?:make|place)\\s+)?(?:a\\s+)?(?:phone\\s+)?(?:call|phone)\\s+(?:to\\s+)?(.+)$")
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

        parseTorch(text)?.let { return it }
        parseTimer(text)?.let { return it }
        parseAlarm(text)?.let { return it }
        parseSearch(text)?.let { return it }
        parseCall(text)?.let { return it }
        parseOpenApp(text)?.let { return it }
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

    private fun parseCall(text: String): ToolInvocation? {
        fun invocation(tool: String, match: MatchResult): ToolInvocation? {
            val contact = match.groupValues.drop(1).firstOrNull { it.isNotBlank() }?.trim()
            if (contact.isNullOrEmpty()) return null
            return ToolInvocation(tool, ToolArgs.of("contact" to contact))
        }
        whatsappVideoCall.find(text)?.let { m -> invocation("orb.calls.whatsapp_video", m)?.let { return it } }
        whatsappVoiceCall.find(text)?.let { m -> invocation("orb.calls.whatsapp_voice", m)?.let { return it } }
        whatsappChat.find(text)?.let { m -> invocation("orb.calls.whatsapp_chat", m)?.let { return it } }
        phoneCall.find(text)?.let { m -> invocation("orb.calls.phone", m)?.let { return it } }
        return null
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
