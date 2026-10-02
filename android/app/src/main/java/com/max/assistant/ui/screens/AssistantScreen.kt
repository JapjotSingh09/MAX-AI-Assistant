package com.max.assistant.ui.screens

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.max.assistant.AppContainer
import com.max.assistant.assistant.AssistantViewModel
import com.max.assistant.assistant.ChatMessage
import com.max.assistant.speech.SpeechInput
import com.max.assistant.speech.SpeechOutput
import com.max.assistant.speech.VoiceState
import com.max.assistant.ui.components.MaxOrb

/**
 * The conversation screen.
 *
 * Responsibilities, in order of importance:
 *  1. make the microphone obvious and make listening feel alive;
 *  2. show the REAL result of every action, including the Android limitations;
 *  3. make confirmation unavoidable for anything destructive or outward-facing.
 *
 * Voice is push-to-talk by default: the orb starts listening on tap, shows the
 * partial transcript live, and closes the microphone the moment the user taps
 * again or the recogniser finishes. Nothing here runs in the background.
 */
@Composable
fun AssistantScreen(
    container: AppContainer,
    vm: AssistantViewModel,
    speech: SpeechInput,
    speaker: SpeechOutput,
    autoListen: Boolean,
    onListeningHandled: () -> Unit,
    onOpenHistory: () -> Unit
) {
    val state by vm.state.collectAsState()
    val voice by speech.ui.collectAsState()
    val speaking by speaker.state.collectAsState()
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // The orb shows the most meaningful of the two speech states: speaking wins
    // over listening, because that is the one the user needs to be able to stop.
    val orbState = when {
        speaking == VoiceState.SPEAKING -> VoiceState.SPEAKING
        voice.state == VoiceState.ERROR -> VoiceState.IDLE
        else -> voice.state
    }

    // --- Permissions ----------------------------------------------------
    // Requested at the moment of use, with a plain-language reason shown first.
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { vm.onPermissionResult() }
    LaunchedEffect(state.permissionRequest) {
        if (state.permissionRequest.isNotEmpty()) {
            permissionLauncher.launch(state.permissionRequest.toTypedArray())
        }
    }
    // Local mirror so the error survives recomposition without holding it in a
    // state object the speech layer would need to know about.
    var speechError by remember { mutableStateOf<String?>(null) }

    fun startListening() {
        speechError = null
        speaker.stop() // never listen and speak at once: MAX would hear itself
        speech.start(
            onText = { vm.send(it, fromVoice = true) },
            onError = { speechError = it }
        )
    }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startListening() else speechError = "Microphone permission is needed to use voice."
    }

    fun onMic() {
        when {
            voice.state == VoiceState.LISTENING -> speech.stop()
            container.permissions.has(Manifest.permission.RECORD_AUDIO) -> startListening()
            else -> micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // Enter the screen from the home orb or from "Hey MAX".
    LaunchedEffect(autoListen) {
        if (autoListen) {
            onMic()
            onListeningHandled()
        }
    }

    // Speak the reply only when the user used voice, or when they allow all
    // replies. A typed conversation should stay silent by default.
    LaunchedEffect(state.lastReply, state.lastTurnWasVoice) {
        val reply = state.lastReply ?: return@LaunchedEffect
        if (reply.isBlank() || state.thinking) return@LaunchedEffect
        if (container.voiceSettings.speakRepliesToVoiceOnly && !state.lastTurnWasVoice) return@LaunchedEffect
        speaker.speak(reply)
    }

    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.size - 1)
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        // --- Offline banner: shown only when genuinely offline, and it says
        // what still works so it does not look like a dead app.
        AnimatedVisibility(visible = state.offline, enter = fadeIn(), exit = fadeOut()) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
            ) {
                Text(
                    "You're offline. Local commands, notes, tasks and timers still work.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }

        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (state.messages.isEmpty()) item { EmptyTranscript(state.status) }
            items(state.messages, key = { it.id }) { m -> MessageBubble(m) }

            // The confirmation card. It cannot be skipped, and it always spells
            // out what the action will really do.
            state.pending?.takeIf { state.permissionRequest.isEmpty() }?.let { p ->
                item {
                    ConfirmationCard(
                        label = p.intent.describe(),
                        onConfirm = { vm.confirm() },
                        onCancel = { vm.cancel() }
                    )
                }
            }

            // Thinking indicator, or the name of the tool currently running.
            if (state.thinking || state.status != null) {
                item { WorkingRow(state.thinking, state.status) }
            }
        }

        // --- Live partial transcript, above the composer while listening.
        AnimatedVisibility(
            visible = voice.partialText.isNotBlank() && voice.state == VoiceState.LISTENING,
            enter = fadeIn(), exit = fadeOut()
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
            ) {
                Text(voice.partialText, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(12.dp))
            }
        }

        speechError?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 6.dp))
        }

        Composer(
            input = input,
            onInputChange = { input = it },
            onSend = { if (input.isNotBlank()) { vm.send(input); input = "" } },
            onMic = ::onMic,
            onStopSpeaking = speaker::stop,
            orbState = orbState,
            amplitude = voice.amplitude,
            busy = state.busy,
            onOpenHistory = onOpenHistory,
            onClear = vm::clearOnScreen
        )
    }
}

