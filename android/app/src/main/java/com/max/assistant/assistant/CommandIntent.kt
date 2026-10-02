package com.max.assistant.assistant

// The CLOSED whitelist of things MAX may do. Anything not listed here is rejected.
// WHY: the AI only *suggests* an action; it can never run arbitrary code or shell commands.
enum class ActionType {
    OPEN_APP, CALL_CONTACT, OPEN_DIALER, SEND_SMS, OPEN_WHATSAPP, OPEN_MAPS, NAVIGATE,
    SET_ALARM, SET_TIMER, CREATE_REMINDER, OPEN_CAMERA, OPEN_BROWSER, ADJUST_VOLUME,
    TOGGLE_FLASHLIGHT, OPEN_SETTINGS, SHOW_NOTIFICATIONS
}

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
        ActionType.CALL_CONTACT -> "Call ${str("contactName")}"
        ActionType.OPEN_DIALER -> "Open the dialer"
        ActionType.SEND_SMS -> "Message ${str("contactName")}: \"${str("message")}\""
        ActionType.OPEN_WHATSAPP -> "Open WhatsApp" + (str("contactName")?.let { " for $it" } ?: "")
        ActionType.OPEN_MAPS -> "Open maps" + (str("query")?.let { " for $it" } ?: "")
        ActionType.NAVIGATE -> "Navigate to ${str("destination")}"
        ActionType.SET_ALARM -> "Set an alarm for %02d:%02d".format(int("hour") ?: 0, int("minute") ?: 0)
        ActionType.SET_TIMER -> "Start a timer for ${(int("seconds") ?: 0) / 60} min ${(int("seconds") ?: 0) % 60} s"
        ActionType.CREATE_REMINDER -> "Create a reminder: ${str("text")}"
        ActionType.OPEN_CAMERA -> "Open the camera"
        ActionType.OPEN_BROWSER -> str("url")?.let { "Open $it" } ?: "Search the web for \"${str("query")}\""
        ActionType.ADJUST_VOLUME -> "Turn volume ${str("direction")}"
        ActionType.TOGGLE_FLASHLIGHT -> "Turn flashlight ${str("state")}"
        ActionType.OPEN_SETTINGS -> "Open settings"
        ActionType.SHOW_NOTIFICATIONS -> "Show your notifications"
    }
}

// Schema validation for every intent, whether it came from the local parser or from the AI.
object IntentValidator {
    // Calls and messages ALWAYS ask the user first, whatever the AI claims.
    private val alwaysConfirm = setOf(ActionType.CALL_CONTACT, ActionType.SEND_SMS)

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

    /** Returns a valid intent, or null if the action is unknown or the parameters are wrong. */
    fun validate(actionName: String?, p: Map<String, Any?>, aiWantsConfirm: Boolean = false): CommandIntent? {
        val action = ActionType.values().firstOrNull { it.name == actionName } ?: return null
        val ok = when (action) {
            ActionType.OPEN_APP -> text(p, "appName", 60)
            ActionType.CALL_CONTACT -> text(p, "contactName", 80)
            ActionType.OPEN_DIALER -> optText(p, "number", 30)
            ActionType.SEND_SMS -> text(p, "contactName", 80) && text(p, "message", 500)
            ActionType.OPEN_WHATSAPP -> optText(p, "contactName", 80) && optText(p, "message", 500)
            ActionType.OPEN_MAPS -> optText(p, "query", 120)
            ActionType.NAVIGATE -> text(p, "destination", 160)
            ActionType.SET_ALARM -> intIn(p, "hour", 0..23) && intIn(p, "minute", 0..59)
            ActionType.SET_TIMER -> intIn(p, "seconds", 1..86400)
            ActionType.CREATE_REMINDER -> text(p, "text", 200) && optText(p, "when", 80)
            ActionType.OPEN_CAMERA, ActionType.SHOW_NOTIFICATIONS -> true
            ActionType.OPEN_BROWSER -> {
                val url = p["url"] as? String
                (url != null && Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(url)) || text(p, "query", 200)
            }
            ActionType.ADJUST_VOLUME -> (p["direction"] as? String) in setOf("up", "down", "mute")
            ActionType.TOGGLE_FLASHLIGHT -> (p["state"] as? String) in setOf("on", "off", "toggle")
            ActionType.OPEN_SETTINGS -> optText(p, "section", 40)
        }
        if (!ok) return null
        return CommandIntent(action, p, action in alwaysConfirm || aiWantsConfirm)
    }
}
