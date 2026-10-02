package com.max.assistant.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.max.assistant.AppContainer
import com.max.assistant.assistant.AssistantViewModel
import com.max.assistant.services.VoiceController
import com.max.assistant.ui.screens.AssistantScreen
import com.max.assistant.ui.screens.HomeScreen
import com.max.assistant.ui.screens.LoginScreen
import com.max.assistant.ui.screens.SettingsScreen

private data class Tab(val route: String, val label: String, val icon: String)

private val tabs = listOf(Tab("home", "Home", "◉"), Tab("assistant", "Assistant", "✦"), Tab("settings", "Settings", "⚙"))

// Top level: signed out -> LoginScreen, signed in -> main tabs.
// (Automations, Activity, Memory and the Notification Center screens are on the roadmap.)
@Composable
fun MaxApp(container: AppContainer) {
    var signedIn by remember { mutableStateOf(container.tokens.hasToken()) }
    if (!signedIn) {
        LoginScreen(container.api) { signedIn = true }
    } else {
        MainTabs(container) { signedIn = false }
    }
}

@Composable
private fun MainTabs(container: AppContainer, onSignedOut: () -> Unit) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val context = LocalContext.current
    val vm: AssistantViewModel = viewModel(factory = AssistantViewModel.Factory(container))
    val voice = remember { VoiceController(context) }
    DisposableEffect(Unit) { onDispose { voice.shutdown() } }
    var autoListen by remember { mutableStateOf(false) }

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
                    onSpeak = { autoListen = true; nav.navigate("assistant") },
                    onType = { autoListen = false; nav.navigate("assistant") }
                )
            }
            composable("assistant") { AssistantScreen(container, vm, voice, autoListen) }
            composable("settings") { SettingsScreen(container, onSignedOut) }
        }
    }
}
