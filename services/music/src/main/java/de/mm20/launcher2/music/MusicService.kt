package de.mm20.launcher2.music

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState as AndroidPlaybackState
import android.media.session.PlaybackState.CustomAction
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import androidx.core.content.edit
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.size.Scale
import de.mm20.launcher2.crashreporter.CrashReporter
import de.mm20.launcher2.notifications.Notification
import de.mm20.launcher2.notifications.NotificationRepository
import de.mm20.launcher2.preferences.media.MediaSettings
import de.mm20.launcher2.preferences.media.MediaSettingsData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Live snapshot of the single currently selected media session, with no persisted fallback.
 */
data class ActiveMediaSession(
    val sessionToken: Any,
    val packageName: String,
    val playbackState: PlaybackState,
    val isTransitioning: Boolean = false,
    val title: String?,
    val artist: String?,
    val album: String?,
    val albumArt: Bitmap?,
)

interface MusicService {
    val playbackState: Flow<PlaybackState>
    val title: Flow<String?>
    val artist: Flow<String?>
    val album: Flow<String?>
    val albumArt: Flow<Bitmap?>
    val position: Flow<Long?>
    val duration: Flow<Long?>

    /** Live state and artwork of the winning media session, or null when no session exists. */
    val activeSession: Flow<ActiveMediaSession?>

    val supportedActions: Flow<SupportedActions>

    val lastPlayerPackage: String?

    fun next()
    fun previous()
    fun pause()
    fun play()
    fun togglePause()
    fun seekTo(position: Long)

    /** Seeks [deltaMs] from the live position (negative for back), clamped to the track. */
    fun seekBy(deltaMs: Long)
    fun performCustomAction(action: CustomAction)
    fun openPlayer(): PendingIntent?

    fun openPlayerChooser(context: Context)

    suspend fun getInstalledPlayerPackages(): Set<String>

    fun resetPlayer()

    /** Re-queries active media sessions and current controller state (e.g. on home resume). */
    fun refresh()
}

