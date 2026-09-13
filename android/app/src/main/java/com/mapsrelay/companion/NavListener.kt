package com.mapsrelay.companion

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.service.notification.StatusBarNotification
import android.util.Log
import timber.log.Timber
import me.trevi.navparser.lib.NavigationData
import me.trevi.navparser.lib.NavigationNotification
import me.trevi.navparser.service.NavigationListener

/**
 * Reads Google Maps' ongoing navigation notification and relays each
 * meaningful change to the watch.
 *
 * Google exposes no API for live guidance, so the notification is the only
 * source. GMapsParser does the parsing; this class decides what is worth
 * sending.
 */
class NavListener : NavigationListener() {

    private var lastManeuver = -1
    private var lastStreet = ""
    private var lastBucket = -1
    private var lastSentAt = 0L
    private var foreground = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
        plantLogBridge()
        // GMapsParser's NavigationListener starts *disabled*: its
        // isGoogleMapsNotification() short-circuits on a `protected var
        // enabled` that defaults to false, so without this every notification
        // is silently ignored and navigation never appears to start. Setting
        // it also re-scans notifications that are already on screen, which
        // matters when the app is opened mid-route.
        enabled = true
        WatchRelay.start(applicationContext)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Status.listenerBound = true
        Log.i(WatchRelay.TAG, "notification listener bound")
    }

    override fun onListenerDisconnected() {
        Status.listenerBound = false
        Log.w(WatchRelay.TAG, "notification listener unbound")
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        stopRelayForeground()
        super.onDestroy()
    }

    /**
     * GMapsParser logs through Timber but only plants a tree in its own debug
     * build, so from a released AAR every warning and swallowed exception goes
     * nowhere. Planting one here routes them to logcat and to the status
     * screen — which is how the RemoteViews failure finally became visible.
     */
    private fun plantLogBridge() {
        if (Timber.treeCount() > 0) return
        Timber.plant(object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                if (priority >= Log.WARN) {
                    Status.lastError = message.take(160)
                }
                Log.println(priority, tag ?: "navparser", message)
            }
        })
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val ch = NotificationChannel(
            CHANNEL_ID, "Navigation relay", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Shown while directions are being sent to the watch" }
        nm.createNotificationChannel(ch)
    }

    /**
     * Go foreground for the duration of a route.
     *
     * Without this the system is free to kill the process between
     * notifications, which on a long drive it eventually will — and the relay
     * would stop silently, mid-route, which is the worst possible failure mode.
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
            // Most likely the user denied the notification permission on
            // Android 13+. Relaying still works; it is just more killable.
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

    /**
     * Diagnostic only - the real work happens in the base class.
     *
     * GMapsParser accepts a notification only if it is ongoing, from the Maps
     * package, and has `id == 1`. That id is an assumption about Maps'
     * internals, so if guidance ever stops being picked up again, this tells
     * you whether the notification was seen at all and what id it carried -
     * the difference between "Maps changed its id" and "the listener is not
     * bound", which are otherwise indistinguishable from the outside.
     */
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (MapsNotificationParser.isNavigation(sbn)) {
            Status.mapsSeen++
            Status.lastMapsId = "id=${sbn!!.id} ongoing=${sbn.isOngoing}"
            // Our own extras-based read is the primary path. GMapsParser's
            // RemoteViews inflation fails on modern Maps notifications, and
            // its failure is silent, so relying on it alone meant relaying
            // nothing at all.
            try {
                val info = MapsNotificationParser.parse(applicationContext, sbn)
                if (info != null) {
                    handleParsed(info)
                }
            } catch (e: Exception) {
                Status.lastError = "parse: ${e.message}"
                Log.w(WatchRelay.TAG, "own parser failed", e)
            }
        }
        super.onNotificationPosted(sbn)
    }

    /** Relay a reading from our own parser. */
    private fun handleParsed(info: MapsNotificationParser.NavInfo) {
        Status.navActive = true
        startRelayForeground()

        var maneuver = Maneuver.fromText(info.instruction)
        if (maneuver == Maneuver.UNKNOWN) {
            maneuver = IconClassifier.classify(info.icon)
            if (maneuver != Maneuver.UNKNOWN) Status.iconFallbacks++
        }

        val bucket = bucketOf(info.meters)
        if (maneuver == lastManeuver && info.instruction == lastStreet && bucket == lastBucket) {
            return
        }
        send(maneuver, info.distanceText, info.instruction, info.eta,
             info.meters.toInt(), force = false)
        lastManeuver = maneuver
        lastStreet = info.instruction
        lastBucket = bucket
    }

    override fun onNavigationNotificationAdded(navNotification: NavigationNotification) {
        handle(navNotification.navigationData)
    }

    override fun onNavigationNotificationUpdated(navNotification: NavigationNotification) {
        handle(navNotification.navigationData)
    }

    override fun onNavigationNotificationRemoved(navNotification: NavigationNotification) {
        stopRelayForeground()
        // Navigation ended. Reset so the next route is not deduped against
        // this one; the watch will fall back to its stale display by itself.
        lastManeuver = -1
        lastStreet = ""
        lastBucket = -1
        Status.navActive = false
    }

    private fun handle(data: NavigationData) {
        Status.navActive = true
        startRelayForeground()

        if (data.isRerouting) {
            send(Maneuver.UNKNOWN, "", "rerouting", "", -1, force = true)
            return
        }
        if (!data.isValid()) return

        val instruction = data.nextDirection.localeString ?: ""
        val distanceText = data.nextDirection.navigationDistance?.localeString ?: ""
        val distanceMeters = metersOf(data)
        val eta = data.eta.localeString ?: ""
        // Text first: when the language matches it distinguishes the fine
        // cases (slight/sharp/roundabout/merge) that the icon cannot. The icon
        // then covers every language the keyword list does not, which is most
        // of them.
        var maneuver = Maneuver.fromText(instruction)
        if (maneuver == Maneuver.UNKNOWN) {
            maneuver = IconClassifier.classify(data.actionIcon.bitmap)
            if (maneuver != Maneuver.UNKNOWN) Status.iconFallbacks++
        }
        val bucket = bucketOf(distanceMeters)

        val changed = maneuver != lastManeuver ||
                instruction != lastStreet ||
                bucket != lastBucket
        if (!changed) return

        send(maneuver, distanceText, instruction, eta, distanceMeters.toInt(), force = false)
        lastManeuver = maneuver
        lastStreet = instruction
        lastBucket = bucket
    }

    private fun send(m: Int, d: String, s: String, e: String, meters: Int, force: Boolean) {
        // Maps rewrites the notification as the distance ticks down, several
        // times a second on a fast road. Relaying each one would flood the BLE
        // link and drain both batteries, so hold a hard floor of one per
        // second on top of the change-detection above.
        val now = System.currentTimeMillis()
        if (!force && now - lastSentAt < MIN_SEND_INTERVAL_MS) return
        lastSentAt = now

        // "dm" is the distance as a number. The watch needs it to decide when
        // to buzz; re-parsing the localised "0.4 km" string over there would be
        // fragile for no reason when the phone already has the value.
        val payload = mapOf("m" to m, "d" to d, "s" to s, "e" to e, "dm" to meters)
        val ok = WatchRelay.send(payload)
        Status.lastPayload = payload.toString()
        Status.sentCount++
        if (!ok) Log.w(WatchRelay.TAG, "relay not ready, dropped: $payload")
    }

    private fun metersOf(data: NavigationData): Double {
        val nd = data.nextDirection.navigationDistance ?: return -1.0
        if (nd.distance < 0) return -1.0
        return when (nd.unit.name) {
            "KM" -> nd.distance * 1000.0
            "M" -> nd.distance
            "MI" -> nd.distance * 1609.34
            "FT" -> nd.distance * 0.3048
            else -> -1.0
        }
    }

    /**
     * Distance buckets. Updates only go out when the turn crosses one of
     * these, which is where the number on the watch actually changes meaning.
     */
    private fun bucketOf(meters: Double): Int {
        if (meters < 0) return -1
        val edges = intArrayOf(20, 50, 100, 200, 500, 1000, 2000, 5000)
        var i = 0
        while (i < edges.size && meters > edges[i]) i++
        return i
    }

    companion object {
        private const val MIN_SEND_INTERVAL_MS = 1000L
        private const val CHANNEL_ID = "relay"
        private const val NOTIF_ID = 1
    }
}

/** Shared counters so the UI can show what the service is doing. */
object Status {
    /** Whether Android has actually bound the notification listener. Granting
     *  access in settings is not the same as being bound, and the difference
     *  is invisible without showing it. */
    @Volatile var listenerBound = false
    @Volatile var navActive = false

    /** Maps notifications seen at all, and the shape of the last one. If
     *  these move while `messages relayed` stays at zero, the notification is
     *  arriving but being rejected. */
    @Volatile var mapsSeen = 0
    @Volatile var lastMapsId = "-"

    /** Last warning or error, including ones GMapsParser logs internally via
     *  Timber. Without a planted tree those vanished, which is what made the
     *  RemoteViews failure invisible. */
    @Volatile var lastError = "-"
    @Volatile var sentCount = 0
    @Volatile var lastPayload = "-"

    /** How often the icon rescued a maneuver the keywords could not name.
     *  Surfaced in the UI because it is the honest measure of whether the
     *  keyword list is adequate for this phone's language. */
    @Volatile var iconFallbacks = 0
}
