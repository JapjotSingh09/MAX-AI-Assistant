package com.max.assistant.permissions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.max.assistant.assistant.ActionType
import com.max.assistant.tools.ToolRegistry

// Permissions are requested only when a feature needs them (never all at startup).
class PermissionManager(private val context: Context) {

    fun has(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /**
     * Runtime permissions an action needs before it can work.
     *
     * The answers come from [ToolRegistry], which is the single place that
     * declares a tool's requirements. PermissionManager only knows how to CHECK
     * and EXPLAIN them - it never decides policy on its own.
     */
    fun requiredFor(action: ActionType): List<String> = ToolRegistry.permissionsFor(action)

    fun missingFor(action: ActionType): List<String> = requiredFor(action).filterNot { has(it) }

    /**
     * Plain-language reason shown to the user BEFORE the system permission
     * dialog. Saying exactly why - and where the data goes - is what makes the
     * prompt defensible rather than alarming.
     */
    fun explain(permission: String): String = when (permission) {
        Manifest.permission.READ_CONTACTS ->
            "MAX needs Contacts access to find people by name, like \"Call Dad\". Contacts stay on your phone and are never uploaded."
        Manifest.permission.RECORD_AUDIO ->
            "MAX needs the microphone only while you tap the mic, or while \"Hey MAX\" listening is switched on in Settings."
        Manifest.permission.CALL_PHONE ->
            "MAX needs Phone permission to place a call after you confirm it. Without it, MAX opens the dialer instead."
        Manifest.permission.POST_NOTIFICATIONS ->
            "MAX needs this to show reminders and timers."
        Manifest.permission.FOREGROUND_SERVICE_MICROPHONE ->
            "Android requires this permission for \"Hey MAX\" to keep listening in the background."
        else -> "MAX needs this permission for the feature you just used."
    }
}
