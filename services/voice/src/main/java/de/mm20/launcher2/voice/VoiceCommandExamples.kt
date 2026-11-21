package de.mm20.launcher2.voice

/** One example phrase and the tool the local parser must turn it into. */
data class VoiceExample(val phrase: String, val tool: String)

/** A group of related voice commands, as shown in the settings tutorial. */
data class VoiceCommandGroup(
    val title: String,
    val description: String,
    val examples: List<VoiceExample>,
)

/**
 * The commands the tutorial teaches. The UI renders this list and the unit tests check that
 * every phrase really parses to its tool, so the tutorial can't drift from the parser.
 */
val voiceCommandGroups: List<VoiceCommandGroup> = listOf(
    VoiceCommandGroup(
        title = "Open apps",
        description = "Say open, launch, start or run, then part of the app's name.",
        examples = listOf(
            VoiceExample("Open Spotify", "orb.apps.open"),
            VoiceExample("Launch camera", "orb.apps.open"),
        ),
    ),
    VoiceCommandGroup(
        title = "Phone calls",
        description = "Calls a contact, or a number with at least 7 digits.",
        examples = listOf(
            VoiceExample("Call Mom", "orb.calls.phone"),
            VoiceExample("Phone Priyanshu Mummy", "orb.calls.phone"),
            VoiceExample("Make a call to 5551234567", "orb.calls.phone"),
        ),
    ),
    VoiceCommandGroup(
        title = "WhatsApp",
        description = "Add \"on WhatsApp\" to call, video call or message a contact.",
        examples = listOf(
            VoiceExample("Call Mom on WhatsApp", "orb.calls.whatsapp_voice"),
            VoiceExample("WhatsApp video call Mom", "orb.calls.whatsapp_video"),
            VoiceExample("Message Mom on WhatsApp", "orb.calls.whatsapp_chat"),
            VoiceExample("WhatsApp Mom", "orb.calls.whatsapp_chat"),
        ),
    ),
    VoiceCommandGroup(
        title = "Timers and alarms",
        description = "Durations can use hours, minutes and seconds.",
        examples = listOf(
            VoiceExample("Set a timer for 10 minutes", "orb.system.set_timer"),
            VoiceExample("Timer for 1 hour 30 minutes", "orb.system.set_timer"),
            VoiceExample("Set an alarm for 7:30 am", "orb.system.set_alarm"),
        ),
    ),
    VoiceCommandGroup(
        title = "Flashlight",
        description = "Turns the torch on or off.",
        examples = listOf(
            VoiceExample("Turn on the flashlight", "orb.system.torch"),
            VoiceExample("Torch off", "orb.system.torch"),
        ),
    ),
    VoiceCommandGroup(
        title = "Search",
        description = "Searches the web for whatever follows.",
        examples = listOf(
            VoiceExample("Search for pasta recipes", "orb.search.query"),
            VoiceExample("Look up the weather in Berlin", "orb.search.query"),
        ),
    ),
)
