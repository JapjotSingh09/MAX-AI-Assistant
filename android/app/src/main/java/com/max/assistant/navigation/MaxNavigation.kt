package com.max.assistant.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.max.assistant.AppContainer
import com.max.assistant.MainActivity
import com.max.assistant.assistant.AssistantViewModel
import com.max.assistant.ui.screens.AssistantScreen
import com.max.assistant.ui.screens.ConversationsScreen
import com.max.assistant.ui.screens.HomeScreen
import com.max.assistant.ui.screens.LoginScreen
import com.max.assistant.ui.screens.SettingsScreen

private data class Tab(val route: String, val label: String, val icon: String)

private val tabs = listOf(
    Tab("home", "Home", "◉"),
    Tab("assistant", "Ask", "✦"),
    Tab("conversations", "History", "☰"),
    Tab("settings", "Settings", "⚙")
)

// Top level: signed out -> LoginScreen, signed in -> main tabs.
@Composable
fun MaxApp(container: AppContainer, launchIntent: android.content.Intent? = null) {
    var signedIn by remember { mutableStateOf(container.tokens.hasToken()) }
    if (!signedIn) {
        LoginScreen(container.api) { signedIn = true }
    } else {
        MainTabs(container, launchIntent) { signedIn = false }
    }
}

@Composable
private fun MainTabs(
    container: AppContainer,
    launchIntent: android.content.Intent?,
    onSignedOut: () -> Unit
) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    // ONE ViewModel for the whole signed-in session. It owns the conversation,
    // the transcript and the pending action, so switching tabs never loses them.
    val vm: AssistantViewModel = viewModel(factory = AssistantViewModel.Factory(container))
    val state by vm.state.collectAsState()

    // Speech objects live on the container (created once, see AppContainer).
    val speech = container.speechInput
    val speaker = container.speechOutput

    // Release the microphone when the whole signed-in UI goes away. TTS is
    // long-lived and cheap, so it is only stopped on sign-out.
    DisposableEffect(Unit) { onDispose { speech.stop(); speaker.stop() } }

    var autoListen by remember { mutableStateOf(false) }

    // "Hey MAX" hand-off: WakeWordService put a pending command on the
    // Activity, and it is routed into the assistant exactly once.
    LaunchedEffect(launchIntent) {
        val command = MainActivity.pendingWakeCommand
        if (MainActivity.pendingOpenAssistant || command.isNotBlank()) {
            nav.navigate("assistant") { launchSingleTop = true }
            if (command.isNotBlank()) {
                autoListen = false
                vm.send(command, fromVoice = true)
            } else {
                autoListen = true
            }
            MainActivity.consumePending()
        }
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEach { t ->
                    NavigationBarItem(
                        selected = backStack?.destination?.route == t.route,
                        onClick = { nav.navigate(t.route) { launchSingleTop = true; popUpTo("home") } },
                        icon = { Text(t.icon) },
                        label = { Text(t.label) }
                    )
                }
            }
        }
    ) { padding ->
        NavHost(nav, startDestination = "home", modifier = Modifier.padding(padding)) {
            composable("home") {
                HomeScreen(
                    name = container.tokens.displayName,
                    offline = state.offline,
                    onSpeak = { autoListen = true; nav.navigate("assistant") },
                    onType = { autoListen = false; nav.navigate("assistant") },
                    onQuickAction = { text ->
                        autoListen = false
                        vm.send(text)
                        nav.navigate("assistant")
                    }
                )
            }
            composable("assistant") {
                AssistantScreen(
                    container = container,
                    vm = vm,
                    speech = speech,
                    speaker = speaker,
                    autoListen = autoListen,
                    onListeningHandled = { autoListen = false },
                    onOpenHistory = { nav.navigate("conversations") }
                )
            }
            composable("conversations") {
                ConversationsScreen(
                    vm = vm,
                    onOpen = { id ->
                        vm.openConversation(id)
                        nav.navigate("assistant") { launchSingleTop = true }
                    }
                )
            }
            composable("settings") { SettingsScreen(container, onSignedOut) }
        }
    }
}
