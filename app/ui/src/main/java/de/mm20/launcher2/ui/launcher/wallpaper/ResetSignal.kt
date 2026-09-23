package de.mm20.launcher2.ui.launcher.wallpaper

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/**
 * Returns a counter that increments every time the launcher re-enters the resumed state,
 * i.e. every time the screen is unlocked and the launcher comes back to the foreground.
 * Wallpapers can use it to reset their simulation (e.g. re-scatter the solar system).
 */
@Composable
fun rememberResetGeneration(): Int {
    val generation = remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                generation.intValue++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return generation.intValue
}
