package de.mm20.launcher2.widgets

import android.content.Context
import de.mm20.launcher2.database.entities.PartialWidgetEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * Widgets backed by the launcher's own feature plugins (calendar, board, tracker).
 *
 * They are registered with Kvaesitso's widget host so they live alongside the built-in
 * widgets, but their data comes from `:data:orb` and their actions are driven by the same
 * agent tools as voice.
 */

@Serializable
data class OrbCalendarWidgetConfig(
    val upcomingCount: Int = 4,
)

data class OrbCalendarWidget(
    override val id: UUID,
    val config: OrbCalendarWidgetConfig = OrbCalendarWidgetConfig(),
) : Widget() {
    override fun toDatabaseEntity(): PartialWidgetEntity = PartialWidgetEntity(
        id = id,
        type = Type,
        config = Json.encodeToString(config),
    )

    override fun getLabel(context: Context): String =
        context.getString(R.string.widget_name_orb_calendar)

    companion object {
        const val Type = "orb.calendar"
    }
}

@Serializable
data class OrbBoardWidgetConfig(
    val showDone: Boolean = false,
)

data class OrbBoardWidget(
    override val id: UUID,
    val config: OrbBoardWidgetConfig = OrbBoardWidgetConfig(),
) : Widget() {
    override fun toDatabaseEntity(): PartialWidgetEntity = PartialWidgetEntity(
        id = id,
        type = Type,
        config = Json.encodeToString(config),
    )

    override fun getLabel(context: Context): String =
        context.getString(R.string.widget_name_orb_board)

    companion object {
        const val Type = "orb.board"
    }
}

@Serializable
data class OrbTrackerWidgetConfig(
    val showTodayTotal: Boolean = true,
)

data class OrbTrackerWidget(
    override val id: UUID,
    val config: OrbTrackerWidgetConfig = OrbTrackerWidgetConfig(),
) : Widget() {
    override fun toDatabaseEntity(): PartialWidgetEntity = PartialWidgetEntity(
        id = id,
        type = Type,
        config = Json.encodeToString(config),
    )

    override fun getLabel(context: Context): String =
        context.getString(R.string.widget_name_orb_tracker)

    companion object {
        const val Type = "orb.tracker"
    }
}
