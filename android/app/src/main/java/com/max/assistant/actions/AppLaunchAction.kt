package com.max.assistant.actions

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

// Opens an installed app by its visible name (e.g. "YouTube").
class AppLaunchAction(private val context: Context) {

    fun open(appName: String): ActionResult {
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(launcher, PackageManager.MATCH_ALL)
        val wanted = appName.trim().lowercase()
        // Exact name first, then "contains", so "maps" finds "Google Maps" only if nothing better exists.
        val match = apps.firstOrNull { it.loadLabel(pm).toString().lowercase() == wanted }
            ?: apps.firstOrNull { it.loadLabel(pm).toString().lowercase().contains(wanted) }
            ?: return ActionResult.Failed("I couldn't find an app called $appName on this phone.")

        val intent = pm.getLaunchIntentForPackage(match.activityInfo.packageName)
            ?: return ActionResult.Failed("I couldn't open $appName.")
        return try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            ActionResult.Completed("Opened ${match.loadLabel(pm)}.")
        } catch (e: Exception) {
            ActionResult.Failed("I couldn't open $appName.")
        }
    }
}
