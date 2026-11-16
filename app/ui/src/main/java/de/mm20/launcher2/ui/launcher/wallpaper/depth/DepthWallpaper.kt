package de.mm20.launcher2.ui.launcher.wallpaper.depth

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.graphics.Rect
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
import de.mm20.launcher2.ui.locals.LocalPreferDarkContentOverWallpaper
import kotlinx.coroutines.CancellationException
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

/** The applied wallpaper photo and, when a subject was found, its cutout (same size). */
class DepthImages(val background: ImageBitmap, val foreground: ImageBitmap?)

/**
 * Where the home screen clock sits, as fractions of the screen size, so the editor can draw the
 * real clock in the same place.
 */
data class ClockPlacement(
    val left: Float,
    val top: Float,
    val width: Float,
    /** Whether the home screen draws the clock in dark colors (for "Auto" clock color). */
    val darkContent: Boolean = false,
)

/**
 * A photo being framed in the editor; [cutout] is its subject, same size, if one was found.
 * [crop] is the framing it was last applied with, in photo pixels.
 */
class DepthSource(val photo: Bitmap, val cutout: Bitmap?, val crop: Rect? = null)

/**
 * The depth-effect photo and its subject cutout, kept in the app's files so the cutout only has
 * to be computed once per photo. The uncropped source is kept too so it can be re-framed later.
 */
object DepthWallpaperStore {
    private const val MaxSide = 2400

    private val _images = MutableStateFlow<DepthImages?>(null)
    val images: StateFlow<DepthImages?> = _images.asStateFlow()
    private var loaded = false

    private fun dir(context: Context) = File(context.filesDir, "depth_wallpaper")
    private fun backgroundFile(context: Context) = File(dir(context), "background.jpg")
    private fun foregroundFile(context: Context) = File(dir(context), "foreground.png")
    private fun sourceFile(context: Context) = File(dir(context), "source.jpg")
    private fun sourceCutoutFile(context: Context) = File(dir(context), "source_cutout.png")

    fun hasPhoto(context: Context) = backgroundFile(context).exists()

    private val _clockPlacement = MutableStateFlow<ClockPlacement?>(null)

    /** Last known home screen clock placement; survives restarts via shared preferences. */
    fun clockPlacement(context: Context): StateFlow<ClockPlacement?> {
        if (_clockPlacement.value == null) {
            val prefs = context.getSharedPreferences("depth_wallpaper", Context.MODE_PRIVATE)
            if (prefs.contains("clock_top")) {
                _clockPlacement.value = ClockPlacement(
                    prefs.getFloat("clock_left", 0f),
                    prefs.getFloat("clock_top", 0f),
                    prefs.getFloat("clock_width", 1f),
                    prefs.getBoolean("clock_dark", false),
                )
            }
        }
        return _clockPlacement.asStateFlow()
    }

