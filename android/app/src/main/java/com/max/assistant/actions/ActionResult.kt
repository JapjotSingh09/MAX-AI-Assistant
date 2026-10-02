package com.max.assistant.actions

// The REAL outcome of an action. MAX only says "done" when Android actually accepted it.
sealed interface ActionResult {
    val message: String
    val status: String // value reported to the backend activity log

    /** Android accepted and performed the action (e.g. app launched, torch switched on). */
    data class Completed(override val message: String) : ActionResult { override val status = "completed" }

    /** Prepared for the user but NOT finished (e.g. SMS composer opened, nothing sent yet). */
    data class Prepared(override val message: String) : ActionResult { override val status = "prepared" }

    /** A runtime permission is missing. The UI asks for it, then the user can retry. */
    data class NeedsPermission(val permissions: List<String>, override val message: String) : ActionResult { override val status = "failed" }

    data class Unsupported(override val message: String) : ActionResult { override val status = "unsupported" }

    data class Failed(override val message: String = "I couldn't complete that action.") : ActionResult { override val status = "failed" }
}
