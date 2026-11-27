package de.mm20.launcher2.music

import android.media.session.PlaybackState as AndroidPlaybackState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaSessionSelectorTest {

    private fun candidate(
        id: String,
        packageName: String = "com.example.$id",
        playbackState: Int = AndroidPlaybackState.STATE_PAUSED,
        isActiveSession: Boolean = true,
        activeSessionIndex: Int = 0,
        notificationPostTime: Long = 1000L,
        lastPlayingAt: Long = 0L,
        isKnownMusicApp: Boolean = false,
        isDenied: Boolean = false,
    ) = SessionCandidate(
        controller = id,
        packageName = packageName,
        playbackState = playbackState,
        isActiveSession = isActiveSession,
        activeSessionIndex = activeSessionIndex,
        notificationPostTime = notificationPostTime,
        lastPlayingAt = lastPlayingAt,
        isKnownMusicApp = isKnownMusicApp,
        isDenied = isDenied,
    )

    @Test
    fun playingNonMusicAppBeatsPausedKnownMusicApp() {
        val pausedMusic = candidate(
            id = "spotify",
            playbackState = AndroidPlaybackState.STATE_PAUSED,
            activeSessionIndex = 0,
            notificationPostTime = 5000L,
            lastPlayingAt = 4000L,
            isKnownMusicApp = true,
        )
        val playingVideo = candidate(
            id = "youtube",
            playbackState = AndroidPlaybackState.STATE_PLAYING,
            activeSessionIndex = 1,
            notificationPostTime = 1000L,
            lastPlayingAt = 4500L,
            isKnownMusicApp = false,
        )

        val winner = selectPreferredSession(listOf(pausedMusic, playingVideo))

        assertEquals("youtube", winner?.controller)
    }

    @Test
    fun playingAppWithOlderNotificationPostTimeBeatsPausedAppWithNewerPostTime() {
        val pausedNewerNotif = candidate(
            id = "paused",
            playbackState = AndroidPlaybackState.STATE_PAUSED,
            notificationPostTime = 9000L,
            lastPlayingAt = 2000L,
        )
        val playingOlderNotif = candidate(
            id = "playing",
            playbackState = AndroidPlaybackState.STATE_PLAYING,
            notificationPostTime = 100L,
            lastPlayingAt = 3000L,
        )

        val winner = selectPreferredSession(listOf(pausedNewerNotif, playingOlderNotif))

        assertEquals("playing", winner?.controller)
    }

    @Test
    fun moreRecentlyStartedPlayingAppBeatsEarlierPlayingApp() {
        val earlierPlaying = candidate(
            id = "earlier",
            playbackState = AndroidPlaybackState.STATE_PLAYING,
            activeSessionIndex = 0,
            lastPlayingAt = 1000L,
        )
        val recentPlaying = candidate(
            id = "recent",
            playbackState = AndroidPlaybackState.STATE_PLAYING,
            activeSessionIndex = 1,
            lastPlayingAt = 5000L,
        )

        val winner = selectPreferredSession(listOf(earlierPlaying, recentPlaying))

        assertEquals("recent", winner?.controller)
    }

    @Test
    fun transitioningBeatsPausedAndPlayingBeatsTransitioning() {
        val paused = candidate(
            id = "paused",
            playbackState = AndroidPlaybackState.STATE_PAUSED,
            lastPlayingAt = 4000L,
        )
        val buffering = candidate(
            id = "buffering",
            playbackState = AndroidPlaybackState.STATE_BUFFERING,
            lastPlayingAt = 1000L,
        )

        assertEquals("buffering", selectPreferredSession(listOf(paused, buffering))?.controller)

        val playing = candidate(
            id = "playing",
            playbackState = AndroidPlaybackState.STATE_PLAYING,
            lastPlayingAt = 500L,
        )

        assertEquals("playing", selectPreferredSession(listOf(paused, buffering, playing))?.controller)
    }

    @Test
    fun pausedSessionTieBreakersPreferLastPlayingActiveSessionIndexAndPostTime() {
        val olderPaused = candidate(
            id = "older",
            playbackState = AndroidPlaybackState.STATE_PAUSED,
            isActiveSession = true,
            activeSessionIndex = 0,
            notificationPostTime = 5000L,
            lastPlayingAt = 1000L,
        )
        val recentPaused = candidate(
            id = "recent",
            playbackState = AndroidPlaybackState.STATE_PAUSED,
            isActiveSession = false,
            activeSessionIndex = Int.MAX_VALUE,
            notificationPostTime = 1000L,
            lastPlayingAt = 2000L,
        )
        assertEquals("recent", selectPreferredSession(listOf(olderPaused, recentPaused))?.controller)

        val notifOnly = candidate(
            id = "notifOnly",
            playbackState = AndroidPlaybackState.STATE_PAUSED,
            isActiveSession = false,
            activeSessionIndex = Int.MAX_VALUE,
            notificationPostTime = 9000L,
            lastPlayingAt = 0L,
        )
        val activeSecond = candidate(
            id = "activeSecond",
            playbackState = AndroidPlaybackState.STATE_PAUSED,
            isActiveSession = true,
            activeSessionIndex = 1,
            notificationPostTime = 100L,
            lastPlayingAt = 0L,
        )
        val activeFirst = candidate(
            id = "activeFirst",
            playbackState = AndroidPlaybackState.STATE_PAUSED,
            isActiveSession = true,
            activeSessionIndex = 0,
            notificationPostTime = 50L,
            lastPlayingAt = 0L,
        )
        assertEquals("activeFirst", selectPreferredSession(listOf(notifOnly, activeSecond, activeFirst))?.controller)
    }

    @Test
    fun deniedPackagesAreExcludedEvenWhilePlaying() {
        val deniedPlaying = candidate(
            id = "denied",
            playbackState = AndroidPlaybackState.STATE_PLAYING,
            lastPlayingAt = 9999L,
            isDenied = true,
        )
        val allowedPaused = candidate(
            id = "allowed",
            playbackState = AndroidPlaybackState.STATE_PAUSED,
            lastPlayingAt = 100L,
            isDenied = false,
        )

        assertEquals("allowed", selectPreferredSession(listOf(deniedPlaying, allowedPaused))?.controller)
        assertNull(selectPreferredSession(listOf(deniedPlaying)))
        assertNull(selectPreferredSession<String>(emptyList()))
    }
}
