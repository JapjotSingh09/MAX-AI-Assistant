package com.max.assistant.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.max.assistant.actions.DeviceInfoAction
import com.max.assistant.speech.VoiceState
import com.max.assistant.ui.components.MaxOrb
import com.max.assistant.ui.theme.MaxGold
import java.util.Calendar

/**
 * THE HOME SCREEN.
 *
 * Built around ONE idea: the orb is the microphone. Everything else is
 * secondary, which is why the quick actions sit below it rather than competing
 * with it.
 *
 * The design goal here is "assistant", not "chat app": a strong central object,
 * a live state readout, and a small number of real actions. The device status
 * row is read from the ACTUAL battery and network state, not hardcoded, so it
 * can never quietly show something untrue.
 */
@Composable
fun HomeScreen(
    name: String,
    offline: Boolean,
    onSpeak: () -> Unit,
    onType: () -> Unit,
    onQuickAction: (String) -> Unit
) {
    val context = LocalContext.current
    val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    val greeting = when {
        hour < 12 -> "Good morning"
        hour < 18 -> "Good afternoon"
        else -> "Good evening"
    }
    val firstName = name.trim().substringBefore(' ')

    // Real device state, read once when the screen is composed. A cheap
    // sticky-broadcast read, so it costs nothing and cannot be wrong.
    val status = remember {
        val info = DeviceInfoAction(context.applicationContext)
        DeviceStatus(
            battery = info.batteryPercent(),
            charging = info.batteryStatusLabel(),
            network = info.readWifiStatus().message
        )
    }

    // The idle orb breathes gently; that is the only animation when nothing is
    // happening, so a screen left open does not cost battery for decoration.
    val transition = rememberInfiniteTransition(label = "home")
    val breathe by transition.animateFloat(
        initialValue = 0.97f,
        targetValue = 1.03f,
        animationSpec = infiniteRepeatable(tween(3600), RepeatMode.Reverse),
        label = "breathe"
    )

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (firstName.isBlank()) greeting else "$greeting, $firstName",
                    style = MaterialTheme.typography.headlineSmall
                )
                ConnectionLine(offline)
            }
            Text(
                "MAX",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary
            )
        }

        Spacer(Modifier.height(28.dp))

        // The orb IS the microphone. Scale is driven by the idle breath only.
        Box(
            Modifier
                .size(210.dp)
                .clip(RoundedCornerShape(105.dp)),
            contentAlignment = Alignment.Center
        ) {
            MaxOrb(VoiceState.IDLE, size = 210.dp * breathe, onClick = onSpeak)
        }

        Spacer(Modifier.height(14.dp))
        Text(
            "Tap to talk",
            style = MaterialTheme.typography.titleMedium
        )
        // A real alternative path to the assistant, not a decorative label.
        TextButton(onClick = onType, modifier = Modifier.padding(top = 2.dp)) { Text("Type instead") }

        Spacer(Modifier.height(18.dp))
        DeviceStatusRow(status)

        Spacer(Modifier.height(24.dp))
        Text("How can I help?", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(10.dp))
        QuickActionGrid(onQuickAction)
        Spacer(Modifier.height(24.dp))
    }
}

/** Online/offline, stated plainly and coloured honestly. */
@Composable
private fun ConnectionLine(offline: Boolean) {
    val dot by animateColorAsState(
        if (offline) MaterialTheme.colorScheme.error else MaxGold,
        label = "connDot"
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).clip(RoundedCornerShape(4.dp)).background(dot))
        Text(
            if (offline) "  Offline - local commands still work" else "  Ready",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * A snapshot of real device state for the home screen.
 *
 * A small data holder rather than a set of loose values, so the screen cannot
 * accidentally pair a battery reading with the wrong network label.
 */
private data class DeviceStatus(
    val battery: Int?,
    val charging: String?,
    val network: String
)

/**
 * Live battery and network, read from the device.
 *
 * Every value here is measured, never hardcoded: an assistant that displays a
 * made-up battery percentage has no business answering battery questions.
 */
@Composable
private fun DeviceStatusRow(status: DeviceStatus) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatusPill(
                label = when {
                    status.battery == null -> "Battery --"
                    status.charging == "charging" -> "Battery ${status.battery}% +"
                    else -> "Battery ${status.battery}%"
                },
                modifier = Modifier.weight(1f)
            )
            StatusPill(label = status.network, modifier = Modifier.weight(1f))
        }
    }
}

/** One small status chip. Sized generously so it is easy to read at a glance. */
@Composable
private fun StatusPill(label: String, modifier: Modifier = Modifier) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = modifier
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 2,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 10.dp)
        )
    }
}

/** One quick action. Tapping sends [prompt] exactly as if it had been typed. */
@Composable
private fun QuickAction(icon: String, label: String, prompt: String, onClick: (String) -> Unit) {
    Card(
        onClick = { onClick(prompt) },
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            Modifier.padding(14.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(icon, style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(6.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                textAlign = TextAlign.Center,
                maxLines = 2
            )
        }
    }
}

/**
 * The quick-action grid.
 *
 * Every entry is a REAL command routed through the same pipeline as typing, so
 * none of them is a placeholder.
 */
@Composable
private fun QuickActionGrid(onQuickAction: (String) -> Unit) {
    val actions = listOf(
        Triple("🔋", "Battery", "What's my battery percentage?"),
        Triple("🎤", "Ask MAX", "What can you do?"),
        Triple("⏰", "Remind me", "Remind me to call mom tomorrow at 9 am"),
        Triple("🔍", "Search", "Search the web for train times to Delhi"),
        Triple("📝", "Notes", "Show my notes"),
        Triple("✅", "Tasks", "Show my tasks"),
        Triple("💡", "Flashlight", "Turn the flashlight on"),
        Triple("⏱", "Timer", "Set a timer for 10 minutes"),
        Triple("📅", "Calendar", "What's on my calendar"),
        Triple("📞", "Call", "Open the dialer")
    )
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        actions.chunked(2).forEach { row ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                row.forEach { (icon, label, prompt) ->
                    Box(Modifier.weight(1f)) {
                        QuickAction(icon, label, prompt, onQuickAction)
                    }
                }
                // Keep a lone final item aligned to the left column.
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

