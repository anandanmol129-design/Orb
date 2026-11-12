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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import de.mm20.launcher2.preferences.ClockWidgetStyle
import de.mm20.launcher2.preferences.MeasurementSystem
import de.mm20.launcher2.ui.base.LocalTime
import de.mm20.launcher2.ui.component.weather.WeatherIcon
import de.mm20.launcher2.ui.component.weather.WeatherIconDefaults
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
 * A built-in clock face sized to the configured clock box.
 *
 * Digit faces are stretched to fill the box exactly (like a lock-screen clock), while
 * round clocks (analog, orbit, sun arc) are scaled uniformly so they never turn into ellipses.
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
    ClockBoxLayout(
        width = width?.dp,
        height = height?.dp,
        stretch = style.isStretchable,
        modifier = modifier,
        clock = { Clock(style, compact, darkColors) },
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
 * Lays out [clock] inside a [width] × [height] box.
 *
 * The clock is first measured at its natural size. With no box size it keeps that size (shrunk
 * uniformly only if wider than the screen). With a box size it is stretched to fill the box
 * exactly when [stretch] is set, otherwise scaled uniformly and centered. With only one dimension
 * given, the other follows the natural aspect ratio.
 */
@Composable
private fun ClockBoxLayout(
    width: Dp?,
    height: Dp?,
    stretch: Boolean,
    modifier: Modifier = Modifier,
    clock: @Composable () -> Unit,
) {
    Layout(content = clock, modifier = modifier) { measurables, constraints ->
        val avail =
            if (constraints.hasBoundedWidth) constraints.maxWidth else Constraints.Infinity
        val clockP = measurables.firstOrNull()?.measure(Constraints(maxWidth = avail))
        val cW = (clockP?.width ?: 0).coerceAtLeast(1)
        val cH = (clockP?.height ?: 0).coerceAtLeast(1)

        val targetW = width?.roundToPx()?.coerceAtMost(avail)
        val targetH = height?.roundToPx()

        val boxW: Int
        val boxH: Int
        if (targetW == null && targetH == null) {
            val s = min(1f, avail.toFloat() / cW)
            boxW = (cW * s).roundToInt()
            boxH = (cH * s).roundToInt()
        } else {
            boxH = targetH ?: (cH * (targetW!!.toFloat() / cW)).roundToInt()
            boxW = targetW ?: (cW * (boxH.toFloat() / cH)).roundToInt().coerceAtMost(avail)
        }

        val finalW = boxW.coerceIn(constraints.minWidth, constraints.maxWidth)
        val finalH = boxH.coerceIn(constraints.minHeight, constraints.maxHeight)

        val uniform = min(finalW.toFloat() / cW, finalH.toFloat() / cH)
        val stretchActive = stretch && (targetW != null || targetH != null)
        val sx = if (stretchActive) finalW.toFloat() / cW else uniform
        val sy = if (stretchActive) finalH.toFloat() / cH else uniform

        layout(finalW, finalH) {
            clockP?.let { p ->
                val x = ((finalW - p.width * sx) / 2f).roundToInt()
                val y = ((finalH - p.height * sy) / 2f).roundToInt()
                p.placeWithLayer(x, y) {
                    scaleX = sx
                    scaleY = sy
                    transformOrigin = TransformOrigin(0f, 0f)
                }
            }
        }
    }
}

/**
 * Current temperature with a condition icon, e.g. "28°C", styled like the clock's date line.
 * Without a recent forecast it shows the location prompt if that is the cause, else nothing.
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
    // Same text style as the date line it sits in.
    val textStyle = if (compact) {
        MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Medium)
    } else {
        MaterialTheme.typography.titleMedium
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
            modifier = Modifier.size(if (compact) 24.dp else 20.dp),
        )
        Text(
            text = formatClockTemperature(forecast.temperature, measurementSystem),
            modifier = Modifier.padding(start = 6.dp),
            style = textStyle,
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
