package de.mm20.launcher2.ui.launcher.widgets.clock

import android.content.Context
import android.content.ContextWrapper
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.clickable
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import de.mm20.launcher2.permissions.PermissionGroup
import de.mm20.launcher2.permissions.PermissionsManager
import de.mm20.launcher2.preferences.weather.WeatherSettings
import de.mm20.launcher2.ui.R
import kotlin.math.max
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.mm20.launcher2.preferences.ClockWidgetStyle
import de.mm20.launcher2.preferences.MeasurementSystem
import de.mm20.launcher2.preferences.ui.ClockWidgetSettings
import de.mm20.launcher2.ui.base.LocalTime
import de.mm20.launcher2.ui.component.weather.WeatherIcon
import de.mm20.launcher2.ui.component.weather.WeatherIconDefaults
import de.mm20.launcher2.ui.locals.LocalDarkTheme
import de.mm20.launcher2.ui.locals.LocalMeasurementSystem
import de.mm20.launcher2.weather.Forecast
import de.mm20.launcher2.weather.WeatherRepository
import org.koin.compose.koinInject
import java.text.NumberFormat
import kotlin.math.min
import kotlin.math.roundToInt
import de.mm20.launcher2.ui.component.weather.WeatherIcon as WeatherIconKind

/** Forecasts older than this are not shown as "current" weather. */
private const val MaxForecastAgeMs = 3 * 60 * 60 * 1000L

/**
 * A built-in clock face plus the current weather beside it, sized to the configured clock box.
 *
 * Digit clocks are stretched to fill the box (taller or wider digits, like a lock-screen clock);
 * round clocks (analog, orbit, sun arc) are scaled uniformly so they never turn into ellipses.
 * The weather is never stretched: it scales uniformly with the box height.
 *
 * Custom (app widget) clocks and the empty style are passed through untouched: custom clocks
 * have their own size and the empty style has nothing to show.
 *
 * @param width clock width in dp, or null for automatic (derived from [height], or natural)
 * @param height clock height in dp, or null for automatic (natural height)
 */
@Composable
fun ClockFace(
    style: ClockWidgetStyle?,
    compact: Boolean,
    darkColors: Boolean,
    width: Int? = null,
    height: Int? = null,
    modifier: Modifier = Modifier,
) {
    if (style == null || style is ClockWidgetStyle.Custom || style is ClockWidgetStyle.Empty) {
        Clock(style, compact, darkColors)
        return
    }
    val clockSettings: ClockWidgetSettings = koinInject()
    val showWeather by clockSettings.showWeather.collectAsState(initial = true)

    ClockBoxLayout(
        width = width?.dp,
        height = height?.dp,
        stretch = style.isStretchable,
        gap = if (compact) 12.dp else 16.dp,
        modifier = modifier,
        clock = { Clock(style, compact, darkColors) },
        weather = if (showWeather) {
            { ClockWeather(compact = compact, darkColors = darkColors) }
        } else null,
    )
}

/** Digit-based faces look right stretched; round faces would distort. */
private val ClockWidgetStyle.isStretchable: Boolean
    get() = when (this) {
        is ClockWidgetStyle.Analog,
        is ClockWidgetStyle.Orbit,
        is ClockWidgetStyle.SunArc -> false

        else -> true
    }

/**
 * Lays out [clock] and an optional [weather] readout side by side inside a [width] × [height]
 * box.
 *
 * Both are first measured at their natural size. With no box size the pair keeps that size
 * (shrunk uniformly only if wider than the screen). With a box size, the weather is scaled
 * uniformly in proportion to the box height, and the clock takes the remaining space: stretched
 * to fill it exactly when [stretch] is set, otherwise scaled uniformly and centered. With only one
 * dimension given, the other follows the natural aspect ratio.
 */
