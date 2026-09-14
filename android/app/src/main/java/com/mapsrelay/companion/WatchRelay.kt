package com.mapsrelay.companion

import android.content.Context
import android.util.Log
import com.garmin.android.connectiq.ConnectIQ
import com.garmin.android.connectiq.IQApp
import com.garmin.android.connectiq.IQDevice

/**
 * Sends payloads to the watch app over Garmin Connect Mobile.
 *
 * A process-wide singleton because both the UI and the notification listener
 * service need to send, and the SDK dislikes being initialised twice.
 */
object WatchRelay {

    const val TAG = "MapsRelay"

    /** Must match `id` in maps-relay/manifest.xml. This is the address of the
     *  watch app; a mismatch here fails silently, which is worth remembering
     *  when nothing arrives. */
    private const val WATCH_APP_ID = "7b7ae6eb8f3e41cbaa48b12cdfe77ee2"

    /** Kept so the SDK can be re-initialised from the send path, which has no
     *  Context of its own. */
    private var appContext: Context? = null

    private var ciq: ConnectIQ? = null
    private var device: IQDevice? = null
    private var app: IQApp? = null
    private var lastRepickAt = 0L

    /** Having a ConnectIQ instance is not the same as having an initialised
     *  one: initialize() is asynchronous, and every call on the SDK before
     *  onSdkReady throws "SDK not initialized". */
    @Volatile private var sdkReady = false

    /** Device we already hold an event registration for. */
    @Volatile private var registeredFor: Long = -1L

    /** When the in-flight payload was sent, or 0 when idle.
     *
     *  Connect IQ carries one message at a time over BLE, and the watch app
     *  acknowledges each payload, so this is a real handshake: one out, wait
     *  for the ack, then the next. The link then runs at exactly the rate the
     *  round trip allows, rather than at an interval guessed in advance. */
    @Volatile private var inFlightSince = 0L

    /** Sequence number of the payload awaiting acknowledgement. */
    @Volatile private var inFlightSeq = 0
    private var nextSeq = 1

    /**
     * How long to wait for an ack before sending regardless.
     *
     * A fallback, not the pacing mechanism. A watch app that never acks is a
     * watch app that is not running, and the display has nothing to lose from
     * another attempt.
     */
    private const val ACK_TIMEOUT_MS = 2500L

    /**
     * Spacing to fall back on while the watch has never acknowledged anything.
     *
     * Without this the handshake punishes its own failure: no acks means every
     * payload waits out the full timeout, so the display crawls at one update
     * per 2.5 s — far worse than the fixed interval the handshake replaced. A
     * watch that is not acking is not doing flow control, so there is nothing
     * to wait for; pace it and move on.
     */
    private const val NO_ACK_INTERVAL_MS = 500L

    /** True once the watch has ever acknowledged a payload. */
    @Volatile private var everAcked = false

    /** Round trip of the last acknowledged payload, in milliseconds — real
     *  evidence of how fast the link actually is. */
    @Volatile var lastRoundTripMs: Long = -1L
        private set

    /** When the watch app last acknowledged anything. */
    @Volatile var lastAckAt: Long = 0L
        private set

    /** Last attempt to launch the watch app, so a failing route does not
     *  prompt on the wrist every second. */
    @Volatile private var lastOpenAt = 0L
    private const val OPEN_INTERVAL_MS = 60_000L

    @Volatile var status: String = "not started"
        private set

    @Volatile var lastSent: String = "-"
        private set

    /** Why the last send was refused before it reached the SDK. "relay not
     *  ready" on its own says nothing about which of four reasons it was. */
    @Volatile var notReady: String = ""
        private set

    /** Is a watch paired and in range? Distinct from whether our app is
     *  running on it — the free-text `status` conflated the two, and they
     *  need completely different things done about them. */
    @Volatile var deviceConnected: Boolean = false
        private set

    /** Friendly name of the picked watch, or "" when there is none. */
    @Volatile var deviceName: String = ""
        private set

