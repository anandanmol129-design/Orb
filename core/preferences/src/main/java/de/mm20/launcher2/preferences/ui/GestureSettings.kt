package de.mm20.launcher2.preferences.ui

import de.mm20.launcher2.preferences.GestureAction
import de.mm20.launcher2.preferences.LauncherDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

data class GestureSettingsData(
    val tap: GestureAction,
    val doubleTap: GestureAction,
    val tripleTap: GestureAction,
    val longPress: GestureAction,
    val homeButton: GestureAction,
)

class GestureSettings internal constructor(
    private val dataStore: LauncherDataStore,
): Flow<GestureSettingsData> by (
    dataStore.data.map {
        GestureSettingsData(
            tap = it.gesturesTap,
            doubleTap = it.gesturesDoubleTap,
            tripleTap = it.gesturesTripleTap,
            longPress = it.gesturesLongPress,
            homeButton = it.gesturesHomeButton,
        )
    }.distinctUntilChanged()
) {
    val tap: Flow<GestureAction> = dataStore.data.map { it.gesturesTap }
        .distinctUntilChanged()

    val doubleTap: Flow<GestureAction> = dataStore.data.map { it.gesturesDoubleTap }
        .distinctUntilChanged()

    val tripleTap: Flow<GestureAction> = dataStore.data.map { it.gesturesTripleTap }
        .distinctUntilChanged()

    val longPress: Flow<GestureAction> = dataStore.data.map { it.gesturesLongPress }
        .distinctUntilChanged()

    val homeButton: Flow<GestureAction> = dataStore.data.map { it.gesturesHomeButton }
        .distinctUntilChanged()

    fun setTap(action: GestureAction) {
        dataStore.update {
            it.copy(gesturesTap = action)
        }
    }

    fun setDoubleTap(action: GestureAction) {
        dataStore.update {
            it.copy(gesturesDoubleTap = action)
        }
    }

    fun setTripleTap(action: GestureAction) {
        dataStore.update {
            it.copy(gesturesTripleTap = action)
        }
    }

    fun setLongPress(action: GestureAction) {
        dataStore.update {
            it.copy(gesturesLongPress = action)
        }
    }

    fun setHomeButton(action: GestureAction) {
        dataStore.update {
            it.copy(gesturesHomeButton = action)
        }
    }


}