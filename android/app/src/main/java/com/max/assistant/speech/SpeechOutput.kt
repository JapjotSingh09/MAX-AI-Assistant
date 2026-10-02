package com.max.assistant.speech

import android.content.Context
import com.max.assistant.settings.VoiceSettings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/** A voice MAX can speak with, as offered in Settings. */
data class VoiceOption(val id: String, val label: String, val locale: String, val networkRequired: Boolean)

/**
 * Text-to-speech output.
 *
 * DESIGN NOTE - why the engine is created up front and never blocks a reply:
 *  `TextToSpeech` boots asynchronously and can take over a second on a cold
 *  device. The previous implementation created it lazily and then silently
 *  DROPPED the first reply while it was still starting. Here the engine is
 *  created as soon as the app starts, and [speak] either speaks or reports
 *  honestly that it could not - it never pretends.
 */
class SpeechOutput(
    private val context: Context,
    private val settings: VoiceSettings
) {
    private val _state = MutableStateFlow(VoiceState.IDLE)
    val state: StateFlow<VoiceState> = _state.asStateFlow()

    /** Set once the engine reports success; guards every `speak` call. */
    private var tts: TextToSpeech? = null
    private var engineReady = false
    private var engineFailed = false

    /**
     * Creates the engine. Safe to call more than once.
     *
     * MUST be called on the main thread. Returns immediately; `engineReady`
     * flips later from the init callback.
     */
    fun init() {
        if (tts != null || engineFailed) return
        tts = TextToSpeech(context) { status ->
            engineReady = status == TextToSpeech.SUCCESS
            // A device with no TTS engine is a real, common configuration.
            // Remember it so we stop retrying and can say so plainly.
            if (!engineReady) engineFailed = true
            applySettings()
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) { _state.value = VoiceState.SPEAKING }
                override fun onDone(utteranceId: String?) { _state.value = VoiceState.IDLE }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) { _state.value = VoiceState.IDLE }
                override fun onError(utteranceId: String?, errorCode: Int) { _state.value = VoiceState.IDLE }
            })
        }
    }

    /** True when the device can speak at all. */
    fun isAvailable(): Boolean = engineReady && !engineFailed

    /** True when the user turned speech output off. */
    fun isEnabled(): Boolean = settings.speechEnabled && isAvailable()

    /**
     * Speaks [text] if speech is enabled.
     *
     * Long replies are truncated on purpose: reading a 2,000-character article
     * aloud is never what a voice assistant should do, and it would block the
     * next interaction.
     *
     * @return false when nothing was spoken, so the caller can react.
     */
    fun speak(text: String): Boolean {
        if (text.isBlank() || !isEnabled()) return false
        val engine = tts ?: return false
        return runCatching {
            engine.speak(truncate(text), TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
            true
        }.getOrDefault(false)
    }

    /** Stops speaking immediately. Safe when nothing is being spoken. */
    fun stop() {
        runCatching { tts?.stop() }
        if (_state.value == VoiceState.SPEAKING) _state.value = VoiceState.IDLE
    }
    /** Voices the user can pick from. Empty until the engine is ready. */
    fun availableVoices(): List<VoiceOption> {
        val engine = tts ?: return emptyList()
        val locale = Locale.getDefault()
        return runCatching {
            engine.voices.orEmpty().asSequence()
                // Offer voices that match the phone's language, nearest first.
                .sortedBy { v ->
                    when {
                        v.locale.language == locale.language && v.locale.country == locale.country -> 0
                        v.locale.language == locale.language -> 1
                        else -> 2
                    }
                }
                // On-device voices work with no signal, so they come first.
                .sortedBy { it.isNetworkConnectionRequired }
                .map {
                    VoiceOption(
                        id = it.name,
                        label = it.name,
                        locale = it.locale.toLanguageTag(),
                        networkRequired = it.isNetworkConnectionRequired
                    )
                }
                .toList()
        }.getOrDefault(emptyList())
    }

    /** Re-applies the user's speed / pitch / voice choice after a change. */
    fun applySettings() {
        val engine = tts ?: return
        if (!engineReady) return
        runCatching {
            engine.setSpeechRate(settings.speechRate)
            engine.setPitch(settings.pitch)
            val selected = settings.voiceId
            val chosen = if (!selected.isNullOrBlank()) {
                engine.voices?.firstOrNull { it.name == selected }
            } else {
                // Default: an on-device voice for the phone's language, so
                // replies work with no signal.
                engine.voices?.firstOrNull {
                    it.locale.language == Locale.getDefault().language && !it.isNetworkConnectionRequired
                }
            }
            if (chosen != null) engine.voice = chosen
        }
    }

    /** Releases the engine. Called when the app really goes away. */
    fun shutdown() {
        runCatching { tts?.stop() }
        runCatching { tts?.shutdown() }
        tts = null
        engineReady = false
        _state.value = VoiceState.IDLE
    }

    companion object {
        private const val UTTERANCE_ID = "max-reply"

        /**
         * Reading aloud stops here. Long enough for "I've set your alarm for
         * 7am and found three notes about the project", short enough to keep
         * the conversation moving.
         */
        const val MAX_SPOKEN_CHARS = 600

        /**
         * Trims to a clean sentence boundary when there is one, else a word.
         *
         * Speaking prefers a finished sentence over a raw character cut, so a
         * reply like "Your alarm is set for 7am. Here is a long explanation..."
         * stops after the first sentence instead of mid-word. The tiny floor
         * stops a stray "Hi." from truncating an otherwise useful answer.
         */
        fun truncate(text: String): String {
            val clean = text.replace(Regex("\\s+"), " ").trim()
            if (clean.length <= MAX_SPOKEN_CHARS) return clean
            val cut = clean.take(MAX_SPOKEN_CHARS)
            val lastStop = maxOf(cut.lastIndexOf('.'), cut.lastIndexOf('!'), cut.lastIndexOf('?'))
            if (lastStop >= 10) return cut.take(lastStop + 1)
            val lastSpace = cut.lastIndexOf(' ')
            return if (lastSpace > 0) cut.take(lastSpace) else cut
        }
    }
}
