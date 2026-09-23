package de.mm20.launcher2.ui.launcher.orb

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import de.mm20.launcher2.orb.OrbShape
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Builds the body outline for the given [shape], centred on [center] and scaled so the shape's
 * characteristic radius is [radius]. All shapes are drawn with rounded corners so they share the
 * same soft, "blob" language as the default circle.
 */
internal fun orbBodyPath(shape: OrbShape, center: Offset, radius: Float): Path = when (shape) {
    OrbShape.Circle -> Path().apply {
        addOval(Rect(center.x - radius, center.y - radius, center.x + radius, center.y + radius))
    }

    OrbShape.Square -> Path().apply {
        val h = radius * 0.9f
        val cr = radius * 0.3f
        addRoundRect(
            RoundRect(
                Rect(center.x - h, center.y - h, center.x + h, center.y + h),
                CornerRadius(cr, cr),
            )
        )
    }

    OrbShape.Pebble -> Path().apply {
        val h = radius * 0.8f
        val cr = radius * 0.52f
        addRoundRect(
            RoundRect(
                Rect(center.x - h, center.y - h, center.x + h, center.y + h),
                CornerRadius(cr, cr),
            )
        )
    }

    OrbShape.Pill -> Path().apply {
        val hw = radius * 1.15f
        val hh = radius * 0.72f
        addRoundRect(
            RoundRect(
                Rect(center.x - hw, center.y - hh, center.x + hw, center.y + hh),
                CornerRadius(hh, hh),
            )
        )
    }

    OrbShape.Triangle -> roundedPolygon(center, radius * 1.15f, 3, radius * 0.45f)

    OrbShape.Hexagon -> roundedPolygon(center, radius * 1.05f, 6, radius * 0.3f)

    OrbShape.Teardrop -> teardropPath(center, radius)

    OrbShape.Cloud -> cloudPath(center, radius)
}

private fun roundedPolygon(
    center: Offset,
    radius: Float,
    sides: Int,
    cornerRadius: Float,
): Path {
    val path = Path()
    val cr = cornerRadius.coerceAtMost(radius * 0.6f)
    val points = List(sides) { i ->
        val angle = -PI / 2 + i * 2.0 * PI / sides
        Offset(
            center.x + radius * cos(angle).toFloat(),
            center.y + radius * sin(angle).toFloat(),
        )
    }
    for (i in 0 until sides) {
        val prev = points[(i - 1 + sides) % sides]
        val p = points[i]
        val next = points[(i + 1) % sides]
        val dirIn = normalized(p - prev)
        val dirOut = normalized(next - p)
        val start = p - dirIn * cr
        val end = p + dirOut * cr
        if (i == 0) path.moveTo(start.x, start.y) else path.lineTo(start.x, start.y)
        path.quadraticBezierTo(p.x, p.y, end.x, end.y)
    }
    path.close()
    return path
}

private fun teardropPath(center: Offset, radius: Float): Path {
    // A drop pointing up: a bulb circle at the bottom with two tangent lines rising to a
    // rounded apex. The bulb arc and the apex are both tangent to the sides, so the outline
    // stays smooth.
    val a = radius * 0.78f
    val oy = radius * 0.2f
    val tipY = -radius * 1.05f
    val d = oy - tipY
    val cosB = (a / d).coerceIn(-1f, 1f)
    val sinB = sqrt(1f - cosB * cosB)
    val tx = a * sinB
    val ty = oy + a * cosB
    val bulbCenter = center + Offset(0f, oy)
    val right = center + Offset(tx, ty)
    val left = center + Offset(-tx, ty)
    val apex = center + Offset(0f, tipY)
    val startAngle = Math.toDegrees(atan2((ty - oy).toDouble(), tx.toDouble())).toFloat()

    return Path().apply {
        moveTo(right.x, right.y)
        arcTo(
            Rect(bulbCenter.x - a, bulbCenter.y - a, bulbCenter.x + a, bulbCenter.y + a),
            startAngle,
            180f - 2f * startAngle,
            false,
        )
        quadraticBezierTo(apex.x, apex.y, right.x, right.y)
        close()
    }
}

private fun cloudPath(center: Offset, radius: Float): Path {
    val cx = center.x
    val cy = center.y
    return Path().apply {
        moveTo(cx - 0.95f * radius, cy + 0.25f * radius)
        cubicTo(
            cx - 0.4f * radius, cy + 0.65f * radius,
            cx + 0.4f * radius, cy + 0.65f * radius,
            cx + 0.95f * radius, cy + 0.25f * radius,
        )
        cubicTo(
            cx + 1.15f * radius, cy + 0.05f * radius,
            cx + 1.0f * radius, cy - 0.35f * radius,
            cx + 0.6f * radius, cy - 0.3f * radius,
        )
        cubicTo(
            cx + 0.55f * radius, cy - 0.55f * radius,
            cx + 0.25f * radius, cy - 0.6f * radius,
            cx, cy - 0.55f * radius,
        )
        cubicTo(
            cx - 0.25f * radius, cy - 0.6f * radius,
            cx - 0.55f * radius, cy - 0.55f * radius,
            cx - 0.6f * radius, cy - 0.3f * radius,
        )
        cubicTo(
            cx - 1.0f * radius, cy - 0.35f * radius,
            cx - 1.15f * radius, cy + 0.05f * radius,
            cx - 0.95f * radius, cy + 0.25f * radius,
        )
        close()
    }
}

private fun normalized(v: Offset): Offset {
    val len = v.getDistance()
    return if (len == 0f) Offset.Zero else Offset(v.x / len, v.y / len)
}
