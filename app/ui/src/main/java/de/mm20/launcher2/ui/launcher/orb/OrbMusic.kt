package de.mm20.launcher2.ui.launcher.orb

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.audiofx.Visualizer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.palette.graphics.Palette
import de.mm20.launcher2.music.MusicService
import de.mm20.launcher2.music.PlaybackState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow

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

/** What the orb knows about the current media session. */
@Immutable
data class OrbMedia(
    /** A media session exists that can be controlled (playing or paused). */
    val hasSession: Boolean = false,
    val playing: Boolean = false,
    val title: String? = null,
    val artist: String? = null,
    val art: ImageBitmap? = null,
    /** A vivid colour picked from [art], for outlines, glow and the control ring. */
    val accent: Color? = null,
)

/**
 * The current media session from [MusicService] (needs notification access), with the cover
 * art's accent colour extracted off the main thread. Falls back to "something is playing" from
 * the audio manager when no session is visible.
 */
@Composable
fun rememberOrbMedia(): State<OrbMedia> {
    val musicService: MusicService = koinInject()
    val musicActive = rememberMusicPlaying()
    val art = produceState<Pair<ImageBitmap, Color?>?>(null, musicService) {
        var last: Bitmap? = null
        musicService.albumArt.collect { bmp ->
            if (bmp == null) {
                // Track changes briefly report no art; don't flash back to the plain body.
                delay(600)
                last = null
                value = null
                return@collect
            }
            if (last != null && last!!.sameAs(bmp)) return@collect
            last = bmp
            value = withContext(Dispatchers.Default) {
                val soft = if (bmp.config == Bitmap.Config.HARDWARE) {
                    bmp.copy(Bitmap.Config.ARGB_8888, false)
                } else bmp
                val accent = runCatching {
                    val p = Palette.from(soft).maximumColorCount(16).generate()
                    val swatch = p.vibrantSwatch ?: p.lightVibrantSwatch ?: p.dominantSwatch
                    swatch?.rgb?.let { Color(it) }
                }.getOrNull()
                soft.asImageBitmap() to accent
            }
        }
    }
    val session = produceState(OrbMedia(), musicService) {
        combine(
            musicService.playbackState,
            musicService.title,
            musicService.artist,
        ) { state, title, artist ->
            OrbMedia(
                hasSession = state != PlaybackState.Stopped,
                playing = state == PlaybackState.Playing,
                title = title,
                artist = artist,
            )
        }.collect { value = it }
    }
    return remember {
        derivedStateOf {
            val s = session.value
            val a = art.value
            s.copy(
                playing = s.playing || musicActive.value,
                art = if (s.hasSession) a?.first else null,
                accent = if (s.hasSession) a?.second else null,
            )
        }
    }
}

/**
 * Live spectrum of whatever the device is playing, from the global output mix
 * ([Visualizer] on session 0). Needs RECORD_AUDIO, which the voice assistant already asks for;
 * without it [live] stays false and the orb falls back to its synthetic groove.
 *
 * [levels] holds [BANDS] values in 0..1, low frequencies first, written from the visualizer's
 * thread and read once per frame while drawing.
 */
class OrbAudio {
    val levels = FloatArray(BANDS)

    @Volatile
    var live = false
        internal set

    // Per-band running peak for auto gain, so quiet and loud tracks both fill the edge.
    private val peaks = FloatArray(BANDS) { 0.25f }
    private var edges: IntArray? = null

    internal fun onFft(fft: ByteArray) {
        val bins = fft.size / 2
        val e = edges?.takeIf { it.size == BANDS + 1 } ?: bandEdges(bins).also { edges = it }
        for (b in 0 until BANDS) {
            var sum = 0f
            val from = e[b]
            val to = max(e[b + 1], from + 1)
            for (k in from until to) {
                val re = fft[2 * k].toFloat()
                val im = fft[2 * k + 1].toFloat()
                sum += hypot(re, im)
            }
            val mag = ln(1f + sum / (to - from)) / LN_MAX
            // Peak decays slowly; level is magnitude relative to it, with a floor so silence
            // stays flat instead of amplifying noise.
            peaks[b] = max(mag, peaks[b] * 0.995f).coerceAtLeast(0.18f)
            levels[b] = ((mag / peaks[b]) - 0.15f).coerceIn(0f, 1f).pow(1.4f)
        }
    }

    internal fun clear() {
        live = false
        levels.fill(0f)
    }

    private fun bandEdges(bins: Int): IntArray {
        // Log-spaced from ~40 Hz up to ~11 kHz of the ~0..22 kHz FFT range.
        val lo = 2f
        val hi = bins * 0.5f
        return IntArray(BANDS + 1) { i ->
            (lo * (hi / lo).pow(i / BANDS.toFloat())).toInt().coerceIn(1, bins - 1)
        }
    }

    companion object {
        const val BANDS = 24
        private val LN_MAX = ln(1f + 128f)
    }
}

/** Runs the system output visualizer while [enabled]; see [OrbAudio]. */
@Composable
fun rememberOrbAudio(enabled: Boolean): OrbAudio {
    val context = LocalContext.current
    val audio = remember { OrbAudio() }
    val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    DisposableEffect(enabled, granted) {
        var visualizer: Visualizer? = null
        if (enabled && granted) {
            visualizer = runCatching {
                Visualizer(0).apply {
                    captureSize = Visualizer.getCaptureSizeRange()[1].coerceAtMost(1024)
                    setDataCaptureListener(
                        object : Visualizer.OnDataCaptureListener {
                            override fun onWaveFormDataCapture(v: Visualizer?, w: ByteArray?, r: Int) {}
                            override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, r: Int) {
                                if (fft != null) {
                                    audio.onFft(fft)
                                    audio.live = true
                                }
                            }
                        },
                        Visualizer.getMaxCaptureRate(),
                        false,
                        true,
                    )
                    this.enabled = true
                }
            }.getOrNull()
        }
        onDispose {
            runCatching {
                visualizer?.enabled = false
                visualizer?.release()
            }
            audio.clear()
        }
    }
    return audio
}

private const val MUSIC_POLL_MS = 250L