    fun setClockPlacement(context: Context, placement: ClockPlacement) {
        if (placement == _clockPlacement.value) return
        _clockPlacement.value = placement
        context.getSharedPreferences("depth_wallpaper", Context.MODE_PRIVATE).edit()
            .putFloat("clock_left", placement.left)
            .putFloat("clock_top", placement.top)
            .putFloat("clock_width", placement.width)
            .putBoolean("clock_dark", placement.darkContent)
            .apply()
    }

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
                val fg = foregroundFile(context).takeIf { it.exists() }
                    ?.let { BitmapFactory.decodeFile(it.path) }
                bg?.let { DepthImages(it.asImageBitmap(), fg?.asImageBitmap()) }
            }
        } catch (e: Exception) {
            Log.e("DepthWallpaper", "Could not load depth wallpaper", e)
            null
        }
    }

    /** The uncropped photo of the current wallpaper (falls back to the cropped one), or null. */
    suspend fun loadSource(context: Context): DepthSource? = withContext(Dispatchers.IO) {
        try {
            val photoFile = sourceFile(context).takeIf { it.exists() }
                ?: backgroundFile(context).takeIf { it.exists() }
                ?: return@withContext null
            val cutoutFile = if (photoFile == sourceFile(context)) sourceCutoutFile(context)
            else foregroundFile(context)
            val photo = BitmapFactory.decodeFile(photoFile.path) ?: return@withContext null
            val cutout = cutoutFile.takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }
            val prefs = context.getSharedPreferences("depth_wallpaper", Context.MODE_PRIVATE)
            val crop = if (photoFile == sourceFile(context) && prefs.contains("crop")) {
                prefs.getString("crop", null)?.let { Rect.unflattenFromString(it) }
            } else null
            DepthSource(photo, cutout, crop)
        } catch (e: Exception) {
            Log.e("DepthWallpaper", "Could not load depth source", e)
            null
        }
    }

    /** Decodes [uri] into a software ARGB_8888 sRGB bitmap. Throws on failure. */
    suspend fun decodePhoto(context: Context, uri: Uri): Bitmap =
        withContext(Dispatchers.IO) { toArgb(decode(context, uri)) }

    /**
     * Cuts out [photo]'s subject, or returns null when none is found. Throws
     * [SegmenterNotReadyException] while the model is downloading.
     */
    suspend fun findSubject(photo: Bitmap, segmenter: SubjectSegmenter): Bitmap? {
        return try {
            toArgb(segmenter.cutOut(photo))
        } catch (e: CancellationException) {
            throw e
        } catch (e: SegmenterNotReadyException) {
            throw e
        } catch (e: Exception) {
            Log.w("DepthWallpaper", "No subject found", e)
            null
        }
    }

    /**
     * Crops [source] to [crop] (in photo pixels) and makes it the wallpaper. When [source] has no
     * cutout, the subject search is retried on the crop, where the subject is bigger.
     * @return whether the applied wallpaper has a subject in front of the clock.
     */
    suspend fun apply(
        context: Context,
        source: DepthSource,
        crop: Rect,
        segmenter: SubjectSegmenter,
    ): Boolean {
        val photo = source.photo
        val r = Rect(
            crop.left.coerceIn(0, photo.width - 1),
            crop.top.coerceIn(0, photo.height - 1),
            crop.right.coerceIn(1, photo.width),
            crop.bottom.coerceIn(1, photo.height),
        )
        val bg = withContext(Dispatchers.Default) {
            Bitmap.createBitmap(photo, r.left, r.top, r.width(), r.height())
        }
        val fg = source.cutout
            ?.let { withContext(Dispatchers.Default) { Bitmap.createBitmap(it, r.left, r.top, r.width(), r.height()) } }
            ?: runCatching { findSubject(bg, segmenter) }
                .onFailure { if (it is CancellationException) throw it }
                .getOrNull()
        withContext(Dispatchers.IO) {
            dir(context).mkdirs()
            sourceFile(context).outputStream().use {
                photo.compress(Bitmap.CompressFormat.JPEG, 92, it)
            }
            if (source.cutout != null) {
                sourceCutoutFile(context).outputStream().use {
                    source.cutout.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            } else sourceCutoutFile(context).delete()
            backgroundFile(context).outputStream().use {
                bg.compress(Bitmap.CompressFormat.JPEG, 92, it)
            }
            if (fg != null) {
                foregroundFile(context).outputStream().use {
                    fg.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            } else foregroundFile(context).delete()
            context.getSharedPreferences("depth_wallpaper", Context.MODE_PRIVATE).edit()
                .putString("crop", r.flattenToString())
                .apply()
        }
        loaded = true
        reload(context)
        return fg != null
    }

    /**
     * [bitmap] as a software ARGB_8888 sRGB bitmap. HDR / wide-gamut photos otherwise decode as
     * RGBA_F16, which the subject segmenter rejects.
     */
    private fun toArgb(bitmap: Bitmap): Bitmap {
        val srgb = bitmap.colorSpace == null ||
                bitmap.colorSpace == ColorSpace.get(ColorSpace.Named.SRGB)
        if (bitmap.config == Bitmap.Config.ARGB_8888 && srgb) return bitmap
        val out = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(out).drawBitmap(bitmap, 0f, 0f, null)
        return out
    }

    /** Software bitmap, EXIF orientation applied, longest side at most [MaxSide]. */
    private fun decode(context: Context, uri: Uri): Bitmap {
        if (Build.VERSION.SDK_INT >= 28) {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
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
    val images = rememberActiveDepthImages()?.takeIf { it.foreground != null }
        ?: return@composed this
    var rootSize by remember { mutableStateOf(Size.Zero) }
    var position by remember { mutableStateOf(Offset.Zero) }
    this
        .onGloballyPositioned {
            rootSize = it.findRootCoordinates().size.toSize()
            position = it.positionInRoot()
        }
        .drawWithContent {
            drawContent()
            images.foreground?.let { drawCropped(it, rootSize, position) }
        }
}

/** Records where the home screen clock is, for the depth wallpaper editor's preview. */
fun Modifier.reportClockPlacement(): Modifier = composed {
    val context = LocalContext.current
    val darkContent = LocalPreferDarkContentOverWallpaper.current
    onGloballyPositioned {
        val root = it.findRootCoordinates().size
        if (root.width <= 0 || root.height <= 0 || it.size.width <= 0) return@onGloballyPositioned
        val pos = it.positionInRoot()
        // Rounded so sub-pixel jitter doesn't cause a write.
        fun r(v: Float) = (v * 1000f).toInt() / 1000f
        DepthWallpaperStore.setClockPlacement(
            context,
            ClockPlacement(
                left = r(pos.x / root.width),
                top = r(pos.y / root.height),
                width = r(it.size.width.toFloat() / root.width),
                darkContent = darkContent,
            )
        )
    }
}