    /** Whether the watch *app* took our last message. Garmin only delivers to
     *  a Connect IQ app that is running, so a SUCCESS is the one piece of
     *  positive evidence that it is open. */
    @Volatile var appRunning: Boolean = false
        private set

    /** Set by the UI so it can repaint when status changes. */
    @Volatile var onStatusChange: (() -> Unit)? = null

    private const val REPICK_INTERVAL_MS = 5000L

    private fun setStatus(s: String) {
        status = s
        Log.i(TAG, "status: $s")
        onStatusChange?.invoke()
    }

    fun start(context: Context) {
        appContext = context.applicationContext
        // This guard is only correct because onSdkShutDown nulls `ciq`. It used
        // to leave the dead instance in place, so every later start() returned
        // here and the watch link could not come back without restarting the
        // process.
        if (ciq != null) return
        val instance = ConnectIQ.getInstance(context, ConnectIQ.IQConnectType.WIRELESS)
        ciq = instance
        setStatus("initialising")

        instance.initialize(context, true, object : ConnectIQ.ConnectIQListener {
            override fun onSdkReady() {
                sdkReady = true
                setStatus("SDK ready, looking for a watch")
                pickDevice(instance)
            }

            override fun onInitializeError(status: ConnectIQ.IQSdkErrorStatus?) {
                sdkReady = false
                // Overwhelmingly the cause is Garmin Connect Mobile missing or
                // not signed in; it is the transport, there is no fallback.
                setStatus("init failed: $status — is Garmin Connect installed?")
            }

            override fun onSdkShutDown() {
                deviceConnected = false
                appRunning = false
                // Drop everything, so the next start() genuinely re-initialises
                // rather than handing back a shut-down instance.
                sdkReady = false
                ciq = null
                device = null
                app = null
                registeredFor = -1L
                setStatus("SDK shut down")
            }
        })
    }

    /**
     * Deliberate, full retry of the watch link, for the button that says so.
     *
     * `start()` alone was a no-op here: it returns early whenever the SDK is
     * already initialised, which is nearly always, so the button promised a
     * reconnection and delivered nothing unless Garmin Connect had shut the
     * SDK down. This drops the current pick and builds it again from
     * knownDevices, which is the part a wedged link actually needs.
     */
    fun reconnect(context: Context) {
        val instance = ciq
        if (instance == null || !sdkReady) {
            start(context)
            return
        }
        val old = device
        val oldApp = app
        if (old != null) {
            // Both registrations, not just the device one. Clearing
            // registeredFor below makes pickDevice register again, so dropping
            // only half of it stacked a second app-event listener on every
            // press -- and a stacked listener means the SDK delivers each
            // message status twice, which is visible in the log as the same
            // payload and sequence number "sent" twice.
            try {
                instance.unregisterForDeviceEvents(old)
            } catch (e: Exception) {
                Log.w(TAG, "unregisterForDeviceEvents failed", e)
            }
            if (oldApp != null) {
                try {
                    instance.unregisterForApplicationEvents(old, oldApp)
                } catch (e: Exception) {
                    Log.w(TAG, "unregisterForApplicationEvents failed", e)
                }
            }
        }
        device = null
        registeredFor = -1L
        appRunning = false
        setStatus("re-picking watch")
        pickDevice(instance)
    }

    private fun pickDevice(instance: ConnectIQ) {
        // Calling knownDevices before onSdkReady throws, and the catch below
        // would then overwrite a perfectly good status line with "SDK not
        // initialized" -- which is exactly what the status screen showed once
        // OsmAnd started pushing turns faster than the SDK could start up.
        if (!sdkReady) return
        val known = try {
            instance.knownDevices ?: emptyList()
        } catch (e: Exception) {
            setStatus("cannot list devices: ${e.message}")
            return
        }

        if (known.isEmpty()) {
            deviceConnected = false
            deviceName = ""
            setStatus("no watch paired in Garmin Connect")
            return
        }

        val d = known[0]
        device = d
        app = IQApp(WATCH_APP_ID)

        deviceName = nameOf(d)

        // Register once per device, but keep refreshing the status either way.
        // pickDevice is reachable from a once-a-second poll now, and
        // re-registering on every call stacks listeners inside the SDK --
        // while returning early here would skip the refresh below and leave
        // the flag stale, which is the whole bug being fixed.
        if (registeredFor != d.deviceIdentifier) {
            registeredFor = d.deviceIdentifier
            registerEvents(instance, d)
            registerAcks(instance, d)
        }
        refreshDeviceStatus()
        setStatus(deviceName)
    }

