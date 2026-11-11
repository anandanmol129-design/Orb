package de.mm20.launcher2.ui.launcher.wallpaper

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import de.mm20.launcher2.preferences.WallpaperType

/**
 * Renders the launcher's active wallpaper. Wallpapers other than the system wallpaper are
 * drawn as a full-screen layer that covers the Android system wallpaper. Add a new entry to
 * [WallpaperType] and a branch here to integrate additional wallpapers.
 *
 * [WallpaperType.Random] shows one of the animated wallpapers in [RandomPool] and picks a
 * different one every time the launcher comes back to the foreground, e.g. after an unlock.
 */
@Composable
fun LauncherWallpaper(
    type: WallpaperType,
    modifier: Modifier = Modifier,
) {
    val active = if (type == WallpaperType.Random) rememberRandomWallpaperType() else type
    when (active) {
        WallpaperType.System, WallpaperType.Random -> Unit
        WallpaperType.Water -> WaterWallpaper(modifier)
        WallpaperType.Prism -> PrismWallpaper(modifier)
        WallpaperType.SolarSystem -> SolarSystemWallpaper(modifier)
        WallpaperType.Boids -> BoidsWallpaper(modifier)
        WallpaperType.DoublePendulum -> DoublePendulumWallpaper(modifier)
        WallpaperType.Games -> GamesWallpaper(modifier)
        WallpaperType.Strings -> StringsWallpaper(modifier)
        WallpaperType.Aurora -> AuroraWallpaper(modifier)
        WallpaperType.MagneticField -> MagneticFieldWallpaper(modifier)
        WallpaperType.FluidInk -> FluidInkWallpaper(modifier)
        WallpaperType.GravitySand -> GravitySandWallpaper(modifier)
        WallpaperType.FlowField -> FlowFieldWallpaper(modifier)
        WallpaperType.ReactionDiffusion -> ReactionDiffusionWallpaper(modifier)
        WallpaperType.Tesseract -> TesseractWallpaper(modifier)
        WallpaperType.Topographic -> TopographicWallpaper(modifier)
    }
}

/** Wallpapers [WallpaperType.Random] chooses from. Games is left out: it is an app, not a backdrop. */
private val RandomPool = listOf(
    WallpaperType.Water,
    WallpaperType.Prism,
    WallpaperType.SolarSystem,
    WallpaperType.Boids,
    WallpaperType.DoublePendulum,
    WallpaperType.Strings,
    WallpaperType.Aurora,
    WallpaperType.MagneticField,
    WallpaperType.FluidInk,
    WallpaperType.GravitySand,
    WallpaperType.FlowField,
    WallpaperType.ReactionDiffusion,
    WallpaperType.Tesseract,
    WallpaperType.Topographic,
)

/** A new pick on every resume, never the same wallpaper twice in a row. */
@Composable
private fun rememberRandomWallpaperType(): WallpaperType {
    val generation = rememberResetGeneration()
    val last = remember { arrayOfNulls<WallpaperType>(1) }
    return remember(generation) {
        RandomPool.filter { it != last[0] }.random().also { last[0] = it }
    }
}
