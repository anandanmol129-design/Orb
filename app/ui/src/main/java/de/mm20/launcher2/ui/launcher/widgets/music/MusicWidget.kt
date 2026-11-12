package de.mm20.launcher2.ui.launcher.widgets.music

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.clickable
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import hct.Hct
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import quantize.QuantizerCelebi
import score.Score
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import android.content.pm.PackageManager
import android.content.res.Resources
import android.media.session.PlaybackState.CustomAction
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredHeightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.roundToIntRect
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import de.mm20.launcher2.music.PlaybackState
import de.mm20.launcher2.music.SupportedActions
import de.mm20.launcher2.ui.R
import de.mm20.launcher2.ui.component.MissingPermissionBanner
import de.mm20.launcher2.ui.component.Tooltip
import de.mm20.launcher2.ui.ktx.conditional
import de.mm20.launcher2.ui.launcher.transitions.EnterHomeTransitionParams
import de.mm20.launcher2.ui.launcher.transitions.HandleEnterHomeTransition
import de.mm20.launcher2.ui.locals.LocalWindowSize
import de.mm20.launcher2.ui.theme.transparency.transparency
import de.mm20.launcher2.widgets.MusicWidget
import kotlin.math.min

/** Background and accent tones picked from the album art, or null when there is no art. */
private data class ArtPalette(val background: Color, val accent: Color)

private suspend fun paletteFrom(art: Bitmap): ArtPalette? = withContext(Dispatchers.Default) {
    try {
        val source = if (art.config == Bitmap.Config.HARDWARE) {
            art.copy(Bitmap.Config.ARGB_8888, false)
        } else art
        val small = Bitmap.createScaledBitmap(source, 48, 48, true)
        val pixels = IntArray(48 * 48)
        small.getPixels(pixels, 0, 48, 0, 0, 48, 48)
        val seed = Score.score(QuantizerCelebi.quantize(pixels, 32)).first()
        val hct = Hct.fromInt(seed)
        // Dark, saturated background so white text always reads; a bright accent for the wave.
        val background = Hct.from(hct.hue, hct.chroma.coerceAtMost(40.0), 20.0).toInt()
        val accent = Hct.from(hct.hue, hct.chroma.coerceIn(30.0, 70.0), 82.0).toInt()
        ArtPalette(Color(background), Color(accent))
    } catch (e: Exception) {
        null
    }
}

@Composable
fun MusicWidget(widget: MusicWidget) {

    val viewModel: MusicWidgetVM = viewModel(key = "music-widget-${widget.id}")

    val albumArt by viewModel.albumArt.collectAsStateWithLifecycle(null)
    val title by viewModel.title.collectAsStateWithLifecycle(null)
    val artist by viewModel.artist.collectAsStateWithLifecycle(null)
    val playbackState by viewModel.playbackState.collectAsStateWithLifecycle(PlaybackState.Stopped)
    val position by viewModel.position.collectAsStateWithLifecycle(null)
    val duration by viewModel.duration.collectAsStateWithLifecycle(null)
    val supportedActions by viewModel.supportedActions.collectAsStateWithLifecycle(SupportedActions())

    Column(Modifier.fillMaxWidth()) {
        MusicWidgetContent(
            viewModel = viewModel,
            widget = widget,
            albumArt = albumArt,
            title = title,
            artist = artist,
            playbackState = playbackState,
            position = position,
            duration = duration,
            supportedActions = supportedActions,
        )
    }
}

