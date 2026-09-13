package com.mapsrelay.companion

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * Supplies the street name and ETA, and keeps the process alive for the
 * duration of a route.
 *
 * It no longer relays anything. The turn itself arrives over AIDL and is
 * handled by [Relay], which is reachable whether or not this service is
 * running — because this service is not always running. Android leaves a
 * NotificationListenerService *enabled but unbound* after the app is replaced,
 * and when the send path lived in here that meant a reinstall silently stopped
 * every turn reaching the watch while the status screen still read
 * "connected, receiving turns".
 *
 * So the split is now: AIDL for the things a route depends on, this for the two
 * cosmetic fields and the foreground notification.
 */
class NavListener : NotificationListenerService() {

    private var foreground = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
        WatchRelay.start(applicationContext)
        OsmAndLink.start(applicationContext)
    }

    override fun onDestroy() {
        stopRelayForeground()
        super.onDestroy()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Status.listenerBound = true
        Log.i(WatchRelay.TAG, "notification listener bound")
        try {
            activeNotifications?.forEach { onNotificationPosted(it) }
        } catch (e: Exception) {
            Log.w(WatchRelay.TAG, "could not scan active notifications", e)
        }
        OsmAndLink.bind()
    }

    override fun onListenerDisconnected() {
        Status.listenerBound = false
        Log.w(WatchRelay.TAG, "notification listener unbound")
        super.onListenerDisconnected()
    }

    /** Caches the cosmetic fields and keeps the process alive. Sending is
     *  driven by the AIDL callback, which is the only source that knows a turn
     *  actually changed. */
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (!OsmAndNotificationParser.isNavigation(sbn)) return
        Status.osmandNotifsSeen++
        // OsmAnd is demonstrably alive and navigating. If the AIDL link is not
        // up -- installed late, updated, force-stopped -- retry it here.
        OsmAndLink.bind()
        try {
            val info = OsmAndNotificationParser.parse(sbn!!) ?: return
            Relay.street = info.street
            Relay.eta = info.eta
            Relay.remaining = info.remaining
            Relay.arrived = info.arrived
            Status.navActive = true
            startRelayForeground()
        } catch (e: Exception) {
            // Cosmetic fields only — never allowed to disturb the relay.
            Status.lastError = "parse: ${e.message}"
            Log.w(WatchRelay.TAG, "parse failed", e)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (!OsmAndNotificationParser.isNavigation(sbn)) return
        // Navigation ended. The watch falls back to its stale display on its own.
        stopRelayForeground()
        Relay.reset()
        Status.navActive = false
    }

    // ------------------------------------------------------------ foreground

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val ch = NotificationChannel(
            CHANNEL_ID, "Navigation relay", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Shown while directions are being sent to the watch" }
        nm.createNotificationChannel(ch)
    }

    /**
     * Go foreground for the duration of a route. Without this the system is
     * free to kill the process between updates, and on a long drive it
     * eventually will — the relay would then stop silently, mid-route.
     */
    private fun startRelayForeground() {
        if (foreground) return
        val n: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Relaying directions")
            .setContentText("Sending OsmAnd guidance to your watch")
            .setSmallIcon(android.R.drawable.ic_menu_directions)
            .setOngoing(true)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIF_ID, n)
            }
            foreground = true
        } catch (e: Exception) {
            // Most likely the notification permission was denied on Android
            // 13+. Relaying still works; it is just more killable.
            Log.w(WatchRelay.TAG, "could not go foreground", e)
        }
    }

    private fun stopRelayForeground() {
        if (!foreground) return
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) {
            Log.w(WatchRelay.TAG, "stopForeground failed", e)
        }
        foreground = false
    }

    companion object {
        private const val CHANNEL_ID = "relay"
        private const val NOTIF_ID = 1
    }
}
