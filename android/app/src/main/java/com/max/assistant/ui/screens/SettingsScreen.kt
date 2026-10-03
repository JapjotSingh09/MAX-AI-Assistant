package com.max.assistant.ui.screens

import android.Manifest
import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.max.assistant.AppContainer
import com.max.assistant.BuildConfig
import com.max.assistant.actions.AppResolver
import com.max.assistant.services.AssistantNotifications
import com.max.assistant.services.NotificationStore
import com.max.assistant.services.WakeWordService
import com.max.assistant.ui.theme.MaxGold

/**
 * SETTINGS.
 *
 * Organised by the questions a user actually has: what does MAX SAY, what can
 * it HEAR, what can it READ, and who am I.
 *
 * EVERY CONTROL IS WIRED TO REAL STATE - there are no decorative switches:
 *  - "Hey MAX" shows Off / On / the ACTUAL status read from the live service.
 *  - A control that cannot work (no microphone permission, no TTS engine) is
 *    DISABLED and says why, instead of pretending.
 *  - The "apps MAX can open" count comes from the real PackageManager, so it
 *    is true on this phone rather than a made-up number.
 *
 * Every line states WHY it exists and where the data goes. "MAX needs this
 * permission" with no reason trains people to tap Allow blindly.
 */
@Composable
fun SettingsScreen(container: AppContainer, onSignedOut: () -> Unit) {
    val context = LocalContext.current
    val perms = container.permissions
    val settings = container.voiceSettings
    val speaker = container.speechOutput
    val speech = container.speechInput

    // Local mirrors so controls feel instant; each change writes straight
    // through to the real setting, so nothing here is decorative.
    var speechOn by remember { mutableStateOf(settings.speechEnabled) }
    var rate by remember { mutableFloatStateOf(settings.speechRate) }
    var voiceOnly by remember { mutableStateOf(settings.speakRepliesToVoiceOnly) }
    var wakeWord by remember { mutableStateOf(settings.wakeWordEnabled) }

    // ---- REAL state, read from the device -------------------------------
    val micGranted = perms.has(Manifest.permission.RECORD_AUDIO)
    val ttsAvailable = speaker.isAvailable()
    val wakeRunning = WakeWordService.isRunning
    val recognitionAvailable = speech.isSupported()
    val installedApps = remember { AppResolver(context).launchableApps().size }

    fun openSettings(action: String) =
        runCatching { context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }

    // The while-in-use rule means a microphone foreground service can only be
    // STARTED from the foreground - which is exactly where this switch is.
    fun startWakeWord() =
        runCatching { context.startForegroundService(Intent(context, WakeWordService::class.java)) }
    fun stopWakeWord() = runCatching {
        context.startService(Intent(context, WakeWordService::class.java).setAction(WakeWordService.ACTION_STOP))
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Spacer(Modifier.height(16.dp))
        Text("Settings", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Everything here is connected to a real capability. If something is unavailable, it says so.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // ===== VOICE & WAKE ==============================================
        SectionCard("VOICE & WAKE", "What MAX hears, and how it answers.") {
            ToggleRow(
                title = "\"Hey MAX\"",
                subtitle = if (!micGranted) "Needs the microphone first."
                else if (!recognitionAvailable) "Speech recognition is not available on this phone."
                else "Listens for \"Hey MAX\" in short windows so you can open MAX hands-free. " +
                    "Uses battery while on, and Android shows a permanent notification.",
                checked = wakeWord && wakeRunning,
                enabled = micGranted && recognitionAvailable,
                onChange = { on ->
                    wakeWord = on
                    settings.wakeWordEnabled = on
                    if (on) startWakeWord() else stopWakeWord()
                }
            )
            StatusLine(
                label = "Background listening",
                value = when {
                    wakeRunning -> "ON - ${WakeWordService.status}"
                    wakeWord -> "Starting…"
                    else -> "Off"
                },
                good = wakeRunning
            )
            if (wakeWord && !micGranted) {
                WarningLine("Grant the microphone to use the wake word.")
            }
            OutlinedButton(
                onClick = { openSettings(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS) },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Grant microphone in Android settings") }

            ToggleRow(
                title = "Speak replies",
                subtitle = if (ttsAvailable) "Read MAX's answers out loud."
                else "No text-to-speech engine is installed on this phone, so this cannot work.",
                checked = speechOn && ttsAvailable,
                enabled = ttsAvailable,
                onChange = { speechOn = it; settings.speechEnabled = it; speaker.applySettings() }
            )
            ToggleRow(
                title = "Only after voice",
                subtitle = "Stay silent for typed messages, speak spoken ones.",
                checked = voiceOnly,
                enabled = true,
                onChange = { voiceOnly = it; settings.speakRepliesToVoiceOnly = it }
            )

            Spacer(Modifier.height(4.dp))
            Text("Speaking speed", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Slider(
                    value = rate,
                    onValueChange = { rate = it; settings.speechRate = it; speaker.applySettings() },
                    valueRange = 0.5f..2.0f,
                    enabled = ttsAvailable,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Text("%.1fx".format(rate), style = MaterialTheme.typography.bodyMedium)
            }
        }

        // ===== ASSISTANT =================================================
        SectionCard("ASSISTANT", "How MAX understands you.") {
            InfoRow(
                "Follow-up context",
                "MAX remembers the app you just opened for 90 seconds, so \"search for X\" " +
                    "knows which app you mean. It expires quickly and never overrides an app you name."
            )
            InfoRow(
                "Ambiguous commands",
                "When a request could mean two things, MAX asks instead of guessing."
            )
            InfoRow(
                "Actions",
                "Every device action is drawn from a fixed whitelist and shown for " +
                    "confirmation when it is outward-facing or destructive. MAX cannot run " +
                    "arbitrary commands."
            )
        }

        // ===== DEVICE ACTIONS ============================================
        SectionCard("DEVICE ACTIONS", "What MAX is allowed to do.") {
            PermissionRow(
                "Microphone", micGranted,
                "Used only while you tap the mic, or while \"Hey MAX\" is on. Audio is processed " +
                    "on the phone and is never recorded or uploaded."
            )
            PermissionRow(
                "Contacts", perms.has(Manifest.permission.READ_CONTACTS),
                "Finds people by name for calls and messages. Stays on this phone."
            )
            PermissionRow(
                "Phone", perms.has(Manifest.permission.CALL_PHONE),
                "Places a call after you confirm it. Without it, the dialer opens instead."
            )
            PermissionRow(
                "Notifications", AssistantNotifications.canPost(context),
                "Android 13+ needs this to show reminders and the listening notice."
            )
            PermissionRow(
                "Notification access", NotificationStore.listenerConnected,
                "Lets MAX read notification titles. Optional, and stays on this phone."
            )
            OutlinedButton(
                onClick = { openSettings(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Notification access settings") }
            OutlinedButton(
                onClick = { openSettings(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS) },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            ) { Text("All MAX permissions") }
        }

        // ===== ABOUT ====================================================
        SectionCard("ABOUT", "What this app is.") {
            InfoRow("Version", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            InfoRow(
                "Apps MAX can open",
                "$installedApps installed apps resolved dynamically. Any app can be opened by " +
                    "name; short aliases like \"YT\" and \"WA\" are also accepted."
            )
            InfoRow(
                "Honest limits",
                "Android does not let any app switch Bluetooth or battery saver, read your " +
                    "calendar silently, or control other apps beyond their public links. " +
                    "Where that is true, MAX says so instead of pretending."
            )
            InfoRow(
                "Privacy",
                "Your session token is encrypted in the Android Keystore. API keys live only on " +
                    "the server. MAX never records or uploads microphone audio."
            )
        }

        // ===== ACCOUNT ===================================================
        Button(
            onClick = {
                container.speechInput.release()
                speaker.stop()
                onSignedOut()
            },
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer
            ),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
        ) { Text("Sign out") }

        Spacer(Modifier.height(32.dp))
    }
}

/** A titled card that groups related settings. */
@Composable
private fun SectionCard(title: String, blurb: String, content: @Composable ColumnScope.() -> Unit) {
    Spacer(Modifier.height(20.dp))
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(18.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                color = MaxGold,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(2.dp))
            Text(
                blurb,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

/** A switch with a title and a real explanation. */
@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onChange,
            modifier = Modifier.semantics { contentDescription = title }
        )
    }
}

/** A read-only explanation. No switch, because there is nothing to change. */
@Composable
private fun InfoRow(label: String, value: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(label, style = MaterialTheme.typography.titleMedium)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** A live state readout. The dot animates so a change is noticeable. */
@Composable
private fun StatusLine(label: String, value: String, good: Boolean) {
    val dot by animateColorAsState(
        if (good) MaxGold else MaterialTheme.colorScheme.onSurfaceVariant,
        label = "statusDot"
    )
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(dot))
        Spacer(Modifier.width(10.dp))
        Text("$label: ", style = MaterialTheme.typography.bodyMedium)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** An honest warning about something that cannot currently work. */
@Composable
private fun WarningLine(text: String) {
    AnimatedVisibility(visible = true) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(vertical = 4.dp)
        )
    }
}

/** One permission line: its state and, more importantly, why it exists. */
@Composable
private fun PermissionRow(name: String, granted: Boolean, why: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (granted) "●" else "○",
                style = MaterialTheme.typography.bodyLarge,
                color = if (granted) MaxGold else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(8.dp))
            Text("$name: ${if (granted) "Allowed" else "Not allowed"}",
                style = MaterialTheme.typography.bodyLarge)
        }
        Text(
            why,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 20.dp)
        )
    }
}

