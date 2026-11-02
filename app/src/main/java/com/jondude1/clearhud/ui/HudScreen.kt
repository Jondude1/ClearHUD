package com.jondude1.clearhud.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jondude1.clearhud.HudBus
import com.jondude1.clearhud.NavState
import com.jondude1.clearhud.hud.Maneuver
import kotlinx.coroutines.delay

/** Imperial distance formatting the way a driver reads it. */
fun formatDistance(meters: Int): Pair<String, String> {
    val miles = meters / 1609.344
    return when {
        miles >= 10 -> "%.0f".format(miles) to "mi"
        miles >= 0.2 -> "%.1f".format(miles) to "mi"
        else -> { val ft = (meters * 3.28084).toInt(); ((ft / 50) * 50).coerceAtLeast(0).toString() to "ft" }
    }
}

fun formatEta(seconds: Int): String {
    val m = seconds / 60
    return if (m >= 60) "${m / 60} h ${m % 60} min" else "$m min"
}

private const val APPROACH_METERS = 400f

/**
 * Phone-screen head-up display in the style of premium factory HUDs:
 * information floats on pure black (invisible in the reflection), light
 * numerals with a soft glow, no frames or panels, color used only for meaning.
 */
@Composable
fun HudScreen(accent: Color, overMargin: Int, modifier: Modifier = Modifier, compact: Boolean = false) {
    val nav by HudBus.nav.collectAsState()
    val gps by HudBus.gpsSpeedMps.collectAsState()
    val obd by HudBus.obd.collectAsState()
    val limit by HudBus.speedLimitMph.collectAsState()
    val alert by HudBus.alert.collectAsState()
    val speed = remember(gps, obd) { HudBus.speedMph() }
    val over = speed != null && limit != null && speed > limit!! + overMargin

    var showAlert by remember { mutableStateOf(false) }
    LaunchedEffect(alert) { if (alert != null) { showAlert = true; delay(7000); showAlert = false } }

    val k = if (compact) 0.42f else 1f
    val s: (Int) -> TextUnit = { (it * k).sp }
    val d: (Int) -> Dp = { (it * k).dp }

    Box(modifier.background(Color.Black).padding(horizontal = d(40), vertical = d(24))) {
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {

            // ── Navigation cluster ──
            Box(Modifier.weight(1.2f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                NavCluster(nav, accent, s, d)
            }

            // ── Speed ──
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                val speedColor by animateColorAsState(if (over) C.red else Color.White, tween(400), label = "spd")
                Text(
                    speed?.toString() ?: "–",
                    style = glow(speedColor, 18f * k),
                    color = speedColor, fontSize = s(168), fontWeight = FontWeight.ExtraLight,
                    fontFamily = FontFamily.SansSerif, lineHeight = s(168), letterSpacing = s(-4),
                )
                Text("mph", color = C.dim, fontSize = s(22), fontWeight = FontWeight.Light, letterSpacing = s(4))
            }

            // ── Limit, arrival, engine ──
            Column(
                Modifier.weight(0.9f).fillMaxHeight(),
                verticalArrangement = Arrangement.SpaceEvenly,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                LimitSign(limit, over, s, d)
                nav?.let { ArrivalBlock(it, accent, s) }
                if (obd.connected) EngineRow(obd.rpm, obd.coolantC, obd.voltage, s, d)
            }
        }

        // ── Alert: a single floating line, no panel ──
        AnimatedVisibility(
            showAlert && alert != null, Modifier.align(Alignment.TopCenter),
            enter = fadeIn(tween(250)), exit = fadeOut(tween(400)),
        ) {
            alert?.let { a ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Canvas(Modifier.size(d(10))) { drawCircle(accent) }
                        Spacer(Modifier.width(d(10)))
                        Text(a.app.uppercase(), color = accent, fontSize = s(15), letterSpacing = s(4), fontWeight = FontWeight.Medium)
                    }
                    Text(
                        if (a.text.isBlank()) a.title else "${a.title}  —  ${a.text}",
                        color = Color.White, fontSize = s(26), fontWeight = FontWeight.Light,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(d(6)))
                    Box(Modifier.width(d(260)).height(d(1)).background(
                        Brush.horizontalGradient(listOf(Color.Transparent, accent.copy(alpha = 0.7f), Color.Transparent))))
                }
            }
        }
    }
}

/** Soft bloom around bright elements, like light projected onto glass. */
private fun glow(c: Color, radius: Float) = TextStyle(shadow = Shadow(c.copy(alpha = 0.45f), Offset.Zero, radius))

