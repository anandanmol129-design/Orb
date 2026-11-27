package de.mm20.launcher2.music

import android.media.session.PlaybackState as AndroidPlaybackState

/**
 * Snapshot of a media session considered when picking the active player for [MusicService].
 */
internal data class SessionCandidate<T>(
    val controller: T,
    val packageName: String,
    val playbackState: Int,
    val isActiveSession: Boolean,
    val activeSessionIndex: Int,
    val notificationPostTime: Long,
    val lastPlayingAt: Long,
    val isKnownMusicApp: Boolean,
    val isDenied: Boolean,
)

/** True when [state] is actively playing audio. */
internal fun isPlayingState(state: Int): Boolean =
    state == AndroidPlaybackState.STATE_PLAYING

/**
 * True when [state] is a transient playback state (buffering, connecting, seeking, or skipping)
 * that may briefly appear between tracks or during playback start/seek.
 */
internal fun isTransitioningState(state: Int): Boolean =
    state == AndroidPlaybackState.STATE_BUFFERING ||
        state == AndroidPlaybackState.STATE_CONNECTING ||
        state == AndroidPlaybackState.STATE_FAST_FORWARDING ||
        state == AndroidPlaybackState.STATE_REWINDING ||
        state == AndroidPlaybackState.STATE_SKIPPING_TO_NEXT ||
        state == AndroidPlaybackState.STATE_SKIPPING_TO_PREVIOUS ||
        state == AndroidPlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM

/** Priority tier for [state]: Playing > Transitioning > Paused > Stopped/None/Error. */
internal fun playbackPriorityTier(state: Int): Int = when {
    isPlayingState(state) -> 3
    isTransitioningState(state) -> 2
    state == AndroidPlaybackState.STATE_PAUSED -> 1
    else -> 0
}

/** Maps an Android [AndroidPlaybackState] integer to Orb's [PlaybackState]. */
internal fun mapPlaybackState(state: Int?): PlaybackState = when (state) {
    AndroidPlaybackState.STATE_PLAYING -> PlaybackState.Playing
    AndroidPlaybackState.STATE_PAUSED -> PlaybackState.Paused
    else -> PlaybackState.Stopped
}

/**
 * Picks the highest-priority non-denied media session from [candidates], or null if none qualify.
 *
 * Playing sessions always beat transitioning or paused sessions regardless of whether the app is a
 * known music player or when its notification was posted.
 */
internal fun <T> selectPreferredSession(candidates: List<SessionCandidate<T>>): SessionCandidate<T>? {
    return candidates
        .asSequence()
        .filter { !it.isDenied }
        .maxWithOrNull(SESSION_COMPARATOR)
}

private val SESSION_COMPARATOR = Comparator<SessionCandidate<*>> { a, b ->
    playbackPriorityTier(a.playbackState).compareTo(playbackPriorityTier(b.playbackState)).takeIf { it != 0 }
        ?: a.lastPlayingAt.compareTo(b.lastPlayingAt).takeIf { it != 0 }
        ?: a.isActiveSession.compareTo(b.isActiveSession).takeIf { it != 0 }
        ?: b.activeSessionIndex.compareTo(a.activeSessionIndex).takeIf { it != 0 }
        ?: a.notificationPostTime.compareTo(b.notificationPostTime).takeIf { it != 0 }
        ?: a.isKnownMusicApp.compareTo(b.isKnownMusicApp)
}