@Composable
private fun MusicWidgetContent(
    viewModel: MusicWidgetVM,
    widget: MusicWidget,
    albumArt: Bitmap?,
    title: String?,
    artist: String?,
    playbackState: PlaybackState,
    position: Long?,
    duration: Long?,
    supportedActions: SupportedActions,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val playing = playbackState == PlaybackState.Playing

    val hasPermission by viewModel.hasPermission.collectAsStateWithLifecycle(true)
    AnimatedVisibility(!hasPermission) {
        MissingPermissionBanner(
            modifier = Modifier.padding(16.dp),
            text = stringResource(R.string.missing_permission_music_widget),
            onClick = { viewModel.requestPermission(context as AppCompatActivity) }
        )
    }
    if (title == null && artist == null) {
        NoData(onOpenPlayer = { viewModel.openPlayerSelector(context) })
        return
    }

    var palette by remember { mutableStateOf<ArtPalette?>(null) }
    LaunchedEffect(albumArt) {
        palette = albumArt?.let { paletteFrom(it) }
    }
    val background by animateColorAsState(
        palette?.background ?: Color(0xFF26233A),
        tween(600),
        label = "musicBackground",
    )
    val accent by animateColorAsState(
        palette?.accent ?: Color(0xFFCBC3FF),
        tween(600),
        label = "musicAccent",
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(background)
    ) {
        // Blurred art bleeding in from the right, fading into the tint.
        val art = albumArt
        if (art != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Image(
                bitmap = art.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .matchParentSize()
                    .blur(40.dp)
                    .alpha(0.55f),
            )
            Box(
                Modifier
                    .matchParentSize()
                    .background(
                        Brush.horizontalGradient(
                            0f to background,
                            0.35f to background.copy(alpha = 0.85f),
                            1f to background.copy(alpha = 0.35f),
                        )
                    )
            )
        }

        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AlbumArt(
                    art = albumArt,
                    playing = playing,
                    accent = accent,
                    onClick = { viewModel.openPlayer(context) },
                    onLongClick = { viewModel.openPlayerSelector(context) },
                    contentDescription = resources.getString(R.string.music_widget_open_player),
                    playerPackage = viewModel.currentPlayerPackage,
                )
                Column(
                    Modifier
                        .weight(1f)
                        .padding(start = 16.dp)
                ) {
                    Text(
                        text = title ?: "",
                        modifier = if (playing) Modifier.basicMarquee(iterations = Int.MAX_VALUE) else Modifier,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = artist ?: "",
                        modifier = Modifier
                            .padding(top = 2.dp)
                            .then(if (playing) Modifier.basicMarquee(iterations = Int.MAX_VALUE) else Modifier),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.72f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Equalizer(
                        playing = playing,
                        color = accent,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }

            WaveProgress(
                position = position,
                duration = duration,
                playing = playing,
                accent = accent,
                seekable = playbackState != PlaybackState.Stopped && supportedActions.seekTo &&
                        widget.config.interactiveProgressBar,
                onSeek = { viewModel.seekTo(it) },
                modifier = Modifier.padding(top = 16.dp),
            )

            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                CompositionLocalProvider(LocalContentColor provides Color.White) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (supportedActions.skipToPrevious) {
                            Tooltip(tooltipText = stringResource(R.string.music_widget_previous_track)) {
                                IconButton(onClick = { viewModel.skipPrevious() }) {
                                    Icon(
                                        painterResource(R.drawable.skip_previous_24px),
                                        stringResource(R.string.music_widget_previous_track),
                                        modifier = Modifier.size(28.dp),
                                    )
                                }
                            }
                        }
                        PlayPauseButton(
                            playing = playing,
                            accent = accent,
                            iconColor = background,
                            onClick = { viewModel.togglePause() },
                        )
                        if (supportedActions.skipToNext) {
                            Tooltip(tooltipText = stringResource(R.string.music_widget_next_track)) {
                                IconButton(onClick = { viewModel.skipNext() }) {
                                    Icon(
                                        painterResource(R.drawable.skip_next_24px),
                                        stringResource(R.string.music_widget_next_track),
                                        modifier = Modifier.size(28.dp),
                                    )
                                }
                            }
                        }
                        CustomActions(
                            actions = supportedActions,
                            onActionSelected = { viewModel.performCustomAction(it) },
                            playerPackage = viewModel.currentPlayerPackage,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AlbumArt(
    art: Bitmap?,
    playing: Boolean,
    accent: Color,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    contentDescription: String,
    playerPackage: String?,
) {
    // The cover grows slightly while music plays and settles back when paused.
    val scale by animateFloatAsState(
        if (playing) 1f else 0.92f,
        spring(dampingRatio = 0.6f, stiffness = 300f),
        label = "artScale",
    )
    val windowSize = LocalWindowSize.current
    var bounds by remember { mutableStateOf(IntRect.Zero) }
    Box(
        modifier = Modifier
            .size(84.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .shadow(12.dp, RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
            .background(accent.copy(alpha = 0.25f))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .semantics { this.contentDescription = contentDescription }
            .onGloballyPositioned { bounds = it.boundsInWindow().roundToIntRect() },
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(art, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "art") { bitmap ->
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    modifier = Modifier.fillMaxSize(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                )
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        painterResource(R.drawable.music_note_48px),
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(40.dp),
                    )
                }
            }
        }
    }
    if (art != null) {
        HandleEnterHomeTransition {
            if (
                it.componentName.packageName == playerPackage &&
                bounds.right > 0f && bounds.left < windowSize.width &&
                bounds.bottom > 0f && bounds.top < windowSize.height
            ) {
                return@HandleEnterHomeTransition EnterHomeTransitionParams(bounds) { _, _ ->
                    Image(
                        bitmap = art.asImageBitmap(),
                        modifier = Modifier
                            .size(84.dp)
                            .clip(RoundedCornerShape(16.dp)),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                    )
                }
            }
            return@HandleEnterHomeTransition null
        }
    }
}

/** Four bars that bounce while playing and flatten when paused. */
@Composable
private fun Equalizer(playing: Boolean, color: Color, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "equalizer")
    val time by transition.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(1400, easing = LinearEasing)),
        label = "equalizerTime",
    )
    val energy by animateFloatAsState(if (playing) 1f else 0f, tween(400), label = "equalizerEnergy")
    Canvas(modifier.size(width = 22.dp, height = 14.dp)) {
        val bars = 4
        val gap = 2.dp.toPx()
        val w = (size.width - gap * (bars - 1)) / bars
        for (i in 0 until bars) {
            val phase = time * 2 * PI.toFloat() * (1 + i * 0.37f) + i * 1.3f
            val level = 0.25f + energy * 0.75f * (0.5f + 0.5f * sin(phase))
            val h = size.height * level.coerceIn(0.2f, 1f)
            drawRoundRect(
                color = color,
                topLeft = Offset(i * (w + gap), size.height - h),
                size = Size(w, h),
                cornerRadius = CornerRadius(w / 2),
            )
        }
    }
}

@Composable
private fun WaveProgress(
    position: Long?,
    duration: Long?,
    playing: Boolean,
    accent: Color,
    seekable: Boolean,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dur = duration?.takeIf { it > 0 }
    var seekFraction by remember { mutableStateOf<Float?>(null) }
    val progress = seekFraction ?: if (dur != null && position != null) {
        (position.toFloat() / dur).coerceIn(0f, 1f)
    } else 0f
    val shownProgress by animateFloatAsState(
        progress,
        if (seekFraction != null) snap() else tween(300),
        label = "musicProgress",
    )

    val transition = rememberInfiniteTransition(label = "wave")
    val phase by transition.animateFloat(
        0f, 2 * PI.toFloat(),
        infiniteRepeatable(tween(1600, easing = LinearEasing)),
        label = "wavePhase",
    )
    val amplitude by animateFloatAsState(if (playing) 1f else 0f, tween(500), label = "waveAmplitude")

    Column(modifier) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(28.dp)
                .then(
                    if (seekable && dur != null) Modifier
                        .pointerInput(dur) {
                            detectTapGestures { offset ->
                                onSeek(((offset.x / size.width).coerceIn(0f, 1f) * dur).toLong())
                            }
                        }
                        .pointerInput(dur) {
                            detectHorizontalDragGestures(
                                onDragStart = { seekFraction = (it.x / size.width).coerceIn(0f, 1f) },
                                onHorizontalDrag = { change, _ ->
                                    seekFraction = (change.position.x / size.width).coerceIn(0f, 1f)
                                },
                                onDragEnd = {
                                    seekFraction?.let { onSeek((it * dur).toLong()) }
                                    seekFraction = null
                                },
                                onDragCancel = { seekFraction = null },
                            )
                        }
                    else Modifier
                )
        ) {
            val mid = size.height / 2
            val stroke = 4.dp.toPx()
            val playedX = size.width * shownProgress
            val wavelength = 28.dp.toPx()
            val amp = 3.5.dp.toPx() * amplitude

            drawLine(
                color = Color.White.copy(alpha = 0.25f),
                start = Offset(playedX, mid),
                end = Offset(size.width, mid),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
            if (playedX > 0f) {
                val path = Path()
                var x = 0f
                path.moveTo(0f, mid + amp * sin(phase))
                while (x < playedX) {
                    x = (x + 2f).coerceAtMost(playedX)
                    path.lineTo(x, mid + amp * sin(2 * PI.toFloat() * x / wavelength + phase))
                }
                drawPath(path, color = accent, style = Stroke(stroke, cap = StrokeCap.Round))
            }
            // Thumb
            drawRoundRect(
                color = Color.White,
                topLeft = Offset(playedX - 2.dp.toPx(), mid - 9.dp.toPx()),
                size = Size(4.dp.toPx(), 18.dp.toPx()),
                cornerRadius = CornerRadius(2.dp.toPx()),
            )
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = formatTimestamp(seekFraction?.let { f -> dur?.let { (f * it).toLong() } } ?: position),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.7f),
            )
            Text(
                text = formatTimestamp(duration),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.7f),
            )
        }
    }
}

