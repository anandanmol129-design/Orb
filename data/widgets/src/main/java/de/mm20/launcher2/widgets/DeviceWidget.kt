package de.mm20.launcher2.widgets

import android.content.Context
import de.mm20.launcher2.database.entities.PartialWidgetEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

@Serializable
data class DeviceWidgetConfig(
    val showStorage: Boolean = true,
    val showMemory: Boolean = true,
    val showTemperature: Boolean = true,
)

data class DeviceWidget(
    override val id: UUID,
    val config: DeviceWidgetConfig = DeviceWidgetConfig(),
) : Widget() {
    override fun toDatabaseEntity(): PartialWidgetEntity {
        return PartialWidgetEntity(
            id = id,
            type = Type,
            config = Json.encodeToString(config),
        )
    }

    override fun getLabel(context: Context): String {
        return context.getString(R.string.widget_name_device)
    }

    companion object {
        const val Type = "device"
    }
}
