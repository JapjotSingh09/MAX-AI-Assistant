package com.max.assistant.ui.screens

import android.Manifest
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.max.assistant.AppContainer
import com.max.assistant.services.NotificationStore
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(container: AppContainer, onSignedOut: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val perms = container.permissions

    fun yesNo(b: Boolean) = if (b) "Allowed" else "Not allowed"
    fun openSettings(action: String) = context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    Column(Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState())) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)

        Text("PERMISSIONS", color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 24.dp, bottom = 8.dp))
        Text("Microphone: ${yesNo(perms.has(Manifest.permission.RECORD_AUDIO))} (only used when you tap the mic)")
        Text("Contacts: ${yesNo(perms.has(Manifest.permission.READ_CONTACTS))} (to find people by name)")
        Text("Phone: ${yesNo(perms.has(Manifest.permission.CALL_PHONE))} (calls after you confirm)")
        Text("Notification access: ${yesNo(NotificationStore.listenerConnected)} (optional, stays on this phone)")
        OutlinedButton(onClick = { openSettings(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Text("Notification access settings")
        }
        OutlinedButton(onClick = { openSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Text("Accessibility (optional, manual)")
        }

        Text("ACCOUNT", color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 24.dp, bottom = 8.dp))
        Button(onClick = { scope.launch { container.api.logout(); onSignedOut() } }, modifier = Modifier.fillMaxWidth()) { Text("Sign out") }
    }
}
