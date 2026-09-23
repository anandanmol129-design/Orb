package de.mm20.launcher2.orb.pie

import android.content.Context
import de.mm20.launcher2.orb.OrbFill
import de.mm20.launcher2.orb.OrbShape
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * An entry in the radial (pie) menu that radiates from the centre sphere.
 */
@Serializable
sealed class PieItem {
    abstract val id: String
    abstract val label: String

    /** Launches an installed application. [id] is `package/class`. */
    @Serializable
    @SerialName("app")
    data class App(
        override val id: String,
        override val label: String,
    ) : PieItem()

    /** Launches an app shortcut (deep link, pinned shortcut, etc.). */
    @Serializable
    @SerialName("shortcut")
    data class Shortcut(
        override val id: String,
        val packageName: String,
        val shortcutId: String,
        override val label: String,
    ) : PieItem()

    /** Runs a built-in launcher action, such as voice or search. */
    @Serializable
    @SerialName("action")
    data class Action(
        override val id: String,
        val type: PieActionType,
        override val label: String,
        /**
         * For [PieActionType.Widgets], which widgets page to open, as a `WidgetScreenTarget`
         * name (e.g. "Widgets1"). Null means the default page. Stored as a string so the pie
         * model doesn't depend on the preferences module.
         */
        val widgetsTarget: String? = null,
    ) : PieItem()

    /** A folder of items, opened as a window over the orb. */
    @Serializable
    @SerialName("folder")
    data class Folder(
        override val id: String,
        override val label: String,
        val children: List<PieItem> = emptyList(),
    ) : PieItem()
}

@Serializable
enum class PieActionType {
    Voice,
    Search,
    Widgets,
    Calendar,
    Board,
    Tracker,
    Settings,
    Edit,
}

/**
 * Persists the user's radial menu configuration.
 *
 * Kept in the feature data layer (rather than the UI) so that voice and future agents can
 * read and rearrange the menu through the same API.
 */
class PieRepository(
    context: Context,
) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val json = Json {
        ignoreUnknownKeys = true
        classDiscriminator = "kind"
        encodeDefaults = true
    }

    fun items(): List<PieItem> {
        val stored = prefs.getString(KEY_ITEMS, null)
        val loaded = if (stored != null) {
            runCatching { json.decodeFromString<List<PieItem>>(stored) }
                .getOrNull()
                ?.takeIf { it.isNotEmpty() }
        } else {
            null
        }
        return ensureEdit(loaded ?: defaults())
    }

    fun save(items: List<PieItem>) {
        prefs.edit().putString(KEY_ITEMS, json.encodeToString(items)).apply()
    }

    fun reset() {
        prefs.edit().remove(KEY_ITEMS).apply()
    }

    fun orbCenterX(): Float = prefs.getFloat(KEY_ORB_CX, 0.5f)

    fun orbCenterY(): Float = prefs.getFloat(KEY_ORB_CY, 0.52f)

    fun orbScale(): Float = prefs.getFloat(KEY_ORB_SCALE, 1f)

    fun saveOrbLayout(centerX: Float, centerY: Float, scale: Float) {
        prefs.edit()
            .putFloat(KEY_ORB_CX, centerX)
            .putFloat(KEY_ORB_CY, centerY)
            .putFloat(KEY_ORB_SCALE, scale)
            .apply()
    }

    fun orbShape(): OrbShape {
        val stored = prefs.getString(KEY_ORB_SHAPE, null)
        return OrbShape.entries.firstOrNull { it.name == stored } ?: OrbShape.Circle
    }

    fun saveOrbShape(shape: OrbShape) {
        prefs.edit().putString(KEY_ORB_SHAPE, shape.name).apply()
    }

    fun orbFill(): OrbFill {
        val stored = prefs.getString(KEY_ORB_FILL, null)
        return OrbFill.entries.firstOrNull { it.name == stored } ?: OrbFill.Filled
    }

    fun saveOrbFill(fill: OrbFill) {
        prefs.edit().putString(KEY_ORB_FILL, fill.name).apply()
    }

    private fun ensureEdit(items: List<PieItem>): List<PieItem> =
        if (items.any { it is PieItem.Action && it.type == PieActionType.Edit }) {
            items
        } else {
            items + PieItem.Action("edit", PieActionType.Edit, "Edit")
        }

    companion object {
        private const val PREFS = "orb_pie"
        private const val KEY_ITEMS = "items_v1"
        private const val KEY_ORB_CX = "orb_center_x"
        private const val KEY_ORB_CY = "orb_center_y"
        private const val KEY_ORB_SCALE = "orb_scale"
        private const val KEY_ORB_SHAPE = "orb_shape"
        private const val KEY_ORB_FILL = "orb_fill"

        fun defaults(): List<PieItem> = listOf(
            PieItem.Action("voice", PieActionType.Voice, "Voice"),
            PieItem.Action("search", PieActionType.Search, "Search"),
            PieItem.Action("calendar", PieActionType.Calendar, "Calendar"),
            PieItem.Action("board", PieActionType.Board, "Tasks"),
            PieItem.Action("tracker", PieActionType.Tracker, "Timer"),
            PieItem.Action("widgets", PieActionType.Widgets, "Widgets"),
            PieItem.Action("settings", PieActionType.Settings, "Settings"),
            PieItem.Action("edit", PieActionType.Edit, "Edit"),
        )
    }
}
