package de.mm20.launcher2.ui.launcher.widgets.weather

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.mm20.launcher2.ktx.tryStartActivity
import de.mm20.launcher2.preferences.MeasurementSystem
import de.mm20.launcher2.preferences.TimeFormat
import de.mm20.launcher2.ui.R
import de.mm20.launcher2.ui.common.WeatherLocationSearchDialog
import de.mm20.launcher2.ui.component.Banner
import de.mm20.launcher2.ui.component.MissingPermissionBanner
import de.mm20.launcher2.ui.component.weather.AnimatedWeatherIcon
import de.mm20.launcher2.ui.component.weather.WeatherIcon
import de.mm20.launcher2.ui.component.weather.WeatherIconDefaults
import de.mm20.launcher2.ui.locals.LocalMeasurementSystem
import de.mm20.launcher2.ui.locals.LocalTimeFormat
import de.mm20.launcher2.ui.utils.formatPercent
import de.mm20.launcher2.ui.utils.formatSpeed
import de.mm20.launcher2.ui.utils.formatTemperature
import de.mm20.launcher2.weather.DailyForecast
import de.mm20.launcher2.weather.Forecast
import de.mm20.launcher2.widgets.WeatherWidget
import java.text.SimpleDateFormat
import java.util.Calendar
import kotlin.math.roundToInt

@Composable
fun WeatherWidget(widget: WeatherWidget) {
    val viewModel: WeatherWidgetVM = viewModel(key = "weather-widget-${widget.id}")

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(null) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            viewModel.selectNow()
        }
    }

    val selectedForecast by viewModel.currentForecast

    val measurementSystem = LocalMeasurementSystem.current
    val timeFormat = LocalTimeFormat.current

    val isProviderAvailable by viewModel.isProviderAvailable.collectAsStateWithLifecycle(true)

    var showLocationDialog by remember { mutableStateOf(false) }

    WeatherLocationSearchDialog(
        expanded = showLocationDialog,
        onDismissRequest = { showLocationDialog = false }
    )

    Column {
        if (!isProviderAvailable) {
            Banner(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                text = stringResource(R.string.weather_widget_no_provider),
                icon = R.drawable.error_24px,
                primaryAction = {
                    Button(
                        onClick = { viewModel.openSettings(context) },
                        contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                    ) {
                        Icon(
                            painterResource(R.drawable.open_in_new_24px),
                            null,
                            modifier = Modifier
                                .padding(end = ButtonDefaults.IconSpacing)
                                .size(ButtonDefaults.IconSize)
                        )
                        Text(stringResource(R.string.settings))
                    }
                }
            )
        }

        val forecast = selectedForecast ?: run {
            val hasPermission by viewModel.hasLocationPermission.collectAsState()
            val autoLocation by viewModel.autoLocation.collectAsState()
            AnimatedVisibility(hasPermission == false && autoLocation == true) {
                MissingPermissionBanner(
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .padding(top = 16.dp),
                    text = stringResource(id = R.string.missing_permission_auto_location),
                    onClick = {
                        viewModel.requestLocationPermission(context as AppCompatActivity)
                    },
                    secondaryAction = {
                        OutlinedButton(onClick = { showLocationDialog = true }) {
                            Text(stringResource(R.string.weather_widget_set_location))
                        }
                    }
                )
            }
            NoData()
            return
        }

        val dailyForecasts by viewModel.dailyForecasts
        val selectedDay by viewModel.currentDailyForecast
        val dayForecasts by viewModel.currentDayForecasts

        WeatherHero(
            forecast = forecast,
            day = selectedDay,
            measurementSystem = measurementSystem,
        )

        if (widget.config.showForecast) {
            AnimatedContent(
                dayForecasts,
                transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(120)) },
                label = "hourly",
            ) { forecasts ->
                HourlyChart(
                    forecasts = forecasts,
                    selected = forecast,
                    measurementSystem = measurementSystem,
                    timeFormat = timeFormat,
                    onSelect = { viewModel.selectForecast(it) },
                )
            }
            if (dailyForecasts.size > 1) {
                DailyList(
                    days = dailyForecasts,
                    selected = selectedDay,
                    measurementSystem = measurementSystem,
                    onSelect = { viewModel.selectDay(it) },
                )
            }
        }

        ProviderLine(forecast, timeFormat)
    }
}

