package com.max.assistant.tools

import com.max.assistant.assistant.ActionType

/**
 * The DEVICE-SIDE tool registry.
 *
 * WHY a registry on the phone too:
 *  - Every tool declares where it runs, which Android permission it needs, and
 *    whether MAX should speak the result aloud. That metadata drives the UI, the
 *    permission prompt and the permission dialog order - none of which can be
 *    inferred from an Intent.
 *  - Adding a capability = adding one entry plus its executor branch. Nothing
 *    else changes.
 *
 * SECURITY: this is still a closed whitelist. `AndroidActionExecutor` dispatches
 * on the `ActionType` enum, so an unknown tool name cannot even be expressed,
 * and no tool ever runs a model-supplied string as code or an arbitrary URI.
 */
data class ToolSpec(
    val type: ActionType,
    /** Short group name shown in Settings. */
    val category: String,
    /**
     * Runtime permissions required BEFORE this tool can work. Empty means the
     * tool needs none - which is the common case, and the reason MAX does not
     * ask for anything at startup.
     */
    val permissions: List<String> = emptyList(),
    /**
     * Whether Android genuinely limits this, so MAX should say so out loud
     * instead of quietly opening a settings screen. See ARCHITECTURE.md.
     */
    val androidLimit: String? = null
)

/**
 * The single source of truth for "what can this phone do, and what does each
 * thing need?".
 */
object ToolRegistry {

    private const val READ_CONTACTS = "android.permission.READ_CONTACTS"

    /** Built once. A `Map` lookup per tool run is cheaper than rebuilding a list. */
    val byType: Map<ActionType, ToolSpec> = listOf(
        // --- Launch and navigation -------------------------------------
        ToolSpec(ActionType.OPEN_APP, "Apps"),
        ToolSpec(ActionType.OPEN_BROWSER, "Web"),
        ToolSpec(ActionType.WEB_SEARCH, "Web"),
        ToolSpec(ActionType.OPEN_MAPS, "Maps"),
        ToolSpec(ActionType.NAVIGATE, "Maps"),
        ToolSpec(ActionType.OPEN_CAMERA, "Device"),
        ToolSpec(ActionType.OPEN_SETTINGS, "Device"),
        // --- People -----------------------------------------------------
        ToolSpec(ActionType.CALL_CONTACT, "People", listOf(READ_CONTACTS)),
        ToolSpec(ActionType.OPEN_DIALER, "People"),
        ToolSpec(ActionType.SEND_SMS, "People", listOf(READ_CONTACTS)),
        ToolSpec(ActionType.OPEN_WHATSAPP, "People", listOf(READ_CONTACTS)),
        ToolSpec(ActionType.SHARE_TEXT, "People"),
        // --- Time -------------------------------------------------------
        ToolSpec(ActionType.SET_ALARM, "Time"),
        ToolSpec(ActionType.SET_TIMER, "Time"),
        ToolSpec(
            ActionType.CREATE_REMINDER, "Time",
            androidLimit = "Reminders use MAX's own alarm, which fires even with the screen off."
        ),
        // --- Device controls --------------------------------------------
        ToolSpec(ActionType.ADJUST_VOLUME, "Device"),
        ToolSpec(ActionType.TOGGLE_FLASHLIGHT, "Device"),
        ToolSpec(
            ActionType.SHOW_NOTIFICATIONS, "Device",
            androidLimit = "Reading notifications needs notification access, which you grant in Settings."
        ),
        ToolSpec(
            ActionType.SET_BLUETOOTH, "Device",
            androidLimit = "Android does not let a normal app switch Bluetooth on or off. MAX opens Bluetooth settings for you."
        ),
        ToolSpec(ActionType.OPEN_WEATHER, "Device"),
        ToolSpec(
            ActionType.READ_CALENDAR, "Device",
            androidLimit = "Android does not let a normal app read calendar events, so MAX opens your calendar app."
        ),
        // --- Personal data kept on the phone ----------------------------
        ToolSpec(ActionType.CREATE_NOTE, "Notes"),
        ToolSpec(ActionType.LIST_NOTES, "Notes"),
        ToolSpec(ActionType.DELETE_NOTE, "Notes"),
        ToolSpec(ActionType.CREATE_TASK, "Tasks"),
        ToolSpec(ActionType.LIST_TASKS, "Tasks"),
        ToolSpec(ActionType.COMPLETE_TASK, "Tasks"),
        // --- Destructive -------------------------------------------------
        ToolSpec(
            ActionType.CLEAR_CONVERSATIONS, "Data",
            androidLimit = "This deletes every conversation on the MAX server. It cannot be undone."
        ),
        ToolSpec(
            ActionType.CLEAR_MEMORY, "Data",
            androidLimit = "This deletes everything MAX has been asked to remember. It cannot be undone."
        ),
        // --- Runs in the backend, not here --------------------------------
        ToolSpec(ActionType.SEARCH_CONVERSATIONS, "Data")
    ).associateBy { it.type }

    /** Every permission the tool needs before it can run. */
    fun permissionsFor(type: ActionType): List<String> = byType[type]?.permissions.orEmpty()

    /** The documented Android restriction for this tool, if it has one. */
    fun limitFor(type: ActionType): String? = byType[type]?.androidLimit

    /**
     * Guards against drift between this file and the backend registry: every
     * enum value must have a spec, or MAX would silently do nothing for it.
     */
    fun isComplete(): Boolean = ActionType.values().all { byType.containsKey(it) }
}
