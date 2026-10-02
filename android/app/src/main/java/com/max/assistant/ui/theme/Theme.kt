package com.max.assistant.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// MAX brand. A near-black canvas with a gold primary reads as "assistant"
// rather than "chat app", and the same hue set works in both themes so the
// light theme is a real light mode, not an inverted dark one.
val MaxGold = Color(0xFFF5A524)
val MaxAmber = Color(0xFFFF7A1A)
val MaxBlue = Color(0xFF3B82F6)
val MaxInk = Color(0xFF07080C)
val MaxPanel = Color(0xFF12141C)
val MaxMist = Color(0xFFF6F7FB)

// Elevation-style surface tints. Compose has no shadow blur on API 26, so the
// "glass" effect is built from layered translucent fills instead - which is
// both cheaper and closer to the intended look.
val MaxSurfaceHigh = Color(0xFF1B1E29)
val MaxSurfaceHighest = Color(0xFF232735)

private val MaxDarkColors = darkColorScheme(
    primary = MaxGold,
    onPrimary = Color(0xFF1A0E00),
    primaryContainer = Color(0xFF2E2200),
    onPrimaryContainer = Color(0xFFFFE3A3),
    secondary = MaxBlue,
    onSecondary = Color(0xFF00193D),
    background = MaxInk,
    onBackground = Color(0xFFE8EAF0),
    surface = MaxPanel,
    onSurface = Color(0xFFE8EAF0),
    surfaceVariant = MaxSurfaceHigh,
    onSurfaceVariant = Color(0xFFA9AEC2),
    outline = Color(0xFF3A3F52),
    error = Color(0xFFFF6B6B)
)

private val MaxLightColors = lightColorScheme(
    primary = Color(0xFFB26A00),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE0B2),
    onPrimaryContainer = Color(0xFF2E2200),
    secondary = Color(0xFF1F5FBF),
    onSecondary = Color.White,
    background = MaxMist,
    onBackground = Color(0xFF12141C),
    surface = Color.White,
    onSurface = Color(0xFF12141C),
    surfaceVariant = Color(0xFFE7E9F2),
    onSurfaceVariant = Color(0xFF4A4F63),
    outline = Color(0xFFC5C9D8),
    error = Color(0xFFB3261E)
)

/**
 * Typography tuned for an assistant: large, confident headlines and generous
 * line height on body text, which is what makes a long spoken reply readable
 * at a glance.
 */
private val MaxTypography = Typography(
    displaySmall = TextStyle(fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    headlineMedium = TextStyle(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp),
    headlineSmall = TextStyle(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.2.sp),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.6.sp)
)

/**
 * @param dark null means "follow the system setting", which is what makes the
 *        light/dark toggle feel native rather than bolted on.
 */
@Composable
fun MaxTheme(
    dark: Boolean? = null,
    content: @Composable () -> Unit
) {
    val useDark = dark ?: isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (useDark) MaxDarkColors else MaxLightColors,
        typography = MaxTypography,
        content = content
    )
}

