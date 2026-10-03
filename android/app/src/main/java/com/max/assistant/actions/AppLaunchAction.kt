package com.max.assistant.actions

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * APP LAUNCH and APP-SPECIFIC ACTIONS, both generic.
 *
 * Two jobs:
 *  1. [open] turns any spoken app name into a launched app via [AppResolver],
 *     with no per-app code.
 *  2. [act] performs an OPERATION inside an app - search, play, navigate - by
 *     looking the package up in [AppActionCatalog] and using a documented
 *     Android deep link.
 *
 * WHY THE FALLBACKS ARE HONEST
 * Third-party apps differ in what they expose. MAX checks that a URI can
 * actually be handled before starting it, and reports exactly what it did: the
 * app, the web version, or neither. It never claims an in-app action succeeded
 * when all it managed was open the app.
 */
class AppLaunchAction(
    private val context: Context,
    private val resolver: AppResolver = AppResolver(context)
) {

    /**
     * "Open Chrome" / "Open Discord" / "Open Telegram".
     *
     * Works for ANY installed app because the name is matched against what is
     * actually on this phone, not against a hardcoded list.
     */
    fun open(appName: String): ActionResult {
        val wanted = appName.trim()
        if (wanted.isEmpty()) return ActionResult.Failed("I didn't catch which app to open.")

        val pkg = resolver.resolve(wanted).firstOrNull()
            ?: return ActionResult.Failed("I couldn't find an app called \"$wanted\" on this phone.")

        val intent = resolver.launchIntent(pkg)
            ?: return ActionResult.Failed("I found $wanted but it has no launcher entry.")
        return try {
            context.startActivity(intent)
            ActionResult.Completed("Opening ${labelOf(pkg)}.")
        } catch (_: Exception) {
            ActionResult.Failed("I couldn't open ${labelOf(pkg)}.")
        }
    }

    /** The visible label for a package, falling back to the package id. */
    fun labelOf(pkg: String): String = try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) {
        pkg
    }
/**
     * Performs [operation] inside the app matching [appName].
     *
     * The decision ladder, in order:
     *  1. App not installed         -> the documented web equivalent, if any.
     *  2. Installed, deep link works -> do it in the app.
     *  3. Installed, no deep link    -> launch the app and say so plainly.
     *  4. Nothing can handle it      -> an honest failure.
     */
    fun act(appName: String, operation: AppOperation, entity: String?): ActionResult {
        val query = entity?.trim().orEmpty()
        val wanted = appName.trim()

        // A bare operation with no app ("search for Android tutorials") is a
        // web search, which every phone can do.
        if (wanted.isEmpty()) {
            if (query.isEmpty()) return ActionResult.Failed("I didn't catch what to search for.")
            return launchUri(
                AppActionCatalog.genericWebSearch(query),
                "Searching the web for \"$query\"."
            )
        }

        val pkg = resolver.resolve(wanted).firstOrNull()
        if (pkg == null) {
            val web = webUrlFor(wanted, operation, query)
            return if (web != null) {
                launchUri(web, "\"$wanted\" isn't installed, so I've opened the web version.")
            } else {
                ActionResult.Failed("I couldn't find an app called \"$wanted\" on this phone.")
            }
        }

        val capability = AppActionCatalog.capabilityFor(pkg, operation)
        if (capability == null) {
            // This app exposes no public way to be steered. Opening it and
            // SAYING SO beats a fake success.
            return open(wanted).let { r ->
                if (r is ActionResult.Completed) {
                    ActionResult.Prepared(
                        "${labelOf(pkg)} doesn't let me do that directly, so I've opened it for you."
                    )
                } else r
            }
        }

        val uri = AppActionCatalog.buildUri(capability, query)
            ?: return ActionResult.Failed("I didn't catch what to look for in $wanted.")

        // Only claim an in-app action when the URI is genuinely handled.
        if (resolver.canOpen(uri)) {
            return launchUri(uri, doneMessage(labelOf(pkg), operation, query))
        }

        val web = AppActionCatalog.buildWebFallback(capability, query)
        if (web != null && resolver.canOpen(web)) {
            return launchUri(web, "I've opened ${labelOf(pkg)} on the web for \"$query\".")
        }

        return open(wanted).let { r ->
            if (r is ActionResult.Completed) {
                ActionResult.Prepared("I've opened ${labelOf(pkg)} - that needs a tap inside the app.")
            } else r
        }
    }

    /** The https URL for an operation, found via the catalog. */
    private fun webUrlFor(appName: String, operation: AppOperation, entity: String): String? {
        if (entity.isEmpty()) return null
        val pkg = ALIAS_PACKAGES[appName.lowercase()] ?: return null
        val capability = AppActionCatalog.capabilityFor(pkg, operation) ?: return null
        return AppActionCatalog.buildWebFallback(capability, entity)
    }

    /** A pastable, honest description of what was actually done. */
    private fun doneMessage(app: String, operation: AppOperation, entity: String): String =
        when (operation) {
            AppOperation.SEARCH -> "Searching $app for \"$entity\"."
            AppOperation.PLAY -> "Opening $app for \"$entity\"."
            AppOperation.PROFILE -> "Opening that $app profile."
            AppOperation.CHAT -> "Opening the chat in $app."
            AppOperation.NAVIGATE -> "Starting directions in $app."
            AppOperation.COMPOSE -> "Opening $app to compose."
            AppOperation.OPEN -> "Opening $app."
        }

    private fun launchUri(uri: String, okMessage: String): ActionResult = try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(uri))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        ActionResult.Completed(okMessage)
    } catch (_: Exception) {
        // Nothing on the phone can handle it - say that rather than implying
        // it half-worked.
        ActionResult.Failed("Nothing on this phone can open that.")
    }

    private companion object {
        /**
         * Web fallbacks for apps that are NOT installed, so a request still works
         * on a phone lacking the app. Keyed by spoken name; DATA, not logic.
         */
        val ALIAS_PACKAGES = mapOf(
            "youtube" to "com.google.android.youtube",
            "spotify" to "com.spotify.music",
            "maps" to "com.google.android.apps.maps",
            "google maps" to "com.google.android.apps.maps",
            "instagram" to "com.instagram.android",
            "whatsapp" to "com.whatsapp",
            "amazon" to "com.amazon.mShop.android.shopping",
            "flipkart" to "com.flipkart.android",
            "netflix" to "com.netflix.mediaclient"
        )
    }
}