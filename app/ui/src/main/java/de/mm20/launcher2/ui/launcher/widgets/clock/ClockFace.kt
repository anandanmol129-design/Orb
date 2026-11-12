package de.mm20.launcher2.ui.launcher.widgets.clock

import androidx.compose.foundation.layout.Arrangement
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
 * A built-in clock face plus the current weather beside it, scaled as one unit to fit the
 * configured clock size.
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

    ScaleToFit(
        width = width?.dp,
        height = height?.dp,
        modifier = modifier,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            // weight(fill = false) makes the Row measure the weather first, so clocks that
            // stretch to the available width never squeeze it out.
            Row(modifier = Modifier.weight(1f, fill = false)) {
                Clock(style, compact, darkColors)
            }
            if (showWeather) {
                ClockWeather(
                    compact = compact,
                    darkColors = darkColors,
                    modifier = Modifier.padding(start = if (compact) 12.dp else 16.dp),
                )
            }
        }
    }
}

/**
 * Lays out [content] at its natural size, then uniformly scales it to fit a [width] × [height]
 * box (centered). With only one dimension given, the other follows the content's aspect ratio;
 * with neither, the content keeps its natural size. The box never exceeds the incoming max width.
 */
@Composable
fun ScaleToFit(
    width: Dp?,
    height: Dp?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val availableWidth =
            if (constraints.hasBoundedWidth) constraints.maxWidth else Constraints.Infinity
        // Natural size: as wide as it likes up to the available width, unbounded height.
        val placeables = measurables.map {
            it.measure(Constraints(maxWidth = availableWidth))
        }
        val naturalWidth = placeables.maxOfOrNull { it.width }?.coerceAtLeast(1) ?: 1
        val naturalHeight = placeables.maxOfOrNull { it.height }?.coerceAtLeast(1) ?: 1

        val targetW = width?.roundToPx()
        val targetH = height?.roundToPx()

        val scale = when {
            targetW != null && targetH != null -> min(
                min(targetW, availableWidth).toFloat() / naturalWidth,
                targetH.toFloat() / naturalHeight,
            )

            targetH != null -> targetH.toFloat() / naturalHeight
            targetW != null -> min(targetW, availableWidth).toFloat() / naturalWidth
            else -> 1f
        }.let {
            // Even an auto-sized face must never overflow the available width.
            min(it, availableWidth.toFloat() / naturalWidth)
        }

        val boxW = (targetW ?: (naturalWidth * scale).roundToInt())
            .coerceAtMost(availableWidth)
            .coerceIn(constraints.minWidth, constraints.maxWidth)
        val boxH = (targetH ?: (naturalHeight * scale).roundToInt())
            .coerceIn(constraints.minHeight, constraints.maxHeight)

        layout(boxW, boxH) {
            placeables.forEach { p ->
                val scaledW = p.width * scale
                val scaledH = p.height * scale
                val x = ((boxW - scaledW) / 2f).roundToInt()
                val y = ((boxH - scaledH) / 2f).roundToInt()
                p.placeWithLayer(x, y) {
                    scaleX = scale
                    scaleY = scale
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
    } ?: return

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
