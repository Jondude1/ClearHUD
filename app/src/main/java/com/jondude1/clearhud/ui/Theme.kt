package com.jondude1.clearhud.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object C {
    val bg = Color(0xFF0B0F14)
    val card = Color(0xFF141A22)
    val cardHi = Color(0xFF1B2330)
    val line = Color(0xFF263040)
    val text = Color(0xFFE8EEF5)
    val dim = Color(0xFF8A97A8)
    val teal = Color(0xFF3EE0C5)
    val amber = Color(0xFFFFB547)
    val red = Color(0xFFFF4D5E)
    val green = Color(0xFF4CDB7A)

    /** Selectable HUD accent colors. */
    val accents = listOf(teal, Color(0xFF55B6FF), Color(0xFFB98CFF), amber, Color(0xFFF2F5F8))
    val accentNames = listOf("Teal", "Blue", "Violet", "Amber", "White")
}

@Composable
fun ClearTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = C.teal, onPrimary = Color(0xFF00201B),
            background = C.bg, surface = C.card, onSurface = C.text, onBackground = C.text,
            surfaceVariant = C.cardHi, onSurfaceVariant = C.dim, outline = C.line,
            secondary = C.teal, error = C.red,
        ),
        content = content,
    )
}
