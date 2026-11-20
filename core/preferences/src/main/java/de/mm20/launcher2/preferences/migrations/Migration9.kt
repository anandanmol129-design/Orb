package de.mm20.launcher2.preferences.migrations

import androidx.datastore.core.DataMigration
import de.mm20.launcher2.preferences.GestureAction
import de.mm20.launcher2.preferences.LauncherSettingsData

/**
 * Re-binds triple tap from toggling the orb's fill to toggling the orb's edit mode.
 *
 * Users who never changed the default (toggle fill) get the new behaviour; anyone who
 * explicitly bound another action keeps it.
 */
class Migration9 : DataMigration<LauncherSettingsData> {
    override suspend fun cleanUp() {
    }

    override suspend fun shouldMigrate(currentData: LauncherSettingsData): Boolean {
        return currentData.schemaVersion < 9
    }

    override suspend fun migrate(currentData: LauncherSettingsData): LauncherSettingsData {
        return currentData.copy(
            schemaVersion = 9,
            gesturesTripleTap = if (currentData.gesturesTripleTap is GestureAction.ToggleFill) {
                GestureAction.ToggleEditMode
            } else {
                currentData.gesturesTripleTap
            },
        )
    }
}
