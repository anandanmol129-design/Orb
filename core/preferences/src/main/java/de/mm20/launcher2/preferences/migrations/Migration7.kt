package de.mm20.launcher2.preferences.migrations

import androidx.datastore.core.DataMigration
import de.mm20.launcher2.preferences.GestureAction
import de.mm20.launcher2.preferences.LauncherSettingsData

/**
 * Migrates from full-screen swipe/tap gestures to orb-area tap gestures.
 *
 * The orb's previous hardcoded behaviour (tap = search, triple tap = toggle fill,
 * long press = pie menu) becomes the default binding for the new slots.
 */
class Migration7 : DataMigration<LauncherSettingsData> {
    override suspend fun cleanUp() {
    }

    override suspend fun shouldMigrate(currentData: LauncherSettingsData): Boolean {
        return currentData.schemaVersion < 7
    }

    override suspend fun migrate(currentData: LauncherSettingsData): LauncherSettingsData {
        return currentData.copy(
            schemaVersion = 7,
            gesturesTap = GestureAction.Search,
            gesturesDoubleTap = GestureAction.NoAction,
            gesturesTripleTap = GestureAction.ToggleFill,
            gesturesLongPress = GestureAction.OpenPie,
        )
    }
}