/** Two-stop sky gradient for a condition, plus whether the sky is light enough to need dark text. */
private data class Sky(val top: Color, val bottom: Color)

private fun skyFor(forecast: Forecast): Sky {
    val night = forecast.night
    fun sky(day: Pair<Long, Long>, night_: Pair<Long, Long>) =
        (if (night) night_ else day).let { Sky(Color(it.first), Color(it.second)) }
    return when (forecast.icon) {
        Forecast.CLEAR -> sky(0xFF2F7FD3 to 0xFF7CC0F2, 0xFF0B1636 to 0xFF27346A)
        Forecast.PARTLY_CLOUDY, Forecast.MOSTLY_CLOUDY, Forecast.BROKEN_CLOUDS ->
            sky(0xFF4F86C0 to 0xFF9DBBD8, 0xFF141E38 to 0xFF35415F)

        Forecast.OVERCAST -> sky(0xFF5E7186 to 0xFF96A6B6, 0xFF1C232E to 0xFF3A4452)
        Forecast.LIGHT_RAIN, Forecast.RAIN, Forecast.HEAVY_RAIN, Forecast.SLEET ->
            sky(0xFF3D5266 to 0xFF71879B, 0xFF151D27 to 0xFF2F3D4C)

        Forecast.THUNDER, Forecast.THUNDERSTORM, Forecast.HEAVY_THUNDERSTORM,
        Forecast.HEAVY_THUNDERSTORM_WITH_RAIN -> sky(0xFF2C2A45 to 0xFF5A527E, 0xFF15131F to 0xFF35304F)

        Forecast.SNOW, Forecast.HAIL, Forecast.EXTREME_COLD ->
            sky(0xFF6B87A8 to 0xFFA9C0D8, 0xFF1F2B3D to 0xFF475A73)

        Forecast.FOG, Forecast.HAZE -> sky(0xFF73808C to 0xFFA8B2BB, 0xFF22272D to 0xFF454C55)
        Forecast.EXTREME_HEAT -> sky(0xFFD0662A to 0xFFF0A956, 0xFF3A1E1A to 0xFF6B3A2A)
        Forecast.WIND, Forecast.STORM -> sky(0xFF477391 to 0xFF8AACC2, 0xFF17222F to 0xFF34475A)
        else -> sky(0xFF4F86C0 to 0xFF9DBBD8, 0xFF141E38 to 0xFF35415F)
    }
}