/** Empty state: what MAX is, plus a suggested first move. */
@Composable
private fun EmptyTranscript(status: String?) {
    Column(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Ask MAX anything", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            status ?: "Try \"Remind me tomorrow at 9 to call dad\", \"Open YouTube\", or just ask a question.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 12.dp)
        )
    }
}

/** One message. The user is right-aligned in a gold bubble, MAX on the left. */
@Composable
private fun MessageBubble(m: ChatMessage) {
    val mine = m.fromUser
    Box(Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
        Box(
            Modifier
                .clip(
                    RoundedCornerShape(
                        topStart = 18.dp, topEnd = 18.dp,
                        bottomStart = if (mine) 18.dp else 6.dp,
                        bottomEnd = if (mine) 6.dp else 18.dp
                    )
                )
                .background(if (mine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Text(
                m.text,
                style = MaterialTheme.typography.bodyLarge,
                color = if (mine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
/**
 * The confirmation card.
 *
 * Destructive and outward-facing actions ALWAYS land here, and there is no way
 * to skip it. The two buttons are equally weighted on purpose: for a delete,
 * Confirm is usually not the "primary" action and should not look like it.
 */
@Composable
private fun ConfirmationCard(label: String, onConfirm: () -> Unit, onCancel: () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("MAX wants to", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(6.dp))
            Text(label, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("Cancel") }
                Button(onClick = onConfirm, modifier = Modifier.weight(1f)) { Text("Confirm") }
            }
        }
    }
}

/** Thinking spinner, or the name of the tool currently running. */
@Composable
private fun WorkingRow(thinking: Boolean, status: String?) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (thinking) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.size(10.dp))
            Text("MAX is thinking…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else if (status != null) {
            Text(status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}

/**
 * The composer: the live orb plus the text field.
 *
 * The orb is 44dp, which is the minimum comfortable touch target, and it
 * doubles as the "stop listening" and "stop speaking" button so there is only
 * one control to learn.
 */
@Composable
private fun Composer(
    input: String,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onMic: () -> Unit,
    onStopSpeaking: () -> Unit,
    orbState: VoiceState,
    amplitude: Float,
    busy: Boolean,
    onOpenHistory: () -> Unit,
    onClear: () -> Unit
) {
    val isSpeaking = orbState == VoiceState.SPEAKING
    val isListening = orbState == VoiceState.LISTENING || orbState == VoiceState.PROCESSING
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // History, and clear the visible transcript. Deleting a whole
            // conversation is a separate, confirmed action on the History tab.
            IconButton(onClick = onOpenHistory) { Text("☰", style = MaterialTheme.typography.titleMedium) }
            IconButton(onClick = onClear) { Text("✕", style = MaterialTheme.typography.titleMedium) }

            OutlinedTextField(
                value = input,
                onValueChange = onInputChange,
                placeholder = { Text("Message MAX") },
                singleLine = true,
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.weight(1f)
            )

            // One button, three states: send / stop listening / stop speaking.
            // It shows the live orb whenever MAX is doing something with sound,
            // so the user always knows whether the microphone is open.
            IconButton(
                onClick = when {
                    isSpeaking -> onStopSpeaking
                    else -> onMic
                },
                enabled = !busy || isListening || isSpeaking
            ) {
                when (orbState) {
                    VoiceState.SPEAKING -> Text("⏹", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.error)
                    VoiceState.LISTENING, VoiceState.PROCESSING -> MaxOrb(orbState, size = 36.dp, amplitude = amplitude)
                    else -> Text("🎙", style = MaterialTheme.typography.titleLarge)
                }
            }

            Button(onClick = onSend, enabled = input.isNotBlank() && !busy) { Text("Send") }
        }
    }
}


