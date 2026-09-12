package com.mapsrelay.companion

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

    override fun onCreate() {
        super.onCreate()
        WatchRelay.start(applicationContext)
    }

    override fun onNavigationNotificationAdded(navNotification: NavigationNotification) {
        handle(navNotification.navigationData)
    }

    override fun onNavigationNotificationUpdated(navNotification: NavigationNotification) {
        handle(navNotification.navigationData)
    }

    override fun onNavigationNotificationRemoved(navNotification: NavigationNotification) {
        // Navigation ended. Reset so the next route is not deduped against
        // this one; the watch will fall back to its stale display by itself.
        lastManeuver = -1
        lastStreet = ""
        lastBucket = -1
        Status.navActive = false
    }

    private fun handle(data: NavigationData) {
        Status.navActive = true

        if (data.isRerouting) {
            send(Maneuver.UNKNOWN, "", "rerouting", "", force = true)
            return
        }
        if (!data.isValid()) return

        val instruction = data.nextDirection.localeString ?: ""
        val distanceText = data.nextDirection.navigationDistance?.localeString ?: ""
        val distanceMeters = metersOf(data)
        val eta = data.eta.localeString ?: ""
        val maneuver = Maneuver.fromText(instruction)
        val bucket = bucketOf(distanceMeters)

        val changed = maneuver != lastManeuver ||
                instruction != lastStreet ||
                bucket != lastBucket
        if (!changed) return

        send(maneuver, distanceText, instruction, eta, force = false)
        lastManeuver = maneuver
        lastStreet = instruction
        lastBucket = bucket
    }

    private fun send(m: Int, d: String, s: String, e: String, force: Boolean) {
        // Maps rewrites the notification as the distance ticks down, several
        // times a second on a fast road. Relaying each one would flood the BLE
        // link and drain both batteries, so hold a hard floor of one per
        // second on top of the change-detection above.
        val now = System.currentTimeMillis()
        if (!force && now - lastSentAt < MIN_SEND_INTERVAL_MS) return
        lastSentAt = now

        val payload = mapOf("m" to m, "d" to d, "s" to s, "e" to e)
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
    }
}

/** Shared counters so the UI can show what the service is doing. */
object Status {
    @Volatile var navActive = false
    @Volatile var sentCount = 0
    @Volatile var lastPayload = "-"
}
