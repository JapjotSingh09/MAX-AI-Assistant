package com.max.assistant.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.max.assistant.services.VoiceState
import com.max.assistant.ui.components.MaxOrb
import java.util.Calendar

@Composable
fun HomeScreen(name: String, onSpeak: () -> Unit, onType: () -> Unit) {
    val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    val greeting = if (hour < 12) "Good morning" else if (hour < 18) "Good afternoon" else "Good evening"
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.fillMaxWidth()) {
            Text("$greeting${if (name.isNotBlank()) ", ${name.substringBefore(' ')}" else ""}", style = MaterialTheme.typography.headlineSmall)
            Text("MAX is ready.", color = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.height(48.dp))
        MaxOrb(VoiceState.IDLE, onClick = onSpeak)
        Spacer(Modifier.height(16.dp))
        Text("How can I help?", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(24.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onSpeak, modifier = Modifier.weight(1f)) { Text("Speak") }
            OutlinedButton(onClick = onType, modifier = Modifier.weight(1f)) { Text("Type") }
        }
    }
}
