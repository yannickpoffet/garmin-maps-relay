package com.mapsrelay.companion

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
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
        WatchRelay.start(applicationContext)
    }

    override fun onDestroy() {
        stopRelayForeground()
        super.onDestroy()
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
    @Volatile var navActive = false
    @Volatile var sentCount = 0
    @Volatile var lastPayload = "-"

    /** How often the icon rescued a maneuver the keywords could not name.
     *  Surfaced in the UI because it is the honest measure of whether the
     *  keyword list is adequate for this phone's language. */
    @Volatile var iconFallbacks = 0
}