@Composable
private fun PlayPauseButton(
    playing: Boolean,
    accent: Color,
    iconColor: Color,
    onClick: () -> Unit,
) {
    // Rounded square while playing, circle while paused, the way a record player's button would feel.
    val corner by animateFloatAsState(if (playing) 22f else 50f, spring(stiffness = 400f), label = "playCorner")
    val size by animateDpAsState(if (playing) 64.dp else 60.dp, spring(dampingRatio = 0.5f), label = "playSize")
    Tooltip(
        tooltipText = stringResource(if (playing) R.string.music_widget_pause else R.string.music_widget_play)
    ) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(RoundedCornerShape(percent = corner.roundToInt()))
                .background(accent)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedContent(
                playing,
                transitionSpec = {
                    (fadeIn() + scaleIn(initialScale = 0.6f)) togetherWith (fadeOut() + scaleOut(targetScale = 0.6f))
                },
                label = "playIcon",
            ) {
                Icon(
                    painterResource(if (it) R.drawable.pause_24px_filled else R.drawable.play_arrow_24px),
                    contentDescription = stringResource(
                        if (it) R.string.music_widget_pause else R.string.music_widget_play
                    ),
                    tint = iconColor,
                    modifier = Modifier.size(30.dp),
                )
            }
        }
    }
}

