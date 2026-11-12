package de.mm20.launcher2.ui.launcher.widgets.device

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import android.text.format.Formatter
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import de.mm20.launcher2.ktx.tryStartActivity
import de.mm20.launcher2.ui.R
import de.mm20.launcher2.widgets.DeviceWidget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private data class BatteryState(
    val level: Int,
    val charging: Boolean,
    val full: Boolean,
    /** Degrees Celsius. */
    val temperature: Float?,
    val fullInMillis: Long?,
)

private data class Usage(val used: Long, val total: Long) {
    val fraction get() = if (total <= 0) 0f else (used.toFloat() / total).coerceIn(0f, 1f)
    val free get() = (total - used).coerceAtLeast(0)
}

@Composable
fun DeviceWidget(widget: DeviceWidget) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var battery by remember { mutableStateOf<BatteryState?>(null) }
    var storage by remember { mutableStateOf<Usage?>(null) }
    var memory by remember { mutableStateOf<Usage?>(null) }

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                battery = intent.toBatteryState(context)
            }
        }
        // ACTION_BATTERY_CHANGED is sticky, so this also delivers the current state immediately.
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )?.let { battery = it.toBatteryState(context) }
        onDispose { context.unregisterReceiver(receiver) }
    }

    LaunchedEffect(widget.config.showStorage, widget.config.showMemory) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var tick = 0
            while (true) {
                // Free memory moves constantly, free storage barely does.
                if (widget.config.showMemory) memory = readMemory(context)
                if (widget.config.showStorage && tick % 20 == 0) storage = readStorage()
                tick++
                delay(3000)
            }
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        val state = battery
        BatteryRing(
            state = state,
            modifier = Modifier
                .size(108.dp)
                .clip(CircleShape)
                .clickable {
                    context.tryStartActivity(
                        Intent(Intent.ACTION_POWER_USAGE_SUMMARY)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                },
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column {
                Text(
                    text = when {
                        state == null -> stringResource(R.string.device_widget_battery)
                        state.full -> stringResource(R.string.device_widget_full)
                        state.charging -> state.fullInMillis?.let {
                            val minutes = (it / 60000).toInt().coerceAtLeast(1)
                            pluralStringResource(
                                R.plurals.battery_part_remaining_charge_time,
                                minutes,
                                minutes,
                            )
                        } ?: stringResource(R.string.battery_part_charging)

                        else -> stringResource(R.string.device_widget_battery)
                    },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val temperature = state?.temperature
                if (widget.config.showTemperature && temperature != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            painterResource(R.drawable.device_thermostat_24px),
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = if (temperature >= 42f) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "${temperature.roundToInt()} °C",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (temperature >= 42f) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 2.dp),
                        )
                    }
                }
            }
            if (widget.config.showStorage) {
                storage?.let {
                    UsageMeter(
                        icon = R.drawable.hard_disk_24px,
                        label = stringResource(R.string.device_widget_storage),
                        usage = it,
                        onClick = {
                            context.tryStartActivity(
                                Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        },
                    )
                }
            }
            if (widget.config.showMemory) {
                memory?.let {
                    UsageMeter(
                        icon = R.drawable.speed_24px,
                        label = stringResource(R.string.device_widget_memory),
                        usage = it,
                    )
                }
            }
        }
    }
}

