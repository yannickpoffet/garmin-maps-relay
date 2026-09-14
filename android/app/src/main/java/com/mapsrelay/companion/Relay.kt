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
    private var lastMeters = Int.MIN_VALUE

    /** Guards the dedupe state and the single pending slot. */
    private val lock = Any()

    /**
     * Set by the status screen so a new instruction repaints immediately.
     *
     * Without it the only repaints were the once-a-second tick and whatever
     * WatchRelay happened to notify, so the instruction on screen lagged the
     * instruction on the wire by up to a second for no reason at all — the
     * data was already there, nothing had asked to see it.
     */
    @Volatile var onUpdate: (() -> Unit)? = null

    /** The newest payload not yet accepted by the link, or null. One slot on
     *  purpose: a superseded payload has no value, and sending it would move
     *  the watch backwards. */
    private var pending: Map<String, Any>? = null

    /** Last good trip read, kept so one failed getAppInfo() call blanks
     *  nothing. */
    private var lastTrip: OsmAndLink.Trip? = null

    /** Reset between routes so a new one is not deduped against the last. */
    fun reset() {
        lastManeuver = -1
        lastStreet = ""
        lastMeters = Int.MIN_VALUE
        pending = null
        // A new route must start from a full payload; otherwise the watch is
        // diffed against a baseline from the previous one.
        lastFull = emptyMap()
        sinceFull = 0
        lastTrip = null
    }

    /**
     * A turn changed. **Runs on a Binder thread.**
     *
     * `distanceTo` is metres and `turnType` is a TurnType constant, so there is
     * nothing to parse and nothing to guess -- this is the whole of what used
     * to be MapsNotificationParser plus IconClassifier.
     */
    /**
     * A turn changed. **Runs on a Binder thread**, several of them.
     *
     * This does not send. It builds the payload and hands it to [pending],
     * replacing whatever was queued, then tries to flush.
     *
     * Queueing the *data* rather than the threads is the point. This method
     * used to be `@Synchronized` and send inline, so Binder threads piled up
     * on the lock and each one went on to transmit the snapshot it had taken
     * before it started waiting. The watch was shown distances it had already
     * passed while fresher ones were thrown away:
     *
     *     dropped dm=1188   <- newer
     *     SUCCESS dm=1207   <- older, actually sent
     *
     * With one slot holding the latest value, a payload superseded before it
     * goes out is simply replaced, and what reaches the watch is always the
     * newest thing known.
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

        // What the phone displays is what the phone knows, updated here.
        //
        // These used to be set inside flush(), after WatchRelay accepted the
        // payload — so the phone's own screen was gated by the BLE link and
        // sat a round trip behind, 0.7-1.6s, for no reason. The watch has to
        // wait for the link. This screen does not.
        Status.street = street
        Status.distance = text
        Status.eta = clockOf(trip?.arrivalTime ?: 0L)
        Status.remaining = distanceText(trip?.leftDistance ?: -1)
        Status.maneuver = maneuver

        synchronized(lock) {
            if (maneuver == lastManeuver && street == lastStreet && dm == lastMeters) {
                onUpdate?.invoke()
                return
            }
            lastManeuver = maneuver
            lastStreet = street
            lastMeters = dm
            pending = payloadOf(maneuver, dm, street, trip)
        }
        onUpdate?.invoke()
        flush()
    }

    /**
     * Send the queued payload if the link will take it.
     *
     * Called when a payload is queued, when the watch acknowledges one (so the
     * next goes the instant the link frees rather than on the next turn), and
     * from the status screen's tick as a backstop.
     */
    fun flush() {
        val queued = synchronized(lock) { pending } ?: return

        // Top the payload up with the newest numbers OsmAnd has, at the moment
        // of transmission rather than the moment of queueing.
        //
        // This is where the remaining staleness lived. A payload waits for the
        // link, and the link takes 0.7-1.6s, so a distance measured when the
        // payload was built is already old by the time it leaves. OsmAnd will
        // answer with the current one for the asking, and the difference is
        // most of a second on every single update.
        val full = refreshed(queued)
        // Only what the watch does not already know.
        val p = synchronized(lock) { deltaOf(full) }

        if (!WatchRelay.send(p)) return
        synchronized(lock) { lastFull = full }
        synchronized(lock) {
            // Only clear it if nothing newer arrived while we were sending.
            if (pending === queued) pending = null
        }
        // Only the send counters belong here. The instruction itself is
        // recorded when it is known, not when the link deigns to take it.
        Status.lastPayload = p.toString()
        Status.sentCount++
        onUpdate?.invoke()
    }

    /**
     * Refresh the displayed instruction straight from OsmAnd.
     *
     * The turn callback only fires on route-data updates, about once a second,
     * while OsmAnd's own screen moves more smoothly than that. Polling its
     * state for the display means the phone reads as current as OsmAnd itself
     * rather than a callback behind. Nothing here touches the send path — the
     * watch is paced by the link, and a payload is refreshed as it leaves.
     */
    fun pollDisplay() {
        if (!Status.navActive) return
        val trip = OsmAndLink.trip() ?: return
        if (trip.nextDistance >= 0) Status.distance = distanceText(trip.nextDistance)
        Status.remaining = distanceText(trip.leftDistance)
        Status.eta = clockOf(trip.arrivalTime)
        if (trip.street.isNotEmpty()) Status.street = trip.street
        onUpdate?.invoke()
    }

    /** The queued payload with its distances and ETA brought up to date. */
    private fun refreshed(p: Map<String, Any>): Map<String, Any> {
        val trip = OsmAndLink.trip() ?: return p
        val m = p["m"] as? Int ?: return p
        // Off route has no distance to a turn, and arrival is about to be
        // superseded anyway; leave both exactly as they were queued.
        if (m == Maneuver.OFF_ROUTE || m == Maneuver.ARRIVE) return p
        val dm = trip.nextDistance
        if (dm < 0) return p

        val text = distanceText(dm)
        // Deliberately does NOT touch lastMeters. That tracks the callback
        // stream, and writing the refreshed value into it ran the dedupe one
        // step ahead of OsmAnd: the refresh would advance it to 880, OsmAnd's
        // next callback would arrive carrying 880, the dedupe would call it a
        // repeat and swallow it. Every other update vanished and the display
        // sat exactly one behind.
        Status.distance = text
        Status.remaining = distanceText(trip.leftDistance)
        Status.eta = clockOf(trip.arrivalTime)

        return p + mapOf(
            "dm" to dm,
            "rm" to trip.leftDistance,
            "e" to clockOf(trip.arrivalTime),
        )
    }

    /**
     * The complete state, before any of it is dropped as unchanged.
     *
     * No rendered strings: the watch formats metres itself. Sending "723 m"
     * beside dm=723 was a dozen bytes of pure duplication on a link where the
     * payload size is the update rate.
     */
    private fun payloadOf(m: Int, dm: Int, street: String,
                          trip: OsmAndLink.Trip?): Map<String, Any> = mapOf(
        "m" to m,
        "dm" to dm,
        "s" to street,
        "e" to clockOf(trip?.arrivalTime ?: 0L),
        // Trip total, not the next turn.
        "rm" to (trip?.leftDistance ?: -1),
        // The turn after this one. Nothing else on the watch can warn that a
        // second maneuver follows immediately.
        "m2" to (trip?.afterManeuver ?: Maneuver.UNKNOWN),
        "dm2" to (trip?.afterDistance ?: -1),
        "s2" to trip?.afterStreet.orEmpty(),
        "a1" to (trip?.nextAngle ?: 0),
        "a2" to (trip?.afterAngle ?: 0),
    )

    /** Full state as last transmitted, to diff the next one against. */
    private var lastFull: Map<String, Any> = emptyMap()

    /** Sends since the last complete payload. */
    private var sinceFull = 0

    /**
     * Whittle a full payload down to what the watch does not already have.
     *
     * A full payload is 117 bytes and typically two of its eleven values are
     * new; a transfer costs about three seconds on this link, and cost tracks
     * size. Dropping the unchanged fields is therefore most of the update
     * rate, not a micro-optimisation.
     *
     * A complete payload goes every FULL_EVERY sends regardless, so a message
     * lost in transit cannot leave the watch permanently wrong about a field
     * that has since stopped changing.
     */
    private fun deltaOf(full: Map<String, Any>): Map<String, Any> {
        if (sinceFull >= FULL_EVERY || lastFull.isEmpty()) {
            sinceFull = 0
            return full
        }
        sinceFull++
        val out = HashMap<String, Any>()
        for ((k, v) in full) {
            if (lastFull[k] != v) out[k] = v
        }
        // The maneuver and the distance to it are what the screen is for;
        // always state them, so a delta is never ambiguous about the turn.
        out["m"] = full["m"] as Any
        out["dm"] = full["dm"] as Any
        return out
    }

    private const val FULL_EVERY = 8

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

    /**
     * No floor at all: the watch's acknowledgement is the flow control now.
     *
     * Every earlier value here was a guess at how fast the link could go —
     * 1000 ms, then 400 ms — and a guess is either too slow or too fast. The
     * handshake measures it instead: one payload out, wait for the ack, send
     * the next. Kept as a named constant because a future flood would want a
     * ceiling, not because this one does.
     */

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

    /** Payloads handed to the SDK. Not the same as payloads that arrived:
     *  this counts the attempt, which is why it needs `ackedCount` beside it
     *  to mean anything. */
    @Volatile var sentCount = 0

    /** Payloads the watch app acknowledged. The only count that says the watch
     *  actually got something and did something with it. */
    @Volatile var ackedCount = 0
    @Volatile var lastPayload = "-"
    @Volatile var lastError = "-"

    /** The current instruction, broken out so the UI can show it as an
     *  instruction rather than as a serialised map. */
    @Volatile var street = ""
    @Volatile var distance = ""
    @Volatile var eta = ""
    @Volatile var remaining = ""
    @Volatile var maneuver = Maneuver.UNKNOWN
}
