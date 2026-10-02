package com.max.assistant.services

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.max.assistant.MainActivity
import com.max.assistant.R

/**
 * Notification plumbing for MAX: channels, the persistent "listening" notice
 * and reminder alerts.
 *
 * ANDROID 13+ (API 33): `POST_NOTIFICATIONS` became a runtime permission. If
 * the user declines it, MAX keeps working and reminders still fire - they just
 * appear in the shade without MAX drawing them. That is the user's choice, not
 * a failure, so MAX never nags about it.
 */
object AssistantNotifications {

    const val CHANNEL_LISTENING = "max_listening"
    const val CHANNEL_REMINDERS = "max_reminders"

    /** Notification id for the persistent foreground-service notice. */
    const val ID_LISTENING = 1001

    private var created = false

    /** Creates the channels. Cheap and idempotent; safe to call repeatedly. */
    fun ensureChannels(context: Context) {
        if (created) return
        created = true
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

        // IMPORTANCE_LOW: the persistent listening notice must not make a sound.
        val listening = NotificationChannel(
            CHANNEL_LISTENING,
            "Always-listening notice",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shown while \"Hey MAX\" listening is switched on. Kept silent on purpose."
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }

        val reminders = NotificationChannel(
            CHANNEL_REMINDERS,
            "Reminders and timers",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Alerts when a reminder or timer you asked MAX for is due."
            enableVibration(true)
        }

        manager.createNotificationChannel(listening)
        manager.createNotificationChannel(reminders)
    }

    /**
     * The ongoing notification a microphone foreground service MUST show.
     *
     * Two actions, both of which really work:
     *  - "Ask MAX" opens the app straight into the assistant with the mic up.
     *  - "Stop" shuts the foreground service down, which is the user-visible
     *    guarantee that the microphone has genuinely stopped.
     */
    fun buildListeningNotification(context: Context, status: String): Notification {
        ensureChannels(context)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        val openIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(MainActivity.EXTRA_OPEN_ASSISTANT, true),
            flags
        )

        val stopIntent = PendingIntent.getService(
            context, 1,
            Intent(context, WakeWordService::class.java).setAction(WakeWordService.ACTION_STOP),
            flags
        )

        return NotificationCompat.Builder(context, CHANNEL_LISTENING)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("MAX is listening for \"Hey MAX\"")
            .setContentText(status)
            .setContentIntent(openIntent)
            .addAction(0, "Ask MAX", openIntent)
            .addAction(0, "Stop", stopIntent)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    /** A reminder/timer alert that opens straight into MAX. */
    fun showReminder(context: Context, title: String, body: String, id: Int) {
        ensureChannels(context)
        val open = PendingIntent.getActivity(
            context, id,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(MainActivity.EXTRA_OPEN_ASSISTANT, true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_REMINDERS)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()
        runCatching {
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(id, notification)
        }
    }

    /**
     * Can MAX post notifications? Always true below Android 13; above that the
     * user has to grant it. Callers use this to explain the situation rather
     * than to decide whether reminders work.
     */
    fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
}
