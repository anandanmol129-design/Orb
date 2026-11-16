package de.mm20.launcher2.ui.launcher.wallpaper.depth

import android.graphics.Rect
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import de.mm20.launcher2.preferences.ui.UiSettings
import de.mm20.launcher2.ui.R
import de.mm20.launcher2.ui.locals.LocalPreferDarkContentOverWallpaper
import de.mm20.launcher2.ui.launcher.widgets.clock.ClockWidget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import kotlin.math.max
import kotlin.math.roundToInt

private enum class SubjectState { None, Searching, Found, NotFound, Downloading }

/**
 * Full-screen live preview of the depth wallpaper: the photo with a sample clock and the subject
 * drawn in front of it. Drag to move, pinch to zoom, double-tap to reset; Apply saves the framed
 * part. Opens on the current photo, or straight into the photo picker when there is none.
 */
@Composable
fun DepthWallpaperEditor(segmenter: SubjectSegmenter, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val uiSettings: UiSettings = koinInject()
    val scope = rememberCoroutineScope()

    var source by remember { mutableStateOf<DepthSource?>(null) }
    var subject by remember { mutableStateOf(SubjectState.None) }
    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var segmentJob by remember { mutableStateOf<Job?>(null) }

    fun findSubject(photo: android.graphics.Bitmap) {
        segmentJob?.cancel()
        subject = SubjectState.Searching
        segmentJob = scope.launch {
            subject = try {
                val cutout = DepthWallpaperStore.findSubject(photo, segmenter)
                if (source?.photo === photo) source = DepthSource(photo, cutout, source?.crop)
                if (cutout != null) SubjectState.Found else SubjectState.NotFound
            } catch (e: CancellationException) {
                throw e
            } catch (e: SegmenterNotReadyException) {
                SubjectState.Downloading
            }
        }
    }

    fun load(uri: Uri) {
        loading = true
        scope.launch {
            try {
                val photo = DepthWallpaperStore.decodePhoto(context, uri)
                source = DepthSource(photo, null)
                findSubject(photo)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("DepthEffect", "Could not open photo", e)
                Toast.makeText(context, R.string.wallpaper_depth_open_error, Toast.LENGTH_LONG).show()
                if (source == null) onDismiss()
            } finally {
                loading = false
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) load(uri)
        else if (source == null) onDismiss()
    }
    val pick = {
        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    LaunchedEffect(Unit) {
        val existing = DepthWallpaperStore.loadSource(context)
        loading = false
        if (existing == null) pick()
        else {
            source = existing
            if (existing.cutout != null) subject = SubjectState.Found
            else findSubject(existing.photo)
        }
    }

    // Framing: the photo's scale and the screen position of its top-left corner.
    var frameSize by remember { mutableStateOf(IntSize.Zero) }
    var scale by remember { mutableFloatStateOf(0f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    val photo = source?.photo
    val coverScale = if (photo == null || frameSize == IntSize.Zero) 0f
    else max(frameSize.width.toFloat() / photo.width, frameSize.height.toFloat() / photo.height)

    fun clamp() {
        if (photo == null) return
        scale = scale.coerceIn(coverScale, coverScale * 5f)
        offsetX = offsetX.coerceIn(frameSize.width - photo.width * scale, 0f)
        offsetY = offsetY.coerceIn(frameSize.height - photo.height * scale, 0f)
    }

    fun reset() {
        if (photo == null) return
        scale = coverScale
        offsetX = (frameSize.width - photo.width * scale) / 2f
        offsetY = (frameSize.height - photo.height * scale) / 2f
    }

    // The current wallpaper reopens with its saved framing; a new photo starts centered.
    LaunchedEffect(photo, frameSize) {
        val crop = source?.crop
        if (photo == null || frameSize == IntSize.Zero) return@LaunchedEffect
        if (crop == null || crop.width() <= 0) reset()
        else {
            scale = frameSize.width.toFloat() / crop.width()
            offsetX = -crop.left * scale
            offsetY = -crop.top * scale
            clamp()
        }
    }

    val photoImage = remember(photo) { photo?.asImageBitmap() }
    val cutoutImage = remember(source?.cutout) { source?.cutout?.asImageBitmap() }

    fun apply() {
        val src = source ?: return
        if (scale <= 0f) return
        saving = true
        scope.launch {
            try {
                segmentJob?.join()
                val left = (-offsetX / scale).roundToInt()
                val top = (-offsetY / scale).roundToInt()
                val crop = Rect(
                    left, top,
                    left + (frameSize.width / scale).roundToInt(),
                    top + (frameSize.height / scale).roundToInt(),
                )
                val hasSubject = DepthWallpaperStore.apply(context, source ?: src, crop, segmenter)
                uiSettings.setWallpaperDepthEffect(true)
                if (!hasSubject) {
                    Toast.makeText(context, R.string.wallpaper_depth_no_subject_applied, Toast.LENGTH_LONG).show()
                }
                onDismiss()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("DepthEffect", "Could not apply depth wallpaper", e)
                Toast.makeText(context, R.string.wallpaper_depth_error, Toast.LENGTH_LONG).show()
                saving = false
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            val density = LocalDensity.current
            val size = with(density) { IntSize(maxWidth.roundToPx(), maxHeight.roundToPx()) }
            if (size != frameSize) frameSize = size
            val screenWidth = maxWidth
            val screenHeight = maxHeight

            val placement by remember { DepthWallpaperStore.clockPlacement(context) }.collectAsState()

            Box(Modifier.fillMaxSize()) {
                if (photoImage != null && scale > 0f) {
                    Canvas(Modifier.fillMaxSize()) {
                        drawFramed(photoImage, offsetX, offsetY, scale)
                    }
                    PreviewClock(placement, screenWidth, screenHeight)
                    if (cutoutImage != null) {
                        Canvas(Modifier.fillMaxSize()) {
                            drawFramed(cutoutImage, offsetX, offsetY, scale)
                        }
                    }
                }
                if (loading || photoImage == null) {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                        color = Color.White,
                    )
                }
            }

            // Gestures on top, so the preview clock never takes a tap.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(photo, coverScale) {
                        detectTransformGestures { centroid, pan, zoom, _ ->
                            if (photo == null) return@detectTransformGestures
                            val newScale = (scale * zoom).coerceIn(coverScale, coverScale * 5f)
                            val f = newScale / scale
                            offsetX = centroid.x - (centroid.x - offsetX) * f + pan.x
                            offsetY = centroid.y - (centroid.y - offsetY) * f + pan.y
                            scale = newScale
                            clamp()
                        }
                    }
                    .pointerInput(photo, coverScale) {
                        detectTapGestures(onDoubleTap = { reset() })
                    }
            )

            Status(
                text = when {
                    subject == SubjectState.Searching -> stringResource(R.string.preference_wallpaper_depth_processing)
                    subject == SubjectState.NotFound -> stringResource(R.string.wallpaper_depth_no_subject)
                    subject == SubjectState.Downloading -> stringResource(R.string.wallpaper_depth_downloading)
                    photoImage != null -> stringResource(R.string.wallpaper_depth_editor_hint)
                    else -> null
                },
                busy = subject == SubjectState.Searching,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 12.dp, start = 16.dp, end = 16.dp),
            )

            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledTonalButton(onClick = { pick() }, enabled = !saving) {
                    Text(stringResource(R.string.wallpaper_depth_change_photo))
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss, enabled = !saving) {
                    Text(stringResource(R.string.wallpaper_depth_cancel), color = Color.White)
                }
                Button(onClick = { apply() }, enabled = !saving && !loading && photoImage != null) {
                    if (saving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(stringResource(R.string.wallpaper_depth_apply))
                }
            }
        }
    }
}

private fun DrawScope.drawFramed(image: ImageBitmap, x: Float, y: Float, scale: Float) {
    drawImage(
        image,
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(image.width, image.height),
        dstOffset = IntOffset(x.roundToInt(), y.roundToInt()),
        dstSize = IntSize((image.width * scale).roundToInt(), (image.height * scale).roundToInt()),
        filterQuality = FilterQuality.Medium,
    )
}

/**
 * The real home screen clock (same style, size and settings), placed where the home screen last
 * drew it. Before the home screen has reported its clock, it sits near the top.
 */
@Composable
private fun PreviewClock(placement: ClockPlacement?, screenWidth: Dp, screenHeight: Dp) {
    val modifier = if (placement != null) {
        Modifier
            .offset(x = screenWidth * placement.left, y = screenHeight * placement.top)
            .width(screenWidth * placement.width)
    } else {
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(top = 64.dp)
    }
    CompositionLocalProvider(
        LocalPreferDarkContentOverWallpaper provides (placement?.darkContent ?: false)
    ) {
        ClockWidget(modifier = modifier, preview = true)
    }
}

@Composable
private fun Status(text: String?, busy: Boolean, modifier: Modifier = Modifier) {
    if (text == null) return
    Row(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(50))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp,
                color = Color.White,
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(text, color = Color.White, style = MaterialTheme.typography.labelLarge)
    }
}