@Composable
fun CustomActions(
    actions: SupportedActions,
    onActionSelected: (CustomAction) -> Unit,
    playerPackage: String?
) {
    val usedSlots = 1 + (if (actions.skipToPrevious) 1 else 0) + (if (actions.skipToNext) 1 else 0)
    val slots = 5 - usedSlots

    for (i in 0 until min(actions.customActions.size, slots - 1)) {
        val action = actions.customActions[i]
        Tooltip(
            tooltipText = action.name.toString()
        ) {
            IconButton(
                onClick = {
                    onActionSelected(action)
                }
            ) {
                CustomActionIcon(action, playerPackage)
            }
        }
    }
    if (slots < actions.customActions.size) {
        var showOverflowMenu by remember { mutableStateOf(false) }
        Box {
            Tooltip(
                tooltipText = stringResource(R.string.action_more_actions)
            ) {
                IconButton(onClick = { showOverflowMenu = true }) {
                    Icon(
                        painterResource(R.drawable.more_vert_24px),
                        contentDescription = null
                    )
                }
            }
            DropdownMenuPopup(
                expanded = showOverflowMenu,
                onDismissRequest = { showOverflowMenu = false },
            ) {
                DropdownMenuGroup(
                    shapes = MenuDefaults.groupShapes(),
                ) {
                    for (i in slots - 1 until actions.customActions.size) {
                        val action = actions.customActions[i]
                        DropdownMenuItem(
                            shape =
                                if (actions.customActions.size == 1) MenuDefaults.standaloneItemShape
                                else when (i) {
                                    0 -> MenuDefaults.leadingItemShape
                                    actions.customActions.lastIndex -> MenuDefaults.trailingItemShape
                                    else -> MenuDefaults.middleItemShape
                                },
                            leadingIcon = {
                                CustomActionIcon(action, playerPackage)
                            },
                            text = {
                                Text(
                                    text = action.name.toString(),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            onClick = {
                                onActionSelected(action)
                            }
                        )
                    }
                }
            }
        }
    } else if (slots == actions.customActions.size) {
        val action = actions.customActions.last()
        Tooltip(
            tooltipText = action.name.toString()
        ) {
            IconButton(
                onClick = {
                    onActionSelected(action)
                },
            ) {
                CustomActionIcon(action, playerPackage)
            }
        }
    }
}

@Composable
fun CustomActionIcon(action: CustomAction, playerPackage: String?) {
    val context = LocalContext.current
    val resources = remember(playerPackage) {
        playerPackage?.let {
            try {
                context.packageManager.getResourcesForApplication(it)
            } catch (e: PackageManager.NameNotFoundException) {
                null
            }
        }
    }

    val drawable = remember(action, resources) {
        if (resources != null) {
            try {
                ResourcesCompat.getDrawable(
                    resources, action.icon, null
                )
            } catch (e: Resources.NotFoundException) {
                null
            }
        } else {
            null
        }
    }

    val painter = rememberAsyncImagePainter(
        ImageRequest.Builder(context)
            .data(drawable)
            .crossfade(false)
            .placeholder(drawable)
            .build(),
    )
    Icon(
        painter = painter,
        contentDescription = null,
    )
}

@Composable
fun NoData(onOpenPlayer: () -> Unit = {}) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenPlayer)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.music_note_24px),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
        Column(Modifier.padding(start = 16.dp)) {
            Text(
                text = stringResource(id = R.string.music_widget_no_data),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = stringResource(id = R.string.music_widget_open_player),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Suppress("DefaultLocale")
private fun formatTimestamp(timestamp: Long?): String {
    if (timestamp == null) return "--:--"

    val totalSeconds = timestamp / 1000
    val days = totalSeconds / 86_400
    val hours = (totalSeconds % 86_400) / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60

    return when {
        days > 0 -> String.format("%d:%02d:%02d:%02d", days, hours, minutes, seconds)
        hours > 0 -> String.format("%d:%02d:%02d", hours, minutes, seconds)
        else -> String.format("%02d:%02d", minutes, seconds)
    }
}