@OptIn(ExperimentalCoroutinesApi::class)
internal class MusicServiceImpl(
    private val context: Context,
    private val notificationRepository: NotificationRepository,
    private val settings: MediaSettings,
) : MusicService {

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, t ->
            CrashReporter.logException(t as? Exception ?: RuntimeException(t))
        }
    )

    private val preferences: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    private val refreshTriggers = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val destroyedSessionTriggers = MutableSharedFlow<MediaSession.Token>(extraBufferCapacity = 16)

    override var lastPlayerPackage: String? = null
        get() {
            if (field == null) {
                field = preferences.getString(PREFS_KEY_LAST_PLAYER, null)
            }
            return field
        }
        set(value) {
            preferences.edit {
                putString(PREFS_KEY_LAST_PLAYER, value)
            }
            field = value
        }

    private sealed interface SessionEvent {
        data class ListenerConnected(val connected: Boolean) : SessionEvent
        data class ActiveSessions(val controllers: List<MediaController>) : SessionEvent
        data class Notifications(val items: List<Notification>) : SessionEvent
        data class Settings(val data: MediaSettingsData, val knownMusicApps: Set<String>) : SessionEvent
        data class PlaybackChanged(val token: MediaSession.Token, val state: Int) : SessionEvent
        data class SessionDestroyed(val token: MediaSession.Token) : SessionEvent
        data object Refresh : SessionEvent
    }

    private class TrackedController(
        val controller: MediaController,
        val callback: MediaController.Callback,
        var playbackState: Int,
        var lastPlayingAt: Long,
    )

    private data class CandidateInfo(
        val controller: MediaController?,
        val isActiveSession: Boolean,
        val activeSessionIndex: Int,
    )

    private data class ControllerSnapshot(
        val controller: MediaController,
        val playbackState: AndroidPlaybackState?,
        val metadata: MediaMetadata?,
    )

    private data class ResolvedTrack(
        val title: String?,
        val artist: String?,
        val album: String?,
        val art: Bitmap?,
    )

    private val currentMediaController: SharedFlow<MediaController?> = channelFlow {
        val events = Channel<SessionEvent>(Channel.UNLIMITED)
        val mainHandler = Handler(Looper.getMainLooper())
        val listenerComponent = ComponentName(context.packageName, NOTIFICATION_LISTENER)
        val sessionManager = context.getSystemService(MediaSessionManager::class.java)

        var listenerConnected = notificationRepository.isConnected.value
        var listenerRegistered = false
        var activeSessions: List<MediaController> = emptyList()
        var notifications: List<Notification> = emptyList()
        var settingsData = MediaSettingsData(emptySet(), emptySet())
        var knownMusicApps: Set<String> = emptySet()
        val tracked = LinkedHashMap<MediaSession.Token, TrackedController>()
        val destroyedTokens = HashSet<MediaSession.Token>()

        val activeListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            events.trySend(SessionEvent.ActiveSessions(controllers.orEmpty()))
        }

        fun queryActiveSessions(): List<MediaController>? {
            val manager = sessionManager ?: return null
            return runCatching { manager.getActiveSessions(listenerComponent) }.getOrNull()
        }

        fun attachSessionListener() {
            val manager = sessionManager ?: return
            if (!listenerRegistered) {
                runCatching {
                    manager.addOnActiveSessionsChangedListener(activeListener, listenerComponent, mainHandler)
                }.onSuccess {
                    listenerRegistered = true
                }
            }
            queryActiveSessions()?.let { activeSessions = it }
        }

        fun detachSessionListener() {
            if (listenerRegistered) {
                runCatching {
                    sessionManager?.removeOnActiveSessionsChangedListener(activeListener)
                }
                listenerRegistered = false
            }
        }

        fun applyEvent(event: SessionEvent) {
            when (event) {
                is SessionEvent.ListenerConnected -> {
                    listenerConnected = event.connected
                    if (event.connected) {
                        attachSessionListener()
                    } else {
                        detachSessionListener()
                        activeSessions = emptyList()
                    }
                }
                is SessionEvent.ActiveSessions -> {
                    activeSessions = event.controllers
                }
                is SessionEvent.Notifications -> {
                    notifications = event.items
                }
                is SessionEvent.Settings -> {
                    settingsData = event.data
                    knownMusicApps = event.knownMusicApps
                }
                is SessionEvent.PlaybackChanged -> {
                    tracked[event.token]?.let { entry ->
                        entry.playbackState = event.state
                        if (isPlayingState(event.state)) {
                            entry.lastPlayingAt = SystemClock.elapsedRealtime()
                        }
                    }
                }
                is SessionEvent.SessionDestroyed -> {
                    destroyedTokens.add(event.token)
                    tracked.remove(event.token)?.let { entry ->
                        runCatching { entry.controller.unregisterCallback(entry.callback) }
                    }
                }
                SessionEvent.Refresh -> {
                    attachSessionListener()
                    val now = SystemClock.elapsedRealtime()
                    for (entry in tracked.values) {
                        val prevState = entry.playbackState
                        val state = runCatching {
                            entry.controller.playbackState?.state
                        }.getOrNull() ?: AndroidPlaybackState.STATE_NONE
                        entry.playbackState = state
                        if (isPlayingState(state) && (!isPlayingState(prevState) || entry.lastPlayingAt == 0L)) {
                            entry.lastPlayingAt = now
                        }
                    }
                }
            }
        }

        suspend fun reconcile() {
            val now = SystemClock.elapsedRealtime()
            val mediaNotifications = notifications.filter {
                (it.mediaSessionToken != null || it.isMediaStyle) &&
                    it.packageName !in settingsData.denyList
            }

            val allRawTokens = HashSet<MediaSession.Token>()
            for (c in activeSessions) {
                allRawTokens.add(c.sessionToken)
            }
            for (n in mediaNotifications) {
                n.mediaSessionToken?.let { allRawTokens.add(it) }
            }
            destroyedTokens.retainAll(allRawTokens)

            val tokenPostTime = HashMap<MediaSession.Token, Long>()
            val pkgPostTime = HashMap<String, Long>()
            for (n in mediaNotifications) {
                n.mediaSessionToken?.let { token ->
                    val prev = tokenPostTime[token]
                    if (prev == null || n.postTime > prev) tokenPostTime[token] = n.postTime
                }
                val prevPkg = pkgPostTime[n.packageName]
                if (prevPkg == null || n.postTime > prevPkg) pkgPostTime[n.packageName] = n.postTime
            }

            val candidateInfo = LinkedHashMap<MediaSession.Token, CandidateInfo>()
            activeSessions.forEachIndexed { index, controller ->
                val token = controller.sessionToken
                if (token !in destroyedTokens && token !in candidateInfo) {
                    candidateInfo[token] = CandidateInfo(
                        controller = controller,
                        isActiveSession = true,
                        activeSessionIndex = index,
                    )
                }
            }
            for (n in mediaNotifications) {
                val token = n.mediaSessionToken ?: continue
                if (token !in destroyedTokens && token !in candidateInfo) {
                    candidateInfo[token] = CandidateInfo(
                        controller = null,
                        isActiveSession = false,
                        activeSessionIndex = Int.MAX_VALUE,
                    )
                }
            }

            // Stop watching controllers that are no longer active or backed by a media notification.
            val staleTokens = tracked.keys.filter { it !in candidateInfo }
            for (token in staleTokens) {
                tracked.remove(token)?.let { entry ->
                    runCatching { entry.controller.unregisterCallback(entry.callback) }
                }
            }

            // Register callbacks before reading initial playback state so no transition is missed.
            for ((token, info) in candidateInfo) {
                if (token in tracked) continue
                val controller = info.controller
                    ?: runCatching { MediaController(context, token) }.getOrNull()
                    ?: continue
                val callback = object : MediaController.Callback() {
                    override fun onPlaybackStateChanged(state: AndroidPlaybackState?) {
                        events.trySend(
                            SessionEvent.PlaybackChanged(
                                token = token,
                                state = state?.state ?: AndroidPlaybackState.STATE_NONE,
                            )
                        )
                    }

                    override fun onSessionDestroyed() {
                        events.trySend(SessionEvent.SessionDestroyed(token))
                    }
                }
                var registered = false
                runCatching {
                    controller.registerCallback(callback, mainHandler)
                    registered = true
                }
                if (!registered) continue
                val initialState = controller.playbackState?.state ?: AndroidPlaybackState.STATE_NONE
                val initialLastPlaying = if (isPlayingState(initialState)) now else 0L
                tracked[token] = TrackedController(
                    controller = controller,
                    callback = callback,
                    playbackState = initialState,
                    lastPlayingAt = initialLastPlaying,
                )
            }

            val candidates = candidateInfo.mapNotNull { (token, info) ->
                val entry = tracked[token] ?: return@mapNotNull null
                val pkg = entry.controller.packageName
                SessionCandidate(
                    controller = entry.controller,
                    packageName = pkg,
                    playbackState = entry.playbackState,
                    isActiveSession = info.isActiveSession,
                    activeSessionIndex = info.activeSessionIndex,
                    notificationPostTime = tokenPostTime[token] ?: pkgPostTime[pkg] ?: 0L,
                    lastPlayingAt = entry.lastPlayingAt,
                    isKnownMusicApp = pkg in knownMusicApps,
                    isDenied = pkg in settingsData.denyList,
                )
            }

            send(selectPreferredSession(candidates)?.controller)
        }

        if (listenerConnected) {
            attachSessionListener()
        }

        launch {
            notificationRepository.isConnected.collect {
                events.send(SessionEvent.ListenerConnected(it))
            }
        }
        launch {
            notificationRepository.notifications.collect {
                events.send(SessionEvent.Notifications(it))
            }
        }
        launch {
            settings.collect { data ->
                val known = getInstalledPlayerPackages().union(data.allowList)
                events.send(SessionEvent.Settings(data, known))
            }
        }
        launch {
            refreshTriggers.collect {
                events.send(SessionEvent.Refresh)
            }
        }
        launch {
            destroyedSessionTriggers.collect {
                events.send(SessionEvent.SessionDestroyed(it))
            }
        }

        try {
            for (first in events) {
                applyEvent(first)
                while (true) {
                    val next = events.tryReceive().getOrNull() ?: break
                    applyEvent(next)
                }
                reconcile()
            }
        } finally {
            detachSessionListener()
            for (entry in tracked.values) {
                runCatching { entry.controller.unregisterCallback(entry.callback) }
            }
            tracked.clear()
            events.close()
        }
    }
        .distinctUntilChanged { old, new -> old?.sessionToken == new?.sessionToken }
        .map { controller ->
            controller?.also { lastPlayerPackage = it.packageName }
        }
        .shareIn(scope, SharingStarted.WhileSubscribed(5000L), 1)

    private val currentSnapshot: SharedFlow<ControllerSnapshot?> = channelFlow {
        val mainHandler = Handler(Looper.getMainLooper())
        currentMediaController.collectLatest { controller ->
            if (controller == null) {
                send(null)
                return@collectLatest
            }
            val stateLock = Any()
            var latestState: AndroidPlaybackState? = null
            var latestMeta: MediaMetadata? = null
            var destroyed = false

            val callback = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: AndroidPlaybackState?) {
                    val snap = synchronized(stateLock) {
                        if (destroyed) return
                        latestState = state
                        ControllerSnapshot(controller, latestState, latestMeta)
                    }
                    trySend(snap)
                }

                override fun onMetadataChanged(metadata: MediaMetadata?) {
                    val snap = synchronized(stateLock) {
                        if (destroyed) return
                        latestMeta = metadata
                        ControllerSnapshot(controller, latestState, latestMeta)
                    }
                    trySend(snap)
                }

                override fun onSessionDestroyed() {
                    synchronized(stateLock) {
                        destroyed = true
                    }
                    destroyedSessionTriggers.tryEmit(controller.sessionToken)
                    trySend(null)
                }
            }

            var registered = false
            try {
                runCatching {
                    controller.registerCallback(callback, mainHandler)
                    registered = true
                }
                val initial = synchronized(stateLock) {
                    latestState = controller.playbackState
                    latestMeta = controller.metadata
                    ControllerSnapshot(controller, latestState, latestMeta)
                }
                trySend(initial)
                refreshTriggers.collect {
                    val freshState = runCatching { controller.playbackState }.getOrNull()
                    val freshMeta = runCatching { controller.metadata }.getOrNull()
                    val snapshotPair = synchronized(stateLock) {
                        if (destroyed) null else latestState to latestMeta
                    } ?: return@collect
                    val (prevState, prevMeta) = snapshotPair
                    val stateChanged =
                        prevState?.state != freshState?.state || prevState?.actions != freshState?.actions
                    val metaChanged = !isSameMetadata(prevMeta, freshMeta)
                    if (stateChanged || metaChanged) {
                        val updated = synchronized(stateLock) {
                            if (!destroyed && latestState === prevState && latestMeta === prevMeta) {
                                if (stateChanged) latestState = freshState
                                if (metaChanged) latestMeta = freshMeta
                                ControllerSnapshot(controller, latestState, latestMeta)
                            } else {
                                null
                            }
                        }
                        if (updated != null) {
                            trySend(updated)
                        }
                    }
                }
            } finally {
                if (registered) {
                    runCatching { controller.unregisterCallback(callback) }
                }
            }
        }
    }
        .buffer(Channel.UNLIMITED)
        .shareIn(scope, SharingStarted.WhileSubscribed(5000L), 1)

    private val currentState: Flow<AndroidPlaybackState?> =
        currentSnapshot
            .map { it?.playbackState }
            .distinctUntilChanged { a, b -> a === b }

    override val playbackState: SharedFlow<PlaybackState> = channelFlow {
        currentState.collectLatest { state ->
            send(mapPlaybackState(state?.state))
        }
    }.shareIn(scope, SharingStarted.WhileSubscribed(), 1)

    private var lastPosition: Long? = null
        get() {
            if (field == null) {
                field = preferences.getLong(PREFS_KEY_POSITION, -1).takeIf { it >= 0 }
            }
            return field
        }
        set(value) {
            preferences.edit {
                putLong(PREFS_KEY_POSITION, value ?: -1)
            }
            field = value
        }

    override val position: SharedFlow<Long?> = channelFlow {
        currentState.collectLatest { state ->
            if (state == null || state.state != AndroidPlaybackState.STATE_PLAYING) {
                send(lastPosition)
                return@collectLatest
            }
            if (state.position < 0 || state.lastPositionUpdateTime == 0L) {
                send(null)
                lastPosition = null
                return@collectLatest
            }
            while (isActive) {
                val offset = SystemClock.elapsedRealtime() - state.lastPositionUpdateTime
                val position = state.position + offset
                lastPosition = position
                send(position)
                delay(1000)
            }
        }
    }.shareIn(scope, SharingStarted.WhileSubscribed(), 1)

    private var lastTitle: String? = null
        get() {
            if (field == null) {
                field = preferences.getString(PREFS_KEY_TITLE, null)
            }
            return field
        }
        set(value) {
            preferences.edit {
                putString(PREFS_KEY_TITLE, value)
            }
            field = value
        }

    override val title: Flow<String?> = channelFlow {
        currentSnapshot
            .distinctUntilChanged { a, b ->
                a?.controller?.sessionToken == b?.controller?.sessionToken && a?.metadata == b?.metadata
            }
            .collectLatest { snapshot ->
                if (snapshot == null) {
                    send(lastTitle)
                    return@collectLatest
                }
                val metadata = snapshot.metadata
                val title = metadata?.let {
                    it.getString(MediaMetadata.METADATA_KEY_TITLE)
                        ?: it.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
                } ?: getAppLabel(snapshot.controller.packageName)?.let {
                    context.getString(
                        R.string.music_widget_default_title,
                        it
                    )
                }
                lastTitle = title
                send(title)
            }
    }.shareIn(scope, SharingStarted.WhileSubscribed(), 1)

    private var lastArtist: String? = null
        get() {
            if (field == null) {
                field = preferences.getString(PREFS_KEY_ARTIST, null)
            }
            return field
        }
        set(value) {
            preferences.edit {
                putString(PREFS_KEY_ARTIST, value)
            }
            field = value
        }

    override val artist: Flow<String?> = channelFlow {
        currentSnapshot
            .distinctUntilChanged { a, b ->
                a?.controller?.sessionToken == b?.controller?.sessionToken && a?.metadata == b?.metadata
            }
            .collectLatest { snapshot ->
                if (snapshot == null) {
                    send(lastArtist)
                    return@collectLatest
                }
                val metadata = snapshot.metadata
                val artist = metadata?.let {
                    it.getString(MediaMetadata.METADATA_KEY_ARTIST)
                        ?: it.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
                } ?: getAppLabel(snapshot.controller.packageName)
                lastArtist = artist
                send(artist)
            }
    }.shareIn(scope, SharingStarted.WhileSubscribed(), 1)

    private var lastAlbum: String? = null
        get() {
            if (field == null) {
                field = preferences.getString(PREFS_KEY_ALBUM, null)
            }
            return field
        }
        set(value) {
            preferences.edit {
                putString(PREFS_KEY_ALBUM, value)
            }
            field = value
        }

    override val album: Flow<String?> = channelFlow {
        currentSnapshot
            .distinctUntilChanged { a, b ->
                a?.controller?.sessionToken == b?.controller?.sessionToken && a?.metadata == b?.metadata
            }
            .collectLatest { snapshot ->
                if (snapshot == null) {
                    send(lastAlbum)
                    return@collectLatest
                }
                val album = snapshot.metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM)
                lastAlbum = album
                send(album)
            }
    }.shareIn(scope, SharingStarted.WhileSubscribed(), 1)

    override val albumArt: Flow<Bitmap?> = channelFlow {
        val size = context.resources.getDimensionPixelSize(R.dimen.album_art_size)
        var lastBitmap: Bitmap? = null
        var hasEmitted = false
        currentSnapshot
            .distinctUntilChanged { a, b ->
                a?.controller?.sessionToken == b?.controller?.sessionToken && a?.metadata == b?.metadata
            }
            .collectLatest { snapshot ->
                if (snapshot == null) {
                    val isNull = preferences.getString(PREFS_KEY_ALBUM_ART, "null") == "null"
                    val bmp: Bitmap? = if (isNull) {
                        null
                    } else {
                        withContext(Dispatchers.IO) {
                            val file = java.io.File(context.filesDir, "album_art")
                            if (!file.exists()) return@withContext null
                            val request = ImageRequest.Builder(context)
                                .data(file)
                                .size(size)
                                .allowHardware(false)
                                .build()
                            runCatching {
                                context.imageLoader.execute(request).drawable?.toBitmap()
                            }.getOrNull()
                        }
                    }
                    if (!hasEmitted || !sameBitmapSafe(bmp, lastBitmap)) {
                        lastBitmap = bmp
                        hasEmitted = true
                        send(bmp)
                    }
                    return@collectLatest
                }
                val metadata = snapshot.metadata
                val bitmap = if (metadata == null) {
                    null
                } else {
                    extractInlineBitmap(metadata, size)
                        ?: extractArtUri(metadata)?.let { loadBitmapFromUri(it, size) }
                }

                if (hasEmitted && sameBitmapSafe(bitmap, lastBitmap)) {
                    return@collectLatest
                }
                lastBitmap = bitmap
                hasEmitted = true
                send(bitmap)
                withContext(Dispatchers.IO) {
                    runCatching {
                        if (bitmap == null) {
                            preferences.edit {
                                putString(PREFS_KEY_ALBUM_ART, "null")
                            }
                        } else {
                            val file = java.io.File(context.filesDir, "album_art")
                            file.outputStream().use {
                                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                            }
                            preferences.edit {
                                putString(PREFS_KEY_ALBUM_ART, "notnull")
                            }
                        }
                    }
                }
            }
    }.shareIn(scope, SharingStarted.WhileSubscribed(), 1)

    override val activeSession: Flow<ActiveMediaSession?> = channelFlow {
        val size = context.resources.getDimensionPixelSize(R.dimen.album_art_size)
        currentSnapshot
            .map { it?.controller }
            .distinctUntilChanged { old, new -> old?.sessionToken == new?.sessionToken }
            .collectLatest { controller ->
                if (controller == null) {
                    send(null)
                    return@collectLatest
                }
                val controllerSnapshots = currentSnapshot
                    .filterNotNull()
                    .filter { it.controller.sessionToken == controller.sessionToken }

                val stateFlow = controllerSnapshots
                    .map { it.playbackState }
                    .distinctUntilChangedBy { it?.state }

                var cachedUri: Uri? = null
                var cachedUriBitmap: Bitmap? = null
                var cachedTitle: String? = null
                var cachedArtist: String? = null
                var cachedInlineBitmap: Bitmap? = null
                var isFirstForController = true

                val trackFlow = controllerSnapshots
                    .map { it.metadata }
                    .distinctUntilChanged()
                    .transformLatest { metadata ->
                        if (metadata == null) {
                            cachedUri = null
                            cachedUriBitmap = null
                            cachedTitle = null
                            cachedArtist = null
                            cachedInlineBitmap = null
                            isFirstForController = false
                            emit(ResolvedTrack(null, null, null, null))
                            return@transformLatest
                        }
                        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
                            ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
                            ?: getAppLabel(controller.packageName)?.let {
                                context.getString(R.string.music_widget_default_title, it)
                            }
                        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
                            ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
                            ?: getAppLabel(controller.packageName)
                        val album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM)

                        val rawInline = extractRawInlineBitmap(metadata)
                        if (rawInline != null) {
                            cachedUri = null
                            cachedUriBitmap = null
                            cachedTitle = null
                            cachedArtist = null
                            val resized = resizeBitmap(rawInline, size)
                            val stable = if (resized != null && sameBitmapSafe(cachedInlineBitmap, resized)) {
                                cachedInlineBitmap
                            } else {
                                cachedInlineBitmap = resized
                                resized
                            }
                            isFirstForController = false
                            emit(ResolvedTrack(title, artist, album, stable))
                            return@transformLatest
                        }

                        cachedInlineBitmap = null
                        val artUri = extractArtUri(metadata)
                        if (artUri == null) {
                            cachedUri = null
                            cachedUriBitmap = null
                            cachedTitle = null
                            cachedArtist = null
                            isFirstForController = false
                            emit(ResolvedTrack(title, artist, album, null))
                            return@transformLatest
                        }

                        val reusable = isNetworkUri(artUri) &&
                            artUri == cachedUri &&
                            cachedUriBitmap != null &&
                            !cachedUriBitmap!!.isRecycled &&
                            title == cachedTitle &&
                            artist == cachedArtist
                        if (reusable) {
                            isFirstForController = false
                            emit(ResolvedTrack(title, artist, album, cachedUriBitmap))
                            return@transformLatest
                        }

                        cachedUri = null
                        cachedUriBitmap = null
                        cachedTitle = null
                        cachedArtist = null
                        val firstForController = isFirstForController
                        isFirstForController = false
                        var resolvedArt: Bitmap? = null
                        if (firstForController) {
                            emit(ResolvedTrack(title, artist, album, null))
                            val loaded = loadBitmapFromUri(artUri, size)
                            if (loaded != null) {
                                resolvedArt = loaded
                                emit(ResolvedTrack(title, artist, album, loaded))
                            }
                        } else {
                            coroutineScope {
                                val deferred = async { loadBitmapFromUri(artUri, size) }
                                val fast = withTimeoutOrNull(ART_FETCH_WAIT_MS) { deferred.await() }
                                if (deferred.isCompleted) {
                                    resolvedArt = fast
                                    emit(ResolvedTrack(title, artist, album, fast))
                                } else {
                                    emit(ResolvedTrack(title, artist, album, null))
                                    val loaded = deferred.await()
                                    if (loaded != null) {
                                        resolvedArt = loaded
                                        emit(ResolvedTrack(title, artist, album, loaded))
                                    }
                                }
                            }
                        }
                        if (resolvedArt == null && isNetworkUri(artUri)) {
                            delay(ART_RETRY_DELAY_MS)
                            resolvedArt = loadBitmapFromUri(artUri, size)
                            if (resolvedArt != null) {
                                emit(ResolvedTrack(title, artist, album, resolvedArt))
                            }
                        }
                        if (resolvedArt != null) {
                            cachedUri = artUri
                            cachedUriBitmap = resolvedArt
                            cachedTitle = title
                            cachedArtist = artist
                        }
                    }

                combine(stateFlow, trackFlow) { state, track ->
                    ActiveMediaSession(
                        sessionToken = controller.sessionToken,
                        packageName = controller.packageName,
                        playbackState = mapPlaybackState(state?.state),
                        isTransitioning = isTransitioningState(state?.state ?: AndroidPlaybackState.STATE_NONE),
                        title = track.title,
                        artist = track.artist,
                        album = track.album,
                        albumArt = track.art,
                    )
                }.collect { send(it) }
            }
    }
        .distinctUntilChanged()
        .shareIn(scope, SharingStarted.WhileSubscribed(5000L), 1)

    private var lastDuration: Long? = null
        get() {
            if (field == null) {
                field = preferences.getLong(PREFS_KEY_DURATION, -1).takeIf { it > 0 }
            }
            return field
        }
        set(value) {
            preferences.edit {
                putLong(PREFS_KEY_DURATION, value ?: -1)
            }
            field = value
        }

    override val duration: Flow<Long?> = channelFlow {
        currentSnapshot
            .distinctUntilChanged { a, b ->
                a?.controller?.sessionToken == b?.controller?.sessionToken && a?.metadata == b?.metadata
            }
            .collectLatest { snapshot ->
                if (snapshot == null) {
                    send(lastDuration)
                    return@collectLatest
                }
                val duration = snapshot.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.takeIf { it > 0 }
                lastDuration = duration
                send(duration)
            }
    }.shareIn(scope, SharingStarted.WhileSubscribed(), 1)

    override val supportedActions: Flow<SupportedActions> = channelFlow {
        currentState.collectLatest { state ->
            if (state == null) {
                send(SupportedActions())
                return@collectLatest
            }
            send(SupportedActions(state.actions, state.customActions))
        }
    }.shareIn(scope, SharingStarted.WhileSubscribed(), 1)

    private fun sameBitmapSafe(a: Bitmap?, b: Bitmap?): Boolean {
        if (a === b) return true
        if (a == null || b == null || a.isRecycled || b.isRecycled) return false
        if (a.width != b.width || a.height != b.height) return false
        return runCatching { a.sameAs(b) }.getOrDefault(false)
    }

    private fun isSameMetadata(a: MediaMetadata?, b: MediaMetadata?): Boolean {
        if (a === b) return true
        if (a == null || b == null) return false
        return a.getString(MediaMetadata.METADATA_KEY_MEDIA_ID) == b.getString(MediaMetadata.METADATA_KEY_MEDIA_ID) &&
            a.getString(MediaMetadata.METADATA_KEY_TITLE) == b.getString(MediaMetadata.METADATA_KEY_TITLE) &&
            a.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) == b.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) &&
            a.getString(MediaMetadata.METADATA_KEY_ARTIST) == b.getString(MediaMetadata.METADATA_KEY_ARTIST) &&
            a.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE) == b.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE) &&
            a.getString(MediaMetadata.METADATA_KEY_ALBUM) == b.getString(MediaMetadata.METADATA_KEY_ALBUM) &&
            a.getLong(MediaMetadata.METADATA_KEY_DURATION) == b.getLong(MediaMetadata.METADATA_KEY_DURATION) &&
            a.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI) == b.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI) &&
            a.getString(MediaMetadata.METADATA_KEY_ART_URI) == b.getString(MediaMetadata.METADATA_KEY_ART_URI) &&
            a.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI) == b.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI) &&
            sameBitmapSafe(extractRawInlineBitmap(a), extractRawInlineBitmap(b))
    }

    private fun extractRawInlineBitmap(metadata: MediaMetadata): Bitmap? =
        metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)

    private fun extractInlineBitmap(metadata: MediaMetadata, size: Int): Bitmap? =
        extractRawInlineBitmap(metadata)?.let { resizeBitmap(it, size) }

    private fun extractArtUri(metadata: MediaMetadata): Uri? {
        val raw = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
            ?.takeIf { it.isNotBlank() }
            ?: metadata.getString(MediaMetadata.METADATA_KEY_ART_URI)
                ?.takeIf { it.isNotBlank() }
            ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)
                ?.takeIf { it.isNotBlank() }
            ?: return null
        return Uri.parse(raw)
    }

    private fun isNetworkUri(uri: Uri): Boolean =
        uri.scheme.equals("http", ignoreCase = true) || uri.scheme.equals("https", ignoreCase = true)

    private suspend fun loadBitmapFromUri(uri: Uri, size: Int): Bitmap? {
        return withContext(Dispatchers.IO) {
            try {
                val bypassCache = uri.scheme?.lowercase() in LOCAL_URI_SCHEMES
                val request = ImageRequest.Builder(context)
                    .data(uri)
                    .size(size)
                    .scale(Scale.FILL)
                    .allowHardware(false)
                    .apply {
                        if (bypassCache) {
                            memoryCachePolicy(CachePolicy.DISABLED)
                            diskCachePolicy(CachePolicy.DISABLED)
                        }
                    }
                    .build()
                context.imageLoader.execute(request).drawable?.toBitmap()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                CrashReporter.logException(e)
                null
            }
        }
    }

    private fun resizeBitmap(bitmap: Bitmap, size: Int): Bitmap? {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0 || size <= 0) return null
        val soft = if (bitmap.config == Bitmap.Config.HARDWARE) {
            bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: return null
        } else {
            bitmap
        }
        val w = soft.width
        val h = soft.height
        val minSide = minOf(w, h)
        val scale = if (minSide > size) size.toFloat() / minSide else 1f
        var dstW = (w * scale).toInt().coerceAtLeast(1)
        var dstH = (h * scale).toInt().coerceAtLeast(1)
        val maxCap = size * 3
        val maxSide = maxOf(dstW, dstH)
        if (maxSide > maxCap) {
            val capScale = maxCap.toFloat() / maxSide
            dstW = (dstW * capScale).toInt().coerceAtLeast(1)
            dstH = (dstH * capScale).toInt().coerceAtLeast(1)
        }
        if (dstW == w && dstH == h) return soft
        return Bitmap.createScaledBitmap(soft, dstW, dstH, true)
    }

    private fun getAppLabel(packageName: String): String? {
        return try {
            context
                .packageManager
                .getPackageInfo(packageName, 0).applicationInfo!!
                .loadLabel(context.packageManager).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }

    override fun previous() {
        scope.launch {
            val controller = currentMediaController.firstOrNull()
            if (controller != null) {
                controller.transportControls.skipToPrevious()
            } else {
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val downEvent = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                audioManager.dispatchMediaKeyEvent(downEvent)
                val upEvent = KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                audioManager.dispatchMediaKeyEvent(upEvent)
            }
        }
    }

    override fun next() {
        scope.launch {
            val controller = currentMediaController.firstOrNull()
            if (controller != null) {
                controller.transportControls.skipToNext()
            } else {
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val downEvent = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT)
                audioManager.dispatchMediaKeyEvent(downEvent)
                val upEvent = KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_NEXT)
                audioManager.dispatchMediaKeyEvent(upEvent)
            }
        }
    }

    override fun play() {
        scope.launch {
            val controller = currentMediaController.firstOrNull()
            if (controller != null) {
                controller.transportControls.play()
            } else {
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val downEvent = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY)
                audioManager.dispatchMediaKeyEvent(downEvent)
                val upEvent = KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY)
                audioManager.dispatchMediaKeyEvent(upEvent)
            }
        }
    }

    override fun pause() {
        scope.launch {
            val controller = currentMediaController.firstOrNull()
            if (controller != null) {
                controller.transportControls.pause()
            } else {
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val downEvent = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE)
                audioManager.dispatchMediaKeyEvent(downEvent)
                val upEvent = KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE)
                audioManager.dispatchMediaKeyEvent(upEvent)
            }
        }
    }

    override fun togglePause() {
        scope.launch {
            val controller = currentMediaController.firstOrNull()
            if (controller != null && controller.playbackState?.state == AndroidPlaybackState.STATE_PLAYING) {
                pause()
            } else {
                play()
            }
        }
    }

    private val Notification.isMediaStyle: Boolean
        get() = extras.getString(android.app.Notification.EXTRA_TEMPLATE)
            ?.contains("MediaStyle") == true

    override fun seekBy(deltaMs: Long) {
        scope.launch {
            val controller = currentMediaController.firstOrNull() ?: return@launch
            val state = controller.playbackState ?: return@launch
            if (state.position < 0) return@launch
            var now = state.position
            if (state.state == AndroidPlaybackState.STATE_PLAYING &&
                state.lastPositionUpdateTime > 0
            ) {
                now += ((SystemClock.elapsedRealtime() - state.lastPositionUpdateTime) *
                    state.playbackSpeed).toLong()
            }
            var target = (now + deltaMs).coerceAtLeast(0L)
            val duration = controller.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
            if (duration > 0) target = target.coerceAtMost(duration - 1000L).coerceAtLeast(0L)
            controller.transportControls.seekTo(target)
        }
    }

    override fun seekTo(position: Long) {
        scope.launch {
            val controller = currentMediaController.firstOrNull()
            controller?.transportControls?.seekTo(position)
        }
    }

    override fun openPlayer(): PendingIntent? {
        val controller = currentMediaController.replayCache.firstOrNull()

        controller?.sessionActivity?.let {
            return it
        }

        val packageName = controller?.packageName ?: lastPlayerPackage

        val intent = packageName?.let {
            context.packageManager.getLaunchIntentForPackage(it)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } ?: return null

        if (context.packageManager.resolveActivity(intent, 0) == null) {
            return null
        }

        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    override fun openPlayerChooser(context: Context) {
        context.startActivity(
            Intent.createChooser(
                Intent("android.intent.action.MUSIC_PLAYER")
                    .apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    },
                null
            )
        )
    }

    override fun performCustomAction(action: CustomAction) {
        scope.launch {
            val controller = currentMediaController.firstOrNull()
            controller?.transportControls?.sendCustomAction(action.action, action.extras)
        }
    }

    override suspend fun getInstalledPlayerPackages(): Set<String> {
        val apps = mutableSetOf<String>()
        withContext(Dispatchers.IO) {
            var intent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_APP_MUSIC) }
            apps.addAll(context.packageManager.queryIntentActivities(intent, 0)
                .map { it.activityInfo.applicationInfo.packageName })
            intent = Intent("android.intent.action.MUSIC_PLAYER")
            apps.addAll(context.packageManager.queryIntentActivities(intent, 0)
                .map { it.activityInfo.applicationInfo.packageName })
        }
        return apps
    }

    override fun resetPlayer() {
        scope.launch {
            preferences.edit {
                clear()
            }
        }
    }

    override fun refresh() {
        refreshTriggers.tryEmit(Unit)
    }

    companion object {
        private const val NOTIFICATION_LISTENER = "de.mm20.launcher2.notifications.NotificationService"

        private const val ART_FETCH_WAIT_MS = 400L
        private const val ART_RETRY_DELAY_MS = 2000L
        private val LOCAL_URI_SCHEMES = setOf("content", "file", "android.resource")

        private const val PREFS = "music"
        private const val PREFS_KEY_TITLE = "title"
        private const val PREFS_KEY_DURATION = "duration"
        private const val PREFS_KEY_POSITION = "position"
        private const val PREFS_KEY_ARTIST = "artist"
        private const val PREFS_KEY_ALBUM = "album"
        private const val PREFS_KEY_ALBUM_ART = "album_art"
        private const val PREFS_KEY_LAST_PLAYER = "last_player"
    }
}

enum class PlaybackState {
    Paused,
    Playing,
    Stopped
}
