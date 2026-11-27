package de.mm20.launcher2.ui.launcher.orb

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.audiofx.Visualizer
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
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
import kotlinx.coroutines.flow.collectLatest
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
    /** Stable identifier for the active track so cover art races track real switches. */
    val trackKey: Any? = null,
)

internal data class ProcessedSession(
    val sessionToken: Any,
    val packageName: String,
    val playbackState: PlaybackState,
    val isTransitioning: Boolean,
    val title: String?,
    val artist: String?,
    val art: ImageBitmap?,
    val accent: Color?,
    val trackKey: Any?,
)

internal enum class SessionArtDecision {
    ShowNow,
    GraceBuffering,
    GracePause,
    HideNow,
}

/**
 * Decides whether the orb should immediately show, briefly keep during a transient gap, or hide
 * cover art for [session]. Cover art only ever appears for an actively playing session (or
 * while that same playing session briefly buffers or pauses).
 */
internal fun decideSessionPlayback(
    session: ProcessedSession?,
    wasShowingSession: Boolean,
    lastPlayingToken: Any?,
): SessionArtDecision {
    if (session == null) return SessionArtDecision.HideNow
    if (session.playbackState == PlaybackState.Playing) return SessionArtDecision.ShowNow
    val samePlayingSession = wasShowingSession && lastPlayingToken == session.sessionToken
    if (!samePlayingSession) return SessionArtDecision.HideNow
    return when {
        session.isTransitioning -> SessionArtDecision.GraceBuffering
        session.playbackState == PlaybackState.Paused -> SessionArtDecision.GracePause
        else -> SessionArtDecision.HideNow
    }
}

/**
 * Combines the resolved [session] with [sessionPlaying] and [audioFallbackPlaying].
 * Cover art, accent, and [OrbMedia.trackKey] are strictly tied to [sessionPlaying], so a paused
 * session never leaks its artwork while another app plays audio or while nothing is playing.
 */
internal fun buildOrbMedia(
    session: ProcessedSession?,
    sessionPlaying: Boolean,
    audioFallbackPlaying: Boolean,
): OrbMedia {
    val showSessionArt = sessionPlaying && session != null
    val hasSession = session != null &&
        (session.playbackState != PlaybackState.Stopped || session.isTransitioning)
    return OrbMedia(
        hasSession = hasSession,
        playing = sessionPlaying || audioFallbackPlaying,
        title = session?.title,
        artist = session?.artist,
        art = if (showSessionArt) session.art else null,
        accent = if (showSessionArt) session.accent else null,
        trackKey = if (showSessionArt) session.trackKey else null,
    )
}

/**
 * The current media session from [MusicService] (needs notification access), with the cover
 * art's accent colour extracted off the main thread. Falls back to "something is playing" from
 * the audio manager for the visualizer groove when no session is visible, without showing stale
 * artwork from a paused session.
 */
