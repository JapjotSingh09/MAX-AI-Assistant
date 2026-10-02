package com.max.assistant.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.max.assistant.BuildConfig
import com.max.assistant.data.remote.ApiException
import com.max.assistant.data.remote.MaxApiClient
import com.max.assistant.speech.VoiceState
import com.max.assistant.ui.components.MaxOrb
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun LoginScreen(api: MaxApiClient, onSignedIn: () -> Unit) {
    var signUpMode by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var isSigningUp by remember { mutableStateOf(false) }
    var isLoggingIn by remember { mutableStateOf(false) }
    var cooldown by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    // After a 429 the button stays disabled for a short cooldown.
    LaunchedEffect(cooldown) {
        if (cooldown > 0) { delay(1000); cooldown -= 1 }
    }

    val working = isSigningUp || isLoggingIn
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        MaxOrb(VoiceState.IDLE, size = 140.dp)
        Text("MAX", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
        Text("Your Personal AI Assistant", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(24.dp))

        if (signUpMode) OutlinedTextField(name, { name = it }, label = { Text("Your name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(email, { email = it.trim() }, label = { Text("Email") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(password, { password = it }, label = { Text("Password") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
        Spacer(Modifier.height(16.dp))

        Button(
            enabled = !working && cooldown == 0 && email.isNotBlank() && password.length >= (if (signUpMode) 8 else 1),
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                error = null
                scope.launch {
                    try {
                        // The flags make duplicate taps harmless: the button is disabled while working.
                        if (signUpMode) { isSigningUp = true; api.signUp(name, email, password) }
                        else { isLoggingIn = true; api.login(email, password) }
                        onSignedIn()
                    } catch (e: ApiException) {
                        // Debug aid only: e.message is already user-safe (never a password/token).
                        if (BuildConfig.DEBUG) android.util.Log.e("MaxAuth", "sign-in failure (code=${e.code}): ${e.message}")
                        error = e.message
                        if (e.code == 429) cooldown = 20
                    } catch (e: Exception) {
                        if (BuildConfig.DEBUG) android.util.Log.e("MaxAuth", "unexpected sign-in failure: ${e.javaClass.simpleName}: ${e.message}")
                        error = "Something went wrong. Please try again."
                    } finally {
                        isSigningUp = false
                        isLoggingIn = false
                    }
                }
            }
        ) {
            Text(if (cooldown > 0) "Please wait ${cooldown}s" else if (working) "Please wait…" else if (signUpMode) "Create account" else "Sign in")
        }
        TextButton(onClick = { signUpMode = !signUpMode; error = null }) {
            Text(if (signUpMode) "I already have an account" else "Create an account")
        }
    }
}
