package de.mm20.launcher2.ui.launcher.wallpaper

/**
 * What the voice assistant and the media stream are doing right now, mirrored here by the Orb
 * home component so wallpapers (which live outside its composition) can react to it. Plain
 * volatile fields, read once per frame from a wallpaper's frame loop, like [WallpaperPointer].
 */
object WallpaperVoice {
    const val IDLE = 0
    const val LISTENING = 1
    const val THINKING = 2
    const val SUCCESS = 3
    const val ERROR = 4

    /** One of [IDLE], [LISTENING], [THINKING], [SUCCESS], [ERROR]. */
    @Volatile
    var state = IDLE

    /** Normalised microphone level (0..1); only non-zero while [state] is [LISTENING]. */
    @Volatile
    var level = 0f

    /** True while anything is playing on the music stream. No amplitude is available for it. */
    @Volatile
    var musicPlaying = false
}
