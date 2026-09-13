package com.mapsrelay.companion

import android.util.Log
import net.osmand.aidlapi.navigation.ADirectionInfo

/**
 * Turns OsmAnd's turn updates into watch payloads.
 *
 * Process-wide and owned by nobody, which is the point. This logic used to live
 * inside [NavListener], with the AIDL callback wired up in its `onCreate`. That
 * coupling had a silent failure mode, and it bit on the first reinstall:
 * Android leaves a NotificationListenerService *enabled but unbound* after an
 * app is replaced, so NavListener never started, the callback was never wired,
 * and every turn OsmAnd delivered went into a null handler. The status screen
 * said "connected, receiving turns" -- truthfully -- while nothing whatsoever
 * reached the watch, and nothing logged a thing.
 *
 * Now [OsmAndLink] calls straight in here, so relaying works whenever the
 * subscription does. The notification listener is reduced to what only it can
 * do: supplying the street and ETA, and holding the process up with a
 * foreground notification.
 */
object Relay {

    private var lastManeuver = -1
    private var lastStreet = ""
    private var lastDistanceText = ""
    private var lastSentAt = 0L

    /** Last good trip read, kept so one failed getAppInfo() call blanks
     *  nothing. */
    private var lastTrip: OsmAndLink.Trip? = null

    /** Reset between routes so a new one is not deduped against the last. */
    fun reset() {
        lastManeuver = -1
        lastStreet = ""
        lastDistanceText = ""
        lastTrip = null
    }

    /**
     * A turn changed. **Runs on a Binder thread.**
     *
     * `distanceTo` is metres and `turnType` is a TurnType constant, so there is
     * nothing to parse and nothing to guess -- this is the whole of what used
     * to be MapsNotificationParser plus IconClassifier.
     */
    fun onDirection(info: ADirectionInfo) {
        val meters = info.distanceTo
        var maneuver = Maneuver.fromTurnType(info.turnType)

        // Counted unconditionally, before any dedupe can swallow it. The gap
        // between "turns arriving" and "messages relayed" is exactly what was
        // invisible when this silently did nothing.
        Status.turnsReceived++
        Status.lastTurnType = "${info.turnType} -> $maneuver @ ${meters}m"

        Status.navActive = true

        // Everything else about the trip, typed, straight from OsmAnd. This is
        // the call that made the notification parser redundant: street name,
        // distance left and arrival time all used to be regexes over text
        // written for a human to read.
        val trip = OsmAndLink.trip() ?: lastTrip
        if (trip != null) lastTrip = trip
        val street = trip?.street.orEmpty()

        // Arrival has no TurnType of its own. It used to be guessed by looking
        // for "arrive"/"Ziel"/"destination" in the notification text; the
        // distance still to travel says the same thing without a word list.
        if (trip != null && trip.leftDistance in 0..ARRIVAL_METERS) {
            maneuver = Maneuver.ARRIVE
        }

        // Off route has no distance to a turn: -1 suppresses the watch's
        // proximity thresholds, which would otherwise fire on a stale number.
        val dm = if (maneuver == Maneuver.OFF_ROUTE) -1 else meters
        val text = distanceText(dm)

        // Send whenever what the watch *displays* would change — not on coarse
        // distance buckets, which is what this used to do. Those edges were
        // 20/50/100/200/500/1000/2000/5000 m, so the whole stretch from 500 m
        // down to 201 m sent nothing at all and the number on the wrist sat
        // frozen while OsmAnd's own screen counted down.
        //
        // Keying on the rendered string is self-limiting: under 1 km it changes
        // every metre, so the cadence is set by the time floor below; above it
        // the display reads "2.4 km" and only moves every 100 m travelled.
        if (maneuver == lastManeuver && street == lastStreet && text == lastDistanceText) {
            return
        }
        // Only record what was actually sent. Recording a throttled update as
        // sent would suppress every later identical one and freeze the display
        // for good — harmless with buckets, fatal per-metre.
        if (!send(maneuver, dm, text, street, trip)) return
        lastManeuver = maneuver
        lastStreet = street
        lastDistanceText = text
    }

    /** @return true if the payload was handed to the transport. */
    private fun send(m: Int, dm: Int, text: String, street: String,
                     trip: OsmAndLink.Trip?): Boolean {
        // A ceiling, not the pacing mechanism — the display-change test above
        // does the real work. It was 1000 ms, which quietly halved the update
        // rate: OsmAnd emits roughly once a second, so jitter alone pushed
        // every other update under the floor and it was dropped.
        val now = System.currentTimeMillis()
        if (now - lastSentAt < MIN_SEND_INTERVAL_MS) return false
        lastSentAt = now

        val payload = mapOf(
            "m" to m,
            "d" to text,
            "s" to street,
            "e" to clockOf(trip?.arrivalTime ?: 0L),
            // Trip total, not the next turn. Deliberately absent from the
            // change test above: it ticks down constantly and would otherwise
            // drive a send on its own every time it moved.
            "r" to distanceText(trip?.leftDistance ?: -1),
            "dm" to dm,
            // The turn after this one. Nothing else on the watch can warn that
            // a second maneuver follows immediately.
            "m2" to (trip?.afterManeuver ?: Maneuver.UNKNOWN),
            "dm2" to (trip?.afterDistance ?: -1),
            "s2" to trip?.afterStreet.orEmpty(),
            "a1" to (trip?.nextAngle ?: 0),
            "a2" to (trip?.afterAngle ?: 0),
        )
        val ok = WatchRelay.send(payload)
        Status.lastPayload = payload.toString()
        if (ok) Status.sentCount++
        if (!ok) Log.w(WatchRelay.TAG, "relay not ready, dropped: $payload")
        return ok
    }

    /** Epoch seconds to a local "14:05", or "" when there is no arrival time. */
    private fun clockOf(epochSeconds: Long): String {
        if (epochSeconds <= 0L) return ""
        val c = java.util.Calendar.getInstance()
        c.timeInMillis = epochSeconds * 1000L
        return String.format(
            "%02d:%02d",
            c.get(java.util.Calendar.HOUR_OF_DAY),
            c.get(java.util.Calendar.MINUTE),
        )
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

    /** Ceiling on send rate. Low enough not to interfere with OsmAnd's roughly
     *  1 Hz updates, high enough that a chatty source cannot flood the BLE
     *  link. The display-change test is what actually paces sends. */
    private const val MIN_SEND_INTERVAL_MS = 400L

    /** Below this many metres left to the destination, the route is done. */
    private const val ARRIVAL_METERS = 30
}

/** Shared counters so the UI can show what the app is doing. */
object Status {
    /** Whether Android has actually *bound* the listener. Being enabled in
     *  settings is not the same thing — after an app is replaced it stays
     *  enabled and unbound — and the difference is invisible otherwise. */
    @Volatile var listenerBound = false
    @Volatile var navActive = false

    /** OsmAnd notifications seen. Feeds street and ETA only. */
    @Volatile var osmandNotifsSeen = 0

    /** Turns delivered by OsmAnd over AIDL, counted before any dedupe. If this
     *  climbs while `sentCount` does not, the fault is downstream of OsmAnd. */
    @Volatile var turnsReceived = 0

    /** The last turn OsmAnd reported, raw and mapped. If an arrow looks wrong,
     *  this says whether the fault is the mapping or the source. */
    @Volatile var lastTurnType = "-"

    @Volatile var sentCount = 0
    @Volatile var lastPayload = "-"
    @Volatile var lastError = "-"
}