@Composable
private fun WeatherHero(
    forecast: Forecast,
    day: DailyForecast?,
    measurementSystem: MeasurementSystem,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val weatherApp = remember {
        context.packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).also {
                it.addCategory(Intent.CATEGORY_APP_WEATHER)
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }, 0
        )
    }

    val sky = skyFor(forecast)
    val top by animateColorAsState(sky.top, tween(700), label = "skyTop")
    val bottom by animateColorAsState(sky.bottom, tween(700), label = "skyBottom")
    var bounds by remember { mutableStateOf(Rect.Zero) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Brush.linearGradient(listOf(top, bottom), start = Offset.Zero, end = Offset.Infinite))
            .onPlaced {
                val p = it.positionInRoot()
                bounds = Rect(p.x, p.y, p.x + it.size.width, p.y + it.size.height)
            }
            .clickable(
                enabled = weatherApp != null,
                interactionSource = remember { MutableInteractionSource() },
                indication = LocalIndication.current,
            ) {
                context.tryStartActivity(
                    Intent().also {
                        it.component = weatherApp?.activityInfo?.let { info ->
                            ComponentName(info.packageName, info.name)
                        }
                    },
                    ActivityOptionsCompat.makeClipRevealAnimation(
                        view,
                        bounds.left.toInt(),
                        bounds.top.toInt(),
                        bounds.width.toInt(),
                        bounds.height.toInt()
                    ).toBundle()
                )
            }
    ) {
        // Soft glow where the sun or moon would sit.
        Canvas(Modifier.matchParentSize()) {
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(Color.White.copy(alpha = if (forecast.night) 0.10f else 0.22f), Color.Transparent),
                    center = Offset(size.width * 0.82f, size.height * 0.18f),
                    radius = size.minDimension * 0.9f,
                ),
                radius = size.minDimension * 0.9f,
                center = Offset(size.width * 0.82f, size.height * 0.18f),
            )
        }
        Column(Modifier.padding(start = 20.dp, end = 12.dp, top = 16.dp, bottom = 14.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            painterResource(R.drawable.location_on_20px),
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.85f),
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            text = forecast.location,
                            style = MaterialTheme.typography.titleSmall,
                            color = Color.White.copy(alpha = 0.92f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                    AnimatedContent(
                        formatTemperature(context, forecast.temperature.toFloat(), measurementSystem),
                        transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(150)) },
                        label = "temperature",
                    ) { temp ->
                        Text(
                            text = temp,
                            fontSize = 64.sp,
                            lineHeight = 68.sp,
                            fontWeight = FontWeight.Light,
                            color = Color.White,
                        )
                    }
                    Text(
                        text = forecast.condition,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (day != null) {
                        Text(
                            text = "↑ ${formatTemperature(context, day.maxTemp.toFloat(), measurementSystem)}" +
                                    "   ↓ ${formatTemperature(context, day.minTemp.toFloat(), measurementSystem)}",
                            style = MaterialTheme.typography.labelLarge,
                            color = Color.White.copy(alpha = 0.8f),
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
                AnimatedWeatherIcon(
                    modifier = Modifier
                        .padding(top = 12.dp, end = 16.dp)
                        .scale(1.7f),
                    icon = weatherIconById(forecast.icon),
                    night = forecast.night,
                    colors = WeatherIconDefaults.colors(bottom),
                )
            }
            DetailPills(forecast, measurementSystem, Modifier.padding(top = 14.dp))
        }
    }
}

@Composable
private fun DetailPills(
    forecast: Forecast,
    measurementSystem: MeasurementSystem,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        forecast.windSpeed?.let { speed ->
            val dir = forecast.windDirection
            Pill(
                icon = if (dir != null) R.drawable.navigation_20px else R.drawable.air_20px,
                // Wind direction is where it blows from; the arrow points where it goes.
                iconRotation = dir?.let { it.toFloat() + 180f } ?: 0f,
                text = formatSpeed(context, speed.toFloat(), measurementSystem),
            )
        }
        forecast.humidity?.let {
            Pill(R.drawable.humidity_mid_20px, text = formatPercent(it.toFloat()))
        }
        val precip = forecast.precipProbability
        if (precip != null && precip > 0) {
            Pill(R.drawable.rainy_20px, text = formatPercent(precip.toFloat()))
        }
        forecast.uvIndex?.takeIf { it >= 1 }?.let {
            Pill(R.drawable.sunny_20px, text = stringResource(R.string.uv_index, it.roundToInt()))
        }
    }
}

@Composable
private fun Pill(@DrawableRes icon: Int, text: String, iconRotation: Float = 0f) {
    val rotation by animateFloatAsState(iconRotation, label = "pillIcon")
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.16f))
            .padding(start = 8.dp, end = 10.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painterResource(icon),
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier
                .size(14.dp)
                .rotate(rotation),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = Color.White,
            modifier = Modifier.padding(start = 4.dp),
            maxLines = 1,
        )
    }
}

/** Colour that reads as the temperature: icy blue when freezing, through green and amber, to red. */
private fun temperatureColor(kelvin: Double): Color {
    val c = (kelvin - 273.15).toFloat()
    val stops = listOf(
        -10f to Color(0xFF7FA7FF),
        0f to Color(0xFF5BC0EB),
        10f to Color(0xFF4CC9A0),
        18f to Color(0xFFB5D660),
        24f to Color(0xFFF2C94C),
        30f to Color(0xFFF2994A),
        36f to Color(0xFFEB5757),
    )
    if (c <= stops.first().first) return stops.first().second
    if (c >= stops.last().first) return stops.last().second
    val i = stops.indexOfFirst { it.first >= c }
    val (t0, c0) = stops[i - 1]
    val (t1, c1) = stops[i]
    return lerp(c0, c1, (c - t0) / (t1 - t0))
}

