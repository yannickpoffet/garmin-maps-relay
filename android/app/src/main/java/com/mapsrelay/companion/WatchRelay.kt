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

    /** When the in-flight send started, or 0 when idle.
     *
     *  Connect IQ carries one message at a time over BLE, and starting another
     *  before the last completes gets both FAILURE_DURING_TRANSFER. Nothing
     *  used to stop that, which mattered little when updates were gated to a
     *  handful per turn and matters a great deal now they track every metre. */
    @Volatile private var inFlightSince = 0L

    /** How long to wait before assuming a send callback is never coming. A lost
     *  callback must not wedge the link shut for the rest of the route. */
    private const val SEND_TIMEOUT_MS = 4000L

    /** Last attempt to launch the watch app, so a failing route does not
     *  prompt on the wrist every second. */
    @Volatile private var lastOpenAt = 0L
    private const val OPEN_INTERVAL_MS = 60_000L

    @Volatile var status: String = "not started"
        private set

    @Volatile var lastSent: String = "-"
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
                // Drop everything, so the next start() genuinely re-initialises
                // rather than handing back a shut-down instance.
                sdkReady = false
                ciq = null
                device = null
                app = null
                setStatus("SDK shut down")
            }
        })
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
            setStatus("no watch paired in Garmin Connect")
            return
        }

        val d = known[0]
        device = d
        app = IQApp(WATCH_APP_ID)

        try {
            instance.registerForDeviceEvents(d) { dev, st ->
                // friendlyName comes back empty sometimes, which rendered the
                // status line as a bare ": CONNECTED".
                setStatus("${nameOf(dev)}: $st")
                // Forget a device that has gone away so the next send re-picks
                // rather than firing into a dead handle.
                if (st != IQDevice.IQDeviceStatus.CONNECTED) {
                    device = null
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "registerForDeviceEvents failed", e)
        }
        setStatus(nameOf(d))
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
     * Throttled, because on some devices this prompts on the wrist.
     */
    fun openOnWatch(force: Boolean = false) {
        val instance = ciq ?: return
        val d = device ?: return
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
    fun send(payload: Map<String, Any>): Boolean {
        // Garmin Connect can shut the SDK down under us mid-route. Without
        // this every later send returns false forever and the watch just stops
        // updating, with nothing on screen to say why.
        if (ciq == null) {
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
        val d = device ?: return false
        val a = app ?: return false

        // One message on the wire at a time.
        val started = System.currentTimeMillis()
        val busy = inFlightSince
        if (busy != 0L && started - busy < SEND_TIMEOUT_MS) return false
        inFlightSince = started

        return try {
            instance.sendMessage(d, a, payload) { _, _, sendStatus ->
                inFlightSince = 0L
                lastSent = "$sendStatus @ ${System.currentTimeMillis() / 1000}"
                if (sendStatus != ConnectIQ.IQMessageStatus.SUCCESS) {
                    Status.lastError = "send: $sendStatus"
                    // Overwhelmingly this means the watch app is not open.
                    // Launch it rather than silently dropping the route.
                    openOnWatch()
                }
                Log.i(TAG, "send -> $sendStatus  $payload")
                onStatusChange?.invoke()
            }
            true
        } catch (e: Exception) {
            inFlightSince = 0L
            Log.w(TAG, "sendMessage failed", e)
            setStatus("send failed: ${e.message}")
            false
        }
    }
}
