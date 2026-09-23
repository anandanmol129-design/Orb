package de.mm20.launcher2.ui.ktx

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp

private val fadeEdgeColors = listOf(Color.Transparent, Color.Black)

fun Modifier.drawFadedEdge(leftEdge: Boolean, edgeWidth: Dp): Modifier = drawWithCache {
    // https://stackoverflow.com/a/75112743
    val edgeWidthPx = edgeWidth.toPx()
    val brush = Brush.horizontalGradient(
        colors = fadeEdgeColors,
        startX = if (leftEdge) 0f else size.width,
        endX = if (leftEdge) edgeWidthPx else size.width - edgeWidthPx
    )
    val topLeft = Offset(if (leftEdge) 0f else size.width - edgeWidthPx, 0f)
    val edgeSize = Size(edgeWidthPx, size.height)
    onDrawWithContent {
        drawContent()
        drawRect(
            topLeft = topLeft,
            size = edgeSize,
            brush = brush,
            blendMode = BlendMode.DstIn
        )
    }
}
