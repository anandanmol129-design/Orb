package de.mm20.launcher2.voice

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.provider.AlarmClock
import de.mm20.launcher2.agent.AgentPlugin
import de.mm20.launcher2.agent.Schema
import de.mm20.launcher2.agent.ToolResult
import de.mm20.launcher2.agent.ToolSpec
import de.mm20.launcher2.applications.AppRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

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
    )

    override suspend fun invoke(name: String, args: JsonObject): ToolResult = when (name) {
        "orb.apps.open" -> openApp(args)
        "orb.search.query" -> search(args)
        "orb.system.torch" -> torch(args)
        "orb.system.set_timer" -> setTimer(args)
        "orb.system.set_alarm" -> setAlarm(args)
        else -> ToolResult.Error("Unknown tool: $name")
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
}
