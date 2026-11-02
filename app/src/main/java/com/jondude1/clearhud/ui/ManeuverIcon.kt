package com.jondude1.clearhud.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import com.jondude1.clearhud.hud.Maneuver
import kotlin.math.cos
import kotlin.math.sin

/**
 * Turn arrows drawn as vector paths on a 100x100 grid, so they stay crisp at
 * any size. Left-hand maneuvers are the right-hand ones mirrored.
 */
@Composable
fun ManeuverIcon(m: Maneuver, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val s = size.minDimension / 100f
        val ox = (size.width - 100 * s) / 2; val oy = (size.height - 100 * s) / 2
        val flip = m.isLeft
        scale(if (flip) -1f else 1f, 1f) {
            translateGrid(ox, oy, s) { drawArrow(m, color) }
        }
    }
}

private inline fun DrawScope.translateGrid(ox: Float, oy: Float, s: Float, block: GridScope.() -> Unit) {
    GridScope(this, ox, oy, s).block()
}

private class GridScope(val d: DrawScope, val ox: Float, val oy: Float, val s: Float) {
    fun p(x: Float, y: Float) = Offset(ox + x * s, oy + y * s)
    val w get() = 11f * s

    fun stroke(color: Color, build: Path.() -> Unit) {
        d.drawPath(Path().apply(build), color, style = Stroke(w, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
    fun head(color: Color, tip: Offset, angleDeg: Float, len: Float = 26f) {
        val a = Math.toRadians(angleDeg.toDouble())
        val back = Offset((tip.x - cos(a) * len * s).toFloat(), (tip.y - sin(a) * len * s).toFloat())
        val nx = (-sin(a) * len * 0.62f * s).toFloat(); val ny = (cos(a) * len * 0.62f * s).toFloat()
        d.drawPath(Path().apply {
            moveTo(tip.x, tip.y); lineTo(back.x + nx, back.y + ny); lineTo(back.x - nx, back.y - ny); close()
        }, color)
    }
    fun Path.m(x: Float, y: Float) = p(x, y).let { moveTo(it.x, it.y) }
    fun Path.l(x: Float, y: Float) = p(x, y).let { lineTo(it.x, it.y) }
    fun Path.q(cx: Float, cy: Float, x: Float, y: Float) { val c = p(cx, cy); val e = p(x, y); quadraticBezierTo(c.x, c.y, e.x, e.y) }
}

private fun GridScope.drawArrow(m: Maneuver, c: Color) {
    when (m) {
        Maneuver.STRAIGHT, Maneuver.UNKNOWN -> {
            stroke(c) { m(50f, 92f); l(50f, 30f) }; head(c, p(50f, 8f), -90f)
        }
        Maneuver.LEFT, Maneuver.RIGHT -> {
            stroke(c) { m(36f, 92f); l(36f, 56f); q(36f, 40f, 52f, 40f); l(70f, 40f) }
            head(c, p(92f, 40f), 0f)
        }
        Maneuver.SLIGHT_LEFT, Maneuver.SLIGHT_RIGHT -> {
            stroke(c) { m(40f, 92f); l(40f, 62f); q(40f, 48f, 52f, 38f); l(64f, 28f) }
            head(c, p(80f, 14f), -42f)
        }
        Maneuver.SHARP_LEFT, Maneuver.SHARP_RIGHT -> {
            stroke(c) { m(34f, 92f); l(34f, 30f); q(34f, 16f, 46f, 28f); l(60f, 50f) }
            head(c, p(74f, 70f), 58f)
        }
        Maneuver.UTURN_LEFT, Maneuver.UTURN_RIGHT -> {
            stroke(c) { m(66f, 92f); l(66f, 40f); q(66f, 14f, 44f, 14f); q(24f, 14f, 24f, 40f); l(24f, 58f) }
            head(c, p(24f, 82f), 90f)
        }
        Maneuver.ROUNDABOUT, Maneuver.ROUNDABOUT_EXIT -> {
            d.drawCircle(c, radius = 20f * s, center = p(50f, 46f), style = Stroke(9f * s))
            stroke(c) { m(50f, 94f); l(50f, 66f) }
            stroke(c) { m(64f, 32f); l(76f, 20f) }
            head(c, p(90f, 6f), -45f, 22f)
        }
        Maneuver.ARRIVE -> {
            stroke(c) { m(50f, 94f); l(50f, 60f) }
            val pin = Path().apply {
                addOval(androidx.compose.ui.geometry.Rect(p(30f, 6f), p(70f, 46f)))
                m(33f, 34f); l(50f, 60f); l(67f, 34f); close()
            }
            d.drawPath(pin, c)
            d.drawCircle(Color.Black, radius = 7f * s, center = p(50f, 26f))
        }
    }
}
