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
import com.max.assistant.services.VoiceState
import com.max.assistant.ui.theme.MaxAmber
import com.max.assistant.ui.theme.MaxBlue
import com.max.assistant.ui.theme.MaxGold

// The animated MAX orb. It breathes when idle and pulses faster while listening/speaking.
@Composable
fun MaxOrb(state: VoiceState, size: Dp = 200.dp, onClick: () -> Unit = {}) {
    val transition = rememberInfiniteTransition(label = "orb")
    val fast = state == VoiceState.LISTENING || state == VoiceState.SPEAKING
    val pulse by transition.animateFloat(
        initialValue = 0.94f, targetValue = 1.08f, label = "pulse",
        animationSpec = infiniteRepeatable(tween(if (fast) 700 else 3200, easing = LinearEasing), RepeatMode.Reverse)
    )
    val spin by transition.animateFloat(
        initialValue = 0f, targetValue = 360f, label = "spin",
        animationSpec = infiniteRepeatable(tween(if (state == VoiceState.PROCESSING) 2500 else 14000, easing = LinearEasing))
    )
    val listening = state == VoiceState.LISTENING
    val core = if (listening) listOf(Color(0xFFD6E6FF), MaxBlue, Color(0xFF10286B)) else listOf(Color(0xFFFFE3A3), MaxGold, MaxAmber)

    Canvas(Modifier.size(size).clickable(onClick = onClick)) {
        val c = Offset(this.size.width / 2, this.size.height / 2)
        val r = this.size.minDimension / 2
        drawCircle(Brush.radialGradient(listOf(core[1].copy(alpha = 0.28f), Color.Transparent), c, r), r, c)
        drawCircle(Brush.radialGradient(core, c, r * 0.6f * pulse), r * 0.6f * pulse, c)
        // Rotating ring made of an arc, so the orb always feels "alive".
        drawArc(MaxBlue, spin, 100f, false, topLeft = Offset(c.x - r * 0.85f, c.y - r * 0.85f), size = androidx.compose.ui.geometry.Size(r * 1.7f, r * 1.7f), style = Stroke(3f))
        drawArc(MaxGold, -spin, 70f, false, topLeft = Offset(c.x - r * 0.95f, c.y - r * 0.95f), size = androidx.compose.ui.geometry.Size(r * 1.9f, r * 1.9f), style = Stroke(2f))
    }
}
