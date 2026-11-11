package de.mm20.launcher2.ui.launcher.wallpaper

import android.content.Context
import android.media.AudioManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Whether anything is playing on the music stream, for wallpapers to groove along with, the same
 * signal the Orb uses for its bounce. No amplitude is available without extra permissions, so
 * wallpapers keep time with a fixed groove (see [MusicPulse]) instead of the actual audio.
 *
 * Written by [PollMusicPlaying] (hosted by [LauncherWallpaper]) and read once per frame.
 */
object WallpaperMusic {
    @Volatile
    var playing = false
}

/** Polls the music stream four times a second while a launcher wallpaper is shown. */
@Composable
internal fun PollMusicPlaying() {
    val context = LocalContext.current
    val audioManager = remember(context) {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    LaunchedEffect(audioManager) {
        try {
            while (isActive) {
                WallpaperMusic.playing = audioManager.isMusicActive
                delay(250L)
            }
        } finally {
            WallpaperMusic.playing = false
        }
    }
}

/**
 * The groove every wallpaper shares while music plays: one beat every half second, with the same
 * punch-and-settle shape as the Orb's bounce, faded in and out over about half a second when
 * playback starts and stops.
 *
 * Keep one per wallpaper (in `remember`), call [update] once per frame from the frame loop with
 * the frame time, then read [beat] (a sharp 0..1 thump on every beat, scaled by [groove]) and
 * [groove] (0..1, how much music is playing right now) to drive small, subtle effects. Both are 0
 * when nothing plays. Allocation-free.
 */
class MusicPulse {
    /** 0..1 eased in while music plays and out when it stops. */
    var groove = 0f
        private set

    /** 0..1 thump on each beat, already multiplied by [groove]. */
    var beat = 0f
        private set

    private var lastNanos = 0L
    private var phaseNanos = 0L

    fun update(frameTimeNanos: Long) {
        val dtNanos = if (lastNanos == 0L) 0L else (frameTimeNanos - lastNanos).coerceIn(0L, 100_000_000L)
        lastNanos = frameTimeNanos
        val dt = dtNanos / 1_000_000_000f

        val target = if (WallpaperMusic.playing) 1f else 0f
        groove += (target - groove) * (dt * GROOVE_RATE).coerceAtMost(1f)
        if (groove < 0.001f) {
            groove = 0f
            beat = 0f
            phaseNanos = 0L
            return
        }

        phaseNanos = (phaseNanos + dtNanos) % BEAT_NANOS
        val ms = phaseNanos / 1_000_000f
        val thump = when {
            ms < RISE_MS -> ms / RISE_MS
            ms < FALL_END_MS -> {
                val f = 1f - (ms - RISE_MS) / (FALL_END_MS - RISE_MS)
                f * f
            }
            else -> 0f
        }
        beat = thump * groove
    }

    private companion object {
        const val BEAT_NANOS = 500_000_000L
        const val RISE_MS = 55f
        const val FALL_END_MS = 300f
        const val GROOVE_RATE = 4f
    }
}