@Composable
fun rememberOrbMedia(hasMediaAccess: Boolean = true): State<OrbMedia> {
    val musicService: MusicService = koinInject()
    val musicActive = rememberMusicPlaying()
    val currentHasMediaAccess = rememberUpdatedState(hasMediaAccess)

    val liveSession = produceState<ProcessedSession?>(null, musicService) {
        var cachedToken: Any? = null
        var cachedRawBitmap: Bitmap? = null
        var cachedProcessedArt: Pair<ImageBitmap, Color?>? = null
        musicService.activeSession.collectLatest { session ->
            if (session == null) {
                cachedToken = null
                cachedRawBitmap = null
                cachedProcessedArt = null
                value = null
                return@collectLatest
            }
            if (session.sessionToken != cachedToken) {
                cachedToken = session.sessionToken
                cachedRawBitmap = null
                cachedProcessedArt = null
            }
            val bmp = session.albumArt
            val processed = when {
                bmp == null -> {
                    cachedRawBitmap = null
                    cachedProcessedArt = null
                    null
                }
                bmp === cachedRawBitmap || (
                    cachedRawBitmap != null &&
                        !cachedRawBitmap!!.isRecycled &&
                        !bmp.isRecycled &&
                        runCatching { cachedRawBitmap!!.sameAs(bmp) }.getOrDefault(false)
                    ) -> {
                    cachedProcessedArt
                }
                else -> {
                    val extracted = withContext(Dispatchers.Default) {
                        val soft = if (bmp.config == Bitmap.Config.HARDWARE) {
                            bmp.copy(Bitmap.Config.ARGB_8888, false)
                        } else {
                            bmp
                        }
                        // A player's stand-in artwork (a generic note icon) is no cover: keep the face.
                        if (soft == null || isPlaceholderArt(soft)) {
                            null
                        } else {
                            val accent = runCatching {
                                val p = Palette.from(soft).maximumColorCount(16).generate()
                                val swatch = p.vibrantSwatch ?: p.lightVibrantSwatch ?: p.dominantSwatch
                                swatch?.rgb?.let { Color(it) }
                            }.getOrNull()
                            soft.asImageBitmap() to accent
                        }
                    }
                    cachedRawBitmap = bmp
                    cachedProcessedArt = extracted
                    extracted
                }
            }
            val trackKey = session.title?.let { Triple(session.sessionToken, it, session.artist) }
                ?: session.sessionToken
            value = ProcessedSession(
                sessionToken = session.sessionToken,
                packageName = session.packageName,
                playbackState = session.playbackState,
                isTransitioning = session.isTransitioning,
                title = session.title,
                artist = session.artist,
                art = processed?.first,
                accent = processed?.second,
                trackKey = trackKey,
            )
        }
    }

    var sessionPlaying by remember { mutableStateOf(false) }
    var lastPlayingToken by remember { mutableStateOf<Any?>(null) }
    val s = liveSession.value
    LaunchedEffect(s?.sessionToken, s?.playbackState, s?.isTransitioning) {
        when (decideSessionPlayback(s, sessionPlaying, lastPlayingToken)) {
            SessionArtDecision.ShowNow -> {
                lastPlayingToken = s!!.sessionToken
                sessionPlaying = true
            }
            SessionArtDecision.GraceBuffering -> {
                delay(BUFFERING_GRACE_MS)
                sessionPlaying = false
                lastPlayingToken = null
            }
            SessionArtDecision.GracePause -> {
                delay(PAUSE_GRACE_MS)
                sessionPlaying = false
                lastPlayingToken = null
            }
            SessionArtDecision.HideNow -> {
                sessionPlaying = false
                lastPlayingToken = null
            }
        }
    }

    // Suppress Android AudioFlinger's 1-3 s standby tail after a session stops playing so pausing
    // doesn't keep the visualizer moving on stale audio output.
    var awaitingAudioIdle by remember { mutableStateOf(false) }
    val wasSessionPlaying = remember { booleanArrayOf(false) }
    LaunchedEffect(sessionPlaying) {
        val hadPlaying = wasSessionPlaying[0]
        wasSessionPlaying[0] = sessionPlaying
        if (sessionPlaying) {
            awaitingAudioIdle = false
        } else if (hadPlaying) {
            awaitingAudioIdle = true
            delay(AUDIO_STANDBY_TAIL_MS)
            awaitingAudioIdle = false
        }
    }
    LaunchedEffect(musicActive.value) {
        if (!musicActive.value) {
            awaitingAudioIdle = false
        }
    }

    return remember {
        derivedStateOf {
            val audioFallbackPlaying =
                musicActive.value && (!currentHasMediaAccess.value || !awaitingAudioIdle)
            buildOrbMedia(
                session = liveSession.value,
                sessionPlaying = sessionPlaying,
                audioFallbackPlaying = audioFallbackPlaying,
            )
        }
    }
}

internal enum class VisualizerHealth {
    Healthy,
    StaleCallbacks,
    SustainedSilence,
}

/**
 * True while the system [Visualizer] is marked live and has delivered non-silent FFT data within
 * the freshness window. Lets the orb fall back smoothly to its synthetic groove whenever Android
 * stalls callbacks or routes playback through hardware audio offload.
 */
internal fun isAudioSignalLive(
    live: Boolean,
    lastFrameAtMs: Long,
    lastSignalAtMs: Long,
    nowMs: Long,
): Boolean {
    if (!live || lastFrameAtMs <= 0L || lastSignalAtMs <= 0L) return false
    if (nowMs - lastFrameAtMs > VISUALIZER_FRAME_STALE_MS) return false
    if (nowMs - lastSignalAtMs > VISUALIZER_SILENCE_FALLBACK_MS) return false
    return true
}

/**
 * Classifies whether a bound [Visualizer] has stopped delivering callbacks or has been reporting
 * sustained silence long enough to warrant re-attaching it.
 */
