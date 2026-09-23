package de.mm20.launcher2.preferences.migrations

import androidx.datastore.core.DataMigration
import de.mm20.launcher2.preferences.GestureAction
import de.mm20.launcher2.preferences.LauncherSettingsData

/**
 * Re-binds triple tap from toggling sleep to toggling the orb's fill (outline/filled).
 *
 * Users who never changed the default (toggle sleep) get the new behaviour; anyone who
 * explicitly bound another action keeps it.
 */
class Migration8 : DataMigration<LauncherSettingsData> {
    override suspend fun cleanUp() {
    }

    override suspend fun shouldMigrate(currentData: LauncherSettingsData): Boolean {
        return currentData.schemaVersion < 8
    }

    override suspend fun migrate(currentData: LauncherSettingsData): LauncherSettingsData {
        return currentData.copy(
            schemaVersion = 8,
            gesturesTripleTap = if (currentData.gesturesTripleTap is GestureAction.ToggleSleep) {
                GestureAction.ToggleFill
            } else {
                currentData.gesturesTripleTap
            },
        )
    }
}
