package de.mm20.launcher2.ui.launcher.wallpaper.depth

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.toSize
import de.mm20.launcher2.preferences.WallpaperType
import de.mm20.launcher2.preferences.ui.UiSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import java.io.File
import kotlin.math.max

/**
 * Cuts the main subject out of a photo. Provided by the app flavor (ML Kit in the default build);
 * absent in builds without it, which hides the depth effect.
 */
interface SubjectSegmenter {
    /** @return [bitmap]'s subject on a transparent background, same size as [bitmap]. */
    suspend fun cutOut(bitmap: Bitmap): Bitmap
}

/** Thrown by a [SubjectSegmenter] while its model is still being downloaded. */
class SegmenterNotReadyException(cause: Throwable? = null) : Exception(cause)

class DepthImages(val background: ImageBitmap, val foreground: ImageBitmap)

/**
 * The depth-effect photo and its subject cutout, kept in the app's files so the cutout only has
 * to be computed once per photo.
 */
object DepthWallpaperStore {
    private const val MaxSide = 2400

    private val _images = MutableStateFlow<DepthImages?>(null)
    val images: StateFlow<DepthImages?> = _images.asStateFlow()
    private var loaded = false

    private fun dir(context: Context) = File(context.filesDir, "depth_wallpaper")
    private fun backgroundFile(context: Context) = File(dir(context), "background.jpg")
    private fun foregroundFile(context: Context) = File(dir(context), "foreground.png")

    fun hasPhoto(context: Context) = backgroundFile(context).exists() && foregroundFile(context).exists()

    suspend fun ensureLoaded(context: Context) {
        if (loaded) return
        loaded = true
        reload(context)
    }

    private suspend fun reload(context: Context) = withContext(Dispatchers.IO) {
        _images.value = try {
            if (!hasPhoto(context)) null
            else {
                val bg = BitmapFactory.decodeFile(backgroundFile(context).path)
                val fg = BitmapFactory.decodeFile(foregroundFile(context).path)
                if (bg == null || fg == null) null
                else DepthImages(bg.asImageBitmap(), fg.asImageBitmap())
            }
        } catch (e: Exception) {
            Log.e("DepthWallpaper", "Could not load depth wallpaper", e)
            null
        }
    }

    /** Decodes [uri], cuts out its subject and stores both. Throws on failure. */
    suspend fun setPhoto(context: Context, uri: Uri, segmenter: SubjectSegmenter) {
        val photo = withContext(Dispatchers.IO) { decode(context, uri) }
        val cutout = segmenter.cutOut(photo)
        withContext(Dispatchers.IO) {
            dir(context).mkdirs()
            backgroundFile(context).outputStream().use {
                photo.compress(Bitmap.CompressFormat.JPEG, 92, it)
            }
            foregroundFile(context).outputStream().use {
                cutout.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        loaded = true
        reload(context)
    }

    /** Software ARGB bitmap, EXIF orientation applied, longest side at most [MaxSide]. */
    private fun decode(context: Context, uri: Uri): Bitmap {
        if (Build.VERSION.SDK_INT >= 28) {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val longest = max(info.size.width, info.size.height)
                if (longest > MaxSide) {
                    val f = MaxSide.toFloat() / longest
                    decoder.setTargetSize(
                        (info.size.width * f).toInt(),
                        (info.size.height * f).toInt()
                    )
                }
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > MaxSide) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return context.contentResolver.openInputStream(uri).use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: throw IllegalArgumentException("Could not decode $uri")
    }
}

/** The depth images when the System wallpaper with depth effect is active, otherwise null. */
@Composable
fun rememberActiveDepthImages(): DepthImages? {
    val uiSettings: UiSettings = koinInject()
    val type by uiSettings.wallpaperType.collectAsState(initial = null)
    val enabled by uiSettings.wallpaperDepthEffect.collectAsState(initial = false)
    if (type != WallpaperType.System || !enabled) return null
    val context = LocalContext.current
    androidx.compose.runtime.LaunchedEffect(Unit) { DepthWallpaperStore.ensureLoaded(context) }
    val images by DepthWallpaperStore.images.collectAsState()
    return images
}

/**
 * Draws [image] like a center-cropped full-screen wallpaper, as seen through a node at
 * [positionInRoot] in a root of [rootSize]. Shared by the background and the foreground overlay
 * so both line up exactly.
 */
private fun DrawScope.drawCropped(image: ImageBitmap, rootSize: Size, positionInRoot: Offset) {
    if (rootSize.width <= 0f || rootSize.height <= 0f) return
    val s = max(rootSize.width / image.width, rootSize.height / image.height)
    val dx = (rootSize.width - image.width * s) / 2f - positionInRoot.x
    val dy = (rootSize.height - image.height * s) / 2f - positionInRoot.y
    clipRect {
        translate(dx, dy) {
            scale(s, s, pivot = Offset.Zero) {
                drawImage(image)
            }
        }
    }
}

/** The depth photo as a full-screen wallpaper layer. */
@Composable
fun DepthWallpaperBackground(images: DepthImages, modifier: Modifier = Modifier) {
    var rootSize by remember { mutableStateOf(Size.Zero) }
    var position by remember { mutableStateOf(Offset.Zero) }
    Canvas(
        modifier = modifier.onGloballyPositioned {
            rootSize = it.findRootCoordinates().size.toSize()
            position = it.positionInRoot()
        }
    ) {
        drawCropped(images.background, rootSize, position)
    }
}

/**
 * Redraws the depth photo's subject on top of this node's content (e.g. the clock), so the
 * content appears tucked behind the subject. Outside this node the wallpaper already shows the
 * same pixels, so overlaying only here is visually identical to a full-screen foreground layer.
 */
fun Modifier.depthForeground(): Modifier = composed {
    val images = rememberActiveDepthImages() ?: return@composed this
    var rootSize by remember { mutableStateOf(Size.Zero) }
    var position by remember { mutableStateOf(Offset.Zero) }
    this
        .onGloballyPositioned {
            rootSize = it.findRootCoordinates().size.toSize()
            position = it.positionInRoot()
        }
        .drawWithContent {
            drawContent()
            drawCropped(images.foreground, rootSize, position)
        }
}
