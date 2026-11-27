package de.mm20.launcher2.ui.launcher.orb

import androidx.compose.ui.graphics.Color
import de.mm20.launcher2.music.PlaybackState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OrbMusicTest {

    private fun session(
        token: Any = "token-1",
        pkg: String = "com.example.music",
        state: PlaybackState = PlaybackState.Playing,
        transitioning: Boolean = false,
        title: String? = "Track",
        artist: String? = "Artist",
        accent: Color? = Color.Red,
        trackKey: Any? = token to title,
    ) = ProcessedSession(
        sessionToken = token,
        packageName = pkg,
        playbackState = state,
        isTransitioning = transitioning,
        title = title,
        artist = artist,
        art = null,
        accent = accent,
        trackKey = trackKey,
    )

    @Test
    fun decideSessionPlayback_coversTransitionsAndColdStarts() {
        val playing = session(token = "t1", state = PlaybackState.Playing)
        assertEquals(
            SessionArtDecision.ShowNow,
            decideSessionPlayback(playing, wasShowingSession = false, lastPlayingToken = null),
        )
        assertEquals(
            SessionArtDecision.HideNow,
            decideSessionPlayback(null, wasShowingSession = true, lastPlayingToken = "t1"),
        )

        val coldPaused = session(token = "t1", state = PlaybackState.Paused)
        assertEquals(
            SessionArtDecision.HideNow,
            decideSessionPlayback(coldPaused, wasShowingSession = false, lastPlayingToken = null),
        )

        val sameBuffering = session(token = "t1", state = PlaybackState.Stopped, transitioning = true)
        assertEquals(
            SessionArtDecision.GraceBuffering,
            decideSessionPlayback(sameBuffering, wasShowingSession = true, lastPlayingToken = "t1"),
        )

        val samePaused = session(token = "t1", state = PlaybackState.Paused)
        assertEquals(
            SessionArtDecision.GracePause,
            decideSessionPlayback(samePaused, wasShowingSession = true, lastPlayingToken = "t1"),
        )

        val sameStopped = session(token = "t1", state = PlaybackState.Stopped, transitioning = false)
        assertEquals(
            SessionArtDecision.HideNow,
            decideSessionPlayback(sameStopped, wasShowingSession = true, lastPlayingToken = "t1"),
        )

        val crossSessionPaused = session(token = "t2", state = PlaybackState.Paused)
        assertEquals(
            SessionArtDecision.HideNow,
            decideSessionPlayback(crossSessionPaused, wasShowingSession = true, lastPlayingToken = "t1"),
        )
    }

    @Test
    fun buildOrbMedia_neverLeaksPausedArtEvenWhenAudioFallbackIsPlaying() {
        val paused = session(
            token = "t1",
            state = PlaybackState.Paused,
            title = "Old Song",
            artist = "Old Artist",
            accent = Color.Green,
            trackKey = "old-key",
        )

        val media = buildOrbMedia(
            session = paused,
            sessionPlaying = false,
            audioFallbackPlaying = true,
        )

        assertTrue(media.hasSession)
        assertTrue(media.playing)
        assertNull(media.art)
        assertNull(media.accent)
        assertNull(media.trackKey)
        assertEquals("Old Song", media.title)
        assertEquals("Old Artist", media.artist)
    }

    @Test
    fun buildOrbMedia_showsAccentAndTrackKeyOnlyWhileSessionPlays() {
        val active = session(
            token = "t1",
            state = PlaybackState.Playing,
            title = "Current Song",
            artist = "Current Artist",
            accent = Color.Cyan,
            trackKey = "current-key",
        )

        val playingMedia = buildOrbMedia(
            session = active,
            sessionPlaying = true,
            audioFallbackPlaying = false,
        )
        assertTrue(playingMedia.hasSession)
        assertTrue(playingMedia.playing)
        assertEquals(Color.Cyan, playingMedia.accent)
        assertEquals("current-key", playingMedia.trackKey)

        val stoppedMedia = buildOrbMedia(
            session = null,
            sessionPlaying = false,
            audioFallbackPlaying = false,
        )
        assertFalse(stoppedMedia.hasSession)
        assertFalse(stoppedMedia.playing)
        assertNull(stoppedMedia.art)
        assertNull(stoppedMedia.accent)
        assertNull(stoppedMedia.trackKey)
    }

    @Test
    fun isAudioSignalLive_detectsStaleCallbacksAndSustainedSilence() {
        assertFalse(isAudioSignalLive(live = false, lastFrameAtMs = 1000L, lastSignalAtMs = 1000L, nowMs = 1100L))
        assertFalse(isAudioSignalLive(live = true, lastFrameAtMs = 0L, lastSignalAtMs = 1000L, nowMs = 1100L))
        assertFalse(isAudioSignalLive(live = true, lastFrameAtMs = 1000L, lastSignalAtMs = 0L, nowMs = 1100L))

        assertTrue(isAudioSignalLive(live = true, lastFrameAtMs = 1000L, lastSignalAtMs = 1000L, nowMs = 2000L))
        assertFalse(
            isAudioSignalLive(
                live = true,
                lastFrameAtMs = 1000L,
                lastSignalAtMs = 2500L,
                nowMs = 1000L + VISUALIZER_FRAME_STALE_MS + 1L,
            )
        )
        assertFalse(
            isAudioSignalLive(
                live = true,
                lastFrameAtMs = 4000L,
                lastSignalAtMs = 1000L,
                nowMs = 1000L + VISUALIZER_SILENCE_FALLBACK_MS + 1L,
            )
        )
    }

    @Test
    fun evaluateVisualizerHealth_detectsStaleAndSilentWatchdogThresholds() {
        assertEquals(
            VisualizerHealth.Healthy,
            evaluateVisualizerHealth(
                attachedAtMs = 1000L,
                lastFrameAtMs = 2000L,
                lastSignalAtMs = 2000L,
                nowMs = 3000L,
                staleRebindMs = 4000L,
                silentRebindMs = 15_000L,
            )
        )
        assertEquals(
            VisualizerHealth.StaleCallbacks,
            evaluateVisualizerHealth(
                attachedAtMs = 1000L,
                lastFrameAtMs = 2000L,
                lastSignalAtMs = 2000L,
                nowMs = 6000L,
                staleRebindMs = 4000L,
                silentRebindMs = 15_000L,
            )
        )
        assertEquals(
            VisualizerHealth.SustainedSilence,
            evaluateVisualizerHealth(
                attachedAtMs = 1000L,
                lastFrameAtMs = 16_500L,
                lastSignalAtMs = 1500L,
                nowMs = 17_000L,
                staleRebindMs = 4000L,
                silentRebindMs = 15_000L,
            )
        )
    }

    @Test
    fun orbAudio_onFftTracksLiveSignalFallsBackOnSilenceAndResetsOnClear() {
        val audio = OrbAudio()
        assertFalse(audio.isLive(nowMs = 1000L))

        val activeFft = ByteArray(128) { 40 }
        audio.onFft(activeFft, nowMs = 1000L)
        assertTrue(audio.live)
        assertEquals(1000L, audio.lastFrameAtMs)
        assertEquals(1000L, audio.lastSignalAtMs)
        assertTrue(audio.isLive(nowMs = 2000L))

        val silentFft = ByteArray(128)
        audio.onFft(silentFft, nowMs = 4200L)
        assertEquals(4200L, audio.lastFrameAtMs)
        assertEquals(1000L, audio.lastSignalAtMs)
        assertFalse(audio.isLive(nowMs = 4200L))

        audio.clear()
        assertFalse(audio.live)
        assertEquals(0L, audio.lastFrameAtMs)
        assertEquals(0L, audio.lastSignalAtMs)
        assertFalse(audio.isLive(nowMs = 4300L))
    }
}
