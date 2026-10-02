package com.max.assistant.ui.screens

import android.Manifest
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.max.assistant.AppContainer
import com.max.assistant.services.AssistantNotifications
import com.max.assistant.services.NotificationStore
import com.max.assistant.services.WakeWordService
import kotlinx.coroutines.launch

/**
 * Settings.
 *
 * Grouped by the questions a user actually has: what does MAX SAY, what can it
 * HEAR, what can it READ, and how do I sign out.
 *
 * Every permission line states WHY it is needed and where the data goes, because
 * "MAX needs this permission" with no reason trains people to tap Allow blindly.
 */
@Composable
fun SettingsScreen(container: AppContainer, onSignedOut: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val perms = container.permissions
    val settings = container.voiceSettings
    val speaker = container.speechOutput

    // Local mirrors so the controls feel instant; each change is written
    // straight through to VoiceSettings.
    var speechOn by remember { mutableStateOf(settings.speechEnabled) }
    var rate by remember { mutableFloatStateOf(settings.speechRate) }
    var voiceOnly by remember { mutableStateOf(settings.speakRepliesToVoiceOnly) }
    var wakeWord by remember { mutableStateOf(settings.wakeWordEnabled) }

    fun yesNo(b: Boolean) = if (b) "Allowed" else "Not allowed"
    fun openSettings(action: String) =
        runCatching { context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }

    // The while-in-use rule means a microphone foreground service can only be
    // STARTED from the foreground - which is exactly where this switch is.
    fun startWakeWord() =
        runCatching { context.startForegroundService(Intent(context, WakeWordService::class.java)) }
    fun stopWakeWord() = runCatching {
        context.startService(Intent(context, WakeWordService::class.java).setAction(WakeWordService.ACTION_STOP))
    }

    // @Composable because it emits Text.
    @Composable
    fun header(title: String) {
        Spacer(Modifier.height(20.dp))
        Text(title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(8.dp))
    }
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Spacer(Modifier.height(12.dp))
        Text("Settings", style = MaterialTheme.typography.headlineSmall)

        // --- Voice output -------------------------------------------------
        header("VOICE OUTPUT")
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Speak replies", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (speaker.isAvailable()) "Read MAX's answers out loud."
                    else "No text-to-speech engine on this phone.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = speechOn && speaker.isAvailable(),
                enabled = speaker.isAvailable(),
                onCheckedChange = { speechOn = it; settings.speechEnabled = it; speaker.applySettings() }
            )
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Only after voice", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Stay silent for typed messages.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = voiceOnly, onCheckedChange = { voiceOnly = it; settings.speakRepliesToVoiceOnly = it })
        }

        Spacer(Modifier.height(8.dp))
        Text("Speed", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Slider(
                value = rate,
                onValueChange = { rate = it; settings.speechRate = it; speaker.applySettings() },
                valueRange = 0.5f..2.0f,
                modifier = Modifier.weight(1f)
            )
            Text("%.1fx".format(rate), style = MaterialTheme.typography.bodyMedium)
        }

        // --- Voice input --------------------------------------------------
        header("VOICE INPUT")
        Text("Microphone: ${yesNo(perms.has(Manifest.permission.RECORD_AUDIO))}", style = MaterialTheme.typography.bodyLarge)
        Text(
            "Used only while you tap the mic, or while \"Hey MAX\" listening is on.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("\"Hey MAX\"", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Runs a background service that listens so you can open MAX hands-free. " +
                        "Android does not allow this without one, and it uses battery while on.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = wakeWord,
                enabled = perms.has(Manifest.permission.RECORD_AUDIO),
                onCheckedChange = { on ->
                    wakeWord = on
                    settings.wakeWordEnabled = on
                    if (on) startWakeWord() else stopWakeWord()
                }
            )
        }
        if (WakeWordService.isRunning) {
            Text(
                "Status: ${WakeWordService.status}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
        if (wakeWord && !perms.has(Manifest.permission.RECORD_AUDIO)) {
            Text(
                "Grant the microphone first to turn this on.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium
            )
        }

        // --- Permissions --------------------------------------------------
        header("PERMISSIONS")
        PermissionRow("Contacts", perms.has(Manifest.permission.READ_CONTACTS), "Find people by name for calls and messages. Stays on this phone.")
        PermissionRow("Phone", perms.has(Manifest.permission.CALL_PHONE), "Place a call after you confirm it. Without it, the dialer opens instead.")
        PermissionRow("Notifications", AssistantNotifications.canPost(context), "Android 13+ needs this to show reminders and the listening notice.")
        PermissionRow("Notification access", NotificationStore.listenerConnected, "Lets MAX read notification titles. Optional, stays on this phone.")

        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { openSettings(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Notification access settings") }
        OutlinedButton(
            onClick = { openSettings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS) },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
        ) { Text("All MAX permissions") }

        // --- Account ------------------------------------------------------
        header("ACCOUNT")
        Button(
            onClick = {
                scope.launch {
                    container.api.logout()
                    // Stop the microphone before the signed-in UI disappears.
                    container.speechInput.stop()
                    container.speechOutput.stop()
                    onSignedOut()
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Sign out") }
        Spacer(Modifier.height(32.dp))
    }
}

/** One permission line: its state and, more importantly, why it exists. */
@Composable
private fun PermissionRow(name: String, granted: Boolean, why: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text("$name: ${if (granted) "Allowed" else "Not allowed"}", style = MaterialTheme.typography.bodyLarge)
        Text(why, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
