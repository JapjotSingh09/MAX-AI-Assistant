package com.max.assistant.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// MAX brand: near-black background, gold/orange primary, blue secondary.
val MaxGold = Color(0xFFF5A524)
val MaxAmber = Color(0xFFFF7A1A)
val MaxBlue = Color(0xFF3B82F6)
val MaxInk = Color(0xFF07080C)
val MaxPanel = Color(0xFF12141C)

private val MaxColors = darkColorScheme(
    primary = MaxGold,
    onPrimary = Color(0xFF1A0E00),
    secondary = MaxBlue,
    background = MaxInk,
    surface = MaxPanel,
    onBackground = Color(0xFFE8EAF0),
    onSurface = Color(0xFFE8EAF0),
    error = Color(0xFFFF6B6B)
)

@Composable
fun MaxTheme(content: @Composable () -> Unit) {
    // MAX is dark by default (and for v1 always dark).
    @Suppress("UNUSED_VARIABLE") val system = isSystemInDarkTheme()
    MaterialTheme(colorScheme = MaxColors, content = content)
}
