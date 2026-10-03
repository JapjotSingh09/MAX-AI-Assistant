package com.max.assistant.assistant.nlu

import com.max.assistant.assistant.ActionType

/**
 * WHAT the user wants, independent of how it was phrased.
 *
 * This is the layer that was missing. The old parser jumped straight from a
 * keyword to an [ActionType], so "battery" implied "open Battery Settings".
 * Here the category is decided FIRST, and an action is only chosen once the
 * category, the target and the sense (information vs. control) are known.
 */
enum class IntentCategory {
    /** Change something on the device: flashlight, volume, Bluetooth, saver. */
    DEVICE_ACTION,

    /** Launch an app by name. */
    APP_LAUNCH,

    /** Do something INSIDE an app: search, play, navigate, open a chat. */
    APP_ACTION,

    /** Read a fact from the device: battery level, Wi-Fi state, model. */
    DEVICE_INFO,

    /** A knowledge or conversation question for the AI. */
    AI_QUERY,

    /** Social/chat requests MAX has no tool for. */
    CONVERSATIONAL,

    /** Genuinely unclear. MAX asks rather than guesses. */
    AMBIGUOUS
}

/** How sure the router is. Below [LOW] the router refuses to act. */
enum class Confidence { HIGH, MEDIUM, LOW }

/**
 * One routing decision: the category, the action if there is one, the entities
 * that were pulled out of the sentence, and - crucially - how sure it is.
 *
 * A null [action] with [IntentCategory.AI_QUERY] means "hand this to the AI".
 * A null [action] with [IntentCategory.AMBIGUOUS] means "ask the user first".
 */
data class IntentFrame(
    val category: IntentCategory,
    val action: ActionType? = null,
    val parameters: Map<String, Any?> = emptyMap(),
    val confidence: Confidence = Confidence.LOW,
    /** Where this reading came from, for the latency/debug log. */
    val source: String = "router",
    /** Human-readable reasons, shown only when MAX has to ask. */
    val alternatives: List<String> = emptyList(),
    /** The question to put to the user when [category] is AMBIGUOUS. */
    val clarification: String? = null
) {
    /** True when this reading is good enough to execute without asking. */
    val isExecutable: Boolean
        get() = action != null && confidence != Confidence.LOW
}