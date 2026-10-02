package com.max.assistant.settings

import android.content.Context
import android.content.SharedPreferences

/**
 * Everything the user can change about how MAX sounds and behaves.
 *
 * WHY plain SharedPreferences and not DataStore/Room:
 *  - This is a handful of scalars (a few booleans, two floats, a string). The
 *    async DataStore API would add a dependency and a coroutine on every read
 *    for no benefit at this size.
 *  - NOTHING here is a secret. The session token lives separately, encrypted,
 *    in `TokenStore`. Preferences are readable only by this app anyway
 *    (`android:allowBackup="false"` keeps them out of cloud backups).
 *
 * Reads are cheap: SharedPreferences is loaded once per process, and this object
 * holds the only reference, so there is no need to re-read on every access.
 */
class VoiceSettings(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("max_settings", Context.MODE_PRIVATE)

    /** Read aloud? The speaker toggle in the UI writes this. */
    var speechEnabled: Boolean
        get() = prefs.getBoolean(KEY_SPEECH_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_SPEECH_ENABLED, value).apply()

    /**
     * Speaking speed. Android's own range is 0.5x..2.0x; 1.0 is normal pace.
     * Clamped here so a corrupted value can never make MAX silent or absurd.
     */
    var speechRate: Float
        get() = prefs.getFloat(KEY_SPEECH_RATE, 1.0f).coerceIn(0.5f, 2.0f)
        set(value) = prefs.edit().putFloat(KEY_SPEECH_RATE, value.coerceIn(0.5f, 2.0f)).apply()

    /** Voice pitch, 0.5..2.0, default 1.0. */
    var pitch: Float
        get() = prefs.getFloat(KEY_PITCH, 1.0f).coerceIn(0.5f, 2.0f)
        set(value) = prefs.edit().putFloat(KEY_PITCH, value.coerceIn(0.5f, 2.0f)).apply()

    /**
     * Preferred TTS voice name, or null for "let MAX choose".
     * A stored name can stop existing (language pack removed), so
     * `SpeechOutput.applySettings` falls back to a default rather than failing.
     */
    var voiceId: String?
        get() = prefs.getString(KEY_VOICE_ID, null)
        set(value) = prefs.edit().apply { if (value == null) remove(KEY_VOICE_ID) else putString(KEY_VOICE_ID, value) }.apply()

    /**
     * "Hey MAX" wake-word listening, run by the foreground service.
     * Default OFF: always-available microphone access is a big ask, so the user
     * opts in. See ARCHITECTURE.md for what this can and cannot do.
     */
    var wakeWordEnabled: Boolean
        get() = prefs.getBoolean(KEY_WAKE_WORD, false)
        set(value) = prefs.edit().putBoolean(KEY_WAKE_WORD, value).apply()

    /** Speak only when the user used voice, rather than after every typed reply. */
    var speakRepliesToVoiceOnly: Boolean
        get() = prefs.getBoolean(KEY_VOICE_ONLY_REPLIES, true)
        set(value) = prefs.edit().putBoolean(KEY_VOICE_ONLY_REPLIES, value).apply()

    companion object {
        private const val KEY_SPEECH_ENABLED = "speech_enabled"
        private const val KEY_SPEECH_RATE = "speech_rate"
        private const val KEY_PITCH = "pitch"
        private const val KEY_VOICE_ID = "voice_id"
        private const val KEY_WAKE_WORD = "wake_word_enabled"
        private const val KEY_VOICE_ONLY_REPLIES = "speak_voice_replies_only"
    }
}
