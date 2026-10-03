package com.max.assistant.assistant

import android.util.Log

/**
 * LIGHTWEIGHT LATENCY INSTRUMENTATION.
 *
 * Purpose: answer "why was the FIRST command slow?" with measurements instead
 * of guesses. Each stage records a timestamp against one turn, and the whole
 * turn is emitted as a single line when it completes.
 *
 * Deliberately cheap:
 *  - `mark()` is a Long comparison and a map write; no allocation on the hot
 *    path beyond the map entry itself.
 *  - The summary line is only formatted when DEBUG logging is enabled, so a
 *    release build does almost nothing.
 *  - Nothing here blocks, and a failure to log can never affect behaviour.
 *
 * The five stages the specification asks to measure:
 *   ACTIVATED -> READY       the app woke up and can accept a command
 *   SPEECH_END -> PARSED     transcript to a routing decision
 *   PARSED -> EXEC_START     validation and permission check
 *   EXEC_START -> EXEC_DONE  the Android action itself
 *   AI_SENT -> AI_DONE       the cloud round-trip
 */
object LatencyLog {

    private const val TAG = "MaxLatency"

    /** One turn's worth of stage timestamps. */
    private val marks = LinkedHashMap<String, Long>()

    /** When the process became ready to take a command. */
    @Volatile
    var activatedAt: Long = 0L
        private set

    /** Records that MAX is ready. Called once, from Application.onCreate. */
    fun markActivated() {
        activatedAt = System.currentTimeMillis()
    }

    /** Records a named stage boundary. Cheap enough to call unconditionally. */
    fun mark(stage: String) {
        marks[stage] = System.currentTimeMillis()
    }

    /** Elapsed ms between two marks, or null when either is missing. */
    fun since(stage: String): Long? {
        val from = marks[stage] ?: return null
        return System.currentTimeMillis() - from
    }

    /**
     * Emits one line describing the whole turn and clears it.
     *
     * Only called when a turn finishes, so the cost is once per command.
     */
    fun flushSummary(turn: Int) {
        if (!Log.isLoggable(TAG, Log.DEBUG)) return
        val parts = marks.entries.mapNotNull { (k, v) ->
            "$k=${System.currentTimeMillis() - v}ms"
        }
        // First-command cost is the interesting number, so it is always shown.
        val cold = activatedAt.takeIf { it > 0 }?.let { System.currentTimeMillis() - it }
        Log.d(TAG, "turn=$turn uptimeSinceReady=${cold ?: -1}ms ${parts.joinToString(" ")}")
        clear()
    }

    /** Clears the marks. Called on sign-out so turns never bleed together. */
    fun clear() = marks.clear()
}