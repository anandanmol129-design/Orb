package de.mm20.launcher2.ui.launcher.orb

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

private const val BAR_COUNT = 28

/**
 * The pill shown beneath the orb while listening: a simple line-bar waveform driven by
 * the live mic level, with the growing transcript printed underneath.
 */
@Composable
fun OrbWavePill(
    amplitude: Float,
    transcript: String,
    modifier: Modifier = Modifier,
) {
    // Sample the level at a fixed rate so the bars keep moving even while the
    // recognizer is quiet between words.
    val amp = rememberUpdatedState(amplitude)
    val history = remember { mutableStateListOf<Float>() }
    LaunchedEffect(Unit) {
        while (true) {
            delay(40)
            history.add(amp.value)
            while (history.size > BAR_COUNT) history.removeAt(0)
        }
    }

    Box(
        modifier = modifier
            .background(
                MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.95f),
                RoundedCornerShape(28.dp),
            )
            .padding(horizontal = 20.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            val barColor = MaterialTheme.colorScheme.onSurface
            Canvas(modifier = Modifier.size(width = 220.dp, height = 40.dp)) {
                val slot = size.width / BAR_COUNT
                val barWidth = slot * 0.55f
                val midY = size.height / 2f
                val minH = size.height * 0.1f
                history.forEachIndexed { index, level ->
                    val h = minH + level * (size.height - minH)
                    val x = index * slot + (slot - barWidth) / 2
                    drawRoundRect(
                        color = barColor,
                        topLeft = Offset(x, midY - h / 2),
                        size = Size(barWidth, h),
                        cornerRadius = CornerRadius(barWidth / 2, barWidth / 2),
                    )
                }
            }
            if (transcript.isNotBlank()) {
                Text(
                    text = transcript,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 240.dp),
                )
            }
        }
    }
}
