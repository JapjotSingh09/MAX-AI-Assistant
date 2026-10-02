package com.max.assistant.actions

import android.content.Context
import android.content.Intent
import android.provider.CalendarContract

// Android has no public "reminders" API. The closest supported behaviour is opening the
// calendar's new-event screen pre-filled, so the user saves it. We report "Prepared".
class ReminderAction(private val context: Context) {

    fun create(text: String, whenText: String?): ActionResult {
        val intent = Intent(Intent.ACTION_INSERT)
            .setData(CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, text)
            .apply { whenText?.let { putExtra(CalendarContract.Events.DESCRIPTION, "Requested time: $it") } }
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            ActionResult.Prepared("Opened your calendar with the reminder filled in. Save it to finish.")
        } catch (e: Exception) {
            ActionResult.Failed("No calendar app is available to save a reminder.")
        }
    }
}
