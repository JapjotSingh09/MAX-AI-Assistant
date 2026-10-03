package com.max.assistant.assistant

// The CLOSED whitelist of things MAX may do. Anything not listed here is rejected.
// WHY: the AI only *suggest* an action; it can never run arbitrary code or shell commands.
//
// KEEP IN SYNC WITH THE BACKEND: every name here must match an entry in
// `src/lib/tools/registry.ts` (TOOL_NAMES). `ToolRegistryTest` asserts the two
// lists have the same length and that every name is described, so a tool added
// on one side without the other fails the build rather than misbehaving later.
enum class ActionType {
    // Launch and navigation
    OPEN_APP, OPEN_BROWSER, WEB_SEARCH, OPEN_MAPS, NAVIGATE, OPEN_CAMERA, OPEN_SETTINGS,
    // People (outward-facing)
    CALL_CONTACT, OPEN_DIALER, SEND_SMS, OPEN_WHATSAPP, SHARE_TEXT,
    // Time
    SET_ALARM, SET_TIMER, CREATE_REMINDER,
    // Device controls
    ADJUST_VOLUME, TOGGLE_FLASHLIGHT, SHOW_NOTIFICATIONS, SET_BLUETOOTH, OPEN_WEATHER, READ_CALENDAR,
    // DEVICE INFORMATION - read a fact. These NEVER open a settings screen.
    // The whole point of splitting these out is that a question about the
    // battery is answered from a sensor, while an instruction to open Battery
    // Settings is OPEN_SETTINGS. They used to be conflated.
    READ_BATTERY_LEVEL, BATTERY_ESTIMATE_QUERY, READ_WIFI_STATUS,
    READ_BLUETOOTH_STATUS, READ_DEVICE_INFO, READ_STORAGE,
    READ_DISPLAY_INFO, READ_SOUND_INFO,
    // Change a setting the OS actually permits us to change.
    SET_BATTERY_SAVER,
    /**
     * Do something INSIDE an app: search, play, open a profile, navigate.
     *
     * One action covering every app, because the app is a PARAMETER rather than
     * a branch. `AppLaunchAction` resolves the package with PackageManager and
     * `AppActionCatalog` supplies a public deep link. That is what keeps this
     * generic instead of one action per app.
     */
    APP_ACTION,
    // Personal data kept on the phone
    CREATE_NOTE, LIST_NOTES, DELETE_NOTE, CREATE_TASK, LIST_TASKS, COMPLETE_TASK,
    // Destructive - the registry makes these always ask for a tap
    CLEAR_CONVERSATIONS, CLEAR_MEMORY,
    // Runs in the backend, not on the phone
    SEARCH_CONVERSATIONS
}

/**
 * The intents that only READ. Grouped here so the executor, the UI and the
 * tests can all ask "is this an information query?" in one place instead of
 * each re-listing the names - which is how the two lists would drift.
 */
val INFORMATION_ACTIONS: Set<ActionType> = setOf(
    ActionType.READ_BATTERY_LEVEL, ActionType.BATTERY_ESTIMATE_QUERY,
    ActionType.READ_WIFI_STATUS, ActionType.READ_BLUETOOTH_STATUS,
    ActionType.READ_DEVICE_INFO, ActionType.READ_STORAGE,
    ActionType.READ_DISPLAY_INFO, ActionType.READ_SOUND_INFO
)