private val HourWidth = 58.dp

@Composable
private fun HourlyChart(
    forecasts: List<Forecast>,
    selected: Forecast,
    measurementSystem: MeasurementSystem,
    timeFormat: TimeFormat,
    onSelect: (Int) -> Unit,
) {
    if (forecasts.isEmpty()) return
    val context = LocalContext.current
    val density = LocalDensity.current
    val scrollState: ScrollState = rememberScrollState()
    val selectedIndex = forecasts.indexOf(selected).coerceAtLeast(0)

    LaunchedEffect(forecasts) {
        // Bring the selected hour into view, with one hour of context before it.
        val target = with(density) { (HourWidth * (selectedIndex - 1).coerceAtLeast(0)).roundToPx() }
        scrollState.animateScrollTo(target)
    }

    val minT = forecasts.minOf { it.temperature }
    val maxT = forecasts.maxOf { it.temperature }
    val span = (maxT - minT).coerceAtLeast(1.0)

    val measurer = rememberTextMeasurer()
    val labelColor = MaterialTheme.colorScheme.onSurface
    val mutedColor = MaterialTheme.colorScheme.onSurfaceVariant
    val rainColor = Color(0xFF5BA4F2)
    val highlight = MaterialTheme.colorScheme.secondaryContainer
    val selectedX by animateFloatAsState(selectedIndex.toFloat(), label = "hourSelection")
    val iconColors = WeatherIconDefaults.colors(MaterialTheme.colorScheme.surface)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState)
            .padding(horizontal = 8.dp)
    ) {
        val totalWidth = HourWidth * forecasts.size
        Canvas(
            Modifier
                .width(totalWidth)
                .height(150.dp)
                .pointerInput(forecasts) {
                    detectTapGestures { offset ->
                        val i = (offset.x / HourWidth.toPx()).toInt().coerceIn(0, forecasts.lastIndex)
                        onSelect(i)
                    }
                }
        ) {
            val colW = HourWidth.toPx()
            // Selected column highlight.
            drawRoundRect(
                color = highlight.copy(alpha = 0.7f),
                topLeft = Offset(selectedX * colW + 3.dp.toPx(), 0f),
                size = Size(colW - 6.dp.toPx(), size.height),
                cornerRadius = CornerRadius(16.dp.toPx()),
            )

            val curveTop = 72.dp.toPx()
            val curveBottom = 116.dp.toPx()
            val points = forecasts.mapIndexed { i, fc ->
                Offset(
                    i * colW + colW / 2,
                    curveBottom - ((fc.temperature - minT) / span).toFloat() * (curveBottom - curveTop),
                )
            }

            // Smooth curve through the hourly temperatures.
            val line = Path().apply {
                moveTo(points.first().x, points.first().y)
                for (i in 1 until points.size) {
                    val p0 = points[i - 1]
                    val p1 = points[i]
                    val mx = (p0.x + p1.x) / 2
                    cubicTo(mx, p0.y, mx, p1.y, p1.x, p1.y)
                }
            }
            val fill = Path().apply {
                addPath(line)
                lineTo(points.last().x, curveBottom + 18.dp.toPx())
                lineTo(points.first().x, curveBottom + 18.dp.toPx())
                close()
            }
            val gradient = Brush.horizontalGradient(
                forecasts.map { temperatureColor(it.temperature) }.let { if (it.size == 1) it + it else it },
                startX = points.first().x,
                endX = points.last().x,
            )
            drawPath(
                fill,
                brush = Brush.verticalGradient(
                    listOf(temperatureColor(maxT).copy(alpha = 0.28f), Color.Transparent),
                    startY = curveTop,
                    endY = curveBottom + 18.dp.toPx(),
                ),
            )
            drawPath(line, brush = gradient, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))

            forecasts.forEachIndexed { i, fc ->
                val p = points[i]
                val isSelected = i == selectedIndex
                drawCircle(
                    color = temperatureColor(fc.temperature),
                    radius = (if (isSelected) 5.5f else 3f).dp.toPx(),
                    center = p,
                )
                if (isSelected) {
                    drawCircle(Color.White, radius = 2.dp.toPx(), center = p)
                }

                val temp = measurer.measure(
                    formatTemperature(context, fc.temperature.toFloat(), measurementSystem),
                    style = TextStyle(
                        fontSize = 13.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        color = labelColor,
                    ),
                )
                drawText(temp, topLeft = Offset(p.x - temp.size.width / 2, p.y - temp.size.height - 6.dp.toPx()))

                val time = measurer.measure(
                    formatHour(context, fc.timestamp, timeFormat),
                    style = TextStyle(fontSize = 11.sp, color = if (isSelected) labelColor else mutedColor),
                )
                drawText(time, topLeft = Offset(p.x - time.size.width / 2, 8.dp.toPx()))

                val precip = fc.precipProbability ?: 0
                if (precip >= 10) {
                    val text = measurer.measure(
                        "$precip%",
                        style = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Medium, color = rainColor),
                    )
                    drawText(text, topLeft = Offset(p.x - text.size.width / 2, size.height - text.size.height - 6.dp.toPx()))
                }
            }
        }
        // Icons sit in a row between the time labels and the curve.
        Row(Modifier.padding(top = 26.dp)) {
            forecasts.forEach { fc ->
                Box(Modifier.width(HourWidth), contentAlignment = Alignment.Center) {
                    de.mm20.launcher2.ui.component.weather.WeatherIcon(
                        icon = weatherIconById(fc.icon),
                        night = fc.night,
                        colors = iconColors,
                        modifier = Modifier
                            .scale(0.85f)
                            .semantics { contentDescription = fc.condition },
                    )
                }
            }
        }
    }
}