@Composable
private fun ClockBoxLayout(
    width: Dp?,
    height: Dp?,
    stretch: Boolean,
    gap: Dp,
    modifier: Modifier = Modifier,
    clock: @Composable () -> Unit,
    weather: (@Composable () -> Unit)?,
) {
    Layout(
        contents = listOf(clock, weather ?: {}),
        modifier = modifier,
    ) { (clockMeasurables, weatherMeasurables), constraints ->
        val avail =
            if (constraints.hasBoundedWidth) constraints.maxWidth else Constraints.Infinity

        val weatherP = weatherMeasurables.firstOrNull()?.measure(Constraints())
        val wW = weatherP?.width ?: 0
        val wH = weatherP?.height ?: 0
        val gapPx = if (weatherP != null && wW > 0) gap.roundToPx() else 0

        val clockMax = if (avail == Constraints.Infinity) avail
        else (avail - wW - gapPx).coerceAtLeast(1)
        val clockP = clockMeasurables.firstOrNull()?.measure(Constraints(maxWidth = clockMax))
        val cW = (clockP?.width ?: 0).coerceAtLeast(1)
        val cH = (clockP?.height ?: 0).coerceAtLeast(1)

        val naturalW = cW + gapPx + wW
        val naturalH = max(cH, wH).coerceAtLeast(1)

        val targetW = width?.roundToPx()?.coerceAtMost(avail)
        val targetH = height?.roundToPx()

        val boxW: Int
        val boxH: Int
        val weatherScale: Float
        if (targetW == null && targetH == null) {
            val s = min(1f, avail.toFloat() / naturalW)
            boxW = (naturalW * s).roundToInt()
            boxH = (naturalH * s).roundToInt()
            weatherScale = s
        } else {
            boxH = targetH ?: (naturalH * (targetW!!.toFloat() / naturalW)).roundToInt()
            boxW = targetW ?: (naturalW * (boxH.toFloat() / naturalH)).roundToInt()
                .coerceAtMost(avail)
            // Weather follows the box height, but never takes more than ~40% of the width.
            val byHeight = (boxH.toFloat() / naturalH).coerceIn(0.6f, 1.6f)
            val byWidth = if (wW > 0) boxW * 0.4f / (wW + gapPx) else byHeight
            weatherScale = min(byHeight, byWidth)
        }

        val finalW = boxW.coerceIn(constraints.minWidth, constraints.maxWidth)
        val finalH = boxH.coerceIn(constraints.minHeight, constraints.maxHeight)

        val weatherAreaW = ((wW + gapPx) * weatherScale)
        val clockAreaW = (finalW - weatherAreaW).coerceAtLeast(1f)
        val clockAreaH = finalH.toFloat()

        val uniform = min(clockAreaW / cW, clockAreaH / cH)
        val stretchActive = stretch && (targetW != null || targetH != null)
        val sx = if (stretchActive) clockAreaW / cW else uniform
        val sy = if (stretchActive) clockAreaH / cH else uniform

        layout(finalW, finalH) {
            clockP?.let { p ->
                val x = ((clockAreaW - p.width * sx) / 2f).roundToInt()
                val y = ((clockAreaH - p.height * sy) / 2f).roundToInt()
                p.placeWithLayer(x, y) {
                    scaleX = sx
                    scaleY = sy
                    transformOrigin = TransformOrigin(0f, 0f)
                }
            }
            weatherP?.let { p ->
                val x = (clockAreaW + gapPx * weatherScale).roundToInt()
                val y = ((finalH - p.height * weatherScale) / 2f).roundToInt()
                p.placeWithLayer(x, y) {
                    scaleX = weatherScale
                    scaleY = weatherScale
                    transformOrigin = TransformOrigin(0f, 0f)
                }
            }
        }
    }
}

/**
 * Current temperature with a condition icon, e.g. "28°C". Renders nothing when there is no
 * recent forecast (no weather provider, or stale data).
 */
