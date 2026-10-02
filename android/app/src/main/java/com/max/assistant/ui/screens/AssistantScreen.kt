package com.max.assistant.ui.screens

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.max.assistant.AppContainer
import com.max.assistant.assistant.AssistantViewModel
import com.max.assistant.services.VoiceController
import com.max.assistant.services.VoiceState

@Composable
fun AssistantScreen(container: AppContainer, vm: AssistantViewModel, voice: VoiceController, autoListen: Boolean) {
    val state by vm.state.collectAsState()
    val voiceState by voice.state.collectAsState()
    var input by remember { mutableStateOf("") }
    var voiceError by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()

    // Permissions are requested only when needed, with a reason shown first (see PermissionManager.explain).
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { vm.onPermissionResult() }
    LaunchedEffect(state.permissionRequest) {
        if (state.permissionRequest.isNotEmpty()) permissionLauncher.launch(state.permissionRequest.toTypedArray())
    }

    fun listen() {
        voiceError = null
        voice.startListening(onText = { vm.send(it) }, onError = { voiceError = it })
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) listen() else voiceError = "Microphone permission is needed to use voice."
    }
    fun onMic() {
        if (voiceState == VoiceState.LISTENING) voice.stop()
        else if (container.permissions.has(Manifest.permission.RECORD_AUDIO)) listen()
        else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    LaunchedEffect(autoListen) { if (autoListen) onMic() }
    // Speak MAX's reply aloud only when the user used voice.
    LaunchedEffect(state.lastReply) {
        val reply = state.lastReply
        if (reply != null && voiceState == VoiceState.PROCESSING) voice.speak(reply)
    }
    LaunchedEffect(state.messages.size) { if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.size - 1) }
    DisposableEffect(Unit) { onDispose { voice.stop() } }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.messages.isEmpty()) item {
                Text("Try: \"Open YouTube\", \"Set a timer for 10 minutes\" or ask me anything.", modifier = Modifier.padding(top = 24.dp))
            }
            items(state.messages, key = { it.id }) { m ->
                Box(Modifier.fillMaxWidth(), contentAlignment = if (m.fromUser) Alignment.CenterEnd else Alignment.CenterStart) {
                    Text(
                        m.text,
                        color = if (m.fromUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .background(if (m.fromUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    )
                }
            }
            state.pending?.takeIf { state.permissionRequest.isEmpty() }?.let { p ->
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text("MAX wants to:", color = MaterialTheme.colorScheme.primary)
                            Text(p.intent.describe(), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(vertical = 8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { vm.cancel() }, modifier = Modifier.weight(1f)) { Text("Cancel") }
                                Button(onClick = { vm.confirm() }, modifier = Modifier.weight(1f)) { Text("Confirm") }
                            }
                        }
                    }
                }
            }
            if (state.busy) item { Text("MAX is thinking…") }
        }
        voiceError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onMic() }) { Text(if (voiceState == VoiceState.LISTENING) "■" else "🎙") }
            OutlinedTextField(input, { input = it }, placeholder = { Text("Message MAX") }, singleLine = true, modifier = Modifier.weight(1f))
            Button(enabled = input.isNotBlank() && !state.busy, onClick = { vm.send(input); input = "" }) { Text("Send") }
        }
    }
}