data class CommandIntent(
    val action: ActionType,
    val parameters: Map<String, Any?>,
    val requiresConfirmation: Boolean
) {
    fun str(key: String): String? = parameters[key] as? String
    fun int(key: String): Int? = (parameters[key] as? Number)?.toInt()

    // Human readable text for the confirmation card, e.g. "Call Dad".
    fun describe(): String = when (action) {
        ActionType.OPEN_APP -> "Open ${str("appName")}"
        ActionType.OPEN_BROWSER -> str("url")?.let { "Open $it" } ?: "Search the web for \"${str("query")}\""
        ActionType.WEB_SEARCH -> "Search the web for \"${str("query")}\""
        ActionType.OPEN_MAPS -> "Open maps" + (str("query")?.let { " for $it" } ?: "")
        ActionType.NAVIGATE -> "Navigate to ${str("destination")}"
        ActionType.OPEN_CAMERA -> "Open the camera"
        ActionType.OPEN_SETTINGS -> "Open settings" + (str("section")?.let { " ($it)" } ?: "")
        ActionType.CALL_CONTACT -> "Call ${str("contactName")}"
        ActionType.OPEN_DIALER -> str("number")?.let { "Open the dialer with $it" } ?: "Open the dialer"
        ActionType.SEND_SMS -> "Message ${str("contactName")}: \"${str("message")}\""
        ActionType.OPEN_WHATSAPP -> "Open WhatsApp" + (str("contactName")?.let { " for $it" } ?: "")
        ActionType.SHARE_TEXT -> "Share a text on the share sheet"
        ActionType.SET_ALARM -> "Set an alarm for %02d:%02d".format(int("hour") ?: 0, int("minute") ?: 0)
        ActionType.SET_TIMER -> "Start a timer for ${(int("seconds") ?: 0) / 60} min ${(int("seconds") ?: 0) % 60} s"
        ActionType.CREATE_REMINDER -> "Create a reminder: ${str("text")}" + (str("when")?.let { " ($it)" } ?: "")
        ActionType.ADJUST_VOLUME -> "Turn volume ${str("direction")}"
        ActionType.TOGGLE_FLASHLIGHT -> "Turn flashlight ${str("state")}"
        ActionType.SHOW_NOTIFICATIONS -> "Show your notifications"
        ActionType.SET_BLUETOOTH -> "Turn Bluetooth ${str("state")}"
        ActionType.OPEN_WEATHER -> "Open the weather" + (str("location")?.let { " for $it" } ?: "")
        ActionType.READ_CALENDAR -> "Open your calendar"
        ActionType.READ_BATTERY_LEVEL -> "Check your battery level"
        ActionType.BATTERY_ESTIMATE_QUERY -> "Check how long your battery will last"
        ActionType.READ_WIFI_STATUS -> "Check your Wi-Fi status"
        ActionType.READ_BLUETOOTH_STATUS -> "Check whether Bluetooth is on"
        ActionType.READ_DEVICE_INFO -> "Read your phone's model and Android version"
        ActionType.READ_STORAGE -> "Check how much storage is free"
        ActionType.READ_DISPLAY_INFO -> "Check your display and brightness"
        ActionType.READ_SOUND_INFO -> "Check your sound settings"
        ActionType.SET_BATTERY_SAVER -> "Turn battery saver ${str("state") ?: "toggle"}"
        ActionType.APP_ACTION -> when (str("operation")) {
            "SEARCH" -> "Search ${str("appName")} for \"${str("entity")}\""
            "PLAY" -> "Play \"${str("entity")}\" in ${str("appName")}"
            "PROFILE" -> "Open that ${str("appName")} profile"
            "CHAT" -> "Open a chat in ${str("appName")}"
            "NAVIGATE" -> "Start directions to ${str("entity")}"
            "COMPOSE" -> "Compose in ${str("appName")}"
            else -> "Open ${str("appName")}"
        }
        ActionType.CREATE_NOTE -> "Create a note: ${str("title") ?: str("content")}"
        ActionType.LIST_NOTES -> "Read back your notes"
        ActionType.DELETE_NOTE -> "Delete the note matching \"${str("query")}\""
        ActionType.CREATE_TASK -> "Create a task: ${str("title")}"
        ActionType.LIST_TASKS -> "Read back your tasks"
        ActionType.COMPLETE_TASK -> "Mark the task matching \"${str("query")}\" as done"
        ActionType.CLEAR_CONVERSATIONS -> "Delete ALL of your conversations"
        ActionType.CLEAR_MEMORY -> "Delete ALL of your memories"
        ActionType.SEARCH_CONVERSATIONS -> "Search your conversations for \"${str("query")}\""
    }
}

// Schema validation for every intent, whether it came from the local parser or from the AI.
object IntentValidator {
    /**
     * Tools where the user must ALWAYS be asked to confirm. This mirrors the
     * `confirm: "always"` entries in the backend registry: outward-facing
     * contact (calls, messages) and irreversible deletions. The AI can never
     * clear these, it can only add confirmations.
     */
    val alwaysConfirm = setOf(
        ActionType.CALL_CONTACT, ActionType.SEND_SMS, ActionType.DELETE_NOTE,
        ActionType.CLEAR_CONVERSATIONS, ActionType.CLEAR_MEMORY
    )

    private fun text(p: Map<String, Any?>, key: String, max: Int = 200): Boolean {
        val v = p[key] as? String ?: return false
        return v.isNotBlank() && v.length <= max
    }

    private fun optText(p: Map<String, Any?>, key: String, max: Int = 200): Boolean =
        !p.containsKey(key) || text(p, key, max)

    private fun intIn(p: Map<String, Any?>, key: String, range: IntRange): Boolean {
        val n = p[key] as? Number ?: return false
        return n.toInt() in range
    }

    private fun enumIn(p: Map<String, Any?>, key: String, allowed: Set<String>): Boolean =
        (p[key] as? String) in allowed

