package com.max.assistant

import android.app.Application
import android.content.Context
import com.max.assistant.actions.AndroidActionExecutor
import com.max.assistant.ai.AIProvider
import com.max.assistant.ai.BackendAIProvider
import com.max.assistant.data.local.TokenStore
import com.max.assistant.data.remote.MaxApiClient
import com.max.assistant.permissions.PermissionManager

// A tiny "service locator": one place that builds the long-lived objects.
// (Kept deliberately simple instead of a dependency-injection framework.)
class AppContainer(context: Context) {
    val tokens = TokenStore(context)
    val api = MaxApiClient(BuildConfig.API_BASE_URL, tokens, isNetworkAvailable = { isOnline(context) })
    val permissions = PermissionManager(context)
    val executor = AndroidActionExecutor(context, permissions)
    val ai: AIProvider = BackendAIProvider(api)

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
    }
}
