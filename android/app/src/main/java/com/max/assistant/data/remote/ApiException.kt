package com.max.assistant.data.remote

// Carries a message that is SAFE to show to the user. Raw backend/network errors never reach the UI.
class ApiException(override val message: String, val code: Int = 0) : Exception(message)

object FriendlyErrors {
    const val OFFLINE = "You're offline."
    const val SERVER_UNREACHABLE = "Can't reach the MAX server. Check the backend URL and try again."
    const val TIMEOUT = "The request timed out. Please try again."
    const val TOO_MANY = "Too many requests. Please wait a moment and try again."
    const val SESSION_EXPIRED = "Your session has expired. Please sign in again."
    const val AI_DOWN = "MAX's AI service is temporarily unavailable."
    const val GENERIC = "Something went wrong. Please try again."
    // The server answered 2xx but without a session token. Rare (an out-of-date
    // app), and worth saying plainly instead of "something went wrong".
    const val NO_SESSION = "Sign-in did not complete. Please update MAX and try again."

    // The backend always sends already-friendly text (it never leaks internals).
    // Prefer it for BOTH 4xx and 5xx so a real cause – for example
    // "MAX's database isn't set up yet." – is shown instead of hiding it behind
    // the generic message. 429 keeps its standard wording; 503 falls back to the
    // AI wording only when the server sent no message.
    fun forStatus(code: Int, serverMessage: String?): String = when {
        code == 429 -> TOO_MANY
        code == 401 -> serverMessage ?: SESSION_EXPIRED
        !serverMessage.isNullOrBlank() -> serverMessage
        code == 503 -> AI_DOWN
        else -> GENERIC
    }
}
