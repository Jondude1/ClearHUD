package com.jondude1.clearhud.ui

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.jondude1.clearhud.Prefs
import kotlinx.coroutines.delay

/** Full-screen phone HUD. Tap anywhere to cycle mirror mode; screen stays on. */
class HudActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply { screenBrightness = 1f }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        val accent = C.accents.getOrElse(Prefs.accent(this)) { C.teal }
        val margin = Prefs.overspeedMargin(this)
        setContent {
            var mode by remember { mutableIntStateOf(Prefs.mirror(this)) }
            var hint by remember { mutableStateOf<String?>(null) }
            LaunchedEffect(hint) { if (hint != null) { delay(1500); hint = null } }
            Box(Modifier.fillMaxSize().clickable(MutableInteractionSource(), null) {
                mode = (mode + 1) % 3; Prefs.setMirror(this, mode)
                hint = listOf("Normal", "Mirrored (flip vertical)", "Mirrored (flip horizontal)")[mode]
            }) {
                HudScreen(accent, margin, Modifier.fillMaxSize().graphicsLayer {
                    scaleY = if (mode == 1) -1f else 1f
                    scaleX = if (mode == 2) -1f else 1f
                })
                hint?.let {
                    Text(it, color = C.dim, fontSize = 16.sp, modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp))
                }
            }
        }
    }
}
