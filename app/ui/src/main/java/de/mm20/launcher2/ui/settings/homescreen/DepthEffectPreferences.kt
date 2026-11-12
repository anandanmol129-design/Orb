package de.mm20.launcher2.ui.settings.homescreen

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import de.mm20.launcher2.preferences.WallpaperType
import de.mm20.launcher2.preferences.ui.UiSettings
import de.mm20.launcher2.ui.R
import de.mm20.launcher2.ui.component.preferences.Preference
import de.mm20.launcher2.ui.component.preferences.SwitchPreference
import de.mm20.launcher2.ui.launcher.wallpaper.depth.DepthWallpaperStore
import de.mm20.launcher2.ui.launcher.wallpaper.depth.SegmenterNotReadyException
import de.mm20.launcher2.ui.launcher.wallpaper.depth.SubjectSegmenter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.koin.compose.getKoin
import org.koin.compose.koinInject

/**
 * Depth effect for the System wallpaper: pick a photo, its subject is cut out once and drawn in
 * front of the clock. Hidden when the build has no [SubjectSegmenter] (F-Droid).
 */
@Composable
fun DepthEffectPreferences(wallpaperType: WallpaperType) {
    val koin = getKoin()
    val segmenter = remember { koin.getOrNull<SubjectSegmenter>() } ?: return
    val uiSettings: UiSettings = koinInject()
    val enabled by uiSettings.wallpaperDepthEffect.collectAsState(initial = false)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var processing by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        processing = true
        scope.launch {
            try {
                DepthWallpaperStore.setPhoto(context, uri, segmenter)
                uiSettings.setWallpaperDepthEffect(true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: SegmenterNotReadyException) {
                Toast.makeText(context, R.string.wallpaper_depth_downloading, Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                android.util.Log.e("DepthEffect", "Cutout failed", e)
                Toast.makeText(context, R.string.wallpaper_depth_error, Toast.LENGTH_LONG).show()
            } finally {
                processing = false
            }
        }
    }
    val pick = {
        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    AnimatedVisibility(wallpaperType == WallpaperType.System) {
        Column {
            SwitchPreference(
                title = stringResource(R.string.preference_wallpaper_depth_effect),
                summary = stringResource(R.string.preference_wallpaper_depth_effect_summary),
                value = enabled,
                onValueChanged = {
                    if (it && !DepthWallpaperStore.hasPhoto(context)) pick()
                    else uiSettings.setWallpaperDepthEffect(it)
                },
            )
            AnimatedVisibility(enabled || processing) {
                Preference(
                    title = stringResource(R.string.preference_wallpaper_depth_photo),
                    summary = stringResource(
                        if (processing) R.string.preference_wallpaper_depth_processing
                        else R.string.preference_wallpaper_depth_photo_summary
                    ),
                    enabled = !processing,
                    onClick = { pick() },
                )
            }
        }
    }
}
