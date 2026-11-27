package de.mm20.launcher2.ui.launcher.orb

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.os.Process
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableLongStateOf
import android.os.SystemClock
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import de.mm20.launcher2.applications.AppRepository
import de.mm20.launcher2.appshortcuts.AppShortcutRepository
import de.mm20.launcher2.ktx.sendWithBackgroundPermission
import de.mm20.launcher2.music.MusicService
import de.mm20.launcher2.permissions.PermissionGroup
import de.mm20.launcher2.permissions.PermissionsManager
import de.mm20.launcher2.orb.MediaArcSize
import de.mm20.launcher2.orb.MediaRingAction
import de.mm20.launcher2.orb.MediaRingLayout
import de.mm20.launcher2.orb.MediaRingSlot
import de.mm20.launcher2.orb.OrbFill
import de.mm20.launcher2.orb.pie.PieActionType
import de.mm20.launcher2.orb.pie.PieItem
import de.mm20.launcher2.orb.pie.PieRepository
import de.mm20.launcher2.preferences.GestureAction
import de.mm20.launcher2.preferences.WidgetScreenTarget
import de.mm20.launcher2.preferences.ui.GestureSettings
import androidx.lifecycle.viewmodel.compose.viewModel
import de.mm20.launcher2.ui.launcher.scaffold.Gesture
import de.mm20.launcher2.ui.launcher.search.SearchVM
import de.mm20.launcher2.ui.launcher.scaffold.LauncherScaffoldState
import de.mm20.launcher2.ui.launcher.scaffold.components.ScaffoldComponent
import de.mm20.launcher2.ui.settings.SettingsActivity
import de.mm20.launcher2.ui.launcher.scaffold.components.WidgetsComponent
import de.mm20.launcher2.ui.launcher.feed.FeedReaderActivity
import de.mm20.launcher2.ui.launcher.widgets.clock.ClockWidget
import de.mm20.launcher2.ui.locals.LocalSnackbarHostState
import de.mm20.launcher2.ui.launcher.wallpaper.WallpaperVoice
import de.mm20.launcher2.voice.VoiceController
import de.mm20.launcher2.voice.VoiceUiState
import de.mm20.launcher2.voice.VoiceSettings
import de.mm20.launcher2.voice.WakeWordListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import java.util.UUID
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Sphere-first home screen.
 *
 * - Tap the sphere to open search.
 * - Press and hold to open the radial menu, then drag and release on a slice.
 * - Releasing inside the sphere's dead zone cancels (or closes a folder).
 * - The Voice slice starts the hybrid voice pipeline; the sphere renders its progress.
 */
internal object OrbHomeComponent : ScaffoldComponent() {

    override val drawBackground: Boolean = false

    // The sphere itself is the search affordance.
    override val showSearchBar: Boolean = false

    override val isAtTop: State<Boolean?> = mutableStateOf(true)
    override val isAtBottom: State<Boolean?> = mutableStateOf(true)

