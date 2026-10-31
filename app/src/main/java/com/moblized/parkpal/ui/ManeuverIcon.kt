package com.moblized.parkpal.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.moblized.parkpal.nav.ManeuverKind
import kotlin.math.cos
import kotlin.math.sin

/** The turn arrow on the direction banner, drawn to match each kind of maneuver. */
@Composable
fun ManeuverIcon(kind: ManeuverKind, color: Color, size: Dp = 56.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension
        val w = s * 0.11f
        fun p(x: Float, y: Float) = Offset(x * s, y * s)
        val stroke = Stroke(width = w, cap = StrokeCap.Round, join = StrokeJoin.Round)

        when (kind) {
            ManeuverKind.ARRIVE -> {
                drawLine(color, p(0.32f, 0.9f), p(0.32f, 0.12f), w * 0.8f, StrokeCap.Round)
                val flag = Path().apply {
                    moveTo(0.32f * s, 0.12f * s)
                    lineTo(0.8f * s, 0.24f * s)
                    lineTo(0.32f * s, 0.46f * s)
                    close()
                }
                drawPath(flag, color)
            }
            ManeuverKind.UTURN -> {
                val path = Path().apply {
                    moveTo(0.66f * s, 0.9f * s)
                    lineTo(0.66f * s, 0.42f * s)
                    arcTo(Rect(0.34f * s, 0.26f * s, 0.66f * s, 0.58f * s), 0f, -180f, false)
                    lineTo(0.34f * s, 0.6f * s)
                }
                drawPath(path, color, style = stroke)
                arrowHead(color, p(0.34f, 0.74f), 180f, s)
            }
            ManeuverKind.ROUNDABOUT -> {
                drawCircle(color, radius = 0.17f * s, center = p(0.5f, 0.42f), style = Stroke(w))
                drawLine(color, p(0.5f, 0.92f), p(0.5f, 0.6f), w, StrokeCap.Round)
                val a = Math.toRadians(45.0)
                val start = Offset((0.5f + 0.17f * sin(a).toFloat()) * s, (0.42f - 0.17f * cos(a).toFloat()) * s)
                val end = Offset((0.5f + 0.36f * sin(a).toFloat()) * s, (0.42f - 0.36f * cos(a).toFloat()) * s)
                drawLine(color, start, end, w, StrokeCap.Round)
                arrowHead(color, end, 45f, s)
            }
            else -> {
                val angle = kind.angle.toFloat()
                val pivot = p(0.5f, 0.52f)
                // Forks and ramps: show the road you're not taking, faintly.
                if (kind == ManeuverKind.FORK_LEFT || kind == ManeuverKind.FORK_RIGHT ||
                    kind == ManeuverKind.RAMP_LEFT || kind == ManeuverKind.RAMP_RIGHT
                ) {
                    drawLine(color.copy(alpha = 0.4f), pivot, p(0.5f, 0.12f), w * 0.8f, StrokeCap.Round)
                }
                if (angle == 0f) {
                    drawLine(color, p(0.5f, 0.92f), p(0.5f, 0.24f), w, StrokeCap.Round)
                    arrowHead(color, p(0.5f, 0.1f), 0f, s)
                } else {
                    val rad = Math.toRadians(angle.toDouble())
                    val len = if (kotlin.math.abs(angle) > 100f) 0.3f else 0.36f
                    val tip = Offset(
                        pivot.x + len * s * sin(rad).toFloat(),
                        pivot.y - len * s * cos(rad).toFloat(),
                    )
                    val back = 0.12f
                    val lineEnd = Offset(
                        tip.x - back * s * sin(rad).toFloat(),
                        tip.y + back * s * cos(rad).toFloat(),
                    )
                    val path = Path().apply {
                        moveTo(0.5f * s, 0.92f * s)
                        lineTo(pivot.x, pivot.y)
                        lineTo(lineEnd.x, lineEnd.y)
                    }
                    drawPath(path, color, style = stroke)
                    arrowHead(color, tip, angle, s)
                }
            }
        }
    }
}

/** A filled triangle with its point at [tip], pointing [angle] degrees clockwise from up. */
private fun DrawScope.arrowHead(color: Color, tip: Offset, angle: Float, s: Float) {
    val rad = Math.toRadians(angle.toDouble())
    val dx = sin(rad).toFloat()
    val dy = -cos(rad).toFloat()
    val h = 0.2f * s
    val half = 0.15f * s
    val baseX = tip.x - dx * h
    val baseY = tip.y - dy * h
    val path = Path().apply {
        moveTo(tip.x, tip.y)
        lineTo(baseX - dy * half, baseY + dx * half)
        lineTo(baseX + dy * half, baseY - dx * half)
        close()
    }
    drawPath(path, color)
}
