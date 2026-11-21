package de.mm20.launcher2.ui.settings.main

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import de.mm20.launcher2.ui.R
import de.mm20.launcher2.ui.component.preferences.Preference
import de.mm20.launcher2.ui.component.preferences.PreferenceCategory
import de.mm20.launcher2.ui.component.preferences.PreferenceScreen
import de.mm20.launcher2.ui.locals.LocalBackStack
import de.mm20.launcher2.ui.settings.about.AboutSettingsRoute
import de.mm20.launcher2.ui.settings.appearance.AppearanceSettingsRoute
import de.mm20.launcher2.ui.settings.backup.BackupSettingsRoute
import de.mm20.launcher2.ui.settings.gestures.GesturesSettingsRoute
import de.mm20.launcher2.ui.settings.homescreen.HomescreenSettingsRoute
import de.mm20.launcher2.ui.settings.icons.IconsSettingsRoute
import de.mm20.launcher2.ui.settings.locale.LocaleSettingsRoute
import de.mm20.launcher2.ui.settings.orb.OrbSettingsRoute
import de.mm20.launcher2.ui.settings.search.SearchSettingsRoute
import kotlinx.serialization.Serializable

@Serializable
data object MainRoute: NavKey

@Composable
fun MainSettingsScreen() {
    val backStack = LocalBackStack.current
    val context = LocalContext.current

    var searchActive by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val configuration = LocalConfiguration.current
    val index = remember(configuration) { buildSettingsSearchIndex(context.resources) }
    val results = remember(index, query) { searchSettings(index, query) }

    fun closeSearch() {
        searchActive = false
        query = ""
    }

    BackHandler(enabled = searchActive) { closeSearch() }

    PreferenceScreen(
        title = {
            if (searchActive) {
                SettingsSearchField(
                    query = query,
                    onQueryChange = { query = it },
                )
            } else {
                Text(
                    stringResource(R.string.settings),
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 16.dp),
                    maxLines = 1
                )
            }
        },
        topBarActions = {
            if (searchActive) {
                IconButton(onClick = { closeSearch() }) {
                    Icon(
                        painter = painterResource(R.drawable.close_24px),
                        contentDescription = stringResource(R.string.close),
                    )
                }
            } else {
                IconButton(onClick = { searchActive = true }) {
                    Icon(
                        painter = painterResource(R.drawable.search_24px),
                        contentDescription = stringResource(R.string.settings_search),
                    )
                }
            }
        },
    ) {
        if (searchActive && query.isNotBlank()) {
            item {
                PreferenceCategory {
                    if (results.isEmpty()) {
                        Preference(
                            title = stringResource(R.string.settings_search_no_results),
                            enabled = false,
                        )
                    }
                    for (result in results) {
                        Preference(
                            title = result.title,
                            summary = result.path,
                            onClick = { backStack.add(result.route) },
                        )
                    }
                }
            }
            return@PreferenceScreen
        }
        item {
            PreferenceCategory {
                Preference(
                    icon = R.drawable.circle_24px,
                    title = stringResource(id = R.string.preference_screen_orb),
                    summary = stringResource(id = R.string.preference_screen_orb_summary),
                    onClick = {
                        backStack.add(OrbSettingsRoute)
                    }
                )
                Preference(
                    icon = R.drawable.palette_24px,
                    title = stringResource(id = R.string.preference_screen_appearance),
                    summary = stringResource(id = R.string.preference_screen_appearance_summary),
                    onClick = {
                        backStack.add(AppearanceSettingsRoute)
                    }
                )
                Preference(
                    icon = R.drawable.home_24px,
                    title = stringResource(id = R.string.preference_screen_homescreen),
                    summary = stringResource(id = R.string.preference_screen_homescreen_summary),
                    onClick = {
                        backStack.add(HomescreenSettingsRoute)
                    }
                )
                Preference(
                    icon = R.drawable.apps_24px,
                    title = stringResource(id = R.string.preference_screen_icons),
                    summary = stringResource(id = R.string.preference_screen_icons_summary),
                    onClick = {
                        backStack.add(IconsSettingsRoute)
                    }
                )
                Preference(
                    icon = R.drawable.search_24px,
                    title = stringResource(id = R.string.preference_screen_search),
                    summary = stringResource(id = R.string.preference_screen_search_summary),
                    onClick = {
                        backStack.add(SearchSettingsRoute)
                    }
                )
                Preference(
                    icon = R.drawable.gesture_24px,
                    title = stringResource(id = R.string.preference_screen_gestures),
                    summary = stringResource(id = R.string.preference_screen_gestures_summary),
                    onClick = {
                        backStack.add(GesturesSettingsRoute)
                    }
                )
                Preference(
                    icon = R.drawable.translate_24px,
                    title = stringResource(id = R.string.preference_screen_locale),
                    summary = stringResource(id = R.string.preference_screen_locale_summary),
                    onClick = {
                        backStack.add(LocaleSettingsRoute)
                    }
                )
                Preference(
                    icon = R.drawable.settings_backup_restore_24px,
                    title = stringResource(id = R.string.preference_screen_backup),
                    summary = stringResource(id = R.string.preference_screen_backup_summary),
                    onClick = {
                        backStack.add(BackupSettingsRoute)
                    }
                )
                Preference(
                    icon = R.drawable.info_24px,
                    title = stringResource(id = R.string.preference_screen_about),
                    summary = stringResource(id = R.string.preference_screen_about_summary),
                    onClick = {
                        backStack.add(AboutSettingsRoute)
                    }
                )
            }
        }
        item {
            PreferenceCategory {
                Preference(
                    icon = R.drawable.restart_alt_24px,
                    title = stringResource(id = R.string.preference_restart_launcher),
                    summary = stringResource(id = R.string.preference_restart_launcher_summary),
                    onClick = { restartLauncher(context) }
                )
            }
        }
    }
}
/**
 * Does what force stopping Orb and reopening it does: ends the whole process, so every bit of
 * in-memory state (stuck gestures, half-finished page transitions) starts over, then brings the
 * home screen back. Settings and the orb's layout are stored on disk and survive.
 */
private fun restartLauncher(context: Context) {
    val home = Intent(Intent.ACTION_MAIN)
        .addCategory(Intent.CATEGORY_HOME)
        .setPackage(context.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    // The system already holds this start request, so it brings the home screen up in a
    // fresh process once this one is gone.
    context.startActivity(home)
    (context as? Activity)?.finishAffinity()
    Runtime.getRuntime().exit(0)
}
