package com.max.assistant.services

import android.Manifest
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import com.max.assistant.MainActivity
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * "Hey MAX" - the closest thing a third-party Android app can honestly offer.
 *
 * ---------------------------------------------------------------------------
 * WHAT ANDROID ACTUALLY ALLOWS (full write-up in ARCHITECTURE.md)
 * ---------------------------------------------------------------------------
 * 1. Android has no public always-on, low-power hotword API for third-party
 *    apps. `SpeechRecognizer` is the only speech engine a normal app can reach,
 *    and it is built for short, push-to-talk sessions.
 * 2. To touch the microphone with the app in the background or the screen off,
 *    the app MUST run a foreground service declared with
 *    `foregroundServiceType="microphone"`, which on Android 14+ also needs
 *    `FOREGROUND_SERVICE_MICROPHONE`. That permission is "while in use", so the
 *    service can only be STARTED while the app is in the foreground.
 * 3. A continuously running recogniser would be accurate but would drain the
 *    battery and would very likely be rejected under Google Play's microphone
 *    foreground-service policy.
 *
 * So MAX ships the honest, workable version:
 *  - A user-enabled microphone foreground service with a permanent, silent
 *    notification and working "Ask MAX" / "Stop" actions.
 *  - A DUTY-CYCLED detection loop: a short listening window, then an idle gap.
 *    That is the battery/accuracy trade-off, stated plainly rather than claimed
 *    away as "always listening".
 *  - It stops the moment the setting is switched off, and right after the wake
 *    word is recognised, so the microphone is never held open needlessly.
 *
 * MAX does NOT claim to work while the device is off, or in states where
 * Android forbids background microphone access.
 */
class WakeWordService : Service() {

    companion object {
        const val ACTION_STOP = "com.max.assistant.action.STOP_WAKE_WORD"

        /** "Hey MAX", plus the transcriptions the recogniser actually produces. */
        private val WAKE_PHRASES = listOf("hey max", "hi max", "a max", "hey mac", "hey maxx")

        /**
         * How long one detection window runs: long enough for "Hey MAX" plus a
         * short command, short enough that the mic is not held open.
         */
        private const val LISTEN_WINDOW_MS = 6_000L

        /**
         * Idle gap between windows. This is the battery dial - shorter means
         * faster detection and more drain. The handler sleeps in between, so an
         * idle cycle costs almost nothing.
         */
        private const val IDLE_GAP_MS = 2_500L

        /** True while the service is running, so Settings can show it. */
        @Volatile
        var isRunning: Boolean = false
            private set

        /** Human-readable status shown in the notification. */
        @Volatile
        var status: String = "Idle"
            private set
    }

    private val handler = Handler(Looper.getMainLooper())
    private val running = AtomicBoolean(false)
    private var recognizer: SpeechRecognizer? = null