@Composable
private fun DailyList(
    days: List<DailyForecast>,
    selected: DailyForecast?,
    measurementSystem: MeasurementSystem,
    onSelect: (Int) -> Unit,
) {
    val context = LocalContext.current
    val dayFormat = remember { SimpleDateFormat("EEE") }
    val lowest = days.minOf { it.minTemp }
    val highest = days.maxOf { it.maxTemp }
    val range = (highest - lowest).coerceAtLeast(1.0)
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    val iconColors = WeatherIconDefaults.colors(MaterialTheme.colorScheme.surface)
    val today = remember { Calendar.getInstance() }

    Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
        days.forEachIndexed { i, day ->
            val isSelected = day == selected
            val bg by animateColorAsState(
                if (isSelected) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f)
                else Color.Transparent,
                label = "daySelection",
            )
            val isToday = Calendar.getInstance().apply { timeInMillis = day.timestamp }.let {
                it.get(Calendar.YEAR) == today.get(Calendar.YEAR) &&
                        it.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)
            }
            val rain = day.hourlyForecasts.mapNotNull { it.precipProbability }.maxOrNull() ?: 0
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(bg)
                    .clickable { onSelect(i) }
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (isToday) stringResource(R.string.date_today) else dayFormat.format(day.timestamp),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    modifier = Modifier.width(56.dp),
                    maxLines = 1,
                )
                Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                    de.mm20.launcher2.ui.component.weather.WeatherIcon(
                        icon = weatherIconById(day.icon),
                        colors = iconColors,
                        modifier = Modifier.scale(0.8f),
                    )
                }
                Text(
                    text = if (rain >= 20) "$rain%" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF5BA4F2),
                    modifier = Modifier.width(36.dp),
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = formatTemperature(context, day.minTemp.toFloat(), measurementSystem),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(40.dp),
                )
                Canvas(
                    Modifier
                        .weight(1f)
                        .height(6.dp)
                        .padding(horizontal = 10.dp)
                ) {
                    val radius = CornerRadius(size.height / 2)
                    drawRoundRect(track, cornerRadius = radius)
                    val start = ((day.minTemp - lowest) / range).toFloat() * size.width
                    val end = ((day.maxTemp - lowest) / range).toFloat() * size.width
                    drawRoundRect(
                        brush = Brush.horizontalGradient(
                            listOf(temperatureColor(day.minTemp), temperatureColor(day.maxTemp)),
                            startX = start,
                            endX = end,
                        ),
                        topLeft = Offset(start, 0f),
                        size = Size((end - start).coerceAtLeast(size.height), size.height),
                        cornerRadius = radius,
                    )
                }
                Text(
                    text = formatTemperature(context, day.maxTemp.toFloat(), measurementSystem),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.width(40.dp),
                )
            }
        }
    }
}

