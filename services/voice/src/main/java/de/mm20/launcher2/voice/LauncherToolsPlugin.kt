package de.mm20.launcher2.voice

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.provider.AlarmClock
import de.mm20.launcher2.agent.AgentPlugin
import de.mm20.launcher2.agent.Schema
import de.mm20.launcher2.agent.ToolResult
import de.mm20.launcher2.agent.ToolSpec
import de.mm20.launcher2.agent.boolean
import de.mm20.launcher2.agent.string
import de.mm20.launcher2.applications.AppRepository
import de.mm20.launcher2.search.Contact
import de.mm20.launcher2.search.SearchableRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import androidx.core.content.ContextCompat

/**
 * Launcher-level agent tools: launching apps, search, and simple system actions.
 *
 * Kept in the voice service because these are the tools the voice pipeline leans on most,
 * but they are ordinary [AgentPlugin]s and therefore also callable by the UI and any other
 * plugin.
 */
class LauncherToolsPlugin(
    private val context: Context,
    private val appRepository: AppRepository,
    private val contacts: SearchableRepository<Contact>,
    private val settings: VoiceSettings,
) : AgentPlugin {

    override val id: String = "orb.launcher"
    override val label: String = "Launcher"

    override fun tools(): List<ToolSpec> = listOf(
        ToolSpec(
            name = "orb.apps.open",
            description = "Open an installed app by (partial) name.",
            parameters = Schema.obj(
                "appName" to Schema.string("Name of the app, e.g. 'WhatsApp'.", required = true),
            ),
        ),
        ToolSpec(
            name = "orb.search.query",
            description = "Run a web search for a query.",
            parameters = Schema.obj(
                "query" to Schema.string("What to search for.", required = true),
            ),
        ),
        ToolSpec(
            name = "orb.system.torch",
            description = "Turn the flashlight on or off.",
            parameters = Schema.obj(
                "on" to Schema.boolean("True to turn on, false to turn off.", required = true),
            ),
        ),
        ToolSpec(
            name = "orb.system.set_timer",
            description = "Set a countdown timer.",
            parameters = Schema.obj(
                "seconds" to Schema.integer("Timer duration in seconds.", required = true),
                "label" to Schema.string("Optional label for the timer."),
            ),
        ),
        ToolSpec(
            name = "orb.system.set_alarm",
            description = "Set an alarm for a given time.",
            parameters = Schema.obj(
                "hour" to Schema.integer("Hour in 24-hour format.", required = true),
                "minute" to Schema.integer("Minute (0-59).", required = true),
                "label" to Schema.string("Optional label for the alarm."),
            ),
        ),
        ToolSpec(
            name = "orb.calls.phone",
            description = "Call a person using the phone app.",
            parameters = Schema.obj(
                "contact" to Schema.string("Person's name or phone number.", required = true),
            ),
        ),
        ToolSpec(
            name = "orb.calls.whatsapp_voice",
            description = "Start a WhatsApp voice call to a contact.",
            parameters = Schema.obj(
                "contact" to Schema.string("Person's name.", required = true),
            ),
        ),
        ToolSpec(
            name = "orb.calls.whatsapp_video",
            description = "Start a WhatsApp video call to a contact.",
            parameters = Schema.obj(
                "contact" to Schema.string("Person's name.", required = true),
            ),
        ),
        ToolSpec(
            name = "orb.calls.whatsapp_chat",
            description = "Open a WhatsApp chat with a contact.",
            parameters = Schema.obj(
                "contact" to Schema.string("Person's name.", required = true),
            ),
        ),
    )

    override suspend fun invoke(name: String, args: JsonObject): ToolResult = when (name) {
        "orb.apps.open" -> openApp(args)
        "orb.search.query" -> search(args)
        "orb.system.torch" -> torch(args)
        "orb.system.set_timer" -> setTimer(args)
        "orb.system.set_alarm" -> setAlarm(args)
        "orb.calls.phone" -> phoneCall(args)
        "orb.calls.whatsapp_voice" -> whatsapp(args, WhatsAppAction.Voice)
        "orb.calls.whatsapp_video" -> whatsapp(args, WhatsAppAction.Video)
        "orb.calls.whatsapp_chat" -> whatsapp(args, WhatsAppAction.Chat)
        else -> ToolResult.Error("Unknown tool: $name")
    }

    private suspend fun phoneCall(args: JsonObject): ToolResult {
        val target = args.string("contact")?.trim().orEmpty()
        if (!settings.allowPhoneCalls) {
            return ToolResult.Error("Phone calls by voice are turned off in Orb's voice settings.")
        }
        if (target.isBlank()) return ToolResult.Error("Who should I call?")
        val number: String
        val name: String
        if (target.filter(Char::isDigit).length >= 7) {
            number = target
            name = target
        } else {
            val contact = when (val match = resolveContact(target)) {
                is ContactMatch.One -> match.contact
                is ContactMatch.Several -> return showOptions(target)
                ContactMatch.None -> return ToolResult.Error("I couldn't find $target in your contacts.")
            }
            number = contact.phoneNumbers.firstOrNull()?.number
                ?: return ToolResult.Error("${contact.name} has no phone number.")
            name = contact.name
        }
        val canPlaceCall = settings.callDirectly && ContextCompat.checkSelfPermission(context, android.Manifest.permission.CALL_PHONE) ==
            PackageManager.PERMISSION_GRANTED
        val intent = Intent(
            if (canPlaceCall) Intent.ACTION_CALL else Intent.ACTION_DIAL,
            Uri.fromParts("tel", number, null),
        )
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return startIntent(
            intent,
            if (canPlaceCall) "Calling $name." else "Opening the phone dialer for $name.",
        )
    }

    private suspend fun whatsapp(args: JsonObject, action: WhatsAppAction): ToolResult {
        val target = args.string("contact")?.trim().orEmpty()
        if (!settings.allowWhatsApp) {
            return ToolResult.Error("WhatsApp commands are turned off in Orb's voice settings.")
        }
        if (target.isBlank()) return ToolResult.Error("Who should I contact on WhatsApp?")
        val contact = when (val match = resolveContact(target)) {
            is ContactMatch.One -> match.contact
            is ContactMatch.Several -> return showOptions(target)
            ContactMatch.None -> return ToolResult.Error("I couldn't find $target in your contacts.")
        }

        // WhatsApp adds its own rows to each contact it knows (message, voice call, video
        // call); opening one of those goes straight to the chat or call.
        val direct = contact.customActions.firstOrNull { custom ->
            custom.packageName in WHATSAPP_PACKAGES &&
                custom.mimeType?.endsWith(action.mimeTypeSuffix, ignoreCase = true) == true
        }
        if (direct != null) {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(direct.uri, direct.mimeType)
                setPackage(direct.packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            return startIntent(intent, action.success(contact.name))
        }

        // Not on WhatsApp as far as the contacts app knows: fall back to the chat by number.
        val digits = contact.phoneNumbers.firstOrNull()?.number?.filter(Char::isDigit).orEmpty()
        if (digits.length < 7) return ToolResult.Error("${contact.name} has no number WhatsApp can use.")
        val whatsappPackage = WHATSAPP_PACKAGES.firstOrNull { packageName ->
            try {
                context.packageManager.getPackageInfo(packageName, 0)
                true
            } catch (_: Exception) {
                false
            }
        } ?: return ToolResult.Error("WhatsApp isn't installed.")
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$digits")).apply {
            setPackage(whatsappPackage)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return startIntent(
            intent,
            if (action == WhatsAppAction.Chat) action.success(contact.name)
            else "Opening ${contact.name}'s WhatsApp chat. Tap call there to start it.",
        )
    }

    /**
     * Asks the launcher to show its search for [query], where every matching contact is
     * listed with its call and WhatsApp buttons, so the user can pick the right one.
     */
    private fun showOptions(query: String): ToolResult = ToolResult.Success(
        data = buildJsonObject { put(SHOW_SEARCH_KEY, JsonPrimitive(query)) },
        message = "Which $query?",
    )

    /**
     * Finds the contact the user meant by [query].
     *
     * Only acts on a sure match: a contact whose whole name is what was said, or, failing
     * that, the only contact whose first two names are the first two names said. A single
     * word ("Priyanshu") or two words shared by several contacts ("JSS Priyanshu") is
     * ambiguous, and the user picks from the list instead.
     */
    private suspend fun resolveContact(query: String): ContactMatch {
        val matches = withTimeoutOrNull(CONTACT_LOOKUP_TIMEOUT_MS) {
            contacts.search(query, allowNetwork = false).first { it.isNotEmpty() }
        }.orEmpty()
        if (matches.isEmpty()) return ContactMatch.None
        return pickContact(query, matches) { it.name }
            ?.let { ContactMatch.One(it) }
            ?: ContactMatch.Several
    }

    private suspend fun openApp(args: JsonObject): ToolResult {
        val query = (args["appName"] as? JsonPrimitive)?.content?.trim().orEmpty()
        if (query.isEmpty()) return ToolResult.Error("Which app should I open?")
        val apps = withContext(Dispatchers.Default) { appRepository.findMany().first() }
        val exact = apps.firstOrNull { it.label.equals(query, ignoreCase = true) }
        val startsWith = apps.firstOrNull { it.label.startsWith(query, ignoreCase = true) }
        val contains = apps.firstOrNull { it.label.contains(query, ignoreCase = true) }
        val app = exact ?: startsWith ?: contains
            ?: return ToolResult.Error("I couldn't find an app called \"$query\".")
        withContext(Dispatchers.Main) { app.launch(context, null) }
        return ToolResult.Success(
            data = buildJsonObject { put("label", JsonPrimitive(app.label)) },
            message = "Opening ${app.label}.",
        )
    }

    private fun search(args: JsonObject): ToolResult {
        val query = (args["query"] as? JsonPrimitive)?.content?.trim().orEmpty()
        if (query.isEmpty()) return ToolResult.Error("What should I search for?")
        val intent = Intent(Intent.ACTION_WEB_SEARCH).apply {
            putExtra(SearchManager.QUERY, query)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return startIntent(intent, "Searching for \"$query\".")
    }

    private fun torch(args: JsonObject): ToolResult {
        val on = (args["on"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()
            ?: return ToolResult.Error("Should I turn the flashlight on or off?")
        return try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return ToolResult.Error("This device has no flashlight.")
            cameraManager.setTorchMode(cameraId, on)
            ToolResult.Success(message = if (on) "Flashlight on." else "Flashlight off.")
        } catch (e: Exception) {
            ToolResult.Error("I couldn't control the flashlight.")
        }
    }

    private fun setTimer(args: JsonObject): ToolResult {
        val seconds = (args["seconds"] as? JsonPrimitive)?.content?.toLongOrNull()
            ?: return ToolResult.Error("How long should the timer be?")
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, seconds.toInt())
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            (args["label"] as? JsonPrimitive)?.content?.let {
                putExtra(AlarmClock.EXTRA_MESSAGE, it)
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return startIntent(intent, "Timer set for ${formatSeconds(seconds)}.")
    }

    private fun setAlarm(args: JsonObject): ToolResult {
        val hour = (args["hour"] as? JsonPrimitive)?.content?.toIntOrNull()
            ?: return ToolResult.Error("What time should the alarm be for?")
        val minute = (args["minute"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            (args["label"] as? JsonPrimitive)?.content?.let {
                putExtra(AlarmClock.EXTRA_MESSAGE, it)
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return startIntent(intent, "Alarm set for %02d:%02d.".format(hour, minute))
    }

    private fun startIntent(intent: Intent, successMessage: String): ToolResult {
        return try {
            context.startActivity(intent)
            ToolResult.Success(message = successMessage)
        } catch (e: Exception) {
            ToolResult.Error("I couldn't do that on this device.")
        }
    }

    private fun formatSeconds(seconds: Long): String {
        val minutes = seconds / 60
        val remainder = seconds % 60
        return when {
            minutes > 0 && remainder > 0 -> "$minutes minutes $remainder seconds"
            minutes > 0 -> "$minutes minutes"
            else -> "$seconds seconds"
        }
    }

    private sealed interface ContactMatch {
        data class One(val contact: Contact) : ContactMatch
        data object Several : ContactMatch
        data object None : ContactMatch
    }

    private enum class WhatsAppAction(val mimeTypeSuffix: String) {
        Voice("voip.call"),
        Video("video.call"),
        Chat("profile");

        fun success(name: String): String = when (this) {
            Voice -> "Calling $name on WhatsApp."
            Video -> "Video calling $name on WhatsApp."
            Chat -> "Opening $name's WhatsApp chat."
        }
    }

    companion object {
        /** Set in a [ToolResult.Success]'s data to have the launcher open search for its value. */
        const val SHOW_SEARCH_KEY = "show_search"

        private val WHATSAPP_PACKAGES = listOf("com.whatsapp", "com.whatsapp.w4b")
        private const val CONTACT_LOOKUP_TIMEOUT_MS = 2_500L
    }
}

/**
 * The one candidate [query] surely means, or null when it is ambiguous: a candidate whose
 * whole name is what was said, or, failing that, the only candidate whose first two names
 * are the first two names said.
 */
internal fun <T> pickContact(query: String, candidates: List<T>, nameOf: (T) -> String): T? {
    val spoken = nameWords(query)
    if (spoken.isEmpty()) return null
    val exact = candidates.filter { nameWords(nameOf(it)) == spoken }
    if (exact.size == 1) return exact.single()
    if (exact.isEmpty() && spoken.size >= 2) {
        val firstTwo = candidates.filter { nameWords(nameOf(it)).take(2) == spoken.take(2) }
        if (firstTwo.size == 1) return firstTwo.single()
    }
    return null
}

private fun nameWords(name: String): List<String> =
    name.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
