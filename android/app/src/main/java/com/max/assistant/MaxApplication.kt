package com.max.assistant

import android.app.Application
import android.content.Context
import com.max.assistant.actions.AndroidActionExecutor
import com.max.assistant.ai.AIProvider
import com.max.assistant.ai.BackendAIProvider
import com.max.assistant.data.local.TokenStore
import com.max.assistant.data.remote.MaxApiClient
import com.max.assistant.permissions.PermissionManager
import com.max.assistant.services.AssistantNotifications
import com.max.assistant.settings.VoiceSettings
import com.max.assistant.speech.SpeechInput
import com.max.assistant.speech.SpeechOutput
import com.max.assistant.tools.LocalStore

// A tiny "service locator": one place that builds the long-lived objects.
// (Kept deliberately simple instead of a dependency-injection framework.)
class AppContainer(private val context: Context) {
    val tokens = TokenStore(context)
    val api = MaxApiClient(BuildConfig.API_BASE_URL, tokens, isNetworkAvailable = { isOnline(context) })
    val permissions = PermissionManager(context)
// Voice settings are plain, non-secret preferences (see VoiceSettings).
    val voiceSettings = VoiceSettings(context)

    // Notes and tasks live on the phone so they keep working offline.
    val localStore = LocalStore(context)

    // Speech objects are created ONCE and shared, because:
    //  - TextToSpeech initialisation is slow, and recreating it drops replies;
    //  - SpeechRecognizer must be created and destroyed on the main thread.
    val speechInput = SpeechInput(context)
    val speechOutput = SpeechOutput(context, voiceSettings)

    val executor = AndroidActionExecutor(context, this)
    val ai: AIProvider = BackendAIProvider(api)

    /** Called once when the process starts. Cheap, safe to repeat. */
    fun warmUp() {
        AssistantNotifications.ensureChannels(context)
        // Boot the TTS engine now, so the first reply is not swallowed while
        // the engine is still starting.
        speechOutput.init()
    }

    companion object {
        // Genuine offline detection: any IOException while this returns false is
        // reported as "You're offline.". When it returns true, failures become
        // "Can't reach the MAX server." so a wrong API_BASE_URL never masquerades
        // as the user being offline.
        fun isOnline(context: Context): Boolean {
            return try {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
                    ?: return true // service missing (tests): don't claim offline
                val net = cm.activeNetwork ?: return false
                val caps = cm.getNetworkCapabilities(net) ?: return false
                caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            } catch (_: Exception) {
                true // never block auth on a capability lookup failure
            }
        }
    }
}

class MaxApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(applicationContext)
        container.warmUp()
    }
}
