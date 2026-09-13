package com.mapsrelay.companion

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import net.osmand.aidlapi.navigation.ADirectionInfo

/**
 * Relays OsmAnd's turn-by-turn guidance to the watch.
 *
 * Two sources, one merge point, and the split is the point:
 *
 *   OsmAnd --AIDL updateNavigationInfo--> m, dm   typed; drives every send
 *      \---ongoing notification---------> s, e    best effort; cached only
 *
 * This used to scrape Google Maps' notification for all four. Maps publishes no
 * guidance API, so that was the only way in, and it broke twice: v0.7 on
 * RemoteViews inflation, v0.8 on assuming the title held what the notification
 * *renders* as. The maneuver was never in there as data at all — it came as an
 * icon bitmap that IconClassifier had to read pixel by pixel.
 *
 * Now the two fields that matter arrive as integers from an app that means to
 * provide them, and the notification is demoted to the two fields whose loss
 * nobody would risk a route over.
 *
 * This remains a NotificationListenerService only because that is still the way
 * to read the street and ETA.
 */
class NavListener : NotificationListenerService() {

    private var lastManeuver = -1
    private var lastStreet = ""
    private var lastBucket = -1
    private var lastSentAt = 0L
    private var foreground = false

    /** Last seen from the notification. Written on the main thread, read on a
     *  Binder thread when a turn update arrives, hence @Volatile. */
    @Volatile private var street = ""
    @Volatile private var eta = ""
    @Volatile private var arrived = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
        WatchRelay.start(applicationContext)
        OsmAndLink.onDirection = { info -> onDirection(info) }
        OsmAndLink.start(applicationContext)
    }

    override fun onDestroy() {
        OsmAndLink.onDirection = null
        stopRelayForeground()
        super.onDestroy()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Status.listenerBound = true
        Log.i(WatchRelay.TAG, "notification listener bound")
        // Pick up a route that is already running.
        try {
            activeNotifications?.forEach { onNotificationPosted(it) }
        } catch (e: Exception) {
            Log.w(WatchRelay.TAG, "could not scan active notifications", e)
        }
        // The listener being (re)bound is as good a moment as any to make sure
        // the OsmAnd side is up too.
        OsmAndLink.bind()
    }

    override fun onListenerDisconnected() {
        Status.listenerBound = false
        Log.w(WatchRelay.TAG, "notification listener unbound")
        super.onListenerDisconnected()
    }

    /**
     * Caches the cosmetic fields and keeps the process alive. Deliberately does
     * not send: sends are driven by the AIDL callback, which is the only source
     * that knows a turn actually changed.
     */
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (!OsmAndNotificationParser.isNavigation(sbn)) return
        Status.osmandNotifsSeen++
        // OsmAnd is demonstrably alive and navigating. If the AIDL link is not
        // up -- it was installed late, updated, or force-stopped -- this is the
        // moment to retry. Throttled inside bind(), so it is cheap to spam.
        OsmAndLink.bind()
        try {
            val info = OsmAndNotificationParser.parse(sbn!!) ?: return
            street = info.street
            eta = info.eta
            arrived = info.arrived
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
        // Navigation ended. Reset so the next route is not deduped against this
        // one; the watch falls back to its stale display on its own.
        stopRelayForeground()
        lastManeuver = -1
        lastStreet = ""
        lastBucket = -1
        street = ""
        eta = ""
        arrived = false
        Status.navActive = false
    }

    /**
     * A turn changed. **Runs on a Binder thread.**
     *
     * `distanceTo` is metres and `turnType` is a TurnType constant, so there is
     * nothing to parse and nothing to guess — the two lines below are the whole
     * of what used to be MapsNotificationParser plus IconClassifier.
     */
    private fun onDirection(info: ADirectionInfo) {
        val meters = info.distanceTo
        var maneuver = Maneuver.fromTurnType(info.turnType)
        Status.lastTurnType = "${info.turnType} -> $maneuver @ ${meters}m"

        // Arrival has no TurnType; the notification is the only hint.
        if (arrived && meters <= ARRIVAL_METERS) {
            maneuver = Maneuver.ARRIVE
        }

        Status.navActive = true
        startRelayForeground()

        val bucket = bucketOf(meters)
        if (maneuver == lastManeuver && street == lastStreet && bucket == lastBucket) {
            return
        }
        send(maneuver, meters)
        lastManeuver = maneuver
        lastStreet = street
        lastBucket = bucket
    }

    private fun send(m: Int, meters: Int) {
        // OsmAnd updates as the distance ticks down, several times a second on
        // a fast road. Relaying each one would flood the BLE link and drain
        // both batteries, so hold a hard floor of one per second on top of the
        // change detection above.
        val now = System.currentTimeMillis()
        if (now - lastSentAt < MIN_SEND_INTERVAL_MS) return
        lastSentAt = now

        // Off route has no distance to a turn: -1 suppresses the watch's
        // proximity thresholds, which would otherwise fire on a stale number.
        val dm = if (m == Maneuver.OFF_ROUTE) -1 else meters

        val payload = mapOf(
            "m" to m,
            "d" to distanceText(dm),
            "s" to street,
            "e" to eta,
            "dm" to dm,
        )
        val ok = WatchRelay.send(payload)
        Status.lastPayload = payload.toString()
        Status.sentCount++
        if (!ok) Log.w(WatchRelay.TAG, "relay not ready, dropped: $payload")
    }

    /**
     * The display string, formatted here from metres rather than lifted from
     * the notification's localised text. That coupling is gone: the watch shows
     * the same units wherever the phone happens to be set.
     */
    private fun distanceText(meters: Int): String = when {
        meters < 0 -> ""
        meters < 1000 -> "$meters m"
        else -> String.format("%.1f km", meters / 1000.0)
    }

    /**
     * Distance buckets. Updates go out only when the turn crosses one of these,
     * which is where the number on the watch changes meaning.
     */
    private fun bucketOf(meters: Int): Int {
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
        private const val MIN_SEND_INTERVAL_MS = 1000L
        private const val CHANNEL_ID = "relay"
        private const val NOTIF_ID = 1

        /** Only treat an arrival-looking notification as arrival once the last
         *  turn is close, so a destination name in the text mid-route does not
         *  end the display early. */
        private const val ARRIVAL_METERS = 30
    }
}

/** Shared counters so the UI can show what the service is doing. */
object Status {
    /** Whether Android has actually bound the listener. Granting access in
     *  settings is not the same thing, and the difference is invisible
     *  otherwise. */
    @Volatile var listenerBound = false
    @Volatile var navActive = false

    /** OsmAnd notifications seen. Only feeds street and ETA now, so this
     *  moving while sentCount does not is no longer a fault. */
    @Volatile var osmandNotifsSeen = 0

    /** The last turn OsmAnd reported, raw and mapped. If an arrow ever looks
     *  wrong, this says whether the fault is the mapping or the source. */
    @Volatile var lastTurnType = "-"

    @Volatile var sentCount = 0
    @Volatile var lastPayload = "-"
    @Volatile var lastError = "-"
}