@Composable
private fun BatteryRing(state: BatteryState?, modifier: Modifier = Modifier) {
    val level = state?.level ?: 0
    val charging = state?.charging == true
    val low = state != null && !charging && level <= 15

    val sweep by animateFloatAsState(
        targetValue = level / 100f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 60f),
        label = "batteryLevel",
    )
    val color by animateColorAsState(
        when {
            low -> MaterialTheme.colorScheme.error
            charging -> MaterialTheme.colorScheme.tertiary
            else -> MaterialTheme.colorScheme.primary
        },
        label = "batteryColor",
    )
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)

    // While charging, a highlight keeps running along the filled part of the ring.
    val shimmer = if (charging && state?.full != true) {
        val transition = rememberInfiniteTransition(label = "charging")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Restart),
            label = "chargingShimmer",
        ).value
    } else null

    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().padding(6.dp)) {
            val stroke = 10.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            val topLeft = Offset(inset, inset)
            drawArc(
                color = track,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(stroke),
            )
            if (sweep > 0.005f) {
                rotate(-90f) {
                    drawArc(
                        color = color,
                        startAngle = 0f,
                        sweepAngle = 360f * sweep,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(stroke, cap = StrokeCap.Round),
                    )
                    if (shimmer != null) {
                        val length = 28f.coerceAtMost(360f * sweep)
                        val head = (360f * sweep) * shimmer
                        drawArc(
                            color = Color.White.copy(alpha = 0.45f * (1f - shimmer)),
                            startAngle = (head - length).coerceAtLeast(0f),
                            sweepAngle = length.coerceAtMost(head.coerceAtLeast(0.1f)),
                            useCenter = false,
                            topLeft = topLeft,
                            size = arcSize,
                            style = Stroke(stroke, cap = StrokeCap.Round),
                        )
                    }
                }
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (charging) {
                Icon(
                    painterResource(R.drawable.bolt_24px_filled),
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = color,
                )
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = if (state == null) "–" else level.toString(),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (low) color else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "%",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 1.dp, bottom = 5.dp),
                )
            }
        }
    }
}

@Composable
private fun UsageMeter(
    @DrawableRes icon: Int,
    label: String,
    usage: Usage,
    onClick: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val fraction by animateFloatAsState(
        usage.fraction,
        animationSpec = spring(stiffness = 80f),
        label = "usage",
    )
    val critical = usage.fraction >= 0.9f
    val color by animateColorAsState(
        if (critical) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary,
        label = "usageColor",
    )
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick,
                ) else Modifier
            ),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painterResource(icon),
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier
                    .padding(start = 4.dp, end = 8.dp)
                    .weight(1f),
                maxLines = 1,
            )
            Text(
                text = stringResource(
                    R.string.device_widget_free_of,
                    Formatter.formatShortFileSize(context, usage.free),
                    Formatter.formatShortFileSize(context, usage.total),
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(6.dp)
        ) {
            val radius = CornerRadius(size.height / 2)
            drawRoundRect(track, cornerRadius = radius)
            drawRoundRect(
                color,
                size = Size((size.width * fraction).coerceAtLeast(size.height), size.height),
                cornerRadius = radius,
            )
        }
    }
}

private fun Intent.toBatteryState(context: Context): BatteryState? {
    val level = getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = getIntExtra(BatteryManager.EXTRA_SCALE, -1)
    if (level < 0 || scale <= 0) return null
    val status = getIntExtra(BatteryManager.EXTRA_STATUS, -1)
    val full = status == BatteryManager.BATTERY_STATUS_FULL
    val charging = full || status == BatteryManager.BATTERY_STATUS_CHARGING
    val temperature = getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        .takeIf { it != Int.MIN_VALUE && it != 0 }
        ?.let { it / 10f }
    val fullIn = if (charging && !full && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        context.getSystemService<BatteryManager>()
            ?.computeChargeTimeRemaining()
            ?.takeIf { it > 0 }
    } else null
    return BatteryState(
        level = (level * 100f / scale).roundToInt(),
        charging = charging,
        full = full,
        temperature = temperature,
        fullInMillis = fullIn,
    )
}

private fun readMemory(context: Context): Usage? {
    val activityManager = context.getSystemService<ActivityManager>() ?: return null
    val info = ActivityManager.MemoryInfo()
    activityManager.getMemoryInfo(info)
    return Usage(used = info.totalMem - info.availMem, total = info.totalMem)
}

private suspend fun readStorage(): Usage? = withContext(Dispatchers.IO) {
    try {
        val stat = StatFs(Environment.getDataDirectory().path)
        Usage(used = stat.totalBytes - stat.availableBytes, total = stat.totalBytes)
    } catch (e: IllegalArgumentException) {
        null
    }
}
