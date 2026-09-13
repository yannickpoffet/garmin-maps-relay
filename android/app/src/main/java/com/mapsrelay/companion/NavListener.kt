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
 * Reads Google Maps' ongoing navigation notification and relays each
 * meaningful change to the watch.
 *
 * This used to sit on top of GMapsParser, which inflates the notification's
 * RemoteViews and walks the view hierarchy. On Android 14 that fails on every
 * single notification -- Maps uses the standard template, so `contentView` is
 * null -- and the failure is silent, because the library logs through a Timber
 * tree it only plants in its own debug build. Reading the documented extras
 * directly is both simpler and the only thing that actually works here, so the
 * dependency is gone.
 */
class NavListener : NotificationListenerService() {

    private var lastManeuver = -1
    private var lastStreet = ""
    private var lastBucket = -1
    private var lastSentAt = 0L
    private var foreground = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
        WatchRelay.start(applicationContext)
    }

    override fun onDestroy() {
        stopRelayForeground()
        super.onDestroy()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Status.listenerBound = true
        Log.i(WatchRelay.TAG, "notification listener bound")
        // Pick up a route that is already running, rather than waiting for the
        // next instruction to arrive.
        try {
            activeNotifications?.forEach { onNotificationPosted(it) }
        } catch (e: Exception) {
            Log.w(WatchRelay.TAG, "could not scan active notifications", e)
        }
    }

    override fun onListenerDisconnected() {
        Status.listenerBound = false
        Log.w(WatchRelay.TAG, "notification listener unbound")
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (!MapsNotificationParser.isNavigation(sbn)) return
        Status.mapsSeen++
        Status.lastMapsId = "id=${sbn!!.id} ongoing=${sbn.isOngoing}"
        try {
            val info = MapsNotificationParser.parse(applicationContext, sbn)
            if (info != null) {
                handle(info)
            }
        } catch (e: Exception) {
            Status.lastError = "parse: ${e.message}"
            Log.w(WatchRelay.TAG, "parse failed", e)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (!MapsNotificationParser.isNavigation(sbn)) return
        // Navigation ended. Reset so the next route is not deduped against
        // this one; the watch falls back to its stale display on its own.
        stopRelayForeground()
        lastManeuver = -1
        lastStreet = ""
        lastBucket = -1
        Status.navActive = false
    }

    private fun handle(info: MapsNotificationParser.NavInfo) {
        Status.navActive = true
        startRelayForeground()

        // Text first: when the phone's language matches it distinguishes the
        // fine cases (slight/sharp/roundabout/merge) the icon cannot. The icon
        // then covers every language the keyword list does not.
        var maneuver = Maneuver.fromText(info.instruction)
        if (maneuver == Maneuver.UNKNOWN) {
            maneuver = IconClassifier.classify(info.icon)
            if (maneuver != Maneuver.UNKNOWN) Status.iconFallbacks++
        }

        val bucket = bucketOf(info.meters)
        if (maneuver == lastManeuver && info.instruction == lastStreet && bucket == lastBucket) {
            return
        }
        send(maneuver, info.distanceText, info.instruction, info.eta, info.meters.toInt())
        lastManeuver = maneuver
        lastStreet = info.instruction
        lastBucket = bucket
    }

    private fun send(m: Int, d: String, s: String, e: String, meters: Int) {
        // Maps rewrites the notification as the distance ticks down, several
        // times a second on a fast road. Relaying each one would flood the BLE
        // link and drain both batteries, so hold a hard floor of one per
        // second on top of the change detection above.
        val now = System.currentTimeMillis()
        if (now - lastSentAt < MIN_SEND_INTERVAL_MS) return
        lastSentAt = now

        // "dm" is the distance as a number: the watch needs one to decide when
        // to buzz, and re-parsing a localised "0.4 km" over there would be
        // fragile for no reason.
        val payload = mapOf("m" to m, "d" to d, "s" to s, "e" to e, "dm" to meters)
        val ok = WatchRelay.send(payload)
        Status.lastPayload = payload.toString()
        Status.sentCount++
        if (!ok) Log.w(WatchRelay.TAG, "relay not ready, dropped: $payload")
    }

    /**
     * Distance buckets. Updates go out only when the turn crosses one of
     * these, which is where the number on the watch changes meaning.
     */
    private fun bucketOf(meters: Double): Int {
        if (meters < 0) return -1
        val edges = intArrayOf(20, 50, 100, 200, 500, 1000, 2000, 5000)
        var i = 0
        while (i < edges.size && meters > edges[i]) i++
        return i
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
     * free to kill the process between notifications, and on a long drive it
     * eventually will -- the relay would then stop silently, mid-route.
     */
    private fun startRelayForeground() {
        if (foreground) return
        val n: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Relaying directions")
            .setContentText("Sending Google Maps guidance to your watch")
            .setSmallIcon(android.R.drawable.ic_menu_directions)
            .setOngoing(true)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
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
        private const val MIN_SEND_INTERVAL_MS = 1000L
        private const val CHANNEL_ID = "relay"
        private const val NOTIF_ID = 1
    }
}

/** Shared counters so the UI can show what the service is doing. */
object Status {
    /** Whether Android has actually bound the listener. Granting access in
     *  settings is not the same thing, and the difference is invisible
     *  otherwise. */
    @Volatile var listenerBound = false
    @Volatile var navActive = false

    /** Maps notifications seen, and the shape of the last one. If these move
     *  while `sentCount` stays at zero, notifications arrive but are rejected. */
    @Volatile var mapsSeen = 0
    @Volatile var lastMapsId = "-"

    @Volatile var sentCount = 0
    @Volatile var lastPayload = "-"
    @Volatile var lastError = "-"

    /** How often the icon rescued a maneuver the keywords could not name -
     *  the honest measure of whether the keyword list suits this phone. */
    @Volatile var iconFallbacks = 0
}