internal fun evaluateVisualizerHealth(
    attachedAtMs: Long,
    lastFrameAtMs: Long,
    lastSignalAtMs: Long,
    nowMs: Long,
    staleRebindMs: Long,
    silentRebindMs: Long,
): VisualizerHealth {
    val frameRef = max(attachedAtMs, lastFrameAtMs)
    if (nowMs - frameRef >= staleRebindMs) return VisualizerHealth.StaleCallbacks
    val signalRef = max(attachedAtMs, lastSignalAtMs)
    if (nowMs - signalRef >= silentRebindMs) return VisualizerHealth.SustainedSilence
    return VisualizerHealth.Healthy
}

/**
 * Live spectrum of whatever the device is playing, from the global output mix
 * ([Visualizer] on session 0). Needs RECORD_AUDIO, which the voice assistant already asks for;
 * without it (or when callbacks stall / report silence) [isLive] stays false and the orb falls
 * back to its synthetic groove.
 *
 * [levels] holds [BANDS] values in 0..1, low frequencies first, written from the visualizer's
 * thread and read once per frame while drawing.
 */
class OrbAudio {
    val levels = FloatArray(BANDS)

    @Volatile
    var live = false
        internal set

    @Volatile
    internal var lastFrameAtMs: Long = 0L

    @Volatile
    internal var lastSignalAtMs: Long = 0L

    // One running peak shared by all bands (auto gain that keeps the spectrum's shape), and a
    // short per-band average to detect hits.
    private var peak = 0.3f
    private val avg = FloatArray(BANDS)
    private val mags = FloatArray(BANDS)
    private var edges: IntArray? = null

    fun isLive(nowMs: Long = SystemClock.elapsedRealtime()): Boolean =
        isAudioSignalLive(live, lastFrameAtMs, lastSignalAtMs, nowMs)

    internal fun onFft(fft: ByteArray, nowMs: Long = SystemClock.elapsedRealtime()) {
        val bins = fft.size / 2
        if (bins < 2) return
        val e = edges?.takeIf { it.size == BANDS + 1 } ?: bandEdges(bins).also { edges = it }
        var frameMax = 0f
        for (b in 0 until BANDS) {
            var sum = 0f
            val from = e[b]
            val to = max(e[b + 1], from + 1)
            for (k in from until to) {
                val re = fft[2 * k].toFloat()
                val im = fft[2 * k + 1].toFloat()
                sum += hypot(re, im)
            }
            // Highs carry less energy than lows; tilt them up a little so the top of the ring
            // still moves.
            val mag = ln(1f + sum / (to - from)) / LN_MAX * (1f + 0.5f * b / BANDS)
            mags[b] = mag
            if (mag > frameMax) frameMax = mag
        }
        peak = max(frameMax, peak * 0.996f).coerceAtLeast(0.2f)
        for (b in 0 until BANDS) {
            val mag = mags[b]
            // Steady loudness lights a column part way, by how strong the band is against the
            // loudest; a hit (the band jumping above its recent average) shoots it out fully.
            val shape = (((mag / peak) - 0.45f) / 0.55f).coerceIn(0f, 1f).pow(1.5f)
            val hit = (mag - avg[b]).coerceAtLeast(0f) / peak
            avg[b] += (mag - avg[b]) * 0.15f
            // Hits push a column out without snapping it fully, so the ring swells with the
            // beat instead of flickering band by band.
            levels[b] = (shape * 0.7f + hit * 2.5f).coerceIn(0f, 1f)
        }
        lastFrameAtMs = nowMs
        if (frameMax > SIGNAL_EPSILON) {
            lastSignalAtMs = nowMs
        }
        live = true
    }

