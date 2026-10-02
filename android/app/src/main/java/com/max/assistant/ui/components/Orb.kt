package com.max.assistant.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.max.assistant.speech.VoiceState
import com.max.assistant.ui.theme.MaxAmber
import com.max.assistant.ui.theme.MaxBlue
import com.max.assistant.ui.theme.MaxGold

/**
 * The MAX orb.
 *
 * It is the app's single most important piece of feedback, so it is driven by
 * THREE inputs rather than one:
 *  - [state] picks the colour and the base animation speed;
 *  - [amplitude] (microphone loudness) scales the glow while listening, so the
 *    orb visibly reacts to the user rather than just spinning;
 *  - [thinking] spins a third, faster ring while MAX waits for the AI.
 *
 * Drawing is a plain Canvas with no shaders or bitmaps: it stays at 60fps on
 * the low-end hardware an assistant has to run on, and costs nothing when idle
 * because every animation is driven by one `rememberInfiniteTransition`.
 */
@Composable
fun MaxOrb(
    state: VoiceState,
    modifier: Modifier = Modifier,
    size: Dp = 200.dp,
    amplitude: Float = -1f,
    thinking: Boolean = false,
    onClick: () -> Unit = {}
) {
    val transition = rememberInfiniteTransition(label = "orb")
    val active = state == VoiceState.LISTENING || state == VoiceState.SPEAKING

    // Idle breathes slowly; speaking pulses; thinking spins a distinct ring.
    val pulse by transition.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.06f,
        label = "pulse",
        animationSpec = infiniteRepeatable(
            tween(if (state == VoiceState.SPEAKING) 700 else 3200, easing = LinearEasing),
            RepeatMode.Reverse
        )
    )
    val spin by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        label = "spin",
        animationSpec = infiniteRepeatable(tween(if (thinking) 1600 else 14000, easing = LinearEasing))
    )

    val listening = state == VoiceState.LISTENING
    val speaking = state == VoiceState.SPEAKING
    val core = when {
        listening -> listOf(Color(0xFFD6E6FF), MaxBlue, Color(0xFF10286B))
        speaking -> listOf(Color(0xFFDFFFE8), Color(0xFF3DD68C), Color(0xFF0B5C36))
        else -> listOf(Color(0xFFFFE3A3), MaxGold, MaxAmber)
    }

    Canvas(modifier.size(size).clickable(onClick = onClick)) {
        val c = Offset(this.size.width / 2, this.size.height / 2)
        val r = this.size.minDimension / 2
        // The microphone level expands the glow, so the orb answers the voice.
        val levelBoost = if (listening && amplitude >= 0f) 1f + amplitude * 0.18f else 1f

        drawCircle(
            Brush.radialGradient(listOf(core[1].copy(alpha = 0.28f * levelBoost), Color.Transparent), c, r),
            r, c
        )
        drawCircle(
            Brush.radialGradient(core, c, r * 0.6f * pulse * levelBoost),
            r * 0.6f * pulse * levelBoost, c
        )

        // Concentric rings, each on its own phase, so the orb never looks static.
        if (thinking) {
            // A fast dashed-feel ring: MAX is working.
            drawArc(
                MaxBlue, spin, 70f, false,
                topLeft = Offset(c.x - r * 0.82f, c.y - r * 0.82f),
                size = androidx.compose.ui.geometry.Size(r * 1.64f, r * 1.64f),
                style = Stroke(3f)
            )
        }
        drawArc(
            MaxBlue, spin, 100f, false,
            topLeft = Offset(c.x - r * 0.85f, c.y - r * 0.85f),
            size = androidx.compose.ui.geometry.Size(r * 1.7f, r * 1.7f),
            style = Stroke(if (active) 4f else 3f)
        )
        drawArc(
            MaxGold, -spin, 70f, false,
            topLeft = Offset(c.x - r * 0.95f, c.y - r * 0.95f),
            size = androidx.compose.ui.geometry.Size(r * 1.9f, r * 1.9f),
            style = Stroke(2f)
        )
        // An outer halo that only appears when the orb is actually doing work.
        if (active) {
            drawCircle(
                Brush.radialGradient(listOf(Color.Transparent, core[1].copy(alpha = 0.18f)), c, r),
                r, c, style = Stroke(1.5f)
            )
        }
    }
}

