package de.mm20.launcher2.preferences.feed

import de.mm20.launcher2.preferences.GestureAction
import de.mm20.launcher2.preferences.LauncherDataStore
import de.mm20.launcher2.preferences.LauncherSettingsData
import kotlinx.coroutines.flow.map

class FeedSettings internal constructor(
    private val launcherDataStore: LauncherDataStore,
) {

    val enabled
        get() = launcherDataStore.data.map { it.hasFeedGesture() }

    fun setEnabled(enabled: Boolean) {
        launcherDataStore.update { data ->
            if (enabled) {
                if (data.hasFeedGesture()) {
                    data
                } else {
                    data.copy(gesturesDoubleTap = GestureAction.Feed)
                }
            } else {
                data.copy(
                    gesturesTap = data.gesturesTap.clearFeed(),
                    gesturesDoubleTap = data.gesturesDoubleTap.clearFeed(),
                    gesturesTripleTap = data.gesturesTripleTap.clearFeed(),
                    gesturesLongPress = data.gesturesLongPress.clearFeed(),
                    gesturesHomeButton = data.gesturesHomeButton.clearFeed(),
                )
            }
        }
    }

    val providerPackage
        get() = launcherDataStore.data.map { it.feedProviderPackage }

    fun setProviderPackage(providerPackage: String?) {
        launcherDataStore.update {
            it.copy(feedProviderPackage = providerPackage)
        }
    }

}

private fun LauncherSettingsData.hasFeedGesture(): Boolean =
    gesturesTap is GestureAction.Feed ||
            gesturesDoubleTap is GestureAction.Feed ||
            gesturesTripleTap is GestureAction.Feed ||
            gesturesLongPress is GestureAction.Feed ||
            gesturesHomeButton is GestureAction.Feed

private fun GestureAction.clearFeed(): GestureAction =
    if (this is GestureAction.Feed) GestureAction.NoAction else this
