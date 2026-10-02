package com.max.assistant.services

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.concurrent.CopyOnWriteArrayList

data class StoredNotification(val appName: String, val title: String, val category: String, val postedAt: Long)

// PRIVACY: notifications are kept ONLY in memory on this phone (capped at 100) and are
// never uploaded to the backend. Only a short title is kept, not the full text.
object NotificationStore {
    @Volatile var listenerConnected = false
    private val items = CopyOnWriteArrayList<StoredNotification>()

    fun add(n: StoredNotification) {
        items.add(0, n)
        while (items.size > 100) items.removeAt(items.size - 1)
    }

    fun recent(limit: Int): List<StoredNotification> = items.take(limit)

    /** Filters for the Notification Center tabs: All, Messages, Calls, Social, System. */
    fun byTab(tab: String): List<StoredNotification> = when (tab) {
        "Messages" -> items.filter { it.category == Notification.CATEGORY_MESSAGE }
        "Calls" -> items.filter { it.category == Notification.CATEGORY_CALL }
        "Social" -> items.filter { it.category == Notification.CATEGORY_SOCIAL }
        "System" -> items.filter { it.category == Notification.CATEGORY_SYSTEM || it.category == Notification.CATEGORY_STATUS }
        else -> items.toList()
    }
}

// Optional service. The user must enable "Notification access" for MAX in Android Settings.
class MaxNotificationListenerService : NotificationListenerService() {
    override fun onListenerConnected() { NotificationStore.listenerConnected = true }
    override fun onListenerDisconnected() { NotificationStore.listenerConnected = false }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().take(80)
        if (title.isBlank() || sbn.packageName == packageName) return
        val appName = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
        } catch (e: Exception) {
            sbn.packageName
        }
        NotificationStore.add(StoredNotification(appName, title, sbn.notification.category.orEmpty(), sbn.postTime))
    }
}
