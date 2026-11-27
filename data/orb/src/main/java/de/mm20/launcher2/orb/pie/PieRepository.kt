package de.mm20.launcher2.orb.pie

import android.content.Context
import de.mm20.launcher2.orb.MediaArcSize
import de.mm20.launcher2.orb.MediaRingAction
import de.mm20.launcher2.orb.MediaRingLayout
import de.mm20.launcher2.orb.MediaRingSlot
import de.mm20.launcher2.orb.OrbFill
import de.mm20.launcher2.orb.OrbShape
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement

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
    Feed,
    Widgets,
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
            runCatching {
                val tree = json.parseToJsonElement(stored)
                val pruned = (tree as? JsonArray)
                    ?.mapNotNull { pruneUnknownActions(it) }
                    ?.let { JsonArray(it) }
                    ?: tree
                json.decodeFromJsonElement<List<PieItem>>(pruned)
            }
                .getOrNull()
                ?.takeIf { it.isNotEmpty() }
        } else {
            null
        }
        val items = loaded ?: defaults()
        // Existing installs keep their saved pie configuration, so adding Feed to the
        // defaults alone would leave the new action unavailable on those devices.
        if (!prefs.getBoolean(KEY_FEED_MIGRATED, false)) {
            val migrated = if (items.any { it is PieItem.Action && it.type == PieActionType.Feed }) {
                items
            } else {
                items + PieItem.Action("feed", PieActionType.Feed, "Feed")
            }
            save(migrated)
            prefs.edit().putBoolean(KEY_FEED_MIGRATED, true).apply()
            return migrated
        }
        return items
    }

    /**
     * Drops items whose action type no longer exists (removed features), so a stored
     * config keeps the rest of its layout instead of falling back to the defaults.
     */
    private fun pruneUnknownActions(element: JsonElement): JsonElement? {
        if (element !is JsonObject) return element
        return when ((element["kind"] as? JsonPrimitive)?.contentOrNull) {
            "action" -> {
                val type = (element["type"] as? JsonPrimitive)?.contentOrNull
                element.takeIf { type != null && PieActionType.entries.any { it.name == type } }
            }
            "folder" -> {
                val children = element["children"] as? JsonArray ?: return element
                JsonObject(element + ("children" to JsonArray(children.mapNotNull { pruneUnknownActions(it) })))
            }
            else -> element
        }
    }

    fun save(items: List<PieItem>) {
        prefs.edit().putString(KEY_ITEMS, json.encodeToString(items)).apply()
    }

    fun reset() {
        prefs.edit().remove(KEY_ITEMS).apply()
    }

    fun orbCenterX(): Float = prefs.getFloat(KEY_ORB_CX, 0.5f)

    /** New installs start with the orb in the middle of the screen's bottom half. */
    fun orbCenterY(): Float = prefs.getFloat(KEY_ORB_CY, 0.75f)

    /**
     * The orb's size relative to its original default, or NaN while the user hasn't resized it.
     * NaN tells the home screen to use a fixed physical size (about 2 cm across the radius)
     * instead, which a screen-relative scale can't express.
     */
    fun orbScale(): Float = prefs.getFloat(KEY_ORB_SCALE, Float.NaN)

    /** Whether any orb position or size has been saved. */
    fun hasOrbLayout(): Boolean =
        prefs.contains(KEY_ORB_CY) || prefs.contains(KEY_ORB_SCALE) || prefs.contains(KEY_ORB_CX)

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

    /** Whether the media ring shows around the pie while something is playing. */
    fun mediaRingEnabled(): Boolean = prefs.getBoolean(KEY_MEDIA_RING, true)

    fun saveMediaRingEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_MEDIA_RING, enabled).apply()
    }

    /** Whether a filled orb wears the current track's cover art. */
    fun mediaCoverArt(): Boolean = prefs.getBoolean(KEY_MEDIA_ART, true)

    fun saveMediaCoverArt(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_MEDIA_ART, enabled).apply()
    }

    /** The action assigned to every media ring slot. Slots never saved keep their default. */
    fun mediaRing(): Map<MediaRingSlot, MediaRingAction> = MediaRingSlot.entries.associateWith { slot ->
        val stored = prefs.getString(KEY_MEDIA_SLOT_PREFIX + slot.name, null)
        MediaRingAction.entries.firstOrNull { it.name == stored } ?: defaultMediaRing.getValue(slot)
    }

    fun saveMediaRingSlot(slot: MediaRingSlot, action: MediaRingAction) {
        prefs.edit().putString(KEY_MEDIA_SLOT_PREFIX + slot.name, action.name).apply()
    }

    fun resetMediaRing() {
        prefs.edit().apply {
            MediaRingSlot.entries.forEach { remove(KEY_MEDIA_SLOT_PREFIX + it.name) }
        }.apply()
    }

    fun mediaRingLayout(): MediaRingLayout =
        MediaRingLayout.entries.firstOrNull { it.name == prefs.getString(KEY_MEDIA_LAYOUT, null) }
            ?: MediaRingLayout.Arc

    fun saveMediaRingLayout(layout: MediaRingLayout) {
        prefs.edit().putString(KEY_MEDIA_LAYOUT, layout.name).apply()
    }

    /** The action at each arc position, left to right. Positions never saved keep their default. */
    fun mediaArc(): List<MediaRingAction> = List(MediaArcSize) { i ->
        val stored = prefs.getString(KEY_MEDIA_ARC_PREFIX + i, null)
        MediaRingAction.entries.firstOrNull { it.name == stored } ?: defaultMediaArc[i]
    }

    fun saveMediaArcSlot(index: Int, action: MediaRingAction) {
        prefs.edit().putString(KEY_MEDIA_ARC_PREFIX + index, action.name).apply()
    }

    fun saveMediaArc(actions: List<MediaRingAction>) {
        prefs.edit().apply {
            repeat(MediaArcSize) { i ->
                val action = actions.getOrNull(i) ?: MediaRingAction.None
                putString(KEY_MEDIA_ARC_PREFIX + i, action.name)
            }
        }.apply()
    }

    fun saveMediaRing(slots: Map<MediaRingSlot, MediaRingAction>) {
        prefs.edit().apply {
            MediaRingSlot.entries.forEach { slot ->
                val action = slots[slot] ?: MediaRingAction.None
                putString(KEY_MEDIA_SLOT_PREFIX + slot.name, action.name)
            }
        }.apply()
    }

    fun resetMediaArc() {
        prefs.edit().apply {
            repeat(MediaArcSize) { remove(KEY_MEDIA_ARC_PREFIX + it) }
        }.apply()
    }

    /** Whether the user dismissed the home-screen prompt for notification access. */
    fun mediaAccessPromptDismissed(): Boolean = prefs.getBoolean(KEY_MEDIA_PROMPT_DISMISSED, false)

    fun saveMediaAccessPromptDismissed(dismissed: Boolean) {
        prefs.edit().putBoolean(KEY_MEDIA_PROMPT_DISMISSED, dismissed).apply()
    }

    fun mediaRewindSeconds(): Int = prefs.getInt(KEY_MEDIA_REWIND, 10)

    fun saveMediaRewindSeconds(seconds: Int) {
        prefs.edit().putInt(KEY_MEDIA_REWIND, seconds).apply()
    }

    fun mediaForwardSeconds(): Int = prefs.getInt(KEY_MEDIA_FORWARD, 10)

    fun saveMediaForwardSeconds(seconds: Int) {
        prefs.edit().putInt(KEY_MEDIA_FORWARD, seconds).apply()
    }


    companion object {
        private const val PREFS = "orb_pie"
        private const val KEY_ITEMS = "items_v1"
        private const val KEY_FEED_MIGRATED = "feed_action_migrated"
        private const val KEY_ORB_CX = "orb_center_x"
        private const val KEY_ORB_CY = "orb_center_y"
        private const val KEY_ORB_SCALE = "orb_scale"
        private const val KEY_ORB_SHAPE = "orb_shape"
        private const val KEY_ORB_FILL = "orb_fill"
        private const val KEY_MEDIA_RING = "media_ring_enabled"
        private const val KEY_MEDIA_ART = "media_cover_art"
        private const val KEY_MEDIA_SLOT_PREFIX = "media_slot_"
        private const val KEY_MEDIA_LAYOUT = "media_layout"
        private const val KEY_MEDIA_ARC_PREFIX = "media_arc_"
        private const val KEY_MEDIA_REWIND = "media_rewind_s"
        private const val KEY_MEDIA_FORWARD = "media_forward_s"
        private const val KEY_MEDIA_PROMPT_DISMISSED = "media_access_prompt_dismissed"

        /** Open player on top, AOD at top-right, next/previous at the sides, play/pause and skips along the bottom. */
        val defaultMediaRing: Map<MediaRingSlot, MediaRingAction> = mapOf(
            MediaRingSlot.Top to MediaRingAction.OpenApp,
            MediaRingSlot.TopRight to MediaRingAction.Aod,
            MediaRingSlot.Right to MediaRingAction.Next,
            MediaRingSlot.BottomRight to MediaRingAction.Forward,
            MediaRingSlot.Bottom to MediaRingAction.PlayPause,
            MediaRingSlot.BottomLeft to MediaRingAction.Rewind,
            MediaRingSlot.Left to MediaRingAction.Previous,
            MediaRingSlot.TopLeft to MediaRingAction.None,
        )

        /** Left to right on the arc above the orb. */
        val defaultMediaArc: List<MediaRingAction> = listOf(
            MediaRingAction.PlayPause,
            MediaRingAction.Rewind,
            MediaRingAction.Previous,
            MediaRingAction.OpenApp,
            MediaRingAction.Next,
            MediaRingAction.Forward,
            MediaRingAction.Aod,
        )

        fun defaults(): List<PieItem> = listOf(
            PieItem.Action("voice", PieActionType.Voice, "Voice"),
            PieItem.Action("search", PieActionType.Search, "Search"),
            PieItem.Action("feed", PieActionType.Feed, "Feed"),
            PieItem.Action("widgets", PieActionType.Widgets, "Widgets"),
            PieItem.Action("settings", PieActionType.Settings, "Settings"),
            PieItem.Action("edit", PieActionType.Edit, "Edit"),
        )
    }
}
