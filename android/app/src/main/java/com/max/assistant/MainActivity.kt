package com.max.assistant

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.max.assistant.navigation.MaxApp
import com.max.assistant.ui.theme.MaxTheme

/**
 * The single activity.
 *
 * `launchMode="singleTop"` plus `onNewIntent` is what makes the "Hey MAX"
 * foreground service work: the service starts this activity with a command,
 * and if MAX is already open the system delivers that command to the EXISTING
 * instance instead of creating a second one. Without `onNewIntent` the extra
 * would be silently dropped and the wake word would appear to do nothing.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as MaxApplication).container
        setContent {
            MaxTheme {
                MaxApp(container, intent)
            }
        }
        // A cold start triggered by "Hey MAX" still has to act on the extra.
        handleIntent(intent)
    }

    /** Called when the activity is already on top and the service re-delivers it. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // `setIntent` keeps `getIntent()` correct, which the composable reads.
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val command = intent?.getStringExtra(EXTRA_PENDING_COMMAND).orEmpty()
        val openAssistant = intent?.getBooleanExtra(EXTRA_OPEN_ASSISTANT, false) ?: false
        if (command.isNotBlank() || openAssistant) {
            pendingWakeCommand = command
            pendingOpenAssistant = true
        }
    }

    companion object {
        /** Set by WakeWordService: open straight into the assistant. */
        const val EXTRA_OPEN_ASSISTANT = "com.max.assistant.extra.OPEN_ASSISTANT"

        /** Set by WakeWordService: a spoken command to run once the UI is up. */
        const val EXTRA_PENDING_COMMAND = "com.max.assistant.extra.PENDING_COMMAND"

        /**
         * Hand-off between `handleIntent` (a plain Activity callback) and the
         * Compose tree, which can only read it from a @Composable or a
         * ViewModel. Consumed exactly once by the navigation layer.
         */
        @Volatile
        var pendingWakeCommand: String = ""
            private set

        @Volatile
        var pendingOpenAssistant: Boolean = false
            private set

        /** Called by the UI once it has acted on the hand-off. */
        fun consumePending() {
            pendingWakeCommand = ""
            pendingOpenAssistant = false
        }
    }
}
