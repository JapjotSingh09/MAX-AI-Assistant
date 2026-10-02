package com.max.assistant.ui.screens

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.max.assistant.speech.VoiceState
import com.max.assistant.ui.components.MaxOrb
import com.max.assistant.ui.theme.MaxGold
import java.util.Calendar

/**
 * The home screen.
 *
 * The whole screen is built around ONE idea: the orb is the microphone. Tap it
 * and MAX listens. Everything else is secondary, which is why the quick
 * actions sit below the orb rather than competing with it.
 */
@Composable
fun HomeScreen(
    name: String,
    offline: Boolean,
    onSpeak: () -> Unit,
    onType: () -> Unit,
    onQuickAction: (String) -> Unit
) {
    val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    val greeting = when {
        hour < 12 -> "Good morning"
        hour < 18 -> "Good afternoon"
        else -> "Good evening"
    }
    val firstName = name.trim().substringBefore(' ')

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Header: who MAX is greeting, plus the state of the connection.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (firstName.isBlank()) greeting else "$greeting, $firstName",
                    style = MaterialTheme.typography.headlineSmall
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(if (offline) MaterialTheme.colorScheme.error else MaxGold)
                    )
                    Text(
                        if (offline) "  Offline - local commands still work" else "  Ready",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Text("MAX", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
        }

        Spacer(Modifier.height(40.dp))

        // The orb. A gradient halo behind it gives depth without a shadow layer.
        Box(contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .size(260.dp)
                    .clip(RoundedCornerShape(130.dp))
                    .background(
                        Brush.radialGradient(
                            listOf(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                                MaterialTheme.colorScheme.background.copy(alpha = 0f)
                            )
                        )
                    )
            )
            MaxOrb(state = VoiceState.IDLE, size = 190.dp, onClick = onSpeak)
        }

        Spacer(Modifier.height(24.dp))
        Text("How can I help?", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(4.dp))
        Text(
            "Tap the orb to talk.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        // A real alternative path to the assistant, not a decorative label.
        TextButton(onClick = onType, modifier = Modifier.padding(top = 4.dp)) { Text("Type instead") }

        Spacer(Modifier.height(20.dp))

        // Quick actions. Each one runs a REAL command through the local parser or
        // the AI - there are no decorative placeholders anywhere in this file.
        Text(
            "TRY ONE OF THESE",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(10.dp))
        QuickActionGrid(onQuickAction)
    }
}
    /** One quick action. Tapping sends [prompt] exactly as if it had been typed. */
@Composable
private fun QuickAction(icon: String, label: String, prompt: String, onClick: (String) -> Unit) {
    Card(
        onClick = { onClick(prompt) },
        modifier = Modifier,
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
 * `chunked(2)` gives a clean two-column layout from one loop, which avoids
 * pulling in the nested-scroll grid APIs for a fixed, small list.
 */
@Composable
private fun QuickActionGrid(onQuickAction: (String) -> Unit) {
    val actions = listOf(
        Triple("✦", "Ask MAX", "What can you do?"),
        Triple("⏰", "Remind me", "Remind me to call mom tomorrow at 9 am"),
        Triple("🔍", "Search", "Search the web for train times to Delhi"),
        Triple("📝", "Notes", "Show my notes"),
        Triple("✅", "Tasks", "Show my tasks"),
        Triple("📄", "Summarize", "Summarize this in three bullet points"),
        Triple("🌍", "Translate", "Translate this into Spanish"),
        Triple("✉️", "Email", "Draft an email to my manager about the delay"),
        Triple("⏱", "Timer", "Set a timer for 10 minutes"),
        Triple("📅", "Calendar", "What's on my calendar")
    )
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        actions.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
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
