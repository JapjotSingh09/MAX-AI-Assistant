package com.max.assistant.actions

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.provider.MediaStore
import android.provider.Settings
import com.max.assistant.assistant.ActionType
import com.max.assistant.assistant.CommandIntent
import com.max.assistant.permissions.PermissionManager
import com.max.assistant.services.NotificationStore

// The ONLY place that touches Android to perform an action.
// It accepts a validated CommandIntent from the closed whitelist (a `when` over an enum,
// so unknown actions can't even be expressed) and returns the true result.
class AndroidActionExecutor(private val context: Context, private val permissions: PermissionManager) {
    private val calls = CallAction(context, permissions)
    private val apps = AppLaunchAction(context)
    private val messages = MessageAction(context, permissions, calls)
    private val navigation = NavigationAction(context)
    private val alarms = AlarmAction(context)
    private val reminders = ReminderAction(context)

    fun execute(intent: CommandIntent): ActionResult = try {
        when (intent.action) {
            ActionType.OPEN_APP -> apps.open(intent.str("appName").orEmpty())
            ActionType.CALL_CONTACT -> calls.callContact(intent.str("contactName").orEmpty())
            ActionType.OPEN_DIALER -> calls.openDialer(intent.str("number"))
            ActionType.SEND_SMS -> messages.composeSms(intent.str("contactName").orEmpty(), intent.str("message").orEmpty())
            ActionType.OPEN_WHATSAPP -> messages.openWhatsApp(intent.str("contactName"), intent.str("message"))
            ActionType.OPEN_MAPS -> navigation.openMaps(intent.str("query"))
            ActionType.NAVIGATE -> navigation.navigate(intent.str("destination").orEmpty())
            ActionType.OPEN_BROWSER -> navigation.openBrowser(intent.str("url"), intent.str("query"))
            ActionType.SET_ALARM -> alarms.setAlarm(intent.int("hour") ?: 0, intent.int("minute") ?: 0, intent.str("label"))
            ActionType.SET_TIMER -> alarms.setTimer(intent.int("seconds") ?: 60)
            ActionType.CREATE_REMINDER -> reminders.create(intent.str("text").orEmpty(), intent.str("when"))
            ActionType.OPEN_CAMERA -> startActivity(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA), "Opened the camera.")
            ActionType.OPEN_SETTINGS -> startActivity(Intent(settingsAction(intent.str("section"))), "Opened settings.")
            ActionType.ADJUST_VOLUME -> adjustVolume(intent.str("direction"))
            ActionType.TOGGLE_FLASHLIGHT -> flashlight(intent.str("state"))
            ActionType.SHOW_NOTIFICATIONS -> showNotifications()
        }
    } catch (e: Exception) {
        // Never leak raw exceptions to the user.
        ActionResult.Failed()
    }

    private fun startActivity(intent: Intent, okMessage: String): ActionResult {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return ActionResult.Completed(okMessage)
    }

    private fun settingsAction(section: String?) = when (section) {
        "wifi" -> Settings.ACTION_WIFI_SETTINGS
        "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
        "sound" -> Settings.ACTION_SOUND_SETTINGS
        "display" -> Settings.ACTION_DISPLAY_SETTINGS
        "battery" -> Settings.ACTION_BATTERY_SAVER_SETTINGS
        "location" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
        else -> Settings.ACTION_SETTINGS
    }

    private fun adjustVolume(direction: String?): ActionResult {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val dir = when (direction) {
            "up" -> AudioManager.ADJUST_RAISE
            "down" -> AudioManager.ADJUST_LOWER
            else -> AudioManager.ADJUST_MUTE
        }
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI)
        return ActionResult.Completed("Volume adjusted.")
    }

    private fun flashlight(state: String?): ActionResult {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
            ?: return ActionResult.Unsupported("This phone doesn't have a flashlight.")
        val turnOn = when (state) { "on" -> true; "off" -> false; else -> !torchOn }
        cm.setTorchMode(id, turnOn) // needs no permission
        torchOn = turnOn
        return ActionResult.Completed(if (turnOn) "Flashlight on." else "Flashlight off.")
    }

    private fun showNotifications(): ActionResult {
        if (!NotificationStore.listenerConnected) {
            // Notification access can only be granted by the user in Android Settings.
            context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return ActionResult.Prepared("Turn on notification access for MAX in Settings, then ask me again.")
        }
        val recent = NotificationStore.recent(5)
        return if (recent.isEmpty()) ActionResult.Completed("You have no recent notifications.")
        else ActionResult.Completed(recent.joinToString("\n") { "${it.appName}: ${it.title}" })
    }

    companion object {
        // The torch has no public "is it on?" query, so we remember our own last change.
        @Volatile private var torchOn = false
    }
}
