package com.max.assistant.actions

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import com.max.assistant.permissions.PermissionManager

// Looks up contacts and starts calls. The UI always asks the user to confirm BEFORE this runs.
class CallAction(private val context: Context, private val permissions: PermissionManager) {

    /** Finds a phone number by contact name. Returns null when not found. */
    fun findNumber(name: String): String? {
        if (!permissions.has(Manifest.permission.READ_CONTACTS)) return null
        val cursor = context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
            arrayOf("%$name%"),
            null
        ) ?: return null
        cursor.use {
            var fallback: String? = null
            while (it.moveToNext()) {
                val display = it.getString(0) ?: continue
                val number = it.getString(1) ?: continue
                if (display.equals(name, ignoreCase = true)) return number // exact match wins
                if (fallback == null) fallback = number
            }
            return fallback
        }
    }

    fun callContact(name: String): ActionResult {
        if (!permissions.has(Manifest.permission.READ_CONTACTS)) {
            return ActionResult.NeedsPermission(listOf(Manifest.permission.READ_CONTACTS), "I need Contacts permission to find $name.")
        }
        val number = findNumber(name) ?: return ActionResult.Failed("I couldn't find $name in your contacts.")
        return dial(number, name)
    }

    fun openDialer(number: String?): ActionResult = dial(number, null, forceDialer = true)

    private fun dial(number: String?, name: String?, forceDialer: Boolean = false): ActionResult {
        val uri = Uri.parse("tel:${number.orEmpty()}")
        // Real call only when CALL_PHONE is granted; otherwise open the dialer (user presses call).
        val canCall = !forceDialer && number != null && permissions.has(Manifest.permission.CALL_PHONE)
        val intent = Intent(if (canCall) Intent.ACTION_CALL else Intent.ACTION_DIAL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            when {
                canCall -> ActionResult.Completed("Calling ${name ?: number}.")
                else -> ActionResult.Prepared("Opened the dialer${name?.let { " for $it" } ?: ""}. Press call to connect.")
            }
        } catch (e: Exception) {
            ActionResult.Failed("I couldn't start the call.")
        }
    }
}
