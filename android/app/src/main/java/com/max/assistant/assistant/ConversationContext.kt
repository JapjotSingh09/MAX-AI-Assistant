package com.max.assistant.assistant

/**
 * SHORT-LIVED CONVERSATIONAL CONTEXT.
 *
 * "Open YouTube" -> "Done." -> "Search for Android tutorials" is a normal
 * thing to say to an assistant, and the second sentence only makes sense
 * because of the first.
 *
 * Deliberately SMALL and SHORT-LIVED:
 *  - Only one fact is kept: the app last acted on. Anything richer starts
 *    inventing meaning the user never expressed.
 *  - It expires after [TTL_MS]. Conversation memory that never expires is how
 *    an assistant starts confidently doing the wrong thing hours later.
 *  - It never overrides an explicitly named app. Context only fills a GAP.
 *
 * This is the whole of "conversation context". It is not a transcript, and it
 * does not try to be.
 */
class ConversationContext {

    /** How long a target app stays relevant. Long enough for a follow-up. */
    private val ttlMs = 90_000L

    private var app: String? = null
    private var appAt: Long = 0L

    /** The app the user most recently acted on, if it is still fresh. */
    fun currentApp(nowMs: Long = System.currentTimeMillis()): String? {
        val name = app ?: return null
        return if (nowMs - appAt <= ttlMs) name else null.also { clear() }
    }

    /** Records an app MAX just opened, so a follow-up can refer to it. */
    fun setApp(name: String?, nowMs: Long = System.currentTimeMillis()) {
        app = name?.trim()?.takeIf { it.isNotEmpty() }
        appAt = nowMs
    }

    fun clear() {
        app = null
        appAt = 0L
    }

    companion object {
        /**
         * True when an app name in [text] is EXPLICIT, i.e. the user named it.
         *
         * When they did, their choice must win over the remembered one - "open
         * Spotify and search X" means Spotify, not whatever was open before.
         */
        fun namesAppExplicitly(text: String): Boolean = EXPLICIT_APP_REF.containsMatchIn(text)

        private val EXPLICIT_APP_REF = Regex(
            "\\b(?:on|in|inside|within|using|via)\\s+[\\p{L}][\\p{L}\\p{N} ._-]{1,24}$|" +
                "\\b(open|launch|switch to|go to)\\s+[\\p{L}][\\p{L}\\p{N} ._-]{1,24}"
                .toRegex(),
            RegexOption.IGNORE_CASE
        )
    }
}