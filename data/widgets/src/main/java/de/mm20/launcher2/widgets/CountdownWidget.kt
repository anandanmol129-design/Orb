package de.mm20.launcher2.widgets

import android.content.Context
import de.mm20.launcher2.database.entities.PartialWidgetEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

@Serializable
data class CountdownWidgetConfig(
    val title: String = "",
    /** Target day as days since the epoch, or null while the widget has not been set up. */
    val targetEpochDay: Long? = null,
    /** Day the countdown was started on; the progress bar runs from here to the target. */
    val startEpochDay: Long? = null,
    val repeatYearly: Boolean = false,
)

data class CountdownWidget(
    override val id: UUID,
    val config: CountdownWidgetConfig = CountdownWidgetConfig(),
) : Widget() {
    override fun toDatabaseEntity(): PartialWidgetEntity {
        return PartialWidgetEntity(
            id = id,
            type = Type,
            config = Json.encodeToString(config),
        )
    }

    override fun getLabel(context: Context): String {
        return context.getString(R.string.widget_name_countdown)
    }

    companion object {
        const val Type = "countdown"
    }
}