@Composable
fun ClockWeather(
    compact: Boolean,
    darkColors: Boolean,
    modifier: Modifier = Modifier,
) {
    val weatherRepository: WeatherRepository = koinInject()
    val forecasts by remember(weatherRepository) {
        weatherRepository.getForecasts()
    }.collectAsState(initial = emptyList())
    val now = LocalTime.current
    val forecast = remember(forecasts, now / 60_000L) {
        currentForecast(forecasts, now)
    }
    if (forecast == null) {
        LocationPrompt(compact = compact, modifier = modifier)
        return
    }

    val measurementSystem = LocalMeasurementSystem.current
    val clockSettings: ClockWidgetSettings = koinInject()
    val useThemeColor by clockSettings.useThemeColor.collectAsState(initial = false)

    val color = if (useThemeColor) {
        if (!darkColors) {
            if (LocalDarkTheme.current) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.primaryContainer
        } else {
            if (LocalDarkTheme.current) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.primary
        }
    } else {
        LocalContentColor.current
    }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        WeatherIcon(
            icon = weatherIconFor(forecast.icon),
            night = forecast.night,
            // The icon sits on the wallpaper, so tone it against a background opposite to the
            // content color.
            colors = WeatherIconDefaults.colors(
                background = if (darkColors) Color.White else Color.Black
            ),
            modifier = Modifier.size(if (compact) 24.dp else 32.dp),
        )
        Text(
            text = formatClockTemperature(forecast.temperature, measurementSystem),
            modifier = Modifier.padding(start = 6.dp),
            style = MaterialTheme.typography.titleLarge.copy(
                fontSize = if (compact) 20.sp else 26.sp,
                fontWeight = FontWeight.SemiBold,
            ),
            color = color,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/**
 * Shown instead of the temperature when weather uses the device location but Orb may not read
 * it: tapping asks for the permission, after which the weather is fetched automatically.
 */
@Composable
private fun LocationPrompt(compact: Boolean, modifier: Modifier) {
    val permissionsManager: PermissionsManager = koinInject()
    val weatherSettings: WeatherSettings = koinInject()
    val hasPermission by remember(permissionsManager) {
        permissionsManager.hasPermission(PermissionGroup.Location)
    }.collectAsState(initial = true)
    val autoLocation by weatherSettings.autoLocation.collectAsState(initial = false)
    if (hasPermission || !autoLocation) return

    val context = LocalContext.current
    Row(
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            .clickable {
                context.findActivity()?.let {
                    permissionsManager.requestPermission(it, PermissionGroup.Location)
                }
            }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painterResource(R.drawable.location_on_24px),
            contentDescription = null,
            modifier = Modifier.size(if (compact) 20.dp else 24.dp),
        )
        Text(
            text = stringResource(R.string.clock_weather_allow_location),
            modifier = Modifier.padding(start = 4.dp),
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            softWrap = false,
        )
    }
}

private fun Context.findActivity(): AppCompatActivity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is AppCompatActivity) return c
        c = c.baseContext
    }
    return null
}

private fun currentForecast(forecasts: List<Forecast>, now: Long): Forecast? {
    if (forecasts.isEmpty()) return null
    val sorted = forecasts.sortedBy { it.timestamp }
    val past = sorted.lastOrNull { it.timestamp <= now }
    return when {
        past != null && now - past.timestamp <= MaxForecastAgeMs -> past
        // All forecasts are in the future (fresh fetch): the nearest one is the best guess.
        past == null && sorted.first().timestamp - now <= MaxForecastAgeMs -> sorted.first()
        else -> null
    }
}

/** "28°C" / "82°F". */
private fun formatClockTemperature(kelvin: Double, measurementSystem: MeasurementSystem): String {
    val fahrenheit = measurementSystem == MeasurementSystem.UnitedStates
    val value = if (fahrenheit) kelvin * 9.0 / 5.0 - 459.67 else kelvin - 273.15
    val formatted = NumberFormat.getInstance().apply { maximumFractionDigits = 0 }
        .format(value.roundToInt())
    return "$formatted°${if (fahrenheit) "F" else "C"}"
}

private fun weatherIconFor(id: Int): WeatherIconKind = when (id) {
    Forecast.CLEAR -> WeatherIconKind.Clear
    Forecast.OVERCAST -> WeatherIconKind.Overcast
    Forecast.EXTREME_COLD -> WeatherIconKind.ExtremeCold
    Forecast.LIGHT_RAIN -> WeatherIconKind.LightRain
    Forecast.HAZE -> WeatherIconKind.Haze
    Forecast.FOG -> WeatherIconKind.Fog
    Forecast.HAIL -> WeatherIconKind.Hail
    Forecast.EXTREME_HEAT -> WeatherIconKind.ExtremeHeat
    Forecast.PARTLY_CLOUDY, Forecast.MOSTLY_CLOUDY, Forecast.BROKEN_CLOUDS ->
        WeatherIconKind.PartlyCloudy
    Forecast.RAIN -> WeatherIconKind.Rain
    Forecast.HEAVY_RAIN -> WeatherIconKind.HeavyRain
    Forecast.SLEET -> WeatherIconKind.Sleet
    Forecast.SNOW -> WeatherIconKind.Snow
    Forecast.THUNDER, Forecast.HEAVY_THUNDERSTORM -> WeatherIconKind.Thunder
    Forecast.THUNDERSTORM, Forecast.HEAVY_THUNDERSTORM_WITH_RAIN -> WeatherIconKind.Thunderstorm
    Forecast.WIND, Forecast.STORM -> WeatherIconKind.Wind
    else -> WeatherIconKind.Unknown
}
