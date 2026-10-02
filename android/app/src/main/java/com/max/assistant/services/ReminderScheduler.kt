package com.max.assistant.services

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Calendar

/**
 * Reminders that actually fire.
 *
 * WHY THIS EXISTS: Android has no public "reminders" API. The previous
 * behaviour - opening the calendar's new-event screen - depends on the user
 * tapping Save and does nothing at the time the reminder was for. This uses the
 * platform's own alarm machinery, so a reminder arrives as a real notification
 * even with the screen off and the app killed.
 *
 * WHY WE DELIBERATELY DO NOT REQUEST `SCHEDULE_EXACT_ALARM`:
 * it is a special-access permission the user must grant in Settings, and Google
 * Play restricts it to apps whose core purpose is alarms or calendars. A
 * reminder that is a few minutes late is a much better trade than a permission
 * prompt that gets denied by policy. `setAndAllowWhileIdle` therefore:
 *  - still fires in Doze, screen off, app killed;
 *  - needs NO permission at all;
 *  - may be batched by the OS, so it can arrive a few minutes late.
 * That last point is a real limitation and is documented in ARCHITECTURE.md.
 */
object ReminderScheduler {

    /** Request code base; each reminder gets its own id above this. */
    private const val BASE = 5_000

    /** Extra keys used to pass the reminder through the alarm Intent. */
    const val EXTRA_ID = "reminder_id"
    const val EXTRA_TEXT = "reminder_text"

    /**
     * Schedules a one-shot reminder.
     *
     * @param whenMillis absolute time in epoch milliseconds.
     * @return true when the alarm was accepted by the system.
     */
    fun schedule(context: Context, id: Int, text: String, whenMillis: Long): Boolean {
        val manager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return false
        // A reminder in the past is not an error: fire it almost immediately so
        // the user still sees it, instead of silently dropping the request.
        val target = whenMillis.coerceAtLeast(System.currentTimeMillis() + 1_000L)
        return runCatching {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, target, pendingIntent(context, id, text))
            true
        }.getOrDefault(false)
    }

    fun cancel(context: Context, id: Int) {
        val manager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        runCatching { manager.cancel(pendingIntent(context, id, "")) }
    }

    private fun pendingIntent(context: Context, id: Int, text: String): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            putExtra(EXTRA_ID, id)
            putExtra(EXTRA_TEXT, text)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            // Immutable is required from Android 12 (API 31).
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_IMMUTABLE else 0)
        // The request code makes each reminder a DISTINCT PendingIntent.
        // Without it, a second reminder would silently replace the first.
        return PendingIntent.getBroadcast(context, BASE + id, intent, flags)
    }
    /**
     * Turns the plain words a reminder was given ("tomorrow at 9 am") into a
     * real timestamp.
     *
     * DELIBERATELY CONSERVATIVE: it understands only the phrases MAX can parse
     * unambiguously. Anything else returns null, and MAX asks for a firmer
     * time rather than guessing and firing at the wrong moment.
     */
    fun resolveWhen(whenText: String?, now: Calendar = Calendar.getInstance()): Long? {
        val text = whenText?.lowercase()?.trim() ?: return null
        val target = (now.clone() as Calendar).apply {
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val time = Regex("\\b(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?\\b").find(text)

        // "in 30 minutes" / "in 2 hours" is relative, so it short-circuits.
        Regex("\\bin\\s+(\\d+)\\s*(minute|min|hour|hr)s?\\b").find(text)?.let { m ->
            val n = m.groupValues[1].toLongOrNull() ?: return null
            val millis = if (m.groupValues[2].startsWith("h")) n * 3_600_000L else n * 60_000L
            return System.currentTimeMillis() + millis
        }

        return when {
            text.contains("tomorrow") -> {
                target.add(Calendar.DAY_OF_YEAR, 1)
                applyTime(target, time) ?: return null
                target.timeInMillis
            }
            text.contains("tonight") -> {
                // "tonight" with no number means roughly now + 3 hours.
                if (time == null) target.add(Calendar.HOUR_OF_DAY, 3) else applyTime(target, time) ?: return null
                target.timeInMillis
            }
            text.contains("today") || time != null -> {
                applyTime(target, time) ?: return null
                // A bare "at 9 am" that already passed means tomorrow.
                if (target.timeInMillis <= System.currentTimeMillis()) target.add(Calendar.DAY_OF_YEAR, 1)
                target.timeInMillis
            }
            else -> null
        }
    }

    private fun applyTime(target: Calendar, match: MatchResult?): Boolean? {
        if (match == null) return null
        val hour = match.groupValues[1].toIntOrNull() ?: return null
        val minute = match.groupValues[2].toIntOrNull() ?: 0
        val meridiem = match.groupValues[3]
        var h = hour
        if (meridiem == "pm" && hour < 12) h += 12
        if (meridiem == "am" && hour == 12) h = 0
        // A 24-hour number with no am/pm ("at 19") is valid; "at 99" is not.
        if (h > 23 || minute > 59) return null
        target.set(Calendar.HOUR_OF_DAY, h)
        target.set(Calendar.MINUTE, minute)
        return true
    }
}

/**
 * Receives the alarm and shows the reminder.
 *
 * `RECEIVE_BOOT_COMPLETED` is NOT declared on purpose: after a reboot Android
 * drops every pending alarm, so a reminder would silently not fire. Rather than
 * ask for another permission, MAX re-schedules saved reminders the next time
 * the app starts, which covers the realistic "phone restarted" case. This is
 * documented in ARCHITECTURE.md as a known limitation.
 */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val text = intent.getStringExtra(ReminderScheduler.EXTRA_TEXT).orEmpty()
        val id = intent.getIntExtra(ReminderScheduler.EXTRA_ID, 0)
        AssistantNotifications.showReminder(
            context,
            "MAX reminder",
            text.ifBlank { "You asked MAX to remind you." },
            id
        )
    }
}