    internal fun clear() {
        live = false
        levels.fill(0f)
        peak = 0.3f
        avg.fill(0f)
        lastFrameAtMs = 0L
        lastSignalAtMs = 0L
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

/**
 * Runs the system output visualizer while [enabled], with a self-healing watchdog that rebinds
 * `Visualizer(0)` if Android stalls callbacks or drops output to silence during long playback.
 */
@Composable
fun rememberOrbAudio(enabled: Boolean): OrbAudio {
    val context = LocalContext.current
    val audio = remember { OrbAudio() }
    val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED
    LaunchedEffect(enabled, granted) {
        if (!enabled || !granted) {
            audio.clear()
            return@LaunchedEffect
        }
        var visualizer: Visualizer? = null

        fun releaseVisualizer() {
            runCatching {
                visualizer?.enabled = false
                visualizer?.release()
            }
            visualizer = null
        }

        fun attachVisualizer(): Long {
            releaseVisualizer()
            audio.live = false
            visualizer = runCatching {
                Visualizer(0).apply {
                    captureSize = Visualizer.getCaptureSizeRange()[1].coerceAtMost(1024)
                    setDataCaptureListener(
                        object : Visualizer.OnDataCaptureListener {
                            override fun onWaveFormDataCapture(v: Visualizer?, w: ByteArray?, r: Int) {}
                            override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, r: Int) {
                                if (fft != null) {
                                    audio.onFft(fft)
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
            return SystemClock.elapsedRealtime()
        }

        var attachedAt = attachVisualizer()
        var staleBackoffMs = VISUALIZER_REBIND_STALE_BASE_MS
        var silentBackoffMs = VISUALIZER_REBIND_SILENT_BASE_MS
        try {
            while (true) {
                delay(VISUALIZER_WATCHDOG_POLL_MS)
                val now = SystemClock.elapsedRealtime()
                if (audio.isLive(now)) {
                    staleBackoffMs = VISUALIZER_REBIND_STALE_BASE_MS
                    silentBackoffMs = VISUALIZER_REBIND_SILENT_BASE_MS
                    continue
                }
                val effectDisabled =
                    visualizer == null || runCatching { visualizer?.enabled != true }.getOrDefault(true)
                val health = evaluateVisualizerHealth(
                    attachedAtMs = attachedAt,
                    lastFrameAtMs = audio.lastFrameAtMs,
                    lastSignalAtMs = audio.lastSignalAtMs,
                    nowMs = now,
                    staleRebindMs = staleBackoffMs,
                    silentRebindMs = silentBackoffMs,
                )
                if (effectDisabled || health == VisualizerHealth.StaleCallbacks) {
                    attachedAt = attachVisualizer()
                    staleBackoffMs = (staleBackoffMs * 2).coerceAtMost(VISUALIZER_REBIND_STALE_MAX_MS)
                } else if (health == VisualizerHealth.SustainedSilence) {
                    attachedAt = attachVisualizer()
                    silentBackoffMs = (silentBackoffMs * 2).coerceAtMost(VISUALIZER_REBIND_SILENT_MAX_MS)
                }
            }
        } finally {
            releaseVisualizer()
            audio.clear()
        }
    }
    return audio
}

private const val MUSIC_POLL_MS = 250L
private const val BUFFERING_GRACE_MS = 900L
private const val PAUSE_GRACE_MS = 350L
private const val AUDIO_STANDBY_TAIL_MS = 3500L
private const val SIGNAL_EPSILON = 0.005f
internal const val VISUALIZER_FRAME_STALE_MS = 1500L
internal const val VISUALIZER_SILENCE_FALLBACK_MS = 3000L
private const val VISUALIZER_WATCHDOG_POLL_MS = 2000L
private const val VISUALIZER_REBIND_STALE_BASE_MS = 4000L
private const val VISUALIZER_REBIND_STALE_MAX_MS = 30_000L
private const val VISUALIZER_REBIND_SILENT_BASE_MS = 15_000L
private const val VISUALIZER_REBIND_SILENT_MAX_MS = 60_000L

/**
 * Whether [art] is a player's stand-in rather than real cover art: an icon with a see-through
 * background, or a flat, colourless image (e.g. a grey note on a plain backdrop).
 */
internal fun isPlaceholderArt(art: Bitmap): Boolean {
    val n = 24
    val small = Bitmap.createScaledBitmap(art, n, n, true)
    val px = IntArray(n * n)
    small.getPixels(px, 0, n, 0, 0, n, n)
    if (small !== art) small.recycle()
    var seeThrough = 0
    var colourful = 0
    val counts = HashMap<Int, Int>()
    val hsv = FloatArray(3)
    for (c in px) {
        if (android.graphics.Color.alpha(c) < 245) seeThrough++
        android.graphics.Color.colorToHSV(c, hsv)
        if (hsv[1] > 0.25f && hsv[2] > 0.15f) colourful++
        // Coarse colour buckets, to see how much of the image is one flat colour.
        val key = (((c shr 20) and 0xF) shl 8) or (((c shr 12) and 0xF) shl 4) or ((c shr 4) and 0xF)
        counts[key] = (counts[key] ?: 0) + 1
    }
    val total = px.size.toFloat()
    if (seeThrough / total > 0.1f) return true
    val flat = (counts.values.maxOrNull() ?: 0) / total
    return colourful / total < 0.03f && flat > 0.5f
}
