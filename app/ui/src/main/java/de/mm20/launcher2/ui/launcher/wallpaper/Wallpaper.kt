package de.mm20.launcher2.ui.launcher.wallpaper

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import de.mm20.launcher2.preferences.WallpaperType

/**
 * Renders the launcher's active wallpaper. Wallpapers other than the system wallpaper are
 * drawn as a full-screen layer that covers the Android system wallpaper. Add a new entry to
 * [WallpaperType] and a branch here to integrate additional wallpapers.
 */
@Composable
fun LauncherWallpaper(
    type: WallpaperType,
    modifier: Modifier = Modifier,
) {
    when (type) {
        WallpaperType.System -> Unit
        WallpaperType.Water -> WaterWallpaper(modifier)
        WallpaperType.Prism -> PrismWallpaper(modifier)
        WallpaperType.SolarSystem -> SolarSystemWallpaper(modifier)
        WallpaperType.Boids -> BoidsWallpaper(modifier)
        WallpaperType.Mandelbrot -> MandelbrotWallpaper(modifier)
        WallpaperType.DoublePendulum -> DoublePendulumWallpaper(modifier)
    }
}
