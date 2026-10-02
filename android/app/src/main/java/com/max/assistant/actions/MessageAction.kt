package com.max.assistant.actions

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.max.assistant.permissions.PermissionManager

// SMS and WhatsApp.
// IMPORTANT: Android/WhatsApp do not allow apps to silently send messages through
// public APIs (and Google Play restricts SMS permissions). So MAX opens the composer
// with the text filled in and reports "Prepared", NEVER "Sent".
class MessageAction(
    private val context: Context,
    private val permissions: PermissionManager,
    private val calls: CallAction
) {
    fun composeSms(contactName: String, message: String): ActionResult {
        if (!permissions.has(Manifest.permission.READ_CONTACTS)) {
            return ActionResult.NeedsPermission(listOf(Manifest.permission.READ_CONTACTS), "I need Contacts permission to find $contactName.")
        }
        val number = calls.findNumber(contactName) ?: return ActionResult.Failed("I couldn't find $contactName in your contacts.")
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$number")).putExtra("sms_body", message).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return start(intent, "Opened your messages to $contactName with the text ready. It has NOT been sent; press send to deliver it.")
    }

    fun openWhatsApp(contactName: String?, message: String?): ActionResult {
        if (contactName == null) {
            val launch = context.packageManager.getLaunchIntentForPackage("com.whatsapp")
                ?: return ActionResult.Failed("WhatsApp doesn't seem to be installed.")
            return start(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), "Opened WhatsApp.", prepared = false)
        }
        if (!permissions.has(Manifest.permission.READ_CONTACTS)) {
            return ActionResult.NeedsPermission(listOf(Manifest.permission.READ_CONTACTS), "I need Contacts permission to find $contactName.")
        }
        val number = calls.findNumber(contactName)?.filter { it.isDigit() }
            ?: return ActionResult.Failed("I couldn't find $contactName in your contacts.")
        val url = "https://wa.me/$number" + (message?.let { "?text=${Uri.encode(it)}" } ?: "")
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage("com.whatsapp").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return start(intent, "Opened the WhatsApp chat with $contactName. The message has NOT been sent; press send to deliver it.")
    }

    private fun start(intent: Intent, okMessage: String, prepared: Boolean = true): ActionResult = try {
        context.startActivity(intent)
        if (prepared) ActionResult.Prepared(okMessage) else ActionResult.Completed(okMessage)
    } catch (e: Exception) {
        ActionResult.Failed("I couldn't open the messaging app.")
    }
}