    /**
     * Like [enumIn], but the key is optional - an absent key is valid.
     *
     * Used by the read-only actions, which are meaningful with no parameters at
     * all but may be qualified with a `subject`. An unknown subject is still
     * rejected, so the parameter cannot be used to pass arbitrary text.
     */
    private fun enumInOrEmpty(p: Map<String, Any?>, key: String, allowed: Set<String>): Boolean =
        !p.containsKey(key) || (p[key] as? String) in allowed

    /** Returns a valid intent, or null if the action is unknown or the parameters are wrong. */
    fun validate(actionName: String?, p: Map<String, Any?>, aiWantsConfirm: Boolean = false): CommandIntent? {
        val action = ActionType.values().firstOrNull { it.name == actionName } ?: return null
        val ok = when (action) {
            ActionType.OPEN_APP -> text(p, "appName", 60)
            ActionType.OPEN_BROWSER -> {
                val url = p["url"] as? String
                (url != null && Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(url)) || text(p, "query", 200)
            }
            ActionType.WEB_SEARCH -> text(p, "query", 200)
            ActionType.OPEN_MAPS -> optText(p, "query", 120)
            ActionType.NAVIGATE -> text(p, "destination", 160)
            // Parameterless tools: nothing to validate beyond the enum name.
            ActionType.OPEN_CAMERA, ActionType.SHOW_NOTIFICATIONS, ActionType.READ_CALENDAR,
            ActionType.LIST_NOTES, ActionType.LIST_TASKS,
            ActionType.CLEAR_CONVERSATIONS, ActionType.CLEAR_MEMORY -> true
            ActionType.OPEN_SETTINGS -> optText(p, "section", 40)
            ActionType.CALL_CONTACT -> text(p, "contactName", 80)
            ActionType.OPEN_DIALER -> optText(p, "number", 30)
            ActionType.SEND_SMS -> text(p, "contactName", 80) && text(p, "message", 500)
            ActionType.OPEN_WHATSAPP -> optText(p, "contactName", 80) && optText(p, "message", 500)
            ActionType.SHARE_TEXT -> text(p, "text", 1000)
            ActionType.SET_ALARM -> intIn(p, "hour", 0..23) && intIn(p, "minute", 0..59) && optText(p, "label", 80)
            ActionType.SET_TIMER -> intIn(p, "seconds", 1..86400)
            ActionType.CREATE_REMINDER -> text(p, "text", 200) && optText(p, "when", 80)
            ActionType.ADJUST_VOLUME -> enumIn(p, "direction", setOf("up", "down", "mute"))
            ActionType.TOGGLE_FLASHLIGHT -> enumIn(p, "state", setOf("on", "off", "toggle"))
            ActionType.SET_BLUETOOTH -> enumIn(p, "state", setOf("on", "off", "toggle"))
            ActionType.OPEN_WEATHER -> optText(p, "location", 80)
            // Information actions are parameterless (or take a fixed "subject"),
            // because a read-only query cannot be steered anywhere. Rejecting
            // free text here is what stops one being used to smuggle anything.
            ActionType.READ_BATTERY_LEVEL, ActionType.BATTERY_ESTIMATE_QUERY,
            ActionType.READ_WIFI_STATUS, ActionType.READ_BLUETOOTH_STATUS,
            ActionType.READ_DEVICE_INFO, ActionType.READ_STORAGE,
            ActionType.READ_DISPLAY_INFO, ActionType.READ_SOUND_INFO ->
                enumInOrEmpty(p, "subject", setOf("battery", "wifi", "bluetooth", "storage", "device", "display", "sound"))
            ActionType.SET_BATTERY_SAVER -> enumIn(p, "state", setOf("on", "off", "toggle"))
            // The app name is a bounded free-text label (it must be, since apps
            // are not enumerable at validation time), but the operation is a
            // CLOSED enum and the entity is length-capped. That combination is
            // safe: nothing here can execute anything, it only names a package
            // to hand to PackageManager.
            ActionType.APP_ACTION -> {
                text(p, "appName", 60) &&
                    enumIn(p, "operation", setOf("SEARCH", "PLAY", "PROFILE", "CHAT", "NAVIGATE", "COMPOSE", "OPEN")) &&
                    optText(p, "entity", 200)
            }
            ActionType.CREATE_NOTE -> text(p, "content", 2000) && optText(p, "title", 80)
            ActionType.DELETE_NOTE -> text(p, "query", 80)
            ActionType.CREATE_TASK -> text(p, "title", 120) && optText(p, "due", 40)
            ActionType.COMPLETE_TASK -> text(p, "query", 80)
            ActionType.SEARCH_CONVERSATIONS -> text(p, "query", 120)
        }
        if (!ok) return null
        return CommandIntent(action, p, action in alwaysConfirm || aiWantsConfirm)
    }
}