@Composable
private fun ProviderLine(forecast: Forecast, timeFormat: TimeFormat) {
    val context = LocalContext.current
    Text(
        text = "${forecast.provider} · ${formatTime(context, forecast.updateTime, timeFormat)}",
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        modifier = Modifier
            .clickable {
                val url = forecast.providerUrl.takeIf { it.isNotBlank() } ?: return@clickable
                context.tryStartActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }
            .padding(horizontal = 20.dp, vertical = 8.dp),
    )
}

private fun formatTime(context: Context, timestamp: Long, timeFormat: TimeFormat): String {
    val timeFormatFlag = when (timeFormat) {
        TimeFormat.TwelveHour -> DateUtils.FORMAT_12HOUR
        TimeFormat.TwentyFourHour -> DateUtils.FORMAT_24HOUR
        else -> 0
    }
    return DateUtils.formatDateTime(context, timestamp, DateUtils.FORMAT_SHOW_TIME or timeFormatFlag)
}

/** Short hour label for the chart: "14" or "2 PM", whichever the user's clock uses. */
private fun formatHour(context: Context, timestamp: Long, timeFormat: TimeFormat): String {
    val is24 = when (timeFormat) {
        TimeFormat.TwentyFourHour -> true
        TimeFormat.TwelveHour -> false
        else -> android.text.format.DateFormat.is24HourFormat(context)
    }
    return SimpleDateFormat(if (is24) "HH:mm" else "h a").format(timestamp)
}

private fun weatherIconById(id: Int): WeatherIcon {
    return when (id) {
        Forecast.CLEAR -> WeatherIcon.Clear
        Forecast.OVERCAST -> WeatherIcon.Overcast
        Forecast.EXTREME_COLD -> WeatherIcon.ExtremeHeat
        Forecast.LIGHT_RAIN -> WeatherIcon.LightRain
        Forecast.HAZE -> WeatherIcon.Haze
        Forecast.FOG -> WeatherIcon.Fog
        Forecast.HAIL -> WeatherIcon.Hail
        Forecast.EXTREME_HEAT -> WeatherIcon.ExtremeHeat
        Forecast.PARTLY_CLOUDY -> WeatherIcon.PartlyCloudy
        Forecast.RAIN -> WeatherIcon.Rain
        Forecast.HEAVY_RAIN -> WeatherIcon.HeavyRain
        Forecast.SLEET -> WeatherIcon.Sleet
        Forecast.SNOW -> WeatherIcon.Snow
        Forecast.THUNDER -> WeatherIcon.Thunder
        Forecast.THUNDERSTORM -> WeatherIcon.Thunderstorm
        Forecast.WIND -> WeatherIcon.Wind
        Forecast.MOSTLY_CLOUDY -> WeatherIcon.PartlyCloudy
        Forecast.STORM -> WeatherIcon.Wind
        Forecast.BROKEN_CLOUDS -> WeatherIcon.PartlyCloudy
        Forecast.HEAVY_THUNDERSTORM -> WeatherIcon.Thunder
        Forecast.HEAVY_THUNDERSTORM_WITH_RAIN -> WeatherIcon.Thunderstorm
        else -> WeatherIcon.Unknown
    }
}

@Composable
fun NoData() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentHeight(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(R.drawable.light_mode_24px),
            contentDescription = "",
            modifier = Modifier
                .padding(24.dp)
                .size(32.dp),
            tint = MaterialTheme.colorScheme.secondary
        )
        Text(
            text = stringResource(id = R.string.weather_no_data),
            style = MaterialTheme.typography.bodySmall
        )
    }
}
