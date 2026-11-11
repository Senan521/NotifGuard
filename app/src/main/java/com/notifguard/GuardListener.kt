package com.notifguard

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** Gelen bildirimin başlık/metin/kanal bilgisine bakar; gereksizse siler.
 *  Kanalı gizleyen veya her şeyi tek kanala gömen uygulamalar için yedek katman. */
class GuardListener : NotificationListenerService() {

    private val skipCategories = setOf(
        Notification.CATEGORY_CALL, Notification.CATEGORY_ALARM, Notification.CATEGORY_MESSAGE,
        Notification.CATEGORY_NAVIGATION, Notification.CATEGORY_TRANSPORT, Notification.CATEGORY_PROGRESS,
        Notification.CATEGORY_REMINDER
    )

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!Rules.filter(this)) return
        val n = sbn.notification
        if (sbn.packageName == packageName || sbn.isOngoing) return
        if ((n.flags and Notification.FLAG_FOREGROUND_SERVICE) != 0) return
        if (n.category in skipCategories) return

        val e = n.extras
        val text = listOfNotNull(
            e.getCharSequence(Notification.EXTRA_TITLE),
            e.getCharSequence(Notification.EXTRA_TEXT),
            e.getCharSequence(Notification.EXTRA_BIG_TEXT),
            e.getCharSequence(Notification.EXTRA_SUB_TEXT),
            n.channelId
        ).joinToString(" ")

        if (Rules.isJunk(this, text)) {
            cancelNotification(sbn.key)
            Rules.inc(this)
        }
    }
}