    /** True while a command captured by the wake word is being handled. */
    private var handlingCommand = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        AssistantNotifications.ensureChannels(this)
        isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopEverything()
            return START_NOT_STICKY
        }
        // `startForeground` MUST be called within a few seconds of the service
        // starting or the system throws ForegroundServiceDidNotStartInTime.
        // It also has to happen BEFORE any microphone use.
        startForeground(
            AssistantNotifications.ID_LISTENING,
            AssistantNotifications.buildListeningNotification(this, status)
        )
        if (!running.compareAndSet(false, true)) return START_STICKY
        scheduleNextWindow(0L)
        // START_STICKY: if Android kills us under memory pressure we restart.
        return START_STICKY
    }

    override fun onDestroy() {
        stopEverything()
        super.onDestroy()
    }
    // --- Duty cycle -------------------------------------------------------

    private fun scheduleNextWindow(delayMs: Long) {
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ listenWindow() }, delayMs)
    }

    /** Runs one short detection window. */
    private fun listenWindow() {
        if (!running.get()) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            // Permission was revoked while we ran. Stop rather than spin.
            status = "Microphone permission was removed"
            updateNotification()
            stopEverything()
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            status = "Speech recognition is not available here"
            updateNotification()
            stopEverything()
            return
        }

        status = "Listening for \"Hey MAX\""
        updateNotification()

        val r = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: android.os.Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: android.os.Bundle?) = Unit

            override fun onPartialResults(partialResults: android.os.Bundle?) {
                // Partials let us react to "hey ma..." before the recogniser
                // has finished the sentence.
                val text = partialResults.firstText() ?: return
                if (containsWakeWord(text)) reactTo(text)
            }

            override fun onResults(results: android.os.Bundle?) {
                val text = results.firstText()
                if (text != null && containsWakeWord(text)) reactTo(text)
                releaseRecognizer()
                if (running.get()) scheduleNextWindow(IDLE_GAP_MS)
            }

            override fun onError(error: Int) {
                // ERROR_RECOGNIZER_BUSY and ERROR_CLIENT are transient: back off
                // and try again rather than killing the service.
                releaseRecognizer()
                if (running.get()) scheduleNextWindow(IDLE_GAP_MS)
            }
        })

        runCatching {
            r.startListening(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 2)
                    // Always prefer the local model here: the loop is duty
                    // cycled, so a network round-trip would blow the window.
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
                }
            )
            // A recogniser that never calls back would leak the microphone.
            handler.postDelayed({
                if (recognizer === r) {
                    releaseRecognizer()
                    scheduleNextWindow(IDLE_GAP_MS)
                }
            }, LISTEN_WINDOW_MS)
        }.onFailure {
            releaseRecognizer()
            if (running.get()) scheduleNextWindow(IDLE_GAP_MS * 2)
        }
    }

    private fun android.os.Bundle?.firstText(): String? =
        this?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull { it.isNotBlank() }

    private fun containsWakeWord(text: String): Boolean {
        val t = text.lowercase(Locale.getDefault())
        return WAKE_PHRASES.any { t.contains(it) }
    }
    /**
     * The wake word was heard. Everything after it on the line is the command.
     * The listening window closes immediately, so the microphone is not held
     * open while MAX works.
     */
    private fun reactTo(spoken: String) {
        if (handlingCommand) return
        handlingCommand = true
        releaseRecognizer()
        status = "Heard you"
        updateNotification()
        // The loop pauses entirely while the command is handled: running the
        // microphone and MAX's own work at the same time is both wasteful and
        // likely to pick up MAX's own voice.
        stopSelfSafely()

        val lower = spoken.lowercase(Locale.getDefault())
        val command = lower.substringAfter("max", "").trim(' ', ',', '.')
            // A bare "hey max" with no command just opens the app.
            .ifBlank { spoken.trim() }

        dispatch(command)
        // Re-arm the loop after the command has had time to run.
        handler.postDelayed({
            handlingCommand = false
            if (running.get()) scheduleNextWindow(IDLE_GAP_MS)
        }, 4_000L)
    }

    /**
     * Handles a command captured by the wake word.
     *
     * MAX opens the app rather than performing the action itself. A background
     * service has nobody in front of the screen to tap "Confirm" on a call or
     * a message, so running one silently would defeat the confirmation model.
     * What the wake word buys is: hands-free open, then the normal, fully
     * visible flow.
     */
    private fun dispatch(command: String) {
        when (command.isBlank()) {
            true -> launch(null)
            // A local command still needs the screen, because confirmation is
            // the whole point for destructive or outward-facing tools.
            else -> launch(command)
        }
    }

    private fun launch(command: String?) {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(MainActivity.EXTRA_OPEN_ASSISTANT, true)
            .putExtra(MainActivity.EXTRA_PENDING_COMMAND, command ?: "")
        runCatching { startActivity(intent) }
    }

    // --- Lifecycle --------------------------------------------------------

    private fun releaseRecognizer() {
        val r = recognizer
        recognizer = null
        runCatching { r?.cancel() }
        runCatching { r?.destroy() }
    }

    private fun updateNotification() {
        runCatching {
            val manager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
            manager.notify(
                AssistantNotifications.ID_LISTENING,
                AssistantNotifications.buildListeningNotification(this, status)
            )
        }
    }

    /** Stops the microphone loop but keeps the service alive and visible. */
    private fun stopSelfSafely() {
        running.set(false)
        handler.removeCallbacksAndMessages(null)
        releaseRecognizer()
    }

    /** Full shutdown: loop, microphone, notification, service. */
    private fun stopEverything() {
        stopSelfSafely()
        // minSdk is 26 (Android 8.0), which is above N (24), so the legacy
        // stopForeground(true) overload is dead code and is not needed.
        stopForeground(STOP_FOREGROUND_REMOVE)
        isRunning = false
        stopSelf()
    }
}