@Composable
private fun NavCluster(nav: NavState?, accent: Color, s: (Int) -> TextUnit, d: (Int) -> Dp) {
    if (nav == null) {
        val heading by HudBus.heading.collectAsState()
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(heading?.let { compass(it) } ?: "–", color = Color.White, fontSize = s(56),
                fontWeight = FontWeight.ExtraLight, letterSpacing = s(6), style = glow(Color.White, 10f))
            Text("heading", color = C.dim, fontSize = s(16), fontWeight = FontWeight.Light, letterSpacing = s(4))
        }
        return
    }
    val approach = (1f - nav.distanceMeters / APPROACH_METERS).coerceIn(0f, 1f)
    val fill by animateFloatAsState(approach, tween(600), label = "approach")
    val near = nav.distanceMeters < APPROACH_METERS

    Row(verticalAlignment = Alignment.CenterVertically) {
        // Proximity bar: fills toward the arrow as the turn approaches.
        Canvas(Modifier.width(d(8)).height(d(210))) {
            val r = CornerRadius(size.width / 2)
            drawRoundRect(Color.White.copy(alpha = 0.12f), cornerRadius = r)
            if (fill > 0f) {
                val h = size.height * fill
                drawRoundRect(
                    Brush.verticalGradient(listOf(accent, accent.copy(alpha = 0.35f)), startY = size.height - h, endY = size.height),
                    topLeft = Offset(0f, size.height - h), size = Size(size.width, h), cornerRadius = r,
                )
            }
        }
        Spacer(Modifier.width(d(26)))
        Column(horizontalAlignment = Alignment.Start) {
            Box(contentAlignment = Alignment.Center) {
                // Halo behind the arrow, brighter as the turn gets close.
                Canvas(Modifier.size(d(190))) {
                    drawCircle(Brush.radialGradient(listOf(accent.copy(alpha = 0.10f + 0.22f * fill), Color.Transparent)))
                    if (near) drawCircle(accent.copy(alpha = 0.25f * fill), radius = size.minDimension / 2.2f, style = Stroke(1.5f))
                }
                ManeuverIcon(nav.maneuver, Color.White, Modifier.size(d(150)))
            }
            if (nav.maneuver != Maneuver.ARRIVE || nav.distanceMeters > 0) {
                val (v, u) = formatDistance(nav.distanceMeters)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(v, color = Color.White, fontSize = s(58), fontWeight = FontWeight.Light,
                        lineHeight = s(58), style = glow(Color.White, 10f))
                    Spacer(Modifier.width(d(8)))
                    Text(u, color = C.dim, fontSize = s(22), fontWeight = FontWeight.Light, modifier = Modifier.padding(bottom = d(9)))
                }
            }
            Text(
                nav.road.ifBlank { nav.maneuver.label }, color = accent, fontSize = s(24),
                fontWeight = FontWeight.Normal, letterSpacing = s(1), maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = d(340)),
            )
        }
    }
}

@Composable
private fun ArrivalBlock(n: NavState, accent: Color, s: (Int) -> TextUnit) {
    if (n.etaSeconds == null && n.arrivalClock == null && n.remainingMeters == null) return
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(n.arrivalClock ?: n.etaSeconds?.let(::formatEta) ?: "", color = Color.White,
            fontSize = s(34), fontWeight = FontWeight.Light)
        val sub = listOfNotNull(
            n.etaSeconds?.let(::formatEta)?.takeIf { n.arrivalClock != null },
            n.remainingMeters?.let { formatDistance(it).let { (v, u) -> "$v $u" } },
        ).joinToString("   ")
        if (sub.isNotEmpty()) Text(sub, color = C.dim, fontSize = s(17), fontWeight = FontWeight.Light, letterSpacing = s(1))
        Text("arrival", color = accent.copy(alpha = 0.7f), fontSize = s(13), letterSpacing = s(4), fontWeight = FontWeight.Light)
    }
}

/** Round regulatory-style limit sign; the ring turns solid red when over. */
@Composable
private fun LimitSign(limit: Int?, over: Boolean, s: (Int) -> TextUnit, d: (Int) -> Dp) {
    val ring by animateColorAsState(if (over) C.red else C.red.copy(alpha = 0.85f), label = "ring")
    val pulse by animateFloatAsState(if (over) 1f else 0f, tween(300), label = "pulse")
    Box(Modifier.size(d(112)), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.minDimension * 0.11f
            if (pulse > 0f) drawCircle(C.red.copy(alpha = 0.25f * pulse), radius = size.minDimension / 2)
            drawCircle(ring, radius = size.minDimension / 2 - w / 2, style = Stroke(w))
        }
        Text(limit?.toString() ?: "–", color = Color.White, fontSize = s(44), fontWeight = FontWeight.Normal)
    }
}

@Composable
private fun EngineRow(rpm: Int?, coolantC: Int?, volts: Double?, s: (Int) -> TextUnit, d: (Int) -> Dp) {
    Row(horizontalArrangement = Arrangement.spacedBy(d(22))) {
        rpm?.let { Readout("%.1f".format(it / 1000f), "rpm ×1000", Color.White, s) }
        coolantC?.let { c -> val f = c * 9 / 5 + 32; Readout("$f°", "coolant", if (f > 230) C.red else Color.White, s) }
        volts?.let { Readout("%.1f".format(it), "volts", if (it < 12.0) C.amber else Color.White, s) }
    }
}

@Composable
private fun Readout(value: String, label: String, color: Color, s: (Int) -> TextUnit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = color, fontSize = s(26), fontWeight = FontWeight.Light)
        Text(label, color = C.dim, fontSize = s(11), letterSpacing = s(2), fontWeight = FontWeight.Light)
    }
}

private fun compass(deg: Float): String =
    listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")[(((deg % 360) + 382.5f) / 45f).toInt() % 8]