    @Composable
    override fun Component(
        modifier: Modifier,
        insets: PaddingValues,
        state: LauncherScaffoldState,
    ) {
        val context = LocalContext.current
        val density = LocalDensity.current
        val haptics = LocalHapticFeedback.current
        val snackbarHostState = LocalSnackbarHostState.current
        val scope = rememberCoroutineScope()

        val pieRepository: PieRepository = koinInject()
        val appRepository: AppRepository = koinInject()
        val appShortcutRepository: AppShortcutRepository = koinInject()
        val voiceController: VoiceController = koinInject()
        val wakeWordListener: WakeWordListener = koinInject()
        val gestureSettings: GestureSettings = koinInject()

        val tapAction by gestureSettings.tap.collectAsState(initial = GestureAction.NoAction)
        val doubleTapAction by gestureSettings.doubleTap.collectAsState(initial = GestureAction.NoAction)
        val tripleTapAction by gestureSettings.tripleTap.collectAsState(initial = GestureAction.NoAction)
        val longPressAction by gestureSettings.longPress.collectAsState(initial = GestureAction.NoAction)
        val currentTapAction = rememberUpdatedState(tapAction)
        val currentDoubleTapAction = rememberUpdatedState(doubleTapAction)
        val currentTripleTapAction = rememberUpdatedState(tripleTapAction)
        val currentLongPressAction = rememberUpdatedState(longPressAction)

        val voiceState by voiceController.state.collectAsState()
        val amplitude by voiceController.amplitude.collectAsState()
        val transcript by voiceController.transcript.collectAsState()
        val musicService: MusicService = koinInject()
        val permissionsManager: PermissionsManager = koinInject()
        val hasMediaAccess by remember {
            permissionsManager.hasPermission(PermissionGroup.Notifications)
        }.collectAsState(initial = true)
        val media by rememberOrbMedia()

        // Mirror the assistant's state for the wallpaper layer, which sits outside this tree.
        SideEffect {
            WallpaperVoice.level = amplitude
            WallpaperVoice.state = when (voiceState) {
                VoiceUiState.Idle -> WallpaperVoice.IDLE
                VoiceUiState.Listening, is VoiceUiState.Confirm -> WallpaperVoice.LISTENING
                VoiceUiState.Thinking -> WallpaperVoice.THINKING
                is VoiceUiState.Success -> WallpaperVoice.SUCCESS
                is VoiceUiState.Error -> WallpaperVoice.ERROR
            }
        }

        var items by remember { mutableStateOf(pieRepository.items()) }
        // Ids of the folder windows currently open; the last one is on top.
        var openFolders by remember { mutableStateOf(emptyList<String>()) }
        var pieOpen by remember { mutableStateOf(false) }
        // True when the pie was opened by a tap (no finger to track); the next tap selects.
        var pieSticky by remember { mutableStateOf(false) }
        var pointer by remember { mutableStateOf(Offset.Zero) }
        var selected by remember { mutableStateOf(-1) }
        var center by remember { mutableStateOf(Offset.Zero) }
        var pieRadius by remember { mutableStateOf(0f) }
        var deadZone by remember { mutableStateOf(0f) }
        var sleeping by remember { mutableStateOf(false) }
        var aodMode by remember { mutableStateOf(false) }
        val currentAodMode = rememberUpdatedState(aodMode)
        val aodAlpha by animateFloatAsState(
            targetValue = if (aodMode) 1f else 0f,
            animationSpec = tween(250),
            label = "aodAlpha",
        )
        var editMode by remember { mutableStateOf(false) }
        var showAppPicker by remember { mutableStateOf(false) }
        var draggingIcon by remember { mutableStateOf(false) }

        // What the orb reacts to as a creature rather than as a menu: a finger resting on it,
        // each completed tap, each return to the home screen, and a finger anywhere on screen
        // that it can watch. The watched position is only ever read inside the sphere, so
        // following a swipe never recomposes this screen.
        var orbPressed by remember { mutableStateOf(false) }
        var pokeCount by remember { mutableIntStateOf(0) }
        var greetCount by remember { mutableIntStateOf(0) }
        val watched = remember { mutableStateOf<Offset?>(null) }

        var orbCX by remember { mutableStateOf(pieRepository.orbCenterX()) }
        var orbCY by remember { mutableStateOf(pieRepository.orbCenterY()) }
        var orbScale by remember { mutableStateOf(pieRepository.orbScale()) }
        var orbShape by remember { mutableStateOf(pieRepository.orbShape()) }
        var orbFill by remember { mutableStateOf(pieRepository.orbFill()) }

        // Music: the media ring's layout and skip lengths, the slot under the finger, and the
        // live spectrum while something plays (stopped while the orb sleeps).
        var mediaRingEnabled by remember { mutableStateOf(pieRepository.mediaRingEnabled()) }
        var mediaCoverArt by remember { mutableStateOf(pieRepository.mediaCoverArt()) }
        var mediaSlots by remember { mutableStateOf(pieRepository.mediaRing()) }
        var mediaArc by remember { mutableStateOf(pieRepository.mediaArc()) }
        var mediaLayout by remember { mutableStateOf(pieRepository.mediaRingLayout()) }
        var rewindSeconds by remember { mutableIntStateOf(pieRepository.mediaRewindSeconds()) }
        var forwardSeconds by remember { mutableIntStateOf(pieRepository.mediaForwardSeconds()) }
        var mediaSelected by remember { mutableStateOf<Int?>(null) }
        // Direction of the last skip from Orb's own controls; track changes from anywhere else
        // (or long after a skip) count as "next".
        var skipDir by remember { mutableIntStateOf(1) }
        var skipAt by remember { mutableLongStateOf(0L) }
        var mediaPromptDismissed by remember { mutableStateOf(pieRepository.mediaAccessPromptDismissed()) }
        val audio = rememberOrbAudio(enabled = media.playing && !sleeping)
        val showMediaRing = mediaRingEnabled && media.hasSession &&
            (if (mediaLayout == MediaRingLayout.Arc) mediaArc else mediaSlots.values)
                .any { it != MediaRingAction.None }

        // Re-read the orb state when the settings activity returns, since it may have been
        // changed there (shape, fill, menu items) or replaced wholesale by a backup restore.
        val lifecycleOwner = LocalLifecycleOwner.current
        var homeResumed by remember {
            mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        }
        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_PAUSE) homeResumed = false
                if (event == Lifecycle.Event.ON_STOP) aodMode = false
                if (event == Lifecycle.Event.ON_RESUME) {
                    homeResumed = true
                    orbShape = pieRepository.orbShape()
                    orbFill = pieRepository.orbFill()
                    mediaRingEnabled = pieRepository.mediaRingEnabled()
                    mediaCoverArt = pieRepository.mediaCoverArt()
                    mediaSlots = pieRepository.mediaRing()
                    mediaArc = pieRepository.mediaArc()
                    mediaLayout = pieRepository.mediaRingLayout()
                    rewindSeconds = pieRepository.mediaRewindSeconds()
                    forwardSeconds = pieRepository.mediaForwardSeconds()
                    if (!editMode) {
                        items = pieRepository.items()
                        orbCX = pieRepository.orbCenterX()
                        orbCY = pieRepository.orbCenterY()
                        orbScale = pieRepository.orbScale()
                    }
                    greetCount++
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }

        val micPermissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (granted) {
                voiceController.startListening()
            } else {
                scope.launch { snackbarHostState.showSnackbar("Microphone permission is required") }
            }
        }

        // Several contacts matched a spoken call: show them in search to pick from. The
        // query is set once search is open, since opening it clears the previous query.
        // Runs in the screen's scope rather than this effect's, the same way the pie's Search
        // slice opens search.
        val searchVM: SearchVM = viewModel()
        LaunchedEffect(Unit) {
            voiceController.searchRequests.collect { query ->
                scope.launch {
                    state.openSearch()
                    searchVM.search(query)
                    state.isSearchBarFocused = false
                }
            }
        }

        // Clear a transient success/error shortly after it is shown.
        LaunchedEffect(voiceState) {
            if (voiceState is VoiceUiState.Success || voiceState is VoiceUiState.Error) {
                delay(1800)
                voiceController.acknowledge()
            }
        }

        // While editing or in AOD mode, lock the scaffold gestures so swipes don't navigate away.
        LaunchedEffect(editMode, aodMode) {
            state.hideSystemBarsOverride = aodMode
            if (editMode || aodMode) {
                state.lock(hideSearchBar = true)
            } else {
                state.unlock()
            }
        }

        DisposableEffect(aodMode) {
            val window = context.findAppCompatActivity()?.window
            if (aodMode) {
                window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            onDispose {
                window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }

        // Back leaves AOD mode cleanly.
        BackHandler(enabled = aodMode) { aodMode = false }

        // Back - and Home, which the scaffold forwards as back while editing - leaves edit
        // mode. Every change is already saved as it is made, so exiting needs no flush.
        if (editMode) {
            BackHandler { editMode = false }
        }

        // Back stops the assistant while it is listening, in case it was started by accident.
        val voiceListening = voiceState == VoiceUiState.Listening || voiceState is VoiceUiState.Confirm
        BackHandler(enabled = voiceListening) { voiceController.cancel() }

        // Back closes the top folder window, one level at a time.
        BackHandler(enabled = openFolders.isNotEmpty()) { openFolders = openFolders.dropLast(1) }

        fun startVoice() {
            if (ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.RECORD_AUDIO,
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                voiceController.startListening()
            } else {
                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }

        // "Hey Orby": listen for the wake phrase while the home screen itself is showing and
        // the assistant is idle (it needs the microphone for itself). Fetch the speech model
        // on its own the first time, but only over an unmetered connection.
        val voiceSettings: VoiceSettings = koinInject()
        val wakeModel by wakeWordListener.modelState.collectAsState()
        LaunchedEffect(homeResumed) {
            if (homeResumed && voiceSettings.wakeWordEnabled &&
                wakeModel !is WakeWordListener.ModelState.Ready &&
                wakeModel !is WakeWordListener.ModelState.Downloading
            ) {
                val connectivity = context.getSystemService(android.net.ConnectivityManager::class.java)
                if (connectivity?.isActiveNetworkMetered == false) wakeWordListener.downloadModel()
            }
        }
        val listenForWakeWord = homeResumed && voiceState == VoiceUiState.Idle && !editMode &&
            !sleeping && !state.isSettledOnSecondaryPage &&
            wakeModel == WakeWordListener.ModelState.Ready
        LaunchedEffect(listenForWakeWord) {
            if (listenForWakeWord) {
                // Let the assistant's recognizer release the microphone first.
                delay(400)
                wakeWordListener.start {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    startVoice()
                }
            } else {
                wakeWordListener.stop()
            }
        }
        DisposableEffect(Unit) { onDispose { wakeWordListener.stop() } }

        // Launches or opens whatever was picked. Pie slices and folder entries share this.
        fun activate(item: PieItem) {
            aodMode = false
            scope.launch {
                when (item) {
                    is PieItem.Folder -> openFolders = openFolders + item.id

                    is PieItem.App ->
                        launchApp(context, appRepository, item.id)

                    is PieItem.Shortcut ->
                        launchShortcut(context, item)

                    is PieItem.Action -> when (item.type) {
                        PieActionType.Voice -> startVoice()

                        PieActionType.Search -> state.openSearch()

                        PieActionType.Feed -> context.startActivity(
                            Intent(context, FeedReaderActivity::class.java)
                        )

                        PieActionType.Widgets -> state.openComponent(
                            WidgetsComponent.forTarget(widgetsTargetOf(item.widgetsTarget)),
                        )

                        PieActionType.Settings ->
                            context.startActivity(SettingsActivity.orbSettingsIntent(context))

                        PieActionType.Edit -> {
                            editMode = true
                            openFolders = emptyList()
                        }
                    }
                }
            }
        }

        fun performMedia(action: MediaRingAction) {
            when (action) {
                MediaRingAction.None -> {}
                MediaRingAction.PlayPause -> musicService.togglePause()
                MediaRingAction.Previous -> {
                    skipDir = -1
                    skipAt = SystemClock.uptimeMillis()
                    musicService.previous()
                }
                MediaRingAction.Next -> {
                    skipDir = 1
                    skipAt = SystemClock.uptimeMillis()
                    musicService.next()
                }
                MediaRingAction.OpenApp -> {
                    aodMode = false
                    runCatching {
                        musicService.openPlayer()?.sendWithBackgroundPermission(context)
                    }
                }
                MediaRingAction.Rewind -> musicService.seekBy(-rewindSeconds * 1000L)
                MediaRingAction.Forward -> musicService.seekBy(forwardSeconds * 1000L)
                MediaRingAction.Aod -> aodMode = !aodMode
            }
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        }

        // Adds a picked item to the top folder window, or to the pie when none is open.
        fun addItem(item: PieItem) {
            val target = openFolders.lastOrNull()
            val next = if (target == null) {
                items + item
            } else {
                replaceFolder(items, target) { it.copy(children = it.children + item) }
            }
            items = next
            pieRepository.save(next)
            showAppPicker = false
        }

        val orbState = if (sleeping) {
            OrbState.Sleep
        } else when {
            editMode || pieOpen -> OrbState.Pie
            else -> when (voiceState) {
                VoiceUiState.Idle -> OrbState.Idle
                VoiceUiState.Listening -> OrbState.Listening
                VoiceUiState.Thinking -> OrbState.Thinking
                is VoiceUiState.Confirm -> OrbState.Listening
                is VoiceUiState.Success -> OrbState.Success
                is VoiceUiState.Error -> OrbState.Error
            }
        }

        // The gaze follows the finger proportionally: a touch near the middle barely moves the
        // eyes, and they only reach their limit once the finger is out at the slices.
        val lookAt = if (pieOpen && center != Offset.Zero && deadZone > 0f) {
            val dx = pointer.x - center.x
            val dy = pointer.y - center.y
            val len = hypot(dx, dy).coerceAtLeast(1f)
            val reach = (len / deadZone).coerceAtMost(1f)
            Offset(dx / len * reach, dy / len * reach)
        } else {
            Offset.Zero
        }

        BoxWithConstraints(modifier = modifier.fillMaxSize()) {
            val width = constraints.maxWidth.toFloat()
            val height = constraints.maxHeight.toFloat()
            val minSide = minOf(width, height)
            center = Offset(width * orbCX, height * orbCY)
            // Slices must sit clear of the dead zone, which in turn must clear the sphere,
            // otherwise releasing on a slice is read as a cancel.
            // Until the user resizes it, the orb is about 2 cm in radius (126 dp is 2 cm at
            // Android's 160 dpi reference density), kept inside the range edit mode allows.
            val sphereRadius = if (orbScale.isNaN()) {
                with(density) { 126.dp.toPx() }.coerceIn(minSide * 0.14f, minSide * 0.448f)
            } else {
                minSide * 0.28f * orbScale
            }
            pieRadius = sphereRadius * 1.43f
            deadZone = maxOf(sphereRadius + with(density) { 4.dp.toPx() }, sphereRadius * 1.07f)
            // Touches only count within this radius of the orb; the rest of the screen
            // belongs to the scaffold's own gestures.
            val confinedRadius = sphereRadius + with(density) { 20.dp.toPx() }

            // The media ring sits a control's width outside the pie. Near a screen edge its
            // controls move to the part of the ring that is on screen.
            val ringMargin = with(density) { 8.dp.toPx() }
            val ringRadius = pieRadius + with(density) { 74.dp.toPx() }
            val ringItemRadius = with(density) { 33.dp.toPx() }
            val ringBounds = androidx.compose.ui.geometry.Rect(
                left = ringMargin,
                top = with(density) { insets.calculateTopPadding().toPx() } + ringMargin,
                right = width - ringMargin,
                bottom = height - with(density) { insets.calculateBottomPadding().toPx() } - ringMargin,
            )
            val ringGeometry = mediaRingGeometry(
                center = center,
                pieRadius = pieRadius,
                radius = ringRadius,
                itemRadius = ringItemRadius,
                bounds = ringBounds,
                layout = mediaLayout,
                ring = mediaSlots,
                arc = mediaArc,
            )
            val mediaRingSlotPositions = if (editMode && mediaRingEnabled && mediaLayout == MediaRingLayout.Ring) {
                mediaRingAllSlotPositions(
                    center = center,
                    pieRadius = pieRadius,
                    radius = ringRadius,
                    itemRadius = ringItemRadius,
                    bounds = ringBounds,
                )
            } else {
                emptyMap()
            }
            val ringActive = showMediaRing && !sleeping

            // Released on a slice: activate it. The dead zone cancels.
            val handleRelease: (Int) -> Unit = { index ->
                items.getOrNull(index)?.let { activate(it) }
            }

            // Built-in clock widget (configured in Settings → Home screen → Clock), pinned to
            // the top of the screen, with the dock at the bottom. Style "No clock" turns it off.
            // The zIndex sits on this wrapper, a sibling of the orb's full-screen gesture layer:
            // Compose sends a touch only to the topmost sibling hit, so without it the orb layer
            // swallowed every tap on the clock and dock. Empty areas of the wrapper take no
            // touches, so they still reach the orb. While the pie is open or editing, the orb
            // comes first so slices and popped-out media controls over the clock/dock still work.
            if (!aodMode) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(insets)
                        .zIndex(if (pieOpen || editMode) 0f else 1f),
                ) {
                    ClockWidget(modifier = Modifier.fillMaxSize())
                }
            }

            if (aodAlpha > 0.001f) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = aodAlpha)),
                )
            }

            if (editMode) {
                val trashCenter = Offset(
                    width / 2f,
                    height - with(density) { (insets.calculateBottomPadding() + 112.dp).toPx() },
                )
                Box(modifier = Modifier.fillMaxSize()) {
                    OrbSphere(
                        state = orbState,
                        lookAt = Offset.Zero,
                        modifier = Modifier.fillMaxSize(),
                        orbCenter = center,
                        orbRadius = sphereRadius,
                        shape = orbShape,
                        fill = orbFill,
                        playing = media.playing,
                        coverArt = if (mediaCoverArt) media.art else null,
                        trackKey = media.title?.let { it to media.artist },
                        skipDirection = if (SystemClock.uptimeMillis() - skipAt < SkipMemoryMs) skipDir else 1,
                        accent = media.accent,
                        audio = audio,
                    )
                    PieEditMode(
                        items = items,
                        center = center,
                        pieRadius = pieRadius,
                        deadZonePx = deadZone,
                        sphereRadius = sphereRadius,
                        minRadius = minSide * 0.14f,
                        maxRadius = minSide * 0.448f,
                        topInsetPx = with(density) { insets.calculateTopPadding().toPx() + 24.dp.toPx() },
                        bottomInsetPx = with(density) { insets.calculateBottomPadding().toPx() + 24.dp.toPx() },
                        screenHeight = height,
                        trashCenter = trashCenter,
                        onReorder = { reordered ->
                            items = reordered
                            pieRepository.save(reordered)
                        },
                        onRemove = { index ->
                            val next = items.toMutableList().also { it.removeAt(index) }
                            items = next
                            pieRepository.save(next)
                        },
                        onRadiusChange = { newRadius ->
                            orbScale = (newRadius / (minSide * 0.28f)).coerceIn(0.5f, 1.6f)
                            pieRepository.saveOrbLayout(orbCX, orbCY, orbScale)
                        },
                        onCenterYChange = { newY ->
                            orbCY = newY / height
                            pieRepository.saveOrbLayout(orbCX, orbCY, orbScale)
                        },
                        onDragStateChange = { draggingIcon = it },
                        exitTaps = when {
                            tripleTapAction is GestureAction.ToggleEditMode -> 3
                            doubleTapAction is GestureAction.ToggleEditMode -> 2
                            tapAction is GestureAction.ToggleEditMode -> 1
                            else -> 0
                        },
                        onExit = {
                            editMode = false
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        },
                        mediaEnabled = mediaRingEnabled,
                        mediaLayout = mediaLayout,
                        mediaArc = mediaArc,
                        mediaSlots = mediaSlots,
                        mediaGeometry = ringGeometry,
                        mediaRingSlotPositions = mediaRingSlotPositions,
                        rewindSeconds = rewindSeconds,
                        forwardSeconds = forwardSeconds,
                        accent = media.accent,
                        onMediaArcChange = { updated ->
                            mediaArc = updated
                            pieRepository.saveMediaArc(updated)
                        },
                        onMediaRingChange = { updated ->
                            mediaSlots = updated
                            pieRepository.saveMediaRing(updated)
                        },
                    )
                    EditModeActions(
                        dragging = draggingIcon,
                        trashCenter = trashCenter,
                        onDone = { editMode = false },
                        onAddApp = { showAppPicker = true },
                        onEditOrb = {
                            context.startActivity(SettingsActivity.orbSettingsIntent(context))
                        },
                    )
                }
            } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        // Watch every touch on the home screen without taking it, so the orb
                        // can follow a finger that is nowhere near it.
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val change = event.changes.firstOrNull() ?: continue
                                watched.value = if (change.pressed) change.position else null
                            }
                        }
                    }
                    .pointerInput(sleeping, items, confinedRadius, ringActive, ringGeometry) {
                        var tapCount = 0
                        var tapDebounce: Job? = null
                        // How long a tap waits for a follow-up tap. Only paid when a double or
                        // triple tap is actually bound, and measured from finger-up to the next
                        // finger-down.
                        val multiTapWindow = minOf(viewConfiguration.doubleTapTimeoutMillis, 220L)

                        fun actionFor(gesture: Gesture): GestureAction = when (gesture) {
                            Gesture.Tap -> currentTapAction.value
                            Gesture.DoubleTap -> currentDoubleTapAction.value
                            Gesture.TripleTap -> currentTripleTapAction.value
                            Gesture.LongPress -> currentLongPressAction.value
                            else -> GestureAction.NoAction
                        }

                        fun isBound(gesture: Gesture) = actionFor(gesture) !is GestureAction.NoAction

                        // The most taps any binding needs. Once a sequence reaches it there is
                        // nothing left to wait for.
                        fun maxBoundTaps(): Int = when {
                            isBound(Gesture.TripleTap) -> 3
                            isBound(Gesture.DoubleTap) -> 2
                            else -> 1
                        }

                        // The gesture a finished sequence of [count] taps stands for: the
                        // highest bound one it reaches. Two taps with nothing on double tap are
                        // an impatient single tap, not a no-op.
                        fun gestureForTaps(count: Int): Gesture = when {
                            count >= 3 && isBound(Gesture.TripleTap) -> Gesture.TripleTap
                            count >= 2 && isBound(Gesture.DoubleTap) -> Gesture.DoubleTap
                            else -> Gesture.Tap
                        }

                        // Runs after a completed tap sequence. Only ToggleSleep can wake a sleeping orb.
                        fun dispatchTapGesture(gesture: Gesture) {
                            val action = actionFor(gesture)
                            when {
                                action is GestureAction.NoAction -> {}
                                action is GestureAction.ToggleFill -> {
                                    val next = if (orbFill == OrbFill.Filled) OrbFill.Outline else OrbFill.Filled
                                    orbFill = next
                                    pieRepository.saveOrbFill(next)
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                }

                                sleeping && action !is GestureAction.ToggleSleep -> {}
                                action is GestureAction.ToggleEditMode -> {
                                    aodMode = false
                                    editMode = true
                                    openFolders = emptyList()
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                }

                                action is GestureAction.ToggleSleep -> {
                                    sleeping = !sleeping
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                }

                                action is GestureAction.Voice -> startVoice()

                                action is GestureAction.OrbSettings -> context.startActivity(
                                    SettingsActivity.orbSettingsIntent(context)
                                )

                                action is GestureAction.OpenFolder -> {
                                    if (findFolder(items, action.id) != null) {
                                        openFolders = listOf(action.id)
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    } else {
                                        scope.launch {
                                            snackbarHostState.showSnackbar("That folder no longer exists")
                                        }
                                    }
                                }

                                action is GestureAction.OpenPie -> {
                                    pieOpen = true
                                    pieSticky = true
                                    selected = -1
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                }

                                else -> scope.launch { state.onOrbGesture(gesture) }
                            }
                        }

                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            pointer = down.position
                            if ((down.position - center).getDistance() > confinedRadius) {
                                if (currentAodMode.value) {
                                    down.consume()
                                    val up = waitForUpOrCancellation()
                                    if (up != null) {
                                        up.consume()
                                        aodMode = false
                                    }
                                }
                                return@awaitEachGesture
                            }
                            // A folder window is open: its entries and dismiss scrim own the screen.
                            if (openFolders.isNotEmpty()) return@awaitEachGesture
                            down.consume()

                            // Sticky pie (opened by a tap): this tap picks a slice.
                            if (pieOpen && pieSticky) {
                                val up = waitForUpOrCancellation()
                                pieOpen = false
                                pieSticky = false
                                mediaSelected = null
                                if (up != null) {
                                    up.consume()
                                    if (ringActive) {
                                        val slot = mediaRingSelection(up.position, ringGeometry)
                                        if (slot != null) {
                                            selected = -1
                                            performMedia(ringGeometry.actions[slot])
                                            return@awaitEachGesture
                                        }
                                    }
                                    val index = pieSelectionIndex(
                                        pointer = up.position,
                                        center = center,
                                        count = items.size,
                                        deadZonePx = deadZone,
                                    )
                                    selected = -1
                                    handleRelease(index)
                                }
                                return@awaitEachGesture
                            }

                            // A finger is back on the orb: hold the multi-tap countdown until
                            // it lifts, so a slow second tap is not split into two single taps.
                            tapDebounce?.cancel()

                            // The pie is the orb's response to touch: it is up immediately, no
                            // long-press wait. A quick tap that never picks a slice falls through
                            // to the tap gestures (tap opens search by default).
                            if (!sleeping) {
                                pieOpen = true
                                pieSticky = false
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            }
                            orbPressed = true

                            var upTime = 0L
                            var wentUp = false
                            var moved = false
                            try {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change =
                                        event.changes.firstOrNull { it.id == down.id } ?: break
                                    pointer = change.position
                                    if ((change.position - down.position).getDistance() >
                                        viewConfiguration.touchSlop
                                    ) {
                                        moved = true
                                    }
                                    if (!sleeping) {
                                        val slot = if (ringActive) {
                                            mediaRingSelection(pointer, ringGeometry)
                                        } else null
                                        mediaSelected = slot
                                        selected = if (slot != null) -1 else pieSelectionIndex(
                                            pointer = pointer,
                                            center = center,
                                            count = items.size,
                                            deadZonePx = deadZone,
                                        )
                                    }
                                    change.consume()
                                    if (!change.pressed) {
                                        upTime = change.uptimeMillis
                                        wentUp = true
                                        break
                                    }
                                }
                            } finally {
                                orbPressed = false
                                pieOpen = false
                            }

                            val index = if (sleeping) -1 else selected
                            val mediaSlot = if (sleeping) null else mediaSelected
                            selected = -1
                            mediaSelected = null
                            if (!wentUp) {
                                tapCount = 0
                                return@awaitEachGesture
                            }

                            val quick = upTime - down.uptimeMillis <=
                                    viewConfiguration.longPressTimeoutMillis

                            when {
                                // Dragged out to the media ring.
                                mediaSlot != null && moved -> {
                                    tapCount = 0
                                    performMedia(ringGeometry.actions[mediaSlot])
                                }

                                // A short press that stayed put is a tap wherever it landed,
                                // including the orb's rim, which already lies over the slices.
                                // Without this a tap near the edge launched a slice instead.
                                quick && (index < 0 || !moved) -> {
                                    if (currentAodMode.value) {
                                        tapCount = 0
                                        aodMode = false
                                    } else {
                                        pokeCount++
                                        tapCount++
                                        val count = tapCount
                                        if (count >= maxBoundTaps()) {
                                            // No binding needs more taps than this: act now.
                                            tapCount = 0
                                            dispatchTapGesture(gestureForTaps(count))
                                        } else {
                                            tapDebounce = scope.launch {
                                                delay(multiTapWindow)
                                                tapCount = 0
                                                dispatchTapGesture(gestureForTaps(count))
                                            }
                                        }
                                    }
                                }

                                index >= 0 -> {
                                    tapCount = 0
                                    handleRelease(index)
                                }

                                else -> {
                                    // Held without choosing anything: the menu itself was the
                                    // response. Long-press bindings other than the pie still run.
                                    tapCount = 0
                                    if (actionFor(Gesture.LongPress) !is GestureAction.OpenPie) {
                                        dispatchTapGesture(Gesture.LongPress)
                                    }
                                }
                            }
                        }
                    },
            ) {
                OrbSphere(
                    state = orbState,
                    lookAt = lookAt,
                    modifier = Modifier.fillMaxSize(),
                    orbCenter = center,
                    orbRadius = sphereRadius,
                    shape = orbShape,
                    fill = orbFill,
                    playing = media.playing,
                    coverArt = if (mediaCoverArt) media.art else null,
                    trackKey = media.title?.let { it to media.artist },
                    skipDirection = if (SystemClock.uptimeMillis() - skipAt < SkipMemoryMs) skipDir else 1,
                    accent = media.accent,
                    audio = audio,
                    pressed = orbPressed,
                    pokeCount = pokeCount,
                    greetCount = greetCount,
                    attention = {
                        // Direction to the watched finger, at full deflection once it is a few
                        // orb-widths away.
                        watched.value?.let { finger ->
                            val dx = finger.x - center.x
                            val dy = finger.y - center.y
                            val len = hypot(dx, dy).coerceAtLeast(1f)
                            val reach = (len / (sphereRadius * 2.5f)).coerceAtMost(1f)
                            Offset(dx / len * reach, dy / len * reach)
                        }
                    },
                )

                if (pieOpen) {
                    PieMenu(
                        items = items,
                        center = center,
                        radiusPx = pieRadius,
                        selectedIndex = selected,
                        modifier = Modifier.fillMaxSize(),
                    )
                    if (ringActive) {
                        MediaRing(
                            geometry = ringGeometry,
                            selected = mediaSelected,
                            playing = media.playing,
                            rewindSeconds = rewindSeconds,
                            forwardSeconds = forwardSeconds,
                            accent = media.accent,
                            modifier = Modifier.fillMaxSize(),
                            aodActive = aodMode,
                        )
                    }
                }

                // Music is playing but the launcher can't see what: ask for notification access,
                // which cover art, track info and the media ring need.
                if (media.playing && !hasMediaAccess && !mediaPromptDismissed &&
                    orbState == OrbState.Idle && !aodMode
                ) {
                    MediaAccessPrompt(
                        onAllow = {
                            context.findAppCompatActivity()?.let {
                                permissionsManager.requestPermission(it, PermissionGroup.Notifications)
                            }
                        },
                        onDismiss = {
                            mediaPromptDismissed = true
                            pieRepository.saveMediaAccessPromptDismissed(true)
                        },
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .offset {
                                IntOffset(
                                    0,
                                    (center.y + sphereRadius * 1.8f + with(density) {
                                        8.dp.toPx()
                                    }).roundToInt(),
                                )
                            },
                    )
                }

                if (orbState != OrbState.Idle && orbState != OrbState.Pie && orbState != OrbState.Sleep) {
                    OrbWavePill(
                        amplitude = amplitude,
                        transcript = transcript,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .offset {
                                IntOffset(
                                    0,
                                    (center.y + sphereRadius + with(density) {
                                        16.dp.toPx()
                                    }).roundToInt(),
                                )
                            },
                    )
                }

                val label = when {
                    pieOpen && mediaSelected != null -> ringGeometry.actions.getOrNull(mediaSelected!!)
                        ?.hoverLabel(media.playing, rewindSeconds, forwardSeconds, aod = aodMode)
                    pieOpen && selected in items.indices -> items[selected].label
                    pieOpen && ringActive && media.title != null ->
                        listOfNotNull(media.title, media.artist).joinToString(" · ")
                    voiceState is VoiceUiState.Success -> (voiceState as VoiceUiState.Success).message
                    voiceState is VoiceUiState.Error -> (voiceState as VoiceUiState.Error).message
                    voiceState is VoiceUiState.Confirm -> (voiceState as VoiceUiState.Confirm).message
                    else -> null
                }
                label?.let {
                    // On a pill so it stays readable over the clock and any wallpaper.
                    Text(
                        text = it,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = insets.calculateTopPadding() + 48.dp)
                            .padding(horizontal = 24.dp)
                            .background(
                                MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f),
                                RoundedCornerShape(50),
                            )
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                }
            }
            }

            val openFolder = openFolders.lastOrNull()?.let { findFolder(items, it) }
            if (openFolder != null) {
                // Above the clock and dock layer, which otherwise draws over the window.
                Box(modifier = Modifier.fillMaxSize().zIndex(2f)) {
                OrbFolderView(
                    folder = openFolder,
                    onOpen = { activate(it) },
                    onAdd = { showAppPicker = true },
                    onRemove = { index ->
                        val next = replaceFolder(items, openFolder.id) { folder ->
                            folder.copy(
                                children = folder.children.filterIndexed { i, _ -> i != index },
                            )
                        }
                        items = next
                        pieRepository.save(next)
                    },
                    onDismiss = { openFolders = openFolders.dropLast(1) },
                )
                }
            }

            if (showAppPicker) {
                val pickedInto = openFolders.lastOrNull()?.let { findFolder(items, it) }
                val missingMediaActions = if (pickedInto == null && mediaRingEnabled) {
                    val activeMedia = if (mediaLayout == MediaRingLayout.Arc) {
                        mediaArc.filter { it != MediaRingAction.None }.toSet()
                    } else {
                        mediaSlots.values.filter { it != MediaRingAction.None }.toSet()
                    }
                    val hasRoom = if (mediaLayout == MediaRingLayout.Arc) {
                        mediaArc.count { it != MediaRingAction.None } < MediaArcSize
                    } else {
                        MediaRingSlot.entries.any { (mediaSlots[it] ?: MediaRingAction.None) == MediaRingAction.None }
                    }
                    if (hasRoom) {
                        MediaRingAction.entries.filter { it != MediaRingAction.None && it !in activeMedia }
                    } else {
                        emptyList()
                    }
                } else {
                    emptyList()
                }
                OrbAddItemSheet(
                    appRepository = appRepository,
                    appShortcutRepository = appShortcutRepository,
                    existingActions = (pickedInto?.children ?: items)
                        .filterIsInstance<PieItem.Action>()
                        .map { it.type }
                        .toSet(),
                    onDismiss = { showAppPicker = false },
                    onPickApp = { packageName, className, label ->
                        addItem(PieItem.App(appPieId(packageName, className), label))
                    },
                    onPickShortcut = { packageName, shortcutId, label ->
                        addItem(
                            PieItem.Shortcut(
                                id = shortcutPieId(packageName, shortcutId),
                                packageName = packageName,
                                shortcutId = shortcutId,
                                label = label,
                            )
                        )
                    },
                    onPickAction = { type ->
                        addItem(PieItem.Action(type.name.lowercase(), type, type.name))
                    },
                    onPickWidgets = { target ->
                        addItem(
                            PieItem.Action(
                                id = "widgets-${target.name}",
                                type = PieActionType.Widgets,
                                label = "Widgets ${target.name.removePrefix("Widgets")}",
                                widgetsTarget = target.name,
                            )
                        )
                    },
                    onPickFolder = {
                        addItem(PieItem.Folder(folderId(), "Folder"))
                    },
                    availableMediaActions = missingMediaActions,
                    onPickMediaAction = { action ->
                        if (mediaLayout == MediaRingLayout.Arc) {
                            val active = mediaArc.filter { it != MediaRingAction.None }
                            if (active.size < MediaArcSize) {
                                val next = active + action
                                val padded = List(MediaArcSize) { i -> next.getOrElse(i) { MediaRingAction.None } }
                                mediaArc = padded
                                pieRepository.saveMediaArc(padded)
                            }
                        } else {
                            val emptySlot = MediaRingSlot.entries.firstOrNull {
                                (mediaSlots[it] ?: MediaRingAction.None) == MediaRingAction.None
                            }
                            if (emptySlot != null) {
                                val updated = mediaSlots + (emptySlot to action)
                                mediaSlots = updated
                                pieRepository.saveMediaRing(updated)
                            }
                        }
                        showAppPicker = false
                    },
                )
            }
        }
    }

    private suspend fun launchApp(
        context: Context,
        appRepository: AppRepository,
        key: String,
    ) {
        val app = withContext(Dispatchers.Default) {
            appRepository.findMany().first().firstOrNull { app ->
                "${app.componentName.packageName}/${app.componentName.className}" == key
            }
        } ?: return
        withContext(Dispatchers.Main) {
            app.launch(context, null)
        }
    }

    private fun launchShortcut(context: Context, item: PieItem.Shortcut) {
        val launcherApps = context.getSystemService(LauncherApps::class.java) ?: return
        try {
            launcherApps.startShortcut(
                item.packageName,
                item.shortcutId,
                null,
                null,
                Process.myUserHandle(),
            )
        } catch (_: Exception) {
            // Shortcut is no longer available; ignore.
        }
    }

    /** Maps a stored widgets-target name to its page, falling back to the default page. */
    private fun widgetsTargetOf(name: String?): WidgetScreenTarget =
        WidgetScreenTarget.entries.firstOrNull { it.name == name } ?: WidgetScreenTarget.Default
}