    private fun registerEvents(instance: ConnectIQ, d: IQDevice) {
        try {
            instance.registerForDeviceEvents(d) { dev, st ->
                // friendlyName comes back empty sometimes, which rendered the
                // status line as a bare ": CONNECTED".
                deviceName = nameOf(dev)
                deviceConnected = (st == IQDevice.IQDeviceStatus.CONNECTED)
                setStatus("$deviceName: $st")
                // Forget a device that has gone away so the next send re-picks
                // rather than firing into a dead handle.
                if (!deviceConnected) {
                    device = null
                    appRunning = false
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "registerForDeviceEvents failed", e)
        }
    }

    /**
     * Listen for messages coming back from the watch app.
     *
     * This is the half that makes the handshake real. The send callback only
     * reports that Garmin took the message off our hands; it says nothing
     * about whether an app was there to receive it. An ack is sent by the
     * watch app itself, so it is proof the app is running and processing —
     * which is exactly the evidence this screen never had.
     */
    private fun registerAcks(instance: ConnectIQ, d: IQDevice) {
        val a = app ?: return
        try {
            Log.i(TAG, "registering for watch app events")
            instance.registerForAppEvents(d, a) { _, _, messages, _ ->
                val now = System.currentTimeMillis()
                var seq = -1
                for (m in messages.orEmpty()) {
                    val map = m as? Map<*, *> ?: continue
                    seq = (map["ack"] as? Number)?.toInt() ?: continue
                }
                Log.i(TAG, "watch -> ack $seq (awaiting $inFlightSeq)")
                if (seq < 0) return@registerForAppEvents

                everAcked = true
                appRunning = true
                lastAckAt = now
                // Ignore a late ack for a payload already given up on; it
                // would otherwise credit the wrong round trip and open the
                // gate for a send that is already in flight.
                if (seq == inFlightSeq && inFlightSince != 0L) {
                    lastRoundTripMs = now - inFlightSince
                    inFlightSince = 0L
                }
                onStatusChange?.invoke()
            }
        } catch (e: Exception) {
            Log.w(TAG, "registerForAppEvents failed", e)
        }
    }

    /**
     * Ask Garmin Connect whether the watch is reachable *now*.
     *
     * `IQDevice.status` is a field the SDK stamps on the objects it hands out;
     * on one from `knownDevices` it is whatever it was last set to, which is
     * routinely NOT_CONNECTED for a watch sitting right there. Reading it was
     * turning a perfectly live watch into "NO WATCH".
     *
     * Worse, the only other writer was the device-event callback, so once the
     * flag went false nothing ever revisited it: the screen stayed on NO WATCH
     * after the watch came back, and pressing any button merely repainted that
     * stale answer. Hence polling — the UI ticks once a second anyway.
     */
    fun refreshDeviceStatus() {
        val instance = ciq ?: return
        if (!sdkReady) return
        val d = device
        if (d == null) {
            pickDevice(instance)
            return
        }
        deviceConnected = try {
            instance.getDeviceStatus(d) == IQDevice.IQDeviceStatus.CONNECTED
        } catch (e: Exception) {
            // A failed query says nothing about the watch; keep what we knew
            // rather than inventing a disconnection.
            deviceConnected
        }
        if (!deviceConnected) appRunning = false
    }

    private fun nameOf(d: IQDevice): String {
        val n = d.friendlyName
        return if (n.isNullOrBlank()) "watch" else n
    }

    /**
     * Ask the watch to open Maps Relay.
     *
     * Garmin drops any message addressed to an app that is not running, which
     * is what FAILURE_DURING_TRANSFER means in practice: the link is fine, the
     * recipient simply is not there. Opening it remotely removes the one manual
     * step this app could not otherwise avoid — remembering to start the watch
     * app before setting off.
     *
     * Deliberately only ever called from the button. It was briefly wired to
     * every failed send, which on this watch answers PROMPT_SHOWN_ON_DEVICE:
     * it does not launch anything, it asks the wearer — so a route with the
     * watch app closed turned into a stream of prompts mid-drive. Opening the
     * app is a decision, so it stays on a button.
     */
    fun openOnWatch(force: Boolean = false) {
        val instance = ciq ?: return
        refreshDeviceStatus()
        val d = device ?: run { setStatus("no watch to open on"); return }
        val a = app ?: return
        val now = System.currentTimeMillis()
        if (!force && now - lastOpenAt < OPEN_INTERVAL_MS) return
        lastOpenAt = now
        try {
            instance.openApplication(d, a) { _, _, st ->
                Log.i(TAG, "openApplication -> $st")
                lastSent = "open: $st"
                onStatusChange?.invoke()
            }
        } catch (e: Exception) {
            Log.w(TAG, "openApplication failed", e)
        }
    }

    /**
     * Fire-and-forget send. Returns false if there is nothing to send to yet.
     *
     * Delivery is not guaranteed even on true: if the watch app is not open,
     * Garmin drops the message. That is a platform constraint, not a bug here.
     */
    @Synchronized
    fun send(payloadIn: Map<String, Any>): Boolean {
        // Sequence number, so an ack can be matched to the payload that earned
        // it and a late one for an abandoned payload can be told apart.
        val payload = payloadIn + ("n" to nextSeq)
        // Garmin Connect can shut the SDK down under us mid-route. Without
        // this every later send returns false forever and the watch just stops
        // updating, with nothing on screen to say why.
        if (ciq == null) {
            notReady = "SDK not started"
            val c = appContext ?: return false
            val now = System.currentTimeMillis()
            if (now - lastRepickAt > REPICK_INTERVAL_MS) {
                lastRepickAt = now
                start(c)
            }
            return false
        }
        val instance = ciq ?: return false
        // The watch can drop off and come back mid-route (out of range, phone
        // Bluetooth blip). Re-picking lazily here means recovery happens on
        // the next instruction instead of requiring the user to notice and
        // press Reconnect while driving.
        if (device == null) {
            val now = System.currentTimeMillis()
            if (now - lastRepickAt > REPICK_INTERVAL_MS) {
                lastRepickAt = now
                pickDevice(instance)
            }
        }
        val d = device ?: run { notReady = "no watch picked"; return false }
        val a = app ?: run { notReady = "no watch app id"; return false }

        // One payload in flight, and the *watch* decides when the next may go:
        // nothing leaves until the last one is acknowledged, or long enough has
        // passed that no ack is coming.
        val started = System.currentTimeMillis()
        val busy = inFlightSince
        val wait = if (everAcked) ACK_TIMEOUT_MS else NO_ACK_INTERVAL_MS
        if (busy != 0L && started - busy < wait) {
            notReady = if (everAcked) "waiting for watch ack" else "pacing (no acks yet)"
            return false
        }
        notReady = ""
        inFlightSince = started
        inFlightSeq = nextSeq

        return try {
            instance.sendMessage(d, a, payload) { _, _, sendStatus ->
                lastSent = "$sendStatus"
                if (sendStatus != ConnectIQ.IQMessageStatus.SUCCESS) {
                    // It never left, so nothing will ack it. Free the slot now
                    // rather than making the next payload wait out the timeout.
                    inFlightSince = 0L
                    appRunning = false
                    Status.lastError = "send: $sendStatus"
                }
                Log.i(TAG, "send -> $sendStatus  $payload")
                onStatusChange?.invoke()
            }
            nextSeq++
            true
        } catch (e: Exception) {
            inFlightSince = 0L
            Log.w(TAG, "sendMessage failed", e)
            setStatus("send failed: ${e.message}")
            false
        }
    }
}
