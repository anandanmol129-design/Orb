package de.mm20.launcher2.ui.launcher.orb

import android.content.Context
import android.media.AudioManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay

/**
 * True while anything is playing on the music stream and drives the idle body's groove.
 * Polled, since it is the one signal that covers every player without media-session plumbing.
 */
@Composable
fun rememberMusicPlaying(): State<Boolean> {
    val context = LocalContext.current
    val audioManager = remember(context) {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    return produceState(false, audioManager) {
        while (true) {
            value = audioManager.isMusicActive()
            delay(MUSIC_POLL_MS)
        }
    }
}

private const val MUSIC_POLL_MS = 250L
