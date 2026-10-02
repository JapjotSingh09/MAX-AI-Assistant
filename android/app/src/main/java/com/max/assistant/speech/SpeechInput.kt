package com.max.assistant.speech

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/** What MAX is doing with the microphone right now. Drives the UI animation. */
enum class VoiceState { IDLE, LISTENING, PROCESSING, SPEAKING, ERROR }

/** Everything the UI needs to render the listening experience. */
data class VoiceUiState(
    val state: VoiceState = VoiceState.IDLE,
    /** Best guess at what was heard, shown live while the user is still talking. */
    val partialText: String = "",
    /** Human-readable reason for the last failure, or null. */
    val error: String? = null,
    /**
     * Microphone loudness in 0f..1f, used to animate the orb in time with the
     * user's voice. -1f when the recogniser does not report a level.
     */
    val amplitude: Float = -1f,
    val supported: Boolean = true
)

/**
 * Speech-to-text, driven by Android's built-in `SpeechRecognizer`.
 *
 * PRIVACY: the microphone opens ONLY while MAX is in the LISTENING state, which
 * happens when the user taps the mic, or when the wake-word foreground service
 * runs its short detection windows. It is never left open continuously - see
 * ARCHITECTURE.md for why a third-party app cannot do better than that on
 * Android, and what the real battery cost is.
 *
 * OFFLINE: `EXTRA_PREFER_OFFLINE` is set, so Android uses the on-device model
 * when one is downloaded. It is a PREFERENCE, not a guarantee - the platform
 * may still go online, so every failure path is handled and reported.
 *
 * THREADING: `SpeechRecognizer` must be created, used and destroyed on the main
 * thread. Callers drive this from the main thread.
 */
class SpeechInput(private val context: Context) {

    private val _ui = MutableStateFlow(VoiceUiState())
    val ui: StateFlow<VoiceUiState> = _ui.asStateFlow()

    private var recognizer: SpeechRecognizer? = null
    /** Guards against a callback firing after the user already cancelled. */
    private var sessionActive = false

    private fun update(f: (VoiceUiState) -> VoiceUiState) {
        _ui.value = f(_ui.value)
    }

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun isSupported(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    /**
     * Starts one recognition session.
     *
     * @param onText called with the final transcript, on the main thread.
     * @param onError called with a message that is safe to show the user.
     */
    fun start(
        onText: (String) -> Unit,
        onError: (String) -> Unit,
        prompt: String? = null,
        requireNetwork: Boolean = false
    ) {
        if (!hasPermission()) {
            fail("Microphone permission is needed to use voice.", onError)
            return
        }
        if (!isSupported()) {
            fail("Voice input isn't available on this phone.", onError)
            return
        }
        stop()

        sessionActive = true
        update { it.copy(state = VoiceState.LISTENING, partialText = "", error = null, amplitude = -1f, supported = true) }

        val r = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                if (sessionActive) update { it.copy(state = VoiceState.LISTENING) }
            }

            override fun onBeginningOfSpeech() {
                if (sessionActive) update { it.copy(partialText = "") }
            }

            /**
             * Partial results arrive while the user is still speaking. Showing
             * them is what makes voice input feel responsive instead of frozen.
             */
            override fun onPartialResults(partialResults: Bundle?) {
                if (!sessionActive) return
                val text = partialResults.firstResult()
                if (!text.isNullOrBlank()) update { it.copy(partialText = text) }
            }

            override fun onRmsChanged(rmsdB: Float) {
                if (!sessionActive) return
                // SpeechRecognizer reports roughly -2..10 dB. Map that onto 0..1
                // so the orb can scale with it. Only redraw when it moved
                // meaningfully: this callback fires about 10x/second.
                val level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
                if (kotlin.math.abs(level - _ui.value.amplitude) > 0.05f) update { it.copy(amplitude = level) }
            }
            override fun onResults(results: Bundle?) {
                val text = results.firstResult()
                finish()
                if (text.isNullOrBlank()) {
                    fail("I didn't catch that. Please try again.", onError)
                } else {
                    update { it.copy(state = VoiceState.PROCESSING, partialText = text) }
                    onText(text)
                }
            }

            override fun onError(error: Int) {
                val wasActive = sessionActive
                val partial = _ui.value.partialText
                finish()
                // ERROR_NO_MATCH with a partial transcript means the recogniser
                // heard something but could not confirm it. Use what we have
                // rather than making the user repeat the whole sentence.
                if (wasActive && error == SpeechRecognizer.ERROR_NO_MATCH && partial.isNotBlank()) {
                    update { it.copy(state = VoiceState.PROCESSING) }
                    onText(partial)
                    return
                }
                fail(messageFor(error), onError)
            }

            override fun onEndOfSpeech() {
                if (sessionActive) update { it.copy(state = VoiceState.PROCESSING) }
            }

            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })

        runCatching { r.startListening(buildIntent(prompt, requireNetwork)) }.onFailure {
            finish()
            fail("Voice input couldn't start on this phone.", onError)
        }
    }
    private fun buildIntent(prompt: String?, requireNetwork: Boolean): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            // Partial results are what make the listening state feel live.
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            // Prefer the on-device model: lower latency, works offline, and the
            // audio never leaves the phone when the model is present.
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, !requireNetwork)
            // Give the user time to finish a sentence instead of cutting them
            // off after a short pause.
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            prompt?.let { putExtra(RecognizerIntent.EXTRA_PROMPT, it) }
        }

    /** Cancels the current session. The microphone closes immediately. */
    fun stop() {
        sessionActive = false
        val r = recognizer
        recognizer = null
        runCatching { r?.cancel() }
        runCatching { r?.destroy() }
        // Speaking is a separate concern (SpeechOutput), so a listening stop
        // must not knock MAX out of a sentence it is saying.
        if (_ui.value.state != VoiceState.SPEAKING) update { it.copy(state = VoiceState.IDLE, amplitude = -1f) }
    }

    private fun finish() {
        sessionActive = false
        val r = recognizer
        recognizer = null
        runCatching { r?.destroy() }
    }

    private fun fail(message: String, onError: (String) -> Unit) {
        update { it.copy(state = VoiceState.ERROR, error = message, amplitude = -1f) }
        onError(message)
    }

    private fun Bundle?.firstResult(): String? =
        this?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull { it.isNotBlank() }

    /**
     * Maps the platform's integer error codes onto something a person can act
     * on. Raw codes mean nothing to a user.
     */
    private fun messageFor(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
            "MAX needs microphone access to hear you. Tap the mic to grant it."
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
            "Speech recognition needs the internet. Check your connection and try again."
        SpeechRecognizer.ERROR_SERVER -> "Google's speech service didn't answer. Please try again."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
            "I'm already listening. Give me a second and try again."
        SpeechRecognizer.ERROR_NO_MATCH -> "I didn't hear anything. Tap the mic and speak again."
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "I stopped listening. Tap the mic and speak again."
        SpeechRecognizer.ERROR_AUDIO -> "I couldn't reach the microphone. Close any app that might be using it."
        SpeechRecognizer.ERROR_CLIENT -> "Voice input is unavailable right now. Please try again."
        else -> "I couldn't hear that clearly. Please try again."
    }
}
