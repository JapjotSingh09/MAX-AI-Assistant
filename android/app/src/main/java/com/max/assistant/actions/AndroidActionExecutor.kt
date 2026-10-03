package com.max.assistant.actions

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.provider.CalendarContract
import android.provider.MediaStore
import android.provider.Settings
import com.max.assistant.AppContainer
import com.max.assistant.assistant.ActionType
import com.max.assistant.assistant.CommandIntent
import com.max.assistant.services.NotificationStore
import com.max.assistant.services.ReminderScheduler
import com.max.assistant.tools.LocalStore
import com.max.assistant.tools.ToolRegistry
import java.util.concurrent.atomic.AtomicInteger

// The ONLY place that touches Android to perform an action.
// It accepts a validated CommandIntent from the closed whitelist (a `when` over an enum,
// so unknown actions can't even be expressed) and returns the true result.
class AndroidActionExecutor(
    private val context: Context,
    private val container: AppContainer,
) {
    private val calls = CallAction(context, container.permissions)
    private val apps = AppLaunchAction(context)
    private val messages = MessageAction(context, container.permissions, calls)
    private val navigation = NavigationAction(context)
    private val alarms = AlarmAction(context)
    private val local: LocalStore = container.localStore

    /**
     * Device INFORMATION. Every branch here READS a value and answers with it.
     * Not one of them starts an Activity - that separation is the whole point
     * of splitting these out from OPEN_SETTINGS.
     */
    private val info = DeviceInfoAction(context)

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
            ActionType.WEB_SEARCH -> navigation.webSearch(intent.str("query").orEmpty())
            ActionType.SHARE_TEXT -> shareText(intent.str("text").orEmpty())
            ActionType.SET_ALARM -> alarms.setAlarm(intent.int("hour") ?: 0, intent.int("minute") ?: 0, intent.str("label"))
            ActionType.SET_TIMER -> alarms.setTimer(intent.int("seconds") ?: 60)
            ActionType.CREATE_REMINDER -> createReminder(intent.str("text").orEmpty(), intent.str("when"))
            ActionType.OPEN_CAMERA -> startActivity(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA), "Opened the camera.")
            ActionType.OPEN_SETTINGS -> startActivity(Intent(settingsAction(intent.str("section"))), "Opened settings.")
            // --- Device information: read a fact, never open a screen ---
            ActionType.READ_BATTERY_LEVEL -> info.readBatteryLevel()
            ActionType.BATTERY_ESTIMATE_QUERY -> info.readBatteryEstimate()
            ActionType.READ_WIFI_STATUS -> info.readWifiStatus()
            ActionType.READ_BLUETOOTH_STATUS -> info.readBluetoothStatus()
            ActionType.READ_DEVICE_INFO -> info.readDeviceInfo()
            ActionType.READ_STORAGE -> info.readStorage()
            ActionType.READ_DISPLAY_INFO -> info.readDisplayInfo()
            ActionType.READ_SOUND_INFO -> info.readSoundInfo()
            ActionType.SET_BATTERY_SAVER -> info.setBatterySaver(intent.str("state"))
            // Generic: one branch serves every app, because the app and the
            // operation are parameters, not cases.
            ActionType.APP_ACTION -> apps.act(
                intent.str("appName").orEmpty(),
                appOperation(intent.str("operation")),
                intent.str("entity")
            )
            ActionType.ADJUST_VOLUME -> adjustVolume(intent.str("direction"))
            ActionType.TOGGLE_FLASHLIGHT -> flashlight(intent.str("state"))
            ActionType.SET_BLUETOOTH -> setBluetooth()
            ActionType.OPEN_WEATHER -> openWeather(intent.str("location"))
            ActionType.READ_CALENDAR -> readCalendar()
            ActionType.SHOW_NOTIFICATIONS -> showNotifications()
            ActionType.CREATE_NOTE -> createNote(intent.str("title"), intent.str("content").orEmpty())
            ActionType.LIST_NOTES -> listNotes()
            ActionType.DELETE_NOTE -> deleteNote(intent.str("query").orEmpty())
            ActionType.CREATE_TASK -> createTask(intent.str("title").orEmpty(), intent.str("due"))
            ActionType.LIST_TASKS -> listTasks()
            ActionType.COMPLETE_TASK -> completeTask(intent.str("query").orEmpty())
            // These run in the backend, never on the phone. Reaching them here
            // would mean the two registries disagree, so say so rather than
            // pretending something happened.
            ActionType.CLEAR_CONVERSATIONS, ActionType.CLEAR_MEMORY, ActionType.SEARCH_CONVERSATIONS ->
                ActionResult.Failed("That one is handled by the MAX server. Please try it in the app.")
        }
    } catch (e: Exception) {
        // Never leak raw exceptions to the user.
        ActionResult.Failed()
    }


    /**
 * The wire name for an app operation. Validation already restricted this to the
 * enum, so the fallback is unreachable in practice; it exists so an unexpected
 * value degrades to "just open the app" rather than throwing.
 */
