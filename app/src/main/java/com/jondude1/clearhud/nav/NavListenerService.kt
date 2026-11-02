package com.jondude1.clearhud.nav

import android.app.Notification
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.content.ContextCompat
import com.jondude1.clearhud.Alert
import com.jondude1.clearhud.DriveService
import com.jondude1.clearhud.HudBus
import com.jondude1.clearhud.Prefs

/**
 * Reads Google Maps' ongoing navigation notification for turn-by-turn data,
 * and picks up calls/messages from other apps for the alert banner.
 */
class NavListenerService : NotificationListenerService() {

    companion object {
        const val MAPS = "com.google.android.apps.maps"
        private val MESSAGING = setOf(
            "com.google.android.apps.messaging", "com.samsung.android.messaging",
            "com.whatsapp", "org.telegram.messenger", "com.facebook.orca",
            "com.discord", "com.google.android.dialer", "com.samsung.android.dialer",
            "com.android.server.telecom", "com.google.android.apps.googlevoice", "org.thoughtcrime.securesms",
        )
    }

    override fun onListenerConnected() {
        HudBus.log("Notification access active")
        activeNotifications?.forEach { onNotificationPosted(it) }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val n = sbn.notification ?: return
        if (sbn.packageName == MAPS) {
            if (!n.isOngoingNav()) return
            val e = n.extras
            val nav = MapsParser.parse(
                e.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
                e.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
                e.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString(),
                e.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(),
            ) ?: return
            val first = HudBus.nav.value == null
            HudBus.nav.value = nav
            if (first) {
                HudBus.log("Maps navigation started")
                // Try to bring the drive service up automatically; Android may refuse from the background.
                runCatching {
                    ContextCompat.startForegroundService(this, Intent(this, DriveService::class.java))
                }
            }
            return
        }
        handleAlert(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.packageName == MAPS && sbn.notification?.isOngoingNav() == true) {
            HudBus.nav.value = null
            HudBus.log("Maps navigation ended")
        }
    }

    private fun Notification.isOngoingNav(): Boolean =
        (flags and Notification.FLAG_ONGOING_EVENT) != 0 &&
            (category == null || category == Notification.CATEGORY_NAVIGATION || category == Notification.CATEGORY_SERVICE ||
                category == Notification.CATEGORY_PROGRESS || category == "navigation")

    private fun handleAlert(sbn: StatusBarNotification) {
        if (!Prefs.forwardAlerts(this) || !HudBus.driveRunning.value) return
        val n = sbn.notification
        val isCall = n.category == Notification.CATEGORY_CALL
        val isMsg = n.category == Notification.CATEGORY_MESSAGE || sbn.packageName in MESSAGING
        if (!isCall && !isMsg) return
        if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return
        val title = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: return
        val text = n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val app = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
        }.getOrDefault(sbn.packageName)
        HudBus.alert.value = Alert(if (isCall) "Call" else app, title, if (isCall) "Incoming call" else text)
    }
}
