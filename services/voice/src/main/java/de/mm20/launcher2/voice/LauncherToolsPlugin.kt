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
            description = "Call a person using the phone app. Ask for spoken confirmation before opening the dialer.",
            parameters = Schema.obj(
                "contact" to Schema.string("Person's name or phone number.", required = true),
            ),
        ),
        ToolSpec(
            name = "orb.calls.whatsapp_video",
            description = "Start a WhatsApp video call to a contact. Ask for spoken confirmation first.",
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
        "orb.calls.whatsapp_video" -> whatsappVideoCall(args)
        else -> ToolResult.Error("Unknown tool: $name")
    }

    private suspend fun phoneCall(args: JsonObject): ToolResult {
        val target = args.string("contact")?.trim().orEmpty()
        if (target.isBlank()) return ToolResult.Error("Who should I call?")
        val isPhoneNumber = target.filter(Char::isDigit).length >= 7
        val contact = if (isPhoneNumber) null else resolveContact(target)
        val number = if (contact == null && isPhoneNumber) {
            target
        } else {
            contact?.phoneNumbers?.firstOrNull()?.number
        } ?: return ToolResult.Error("I couldn't find a phone number for $target.")

        if (args.boolean("confirmed") != true) {
            return ToolResult.ConfirmationRequired(
                "Call ${contact?.name ?: target} at $number? Say yes to continue."
            )
        }
        val canPlaceCall = ContextCompat.checkSelfPermission(context, android.Manifest.permission.CALL_PHONE) ==
            PackageManager.PERMISSION_GRANTED
        val intent = Intent(
            if (canPlaceCall) Intent.ACTION_CALL else Intent.ACTION_DIAL,
            Uri.fromParts("tel", number, null),
        )
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return startIntent(
            intent,
            if (canPlaceCall) "Calling ${contact?.name ?: target}."
            else "Opening the phone dialer for ${contact?.name ?: target}.",
        )
    }

    private suspend fun whatsappVideoCall(args: JsonObject): ToolResult {
        val target = args.string("contact")?.trim().orEmpty()
        if (target.isBlank()) return ToolResult.Error("Who should I video call?")
        val contact = resolveContact(target)
            ?: return ToolResult.Error("I couldn't find $target in your enabled contacts.")

        if (args.boolean("confirmed") != true) {
            return ToolResult.ConfirmationRequired(
                "Video call ${contact.name} on WhatsApp? Say yes to continue."
            )
        }

        val directAction = contact.customActions.firstOrNull { action ->
            action.packageName in WHATSAPP_PACKAGES &&
                action.mimeType?.contains("video.call", ignoreCase = true) == true
        }
        if (directAction != null) {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(directAction.uri, directAction.mimeType)
                setPackage(directAction.packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            return startIntent(intent, "Opening a WhatsApp video call with ${contact.name}.")
        }

        val number = contact.phoneNumbers.firstOrNull()?.number
            ?: return ToolResult.Error("I couldn't find a phone number for ${contact.name}.")
        val digits = number.filter(Char::isDigit)
        if (digits.length < 7) return ToolResult.Error("I couldn't use ${contact.name}'s saved number for WhatsApp.")
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
            "Opening ${contact.name}'s WhatsApp chat. Tap the video call button to start the call.",
        )
    }

    private suspend fun resolveContact(query: String): Contact? {
        val matches = withTimeoutOrNull(CONTACT_LOOKUP_TIMEOUT_MS) {
            contacts.search(query, allowNetwork = false).first { it.isNotEmpty() }
        }.orEmpty()
        return matches.firstOrNull { it.name.equals(query, ignoreCase = true) }
            ?: matches.firstOrNull { it.name.startsWith(query, ignoreCase = true) }
            ?: matches.firstOrNull { it.name.contains(query, ignoreCase = true) }
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

    private companion object {
        val WHATSAPP_PACKAGES = listOf("com.whatsapp", "com.whatsapp.w4b")
        const val CONTACT_LOOKUP_TIMEOUT_MS = 2_500L
    }
}