private fun appOperation(name: String?): AppOperation = when (name) {
    "SEARCH" -> AppOperation.SEARCH
    "PLAY" -> AppOperation.PLAY
    "PROFILE" -> AppOperation.PROFILE
    "CHAT" -> AppOperation.CHAT
    "NAVIGATE" -> AppOperation.NAVIGATE
    "COMPOSE" -> AppOperation.COMPOSE
    else -> AppOperation.OPEN
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
        // Storage has no single public constant; the app-list page is the only
        // place Android exposes it, so it is the honest target.
        "storage" -> Settings.ACTION_APPLICATION_SETTINGS
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

    // --- Honest handlers for tools Android restricts -----------------------

    /**
     * Android does NOT expose any public API for switching Bluetooth on or off.
     * `BluetoothAdapter.enable()` was deprecated in API 33 and throws for
     * non-privileged apps. So MAX opens Bluetooth settings and says exactly
     * that, instead of silently failing or pretending it worked.
     */
    private fun setBluetooth(): ActionResult {
        runCatching {
            context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { return ActionResult.Failed("This phone has no Bluetooth settings screen.") }
        return ActionResult.Prepared(
            "Android doesn't let apps switch Bluetooth directly, so I've opened Bluetooth settings for you."
        )
    }

    /**
     * Opens the weather. Without a location there is nothing specific to look
     * up, so the search page is used (the browser asks the system for a place).
     */
    private fun openWeather(location: String?): ActionResult {
        val query = if (location.isNullOrBlank()) "weather" else "weather ${Uri.encode(location)}"
        return try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=$query"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            ActionResult.Completed(if (location.isNullOrBlank()) "Opened the weather." else "Opened the weather for $location.")
        } catch (e: Exception) {
            ActionResult.Failed("No app on this phone can show the weather.")
        }
    }

    /**
     * Reading calendar events needs `READ_CALENDAR`, a permission Google Play
     * treats as sensitive and that most users cannot tell apart from reading
     * their messages. MAX does not ask for it: it opens the calendar, which is
     * what the user actually wanted.
     */
    private fun readCalendar(): ActionResult {
        return try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW).setData(CalendarContract.CONTENT_URI)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            ActionResult.Prepared("Android doesn't let apps read your calendar, so I've opened it for you.")
        } catch (e: Exception) {
            ActionResult.Failed("No calendar app is available on this phone.")
        }
    }

    /** Puts text on the Android share sheet so the user can send it anywhere. */
    private fun shareText(text: String): ActionResult {
        if (text.isBlank()) return ActionResult.Failed("There was nothing to share.")
        return try {
            context.startActivity(
                Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            ActionResult.Completed("Opened the share sheet.")
        } catch (e: Exception) {
            ActionResult.Failed("Nothing on this phone can share text.")
        }
    }

    // --- Notes and tasks, stored on this phone ------------------------------

    private fun createNote(title: String?, content: String): ActionResult {
        val note = local.addNote(title, content)
            ?: return ActionResult.Failed("That note was empty, so I didn't save it.")
        return ActionResult.Completed("Saved the note \"${note.title}\" on your phone.")
    }

    private fun listNotes(): ActionResult {
        val notes = local.notes().take(10)
        if (notes.isEmpty()) return ActionResult.Completed("You don't have any notes yet.")
        return ActionResult.Completed(notes.joinToString("\n") { "- ${it.title}" })
    }

    private fun deleteNote(query: String): ActionResult {
        val removed = local.deleteNote(query)
            ?: return ActionResult.Failed("I couldn't find a note matching \"$query\".")
        return ActionResult.Completed("Deleted the note \"${removed.title}\".")
    }

    private fun createTask(title: String, due: String?): ActionResult {
        val task = local.addTask(title, due) ?: return ActionResult.Failed("That task was empty, so I didn't save it.")
        val suffix = task.due?.let { " (due $it)" } ?: ""
        return ActionResult.Completed("Added the task \"${task.title}\"$suffix.")
    }

    private fun listTasks(): ActionResult {
        val tasks = local.tasks().take(10)
        if (tasks.isEmpty()) return ActionResult.Completed("You don't have any open tasks.")
        return ActionResult.Completed(
            tasks.joinToString("\n") { "- ${it.title}" + (it.due?.let { d -> " ($d)" } ?: "") }
        )
    }

    private fun completeTask(query: String): ActionResult {
        val done = local.completeTask(query)
            ?: return ActionResult.Failed("I couldn't find an open task matching \"$query\".")
        return ActionResult.Completed("Marked \"${done.title}\" as done.")
    }
    /**
     * Reminders fire through MAX's own alarm, so they arrive even with the
     * screen off. When the phrase is too vague to resolve ("remind me about
     * the thing"), MAX opens the calendar pre-filled instead of guessing a
     * date - a wrong reminder is worse than no reminder.
     */
    private fun createReminder(text: String, whenText: String?): ActionResult {
        val whenMillis = ReminderScheduler.resolveWhen(whenText)
        if (whenMillis != null) {
            val id = nextReminderId()
            return if (ReminderScheduler.schedule(context, id, text, whenMillis)) {
                ActionResult.Completed(
                    "Reminder set for \"$text\". It'll alert you even if your screen is off."
                )
            } else {
                ActionResult.Failed("I couldn't schedule that reminder on this phone.")
            }
        }
        // No usable time: fall back to the calendar, honestly labelled.
        val intent = Intent(Intent.ACTION_INSERT)
            .setData(CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, text)
            .apply { whenText?.let { putExtra(CalendarContract.Events.DESCRIPTION, "Requested time: $it") } }
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            ActionResult.Prepared(
                "I need a clearer time to set a reminder, so I've opened your calendar with it filled in."
            )
        } catch (e: Exception) {
            ActionResult.Failed("No calendar app is available to save a reminder.")
        }
    }

    /**
     * Monotonic reminder ids. Two reminders must never share a PendingIntent,
     * or the second silently replaces the first.
     */
    private val reminderIds = AtomicInteger(System.currentTimeMillis().toInt())

    private fun nextReminderId(): Int = reminderIds.incrementAndGet()

    companion object {
        // The torch has no public "is it on?" query, so we remember our own last change.
        @Volatile
        private var torchOn = false
    }
}
