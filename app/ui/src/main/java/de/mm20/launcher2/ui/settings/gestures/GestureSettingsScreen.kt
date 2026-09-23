package de.mm20.launcher2.ui.settings.gestures

import androidx.annotation.DrawableRes
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey
import de.mm20.launcher2.FeatureFlags
import de.mm20.launcher2.icons.LauncherIcon
import de.mm20.launcher2.ktx.isAtLeastApiLevel
import de.mm20.launcher2.preferences.GestureAction
import de.mm20.launcher2.preferences.WidgetScreenTarget
import de.mm20.launcher2.search.SavableSearchable
import de.mm20.launcher2.ui.R
import de.mm20.launcher2.ui.common.SearchablePicker
import de.mm20.launcher2.ui.component.ShapedLauncherIcon
import de.mm20.launcher2.ui.component.preferences.GuardedPreference
import de.mm20.launcher2.ui.component.preferences.ListPreference
import de.mm20.launcher2.ui.component.preferences.PreferenceCategory
import de.mm20.launcher2.ui.component.preferences.PreferenceScreen
import de.mm20.launcher2.ui.ktx.toPixels
import kotlinx.serialization.Serializable

@Serializable
data object GesturesSettingsRoute : NavKey

@Composable
fun GestureSettingsScreen() {
    val viewModel: GestureSettingsScreenVM = viewModel()

    val hasPermission by viewModel.hasPermission.collectAsStateWithLifecycle(null)

    val shortcutOptions by viewModel.shortcutOptions.collectAsStateWithLifecycle(emptyList())
    val widgetOptions by viewModel.widgetOptions.collectAsStateWithLifecycle(emptyList())

    // Actions available to every gesture (including the home button).
    val options = buildSet {
        add(GestureAction.NoAction::class)
        add(GestureAction.Notifications::class)
        add(GestureAction.QuickSettings::class)
        if (isAtLeastApiLevel(28)) add(GestureAction.ScreenLock::class)
        add(GestureAction.Recents::class)
        add(GestureAction.PowerMenu::class)
        add(GestureAction.Search::class)
        add(GestureAction.Widgets::class)
        add(GestureAction.Launch::class)
        add(GestureAction.LauncherSettings::class)
        if (FeatureFlags.feed) add(GestureAction.Feed::class)
    }

    // Orb gestures can additionally open the pie menu or toggle sleep.
    val orbOptions = options +
            GestureAction.OpenPie::class +
            GestureAction.ToggleSleep::class +
            GestureAction.ToggleFill::class


    val context = LocalContext.current
    PreferenceScreen(title = stringResource(R.string.preference_screen_gestures)) {
        item {
            PreferenceCategory {
                val tap by viewModel.tap.collectAsStateWithLifecycle(null)
                GuardedPreference(
                    locked = hasPermission == false && requiresAccessibilityService(tap),
                    description = stringResource(R.string.missing_permission_accessibility_gesture_settings),
                    onUnlock = { viewModel.requestPermission(context as AppCompatActivity) },
                ) {
                    GesturePreference(
                        title = stringResource(R.string.preference_gesture_tap),
                        icon = R.drawable.gesture_24px,
                        value = tap,
                        onValueChanged = viewModel::setTap,
                        options = orbOptions,
                        shortcutOptions = shortcutOptions,
                        widgetOptions = widgetOptions,
                    )
                }

                val doubleTap by viewModel.doubleTap.collectAsStateWithLifecycle(null)
                GuardedPreference(
                    locked = hasPermission == false && requiresAccessibilityService(doubleTap),
                    description = stringResource(R.string.missing_permission_accessibility_gesture_settings),
                    onUnlock = { viewModel.requestPermission(context as AppCompatActivity) },
                ) {
                    GesturePreference(
                        title = stringResource(R.string.preference_gesture_double_tap),
                        icon = R.drawable.adjust_24px,
                        value = doubleTap,
                        onValueChanged = viewModel::setDoubleTap,
                        options = orbOptions,
                        shortcutOptions = shortcutOptions,
                        widgetOptions = widgetOptions,
                    )
                }

                val tripleTap by viewModel.tripleTap.collectAsStateWithLifecycle(null)
                GuardedPreference(
                    locked = hasPermission == false && requiresAccessibilityService(tripleTap),
                    description = stringResource(R.string.missing_permission_accessibility_gesture_settings),
                    onUnlock = { viewModel.requestPermission(context as AppCompatActivity) },
                ) {
                    GesturePreference(
                        title = stringResource(R.string.preference_gesture_triple_tap),
                        icon = R.drawable.adjust_24px,
                        value = tripleTap,
                        onValueChanged = viewModel::setTripleTap,
                        options = orbOptions,
                        shortcutOptions = shortcutOptions,
                        widgetOptions = widgetOptions,
                    )
                }

                val longPress by viewModel.longPress.collectAsStateWithLifecycle(null)
                GuardedPreference(
                    locked = hasPermission == false && requiresAccessibilityService(longPress),
                    description = stringResource(R.string.missing_permission_accessibility_gesture_settings),
                    onUnlock = { viewModel.requestPermission(context as AppCompatActivity) },
                ) {
                    GesturePreference(
                        title = stringResource(R.string.preference_gesture_long_press),
                        icon = R.drawable.circle_24px,
                        value = longPress,
                        onValueChanged = viewModel::setLongPress,
                        options = orbOptions,
                        shortcutOptions = shortcutOptions,
                        widgetOptions = widgetOptions,
                    )
                }

                val homeButton by viewModel.homeButton.collectAsStateWithLifecycle(null)
                GuardedPreference(
                    locked = hasPermission == false && requiresAccessibilityService(homeButton),
                    description = stringResource(R.string.missing_permission_accessibility_gesture_settings),
                    onUnlock = { viewModel.requestPermission(context as AppCompatActivity) },
                ) {
                    GesturePreference(
                        title = stringResource(R.string.preference_gesture_home_button),
                        icon = R.drawable.home_24px,
                        value = homeButton,
                        onValueChanged = viewModel::setHomeButton,
                        options = options,
                        shortcutOptions = shortcutOptions,
                        widgetOptions = widgetOptions,
                    )
                }
            }
        }
    }
}

fun requiresAccessibilityService(action: GestureAction?): Boolean {
    return when (action) {
        is GestureAction.ScreenLock,
        is GestureAction.Recents,
        is GestureAction.PowerMenu,
            -> true

        else -> false
    }
}

