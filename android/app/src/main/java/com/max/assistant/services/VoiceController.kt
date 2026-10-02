package com.max.assistant.services

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

enum class VoiceState { IDLE, LISTENING, PROCESSING, EXECUTING, SPEAKING, ERROR }

// Speech-to-text + text-to-speech.
// PRIVACY: the microphone opens ONLY when the user taps the mic and closes after one
// phrase. There is no background listening in v1. (A wake word can be added later by
// putting a replaceable WakeWordEngine in front of startListening.)
// Must be created and used on the main thread.
class VoiceController(private val context: Context) {
    private val _state = MutableStateFlow(VoiceState.IDLE)
    val state: StateFlow<VoiceState> = _state.asStateFlow()

    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    fun setState(s: VoiceState) { _state.value = s }

    fun isAvailable() = SpeechRecognizer.isRecognitionAvailable(context)

    fun startListening(onText: (String) -> Unit, onError: (String) -> Unit) {
        if (!isAvailable()) { _state.value = VoiceState.ERROR; onError("Voice input isn't available on this phone."); return }
        stop()
        val r = SpeechRecognizer.createSpeechRecognizer(context)
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { _state.value = VoiceState.LISTENING }
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (text.isNullOrBlank()) { _state.value = VoiceState.IDLE; onError("I didn't catch that.") }
                else { _state.value = VoiceState.PROCESSING; onText(text) }
                release()
            }
            override fun onError(error: Int) {
                _state.value = VoiceState.ERROR
                onError(if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) "Microphone permission is needed." else "I couldn't hear that. Please try again.")
                release()
            }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        recognizer = r
        r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        })
    }

    fun speak(text: String) {
        val engine = tts ?: TextToSpeech(context) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
        }.also { tts = it }
        if (!ttsReady) { _state.value = VoiceState.IDLE; return } // engine still starting: skip speaking
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) { _state.value = VoiceState.SPEAKING }
            override fun onDone(utteranceId: String?) { _state.value = VoiceState.IDLE }
            @Deprecated("Deprecated in Java") override fun onError(utteranceId: String?) { _state.value = VoiceState.IDLE }
        })
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "max-reply")
    }

    fun stop() {
        recognizer?.cancel()
        release()
        tts?.stop()
        _state.value = VoiceState.IDLE
    }

    private fun release() {
        recognizer?.destroy()
        recognizer = null
    }

    fun shutdown() {
        stop()
        tts?.shutdown()
        tts = null
    }
}
