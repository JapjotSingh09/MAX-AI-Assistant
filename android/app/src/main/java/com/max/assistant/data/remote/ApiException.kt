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

    fun forStatus(code: Int, serverMessage: String?): String = when (code) {
        429 -> TOO_MANY
        401 -> serverMessage ?: SESSION_EXPIRED
        503 -> AI_DOWN
        in 400..499 -> serverMessage ?: GENERIC // the backend sends already-friendly text
        else -> GENERIC
    }
}
