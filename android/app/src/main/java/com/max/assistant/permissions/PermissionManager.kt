package com.max.assistant.permissions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.max.assistant.assistant.ActionType

// Permissions are requested only when a feature needs them (never all at startup).
class PermissionManager(private val context: Context) {

    fun has(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /** Runtime permissions an action needs before it can work. */
    fun requiredFor(action: ActionType): List<String> = when (action) {
        ActionType.CALL_CONTACT, ActionType.SEND_SMS -> listOf(Manifest.permission.READ_CONTACTS)
        ActionType.OPEN_WHATSAPP -> listOf(Manifest.permission.READ_CONTACTS)
        else -> emptyList()
    }

    fun missingFor(action: ActionType): List<String> = requiredFor(action).filterNot { has(it) }

    /** Plain-language reason shown to the user BEFORE the system permission dialog. */
    fun explain(permission: String): String = when (permission) {
        Manifest.permission.READ_CONTACTS -> "MAX needs Contacts access to find people by name, like \"Call Dad\". Contacts stay on your phone."
        Manifest.permission.RECORD_AUDIO -> "MAX needs the microphone only while you tap the mic button to speak a command."
        Manifest.permission.CALL_PHONE -> "MAX needs Phone permission to place a call after you confirm it."
        Manifest.permission.POST_NOTIFICATIONS -> "MAX needs this to show reminders and timers."
        else -> "MAX needs this permission for the feature you just used."
    }
}