private fun Context.findAppCompatActivity(): AppCompatActivity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is AppCompatActivity) return c
        c = c.baseContext
    }
    return null
}

/** Generates the stable id used to reference an installed app from the pie. */
fun appPieId(packageName: String, className: String): String = "$packageName/$className"

/** Generates the stable id used to reference an app shortcut from the pie. */
fun shortcutPieId(packageName: String, shortcutId: String): String =
    "shortcut:$packageName/$shortcutId"

/** Creates a fresh id for a new folder entry. */
fun folderId(): String = UUID.randomUUID().toString()

/** Returns the folder with [id] anywhere in the tree, or null when it is gone. */
private fun findFolder(items: List<PieItem>, id: String): PieItem.Folder? {
    for (item in items) {
        if (item !is PieItem.Folder) continue
        if (item.id == id) return item
        findFolder(item.children, id)?.let { return it }
    }
    return null
}

/** Maps the folder with [id] through [transform], at any depth, leaving the rest untouched. */
private fun replaceFolder(
    items: List<PieItem>,
    id: String,
    transform: (PieItem.Folder) -> PieItem.Folder,
): List<PieItem> = items.map { item ->
    if (item !is PieItem.Folder) {
        item
    } else if (item.id == id) {
        transform(item)
    } else {
        item.copy(children = replaceFolder(item.children, id, transform))
    }
}

/** How long a skip from Orb's controls decides the direction of the next track flip. */
private const val SkipMemoryMs = 4000L
