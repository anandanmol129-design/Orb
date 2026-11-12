package de.mm20.launcher2.ui.launcher.orb

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.os.Process
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import de.mm20.launcher2.applications.AppRepository
import de.mm20.launcher2.appshortcuts.AppShortcutRepository
import de.mm20.launcher2.orb.OrbFill
import de.mm20.launcher2.orb.pie.PieActionType
import de.mm20.launcher2.orb.pie.PieItem
import de.mm20.launcher2.orb.pie.PieRepository
import de.mm20.launcher2.preferences.GestureAction
import de.mm20.launcher2.preferences.WidgetScreenTarget
import de.mm20.launcher2.preferences.ui.GestureSettings
import de.mm20.launcher2.ui.launcher.scaffold.Gesture
import de.mm20.launcher2.ui.launcher.scaffold.LauncherScaffoldState
import de.mm20.launcher2.ui.launcher.scaffold.components.ScaffoldComponent
import de.mm20.launcher2.ui.settings.orb.OrbSettingsActivity
import de.mm20.launcher2.ui.launcher.scaffold.components.WidgetsComponent
import de.mm20.launcher2.ui.launcher.feed.FeedReaderActivity
import de.mm20.launcher2.ui.launcher.widgets.clock.ClockWidget
import de.mm20.launcher2.ui.locals.LocalSnackbarHostState
import de.mm20.launcher2.ui.launcher.wallpaper.WallpaperVoice
import de.mm20.launcher2.voice.VoiceController
import de.mm20.launcher2.voice.VoiceUiState
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
        val musicPlaying = rememberMusicPlaying()

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

        // Re-read the orb state when the settings activity returns, since it may have been
        // changed there (shape, fill, menu items) or replaced wholesale by a backup restore.
        val lifecycleOwner = LocalLifecycleOwner.current
        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    orbShape = pieRepository.orbShape()
                    orbFill = pieRepository.orbFill()
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

        // Clear a transient success/error shortly after it is shown.
        LaunchedEffect(voiceState) {
            if (voiceState is VoiceUiState.Success || voiceState is VoiceUiState.Error) {
                delay(1800)
                voiceController.acknowledge()
            }
        }

        // While editing, lock the scaffold gestures so swipes don't navigate away mid-edit.
        LaunchedEffect(editMode) {
            if (editMode) {
                state.lock(hideSearchBar = true)
            } else {
                state.unlock()
            }
        }

        // Back - and Home, which the scaffold forwards as back while editing - leaves edit
        // mode. Every change is already saved as it is made, so exiting needs no flush.
        if (editMode) {
            BackHandler { editMode = false }
        }

        // Launches or opens whatever was picked. Pie slices and folder entries share this.
        fun activate(item: PieItem) {
            scope.launch {
                when (item) {
                    is PieItem.Folder -> openFolders = openFolders + item.id

                    is PieItem.App ->
                        launchApp(context, appRepository, item.id)

                    is PieItem.Shortcut ->
                        launchShortcut(context, item)

                    is PieItem.Action -> when (item.type) {
                        PieActionType.Voice -> {
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

                        PieActionType.Search -> state.openSearch()

                        PieActionType.Feed -> context.startActivity(
                            Intent(context, FeedReaderActivity::class.java)
                        )

                        PieActionType.Widgets -> state.openComponent(
                            WidgetsComponent.forTarget(widgetsTargetOf(item.widgetsTarget)),
                        )

                        PieActionType.Settings ->
                            context.startActivity(
                                android.content.Intent(context, OrbSettingsActivity::class.java)
                            )

                        PieActionType.Edit -> {
                            editMode = true
                            openFolders = emptyList()
                        }
                    }
                }
            }
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
            val sphereRadius = minSide * 0.28f * orbScale
            pieRadius = sphereRadius * 1.43f
            deadZone = maxOf(sphereRadius + with(density) { 4.dp.toPx() }, sphereRadius * 1.07f)
            // Touches only count within this radius of the orb; the rest of the screen
            // belongs to the scaffold's own gestures.
            val confinedRadius = sphereRadius + with(density) { 20.dp.toPx() }

            // Released on a slice: activate it. The dead zone cancels.
            val handleRelease: (Int) -> Unit = { index ->
                items.getOrNull(index)?.let { activate(it) }
            }

            // Built-in clock widget (configured in Settings → Home screen → Clock), pinned to
            // the top of the screen. Style "No clock" turns it off. zIndex keeps it above the
            // orb's full-screen gesture layer so its taps are never stolen (upstream has no
            // such layer above the clock).
            ClockWidget(
                modifier = modifier
                    .fillMaxSize()
                    .padding(insets)
                    .zIndex(1f),
            )

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
                        playing = musicPlaying.value,
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
                    )
                    EditModeActions(
                        dragging = draggingIcon,
                        trashCenter = trashCenter,
                        onDone = { editMode = false },
                        onAddApp = { showAppPicker = true },
                        onEditOrb = {
                            context.startActivity(Intent(context, OrbSettingsActivity::class.java))
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
                    .pointerInput(sleeping, items) {
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
                                action is GestureAction.ToggleSleep -> {
                                    sleeping = !sleeping
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
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
                                if (up != null) {
                                    up.consume()
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
                                        selected = pieSelectionIndex(
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
                            selected = -1
                            if (!wentUp) {
                                tapCount = 0
                                return@awaitEachGesture
                            }

                            val quick = upTime - down.uptimeMillis <=
                                    viewConfiguration.longPressTimeoutMillis

                            when {
                                // A short press that stayed put is a tap wherever it landed,
                                // including the orb's rim, which already lies over the slices.
                                // Without this a tap near the edge launched a slice instead.
                                quick && (index < 0 || !moved) -> {
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
                    playing = musicPlaying.value,
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
                    pieOpen && selected in items.indices -> items[selected].label
                    voiceState is VoiceUiState.Success -> (voiceState as VoiceUiState.Success).message
                    voiceState is VoiceUiState.Error -> (voiceState as VoiceUiState.Error).message
                    voiceState is VoiceUiState.Confirm -> (voiceState as VoiceUiState.Confirm).message
                    else -> null
                }
                label?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = insets.calculateTopPadding() + 48.dp),
                    )
                }
            }
            }

            val openFolder = openFolders.lastOrNull()?.let { findFolder(items, it) }
            if (openFolder != null) {
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

            if (showAppPicker) {
                val pickedInto = openFolders.lastOrNull()?.let { findFolder(items, it) }
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
