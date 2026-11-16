package de.mm20.launcher2.ui.settings.homescreen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import de.mm20.launcher2.preferences.WallpaperType
import de.mm20.launcher2.preferences.ui.UiSettings
import de.mm20.launcher2.ui.R
import de.mm20.launcher2.ui.component.preferences.Preference
import de.mm20.launcher2.ui.component.preferences.SwitchPreference
import de.mm20.launcher2.ui.launcher.wallpaper.depth.DepthWallpaperEditor
import de.mm20.launcher2.ui.launcher.wallpaper.depth.DepthWallpaperStore
import de.mm20.launcher2.ui.launcher.wallpaper.depth.SubjectSegmenter
import org.koin.compose.getKoin
import org.koin.compose.koinInject

/**
 * Depth effect for the System wallpaper: pick a photo, frame it in a live preview, and its
 * subject is drawn in front of the clock. Hidden when the build has no [SubjectSegmenter] (F-Droid).
 */
@Composable
fun DepthEffectPreferences(wallpaperType: WallpaperType) {
    val koin = getKoin()
    val segmenter = remember { koin.getOrNull<SubjectSegmenter>() } ?: return
    val uiSettings: UiSettings = koinInject()
    val enabled by uiSettings.wallpaperDepthEffect.collectAsState(initial = false)
    val context = LocalContext.current
    var editing by remember { mutableStateOf(false) }

    if (editing) {
        DepthWallpaperEditor(segmenter = segmenter, onDismiss = { editing = false })
    }

    AnimatedVisibility(wallpaperType == WallpaperType.System) {
        Column {
            SwitchPreference(
                title = stringResource(R.string.preference_wallpaper_depth_effect),
                summary = stringResource(R.string.preference_wallpaper_depth_effect_summary),
                value = enabled,
                onValueChanged = {
                    if (it && !DepthWallpaperStore.hasPhoto(context)) editing = true
                    else uiSettings.setWallpaperDepthEffect(it)
                },
            )
            AnimatedVisibility(enabled) {
                Preference(
                    title = stringResource(R.string.preference_wallpaper_depth_photo),
                    summary = stringResource(R.string.preference_wallpaper_depth_photo_summary),
                    onClick = { editing = true },
                )
            }
        }
    }
}
