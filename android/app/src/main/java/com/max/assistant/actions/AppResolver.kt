package com.max.assistant.actions

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri

/**
 * THE GENERIC APP RESOLVER.
 *
 * There is deliberately NO `if (appName == "youtube")` anywhere in MAX. An app
 * name becomes a package by asking Android:
 *
 *     user text -> PackageManager -> installed apps -> scored match -> launch
 *
 * That is why "open Discord", "open Telegram" and "open that calculator app I
 * installed last week" all work without a line of code each.
 *
 * The alias table is the ONLY hardcoded app knowledge in the project. It exists
 * solely for names Android cannot resolve reliably - slang ("YT") or labels
 * that collide with something else installed - and it is DATA, so extending it
 * never means editing branching logic.
 */
class AppResolver(private val context: Context) {

    /**
     * Short, non-obvious names -> the package they almost certainly mean.
     *
     * Android already resolves apps by their visible LABEL, so "YouTube" needs
     * no entry. These cover only what label matching genuinely cannot.
     */
    private val aliases: Map<String, List<String>> = mapOf(
        "yt" to listOf("com.google.android.youtube"),
        "wa" to listOf("com.whatsapp"),
        "tg" to listOf("org.telegram.messenger"),
        "gm" to listOf("com.google.android.gm"),
        "fb" to listOf("com.facebook.katana"),
        "ig" to listOf("com.instagram.android"),
        "sa" to listOf("com.spotify.music"),
        "sp" to listOf("com.spotify.music"),
        "dis" to listOf("com.discord"),
        "vscode" to listOf("com.microsoft.editor"),
        "vs code" to listOf("com.microsoft.editor"),
        "calc" to listOf("com.android.calculator2", "com.google.android.calculator"),
        "clock" to listOf("com.android.deskclock"),
        "settings" to listOf("com.android.settings"),
        "files" to listOf("com.android.documentsui")
    )

    /**
     * Launchable apps, built once per process.
     *
     * `queryIntentActivities` is slow and the installed set barely changes, so
     * caching it is what keeps "open X" instant on the voice hot path.
     */
    private val installed: List<ResolveInfo> by lazy { queryLaunchable() }

    private fun queryLaunchable(): List<ResolveInfo> = try {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        context.packageManager.queryIntentActivities(launcher, PackageManager.MATCH_ALL)
    } catch (_: Exception) {
        emptyList()
    }

    /** The app's user-visible label, e.g. "Google Maps". */
    fun labelOf(info: ResolveInfo): String = try {
        context.packageManager.getApplicationLabel(info.activityInfo.applicationInfo).toString()
    } catch (_: Exception) {
        info.activityInfo.packageName
    }
/**
     * Resolves a spoken or typed app name to installed packages, best first.
     *
     * Scored rather than exact-then-contains, which is what makes "maps" prefer
     * "Google Maps" over "Maps & Routes" and lets "the discord app" still
     * resolve when the user was not literal.
     */
    fun resolve(name: String): List<String> {
        val q = name.trim().lowercase()
        if (q.isEmpty()) return emptyList()

        // An explicit alias is a deliberate override, so it wins outright.
        aliases[q]?.forEach { pkg -> if (isInstalled(pkg)) return listOf(pkg) }

        val scored = installed.mapNotNull { info ->
            val pkg = info.activityInfo.packageName
            val s = score(q, labelOf(info).lowercase(), pkg) ?: return@mapNotNull null
            pkg to s
        }
        return scored.sortedByDescending { it.second }.map { it.first }
    }

    /**
     * How well [query] matches a candidate, or null when it does not match.
     *
     * Generous but ORDERED, so a better match always sorts above a worse one
     * instead of both being "found" and the first one arbitrarily winning.
     */
    private fun score(query: String, label: String, pkg: String): Int? {
        if (label == query) return 100
        // Package id typed literally: "com.spotify.music".
        if (pkg.lowercase() == query) return 95
        if (label.startsWith(query)) return 80
        // A whole word inside the label: "YouTube Music" for "youtube".
        if (Regex("\\b" + Regex.escape(query) + "\\b").containsMatchIn(label)) return 70
        // A short query must not substring-match everything on the phone.
        if (query.length >= 4 && label.contains(query)) return 55
        // Every query word appears in the label: "google maps" for "maps app".
        val words = query.split(' ').filter { it.isNotBlank() }
        if (words.size > 1 && words.all { label.contains(it) }) return 45
        // The user may have prefixed "the" or "my".
        val stripped = query.removePrefix("the ").removePrefix("my ").trim()
        if (stripped != query) return score(stripped, label, pkg)
        return null
    }

    private fun isInstalled(pkg: String): Boolean = try {
        context.packageManager.getApplicationInfo(pkg, 0)
        true
    } catch (_: Exception) {
        false
    }

    /** The Intent that launches [pkg], or null when it has no launcher entry. */
    fun launchIntent(pkg: String): Intent? = try {
        context.packageManager.getLaunchIntentForPackage(pkg)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    } catch (_: Exception) {
        null
    }

    /** Launchable apps as (label, package) pairs, for the Settings alias screen. */
    fun launchableApps(): List<Pair<String, String>> =
        installed.map { labelOf(it) to it.activityInfo.packageName }
            .sortedBy { it.first.lowercase() }

    /**
     * Whether ANY installed app can handle [uri].
     *
     * This is how MAX knows a deep link is real before promising it - see
     * AppActionCatalog. Checking first is what stops MAX claiming it searched
     * inside an app when nothing actually opened.
     */
    fun canOpen(uri: String): Boolean = try {
        context.packageManager
            .resolveActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)), 0) != null
    } catch (_: Exception) {
        false
    }
}