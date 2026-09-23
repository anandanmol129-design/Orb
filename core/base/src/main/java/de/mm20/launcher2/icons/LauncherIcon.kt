package de.mm20.launcher2.icons

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.PorterDuffXfermode
import androidx.core.graphics.withScale
import de.mm20.launcher2.ktx.drawWithColorFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import palettes.TonalPalette
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap

sealed interface LauncherIcon

data class LauncherIconRenderSettings(
    val size: Int,
    val fgThemeColor: Int,
    val bgThemeColor: Int,
    val fgTone: Int,
    val bgTone: Int,
)

data class StaticLauncherIcon(
    val foregroundLayer: LauncherIconLayer,
    val backgroundLayer: LauncherIconLayer,
) : LauncherIcon {
    private var cachedBitmap: WeakReference<Bitmap>? = null
    private var cachedRenderSettings: LauncherIconRenderSettings? = null
    private var renderSemaphore = Semaphore(1)

    // Reused across renders; only accessed inside [renderSemaphore].
    private val clearPaint = Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }
    private val layerPaint = Paint()

    fun getCachedBitmap(settings: LauncherIconRenderSettings): Bitmap? {
        return if (cachedRenderSettings == settings) cachedBitmap?.get() else null
    }

    /**
     * Render this icon to a bitmap.
     */
    suspend fun render(settings: LauncherIconRenderSettings): Bitmap {
        val cachedBmp = cachedBitmap?.get()
        if (cachedRenderSettings == settings && cachedBmp != null) return cachedBmp
        val bmp = withContext(Dispatchers.Default) {
            renderSemaphore.withPermit {
                val bmp =
                    if (cachedBmp == null || cachedBmp.width != settings.size || cachedBmp.height != settings.size) {
                        Bitmap.createBitmap(settings.size, settings.size, Bitmap.Config.ARGB_8888)!!
                    } else cachedBmp
                val canvas = Canvas(bmp)
                canvas.drawRect(
                    0f, 0f, canvas.width.toFloat(), canvas.height.toFloat(), clearPaint
                )
                renderLayer(canvas, backgroundLayer, settings.bgThemeColor, settings.bgTone)
                renderLayer(canvas, foregroundLayer, settings.fgThemeColor, settings.fgTone)
                cachedBitmap = WeakReference(bmp)
                cachedRenderSettings = settings
                bmp
            }
        }
        return bmp
    }

    private fun renderLayer(canvas: Canvas, layer: LauncherIconLayer, themeColor: Int, tone: Int) {
        when(layer) {
            is ColorLayer -> {
                layerPaint.color =
                    if (layer.color == 0) themeColor else getTone(layer.color, tone)
                canvas.drawRect(
                    0f, 0f, canvas.width.toFloat(), canvas.height.toFloat(), layerPaint
                )
            }
            is StaticIconLayer -> {
                canvas.withScale(
                    layer.scale,
                    layer.scale,
                    canvas.width / 2f,
                    canvas.height / 2f,
                ) {
                    layer.icon.setBounds(0, 0, canvas.width, canvas.height)
                    layer.icon.draw(canvas)
                }
            }
            is TintedIconLayer -> {
                val color = if (layer.color == 0) themeColor else getTone(layer.color, tone)
                canvas.withScale(
                    layer.scale,
                    layer.scale,
                    canvas.width / 2f,
                    canvas.height / 2f,
                ) {
                    layer.icon.setBounds(0, 0, canvas.width, canvas.height)
                    layer.icon.drawWithColorFilter(canvas,
                        PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
                    )
                }
            }
            else -> {}
        }
    }

    private fun getTone(argb: Int, tone: Int): Int {
        val key = (argb.toLong() shl 32) or (tone.toLong() and 0xffffffffL)
        return toneCache.getOrPut(key) {
            TonalPalette
                .fromInt(argb)
                .tone(tone)
        }
    }

    private companion object {
        // TonalPalette's HCT solve is deterministic per (color, tone); cache the resulting color.
        val toneCache = ConcurrentHashMap<Long, Int>()
    }
}

interface DynamicLauncherIcon : LauncherIcon {
    suspend fun getIcon(time: Long): StaticLauncherIcon
}