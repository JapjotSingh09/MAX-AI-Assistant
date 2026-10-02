package com.max.assistant.actions

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock

// Alarms and timers are handed to the phone's Clock app via the official AlarmClock intents.
class AlarmAction(private val context: Context) {

    fun setAlarm(hour: Int, minute: Int, label: String?): ActionResult {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .apply { label?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) } }
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return run(intent, "Asked your Clock app to set an alarm for %02d:%02d.".format(hour, minute))
    }

    fun setTimer(seconds: Int): ActionResult {
        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return run(intent, "Asked your Clock app to start a timer.")
    }

    private fun run(intent: Intent, okMessage: String): ActionResult = try {
        context.startActivity(intent)
        ActionResult.Completed(okMessage)
    } catch (e: Exception) {
        ActionResult.Failed("I couldn't reach your Clock app.")
    }
}
