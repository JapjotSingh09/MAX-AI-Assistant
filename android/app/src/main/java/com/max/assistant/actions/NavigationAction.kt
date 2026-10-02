package com.max.assistant.actions

import android.content.Context
import android.content.Intent
import android.net.Uri

// Maps, navigation and the browser, all through standard public Android intents.
class NavigationAction(private val context: Context) {

    fun openMaps(query: String?): ActionResult =
        launch(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(query.orEmpty())}")), "Opened maps.")

    fun navigate(destination: String): ActionResult =
        launch(Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${Uri.encode(destination)}")), "Started navigation to $destination.")

    fun openBrowser(url: String?, query: String?): ActionResult {
        val target = url ?: "https://www.google.com/search?q=${Uri.encode(query.orEmpty())}"
        return launch(Intent(Intent.ACTION_VIEW, Uri.parse(target)), "Opened your browser.")
    }

    private fun launch(intent: Intent, okMessage: String): ActionResult = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        ActionResult.Completed(okMessage)
    } catch (e: Exception) {
        // ActivityNotFoundException: no app can handle it on this phone.
        ActionResult.Failed("No app on this phone can do that.")
    }
}
