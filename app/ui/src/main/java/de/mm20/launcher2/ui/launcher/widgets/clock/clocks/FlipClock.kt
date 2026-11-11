package de.mm20.launcher2.ui.launcher.widgets.clock.clocks

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import palettes.TonalPalette
import java.time.Instant
import java.time.ZoneId

private const val FlipDurationMillis = 600

@Composable
fun FlipClock(
    time: Long,
    compact: Boolean,
    showSeconds: Boolean,
    twentyFourHours: Boolean,
    useThemeColor: Boolean,
    darkColors: Boolean,
) {
    val parsed = Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault())
    val hour = if (twentyFourHours) parsed.hour else (((parsed.hour + 11) % 12) + 1)
    val minute = parsed.minute
    val second = parsed.second

    // Same colour approach as OrbitClock: a filled card in the content colour (or a theme
    // tone), with the digit drawn in the inverted / contrasting colour. LocalContentColor is
    // already chosen by the widget to contrast with the wallpaper.
    val fgTone = if (!darkColors) 10 else 90
    val bgTone = if (!darkColors) 90 else 30

    val cardColor = if (useThemeColor) {
        Color(TonalPalette.fromInt(MaterialTheme.colorScheme.primaryContainer.toArgb()).tone(bgTone))
    } else {
        LocalContentColor.current
    }
    val digitColor = if (useThemeColor) {
        Color(TonalPalette.fromInt(MaterialTheme.colorScheme.onPrimaryContainer.toArgb()).tone(fgTone))
    } else {
        LocalContentColor.current.flipInvert()
    }

    val dims = if (compact) {
        FlipDimensions(cardWidth = 20.dp, cardHeight = 30.dp, corner = 4.dp, gap = 2.dp, colonWidth = 10.dp, dot = 3.dp)
    } else {
        FlipDimensions(cardWidth = 36.dp, cardHeight = 54.dp, corner = 6.dp, gap = 3.dp, colonWidth = 16.dp, dot = 5.dp)
    }

    val fontSize = with(LocalDensity.current) { (dims.cardHeight * 0.72f).toSp() }
    val textStyle = MaterialTheme.typography.displayMedium.copy(
        fontSize = fontSize,
        lineHeight = fontSize,
        fontWeight = FontWeight.Medium,
        fontFeatureSettings = "tnum",
        textAlign = TextAlign.Center,
        color = digitColor,
        lineHeightStyle = LineHeightStyle(
            alignment = LineHeightStyle.Alignment.Center,
            trim = LineHeightStyle.Trim.Both,
        ),
    )

    Row(
        modifier = Modifier.padding(vertical = if (!compact) 12.dp else 0.dp),
        horizontalArrangement = Arrangement.spacedBy(dims.gap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FlipDigit(hour / 10, dims, cardColor, digitColor, textStyle)
        FlipDigit(hour % 10, dims, cardColor, digitColor, textStyle)
        FlipColon(dims, cardColor)
        FlipDigit(minute / 10, dims, cardColor, digitColor, textStyle)
        FlipDigit(minute % 10, dims, cardColor, digitColor, textStyle)
        // Like the other clocks, seconds are only shown in the large layout.
        if (!compact && showSeconds) {
            FlipColon(dims, cardColor)
            FlipDigit(second / 10, dims, cardColor, digitColor, textStyle)
            FlipDigit(second % 10, dims, cardColor, digitColor, textStyle)
        }
    }
}

private data class FlipDimensions(
    val cardWidth: Dp,
    val cardHeight: Dp,
    val corner: Dp,
    val gap: Dp,
    val colonWidth: Dp,
    val dot: Dp,
)

