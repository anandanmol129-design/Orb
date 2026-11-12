package de.mm20.launcher2.widgets

import android.content.Context
import de.mm20.launcher2.database.entities.PartialWidgetEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

@Serializable
data class ProgressWidgetConfig(
    val showDay: Boolean = true,
    val showWeek: Boolean = true,
    val showMonth: Boolean = true,
    val showYear: Boolean = true,
)

data class ProgressWidget(
    override val id: UUID,
    val config: ProgressWidgetConfig = ProgressWidgetConfig(),
) : Widget() {
    override fun toDatabaseEntity(): PartialWidgetEntity {
        return PartialWidgetEntity(
            id = id,
            type = Type,
            config = Json.encodeToString(config),
        )
    }

    override fun getLabel(context: Context): String {
        return context.getString(R.string.widget_name_progress)
    }

    companion object {
        const val Type = "progress"
    }
}
