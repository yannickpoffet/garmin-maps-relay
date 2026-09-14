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
 * Keeps the process alive for the duration of a route, and nothing else.
 *
 * It reads no content out of OsmAnd's notification any more — only whether one
 * is present, which is how a route's start and end are noticed. Street name,
 * distance left and arrival time all now come typed from `getAppInfo()`, so
 * the last regex over text written for human eyes is gone.
 *
 * It relays nothing either: turns arrive over AIDL and are handled by [Relay],
 * which is reachable whether or not this service is running. Android leaves a
 * NotificationListenerService *enabled but unbound* after the app is replaced,
 * and while the send path lived in here a reinstall silently stopped every
 * turn reaching the watch.
 */
class NavListener : NotificationListenerService() {

    private var foreground = false

    /** So the watch app is opened once when a route begins, not on every
     *  notification update. */
    private var routeRunning = false

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
        if (!isOsmAndNavigation(sbn)) return
        Status.osmandNotifsSeen++
        // OsmAnd is demonstrably alive and navigating. If the AIDL link is not
        // up -- installed late, updated, force-stopped -- retry it here.
        OsmAndLink.bind()
        Status.navActive = true
        startRelayForeground()

        // A route just started. Open the watch app now, once: it has to be
        // running for Garmin to deliver anything to it, and this is the one
        // moment when doing it unprompted is obviously right. v0.12 called
        // this on every failed send instead, which on this watch means a
        // prompt on the wrist, over and over, mid-drive.
        if (!routeRunning) {
            routeRunning = true
            WatchRelay.openOnWatch(force = true)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (!isOsmAndNavigation(sbn)) return
        // Navigation ended. The watch falls back to its stale display on its own.
        stopRelayForeground()
        Relay.reset()
        routeRunning = false
        Status.navActive = false
    }

    /** OsmAnd's ongoing navigation notification — used as a route-is-running
     *  signal, never for its contents. Free, paid and nightly builds. */
    private fun isOsmAndNavigation(sbn: StatusBarNotification?): Boolean {
        val s = sbn ?: return false
        return s.isOngoing && s.packageName in OSMAND_PACKAGES
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
        private val OSMAND_PACKAGES =
            setOf("net.osmand", "net.osmand.plus", "net.osmand.dev")

        private const val CHANNEL_ID = "relay"
        private const val NOTIF_ID = 1
    }
}