@Composable
private fun FlipDigit(
    digit: Int,
    dims: FlipDimensions,
    cardColor: Color,
    digitColor: Color,
    textStyle: TextStyle,
) {
    var current by remember { mutableIntStateOf(digit) }
    var previous by remember { mutableIntStateOf(digit) }
    var flipping by remember { mutableStateOf(false) }
    // 0 -> 1 over the whole flip: first half folds the old top down, second half
    // lands the new bottom. Only read inside graphicsLayer/draw lambdas, so frames
    // during the flip don't recompose, and nothing runs while idle.
    val progress = remember { Animatable(1f) }

    LaunchedEffect(digit) {
        if (digit == current) return@LaunchedEffect
        previous = current
        current = digit
        flipping = true
        try {
            progress.snapTo(0f)
            progress.animateTo(
                1f,
                tween(durationMillis = FlipDurationMillis, easing = FastOutSlowInEasing)
            )
        } finally {
            flipping = false
            previous = current
        }
    }

    val halfHeight = dims.cardHeight / 2
    val topShape = RoundedCornerShape(topStart = dims.corner, topEnd = dims.corner)
    val bottomShape = RoundedCornerShape(bottomStart = dims.corner, bottomEnd = dims.corner)

    Box(modifier = Modifier.size(dims.cardWidth, dims.cardHeight)) {
        Column {
            // Static top: always the new digit (revealed as the old top folds away).
            FlipHalf(
                digit = current, top = true, halfHeight = halfHeight, cardHeight = dims.cardHeight,
                shape = topShape, cardColor = cardColor, textStyle = textStyle,
                shade = if (flipping) {
                    { (1f - (progress.value * 2f).coerceIn(0f, 1f)) * 0.25f }
                } else null,
            )
            // Static bottom: the old digit until the new bottom flap lands on it.
            FlipHalf(
                digit = if (flipping) previous else current, top = false, halfHeight = halfHeight,
                cardHeight = dims.cardHeight, shape = bottomShape, cardColor = cardColor,
                textStyle = textStyle,
                shade = if (flipping) {
                    { ((progress.value - 0.5f) * 2f).coerceIn(0f, 1f) * 0.2f }
                } else null,
            )
        }

        if (flipping) {
            // Top flap: old digit's top half folding down, 0 -> -90 degrees.
            FlipHalf(
                digit = previous, top = true, halfHeight = halfHeight, cardHeight = dims.cardHeight,
                shape = topShape, cardColor = cardColor, textStyle = textStyle,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .graphicsLayer {
                        val f = (progress.value * 2f).coerceIn(0f, 1f)
                        transformOrigin = TransformOrigin(0.5f, 1f)
                        cameraDistance = 12f * density
                        rotationX = -90f * f
                        alpha = if (f < 1f) 1f else 0f
                    },
                shade = { (progress.value * 2f).coerceIn(0f, 1f) * 0.45f },
            )
            // Bottom flap: new digit's bottom half landing, 90 -> 0 degrees.
            FlipHalf(
                digit = current, top = false, halfHeight = halfHeight, cardHeight = dims.cardHeight,
                shape = bottomShape, cardColor = cardColor, textStyle = textStyle,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .graphicsLayer {
                        val f = ((progress.value - 0.5f) * 2f).coerceIn(0f, 1f)
                        transformOrigin = TransformOrigin(0.5f, 0f)
                        cameraDistance = 12f * density
                        rotationX = 90f * (1f - f)
                        alpha = if (progress.value > 0.5f) 1f else 0f
                    },
                shade = { (1f - ((progress.value - 0.5f) * 2f).coerceIn(0f, 1f)) * 0.45f },
            )
        }

        // Hinge line across the middle of the card.
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .height(if (dims.cardHeight > 40.dp) 1.5.dp else 1.dp)
                .background(digitColor.copy(alpha = 0.35f))
        )
    }
}

/**
 * One half of a card: a half-height clipped window onto a full-height centred digit.
 */
@Composable
private fun FlipHalf(
    digit: Int,
    top: Boolean,
    halfHeight: Dp,
    cardHeight: Dp,
    shape: Shape,
    cardColor: Color,
    textStyle: TextStyle,
    modifier: Modifier = Modifier,
    shade: (() -> Float)? = null,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(halfHeight)
            .clip(shape)
            .background(cardColor)
            .let { m ->
                if (shade == null) m
                else m.drawWithContent {
                    drawContent()
                    val a = shade()
                    if (a > 0f) drawRect(Color.Black.copy(alpha = a.coerceIn(0f, 1f)))
                }
            }
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight(
                    align = if (top) Alignment.Top else Alignment.Bottom,
                    unbounded = true,
                )
                .height(cardHeight),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = digit.toString(), style = textStyle)
        }
    }
}

@Composable
private fun FlipColon(dims: FlipDimensions, color: Color) {
    Column(
        modifier = Modifier
            .width(dims.colonWidth)
            .height(dims.cardHeight),
        verticalArrangement = Arrangement.spacedBy(dims.cardHeight / 4, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(dims.dot)
                .clip(CircleShape)
                .background(color)
        )
        Box(
            Modifier
                .size(dims.dot)
                .clip(CircleShape)
                .background(color)
        )
    }
}

private fun Color.flipInvert(): Color = Color(1f - red, 1f - green, 1f - blue, alpha, colorSpace)
