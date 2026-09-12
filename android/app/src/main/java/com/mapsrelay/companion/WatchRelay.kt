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

    private var ciq: ConnectIQ? = null
    private var device: IQDevice? = null
    private var app: IQApp? = null
    private var lastRepickAt = 0L

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
        if (ciq != null) return
        val instance = ConnectIQ.getInstance(context, ConnectIQ.IQConnectType.WIRELESS)
        ciq = instance
        setStatus("initialising")

        instance.initialize(context, true, object : ConnectIQ.ConnectIQListener {
            override fun onSdkReady() {
                setStatus("SDK ready, looking for a watch")
                pickDevice(instance)
            }

            override fun onInitializeError(status: ConnectIQ.IQSdkErrorStatus?) {
                // Overwhelmingly the cause is Garmin Connect Mobile missing or
                // not signed in; it is the transport, there is no fallback.
                setStatus("init failed: $status — is Garmin Connect installed?")
            }

            override fun onSdkShutDown() {
                setStatus("SDK shut down")
            }
        })
    }

    private fun pickDevice(instance: ConnectIQ) {
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
                setStatus("${dev.friendlyName}: $st")
                // Forget a device that has gone away so the next send re-picks
                // rather than firing into a dead handle.
                if (st != IQDevice.IQDeviceStatus.CONNECTED) {
                    device = null
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "registerForDeviceEvents failed", e)
        }
        setStatus("watch: ${d.friendlyName}")
    }

    /**
     * Fire-and-forget send. Returns false if there is nothing to send to yet.
     *
     * Delivery is not guaranteed even on true: if the watch app is not open,
     * Garmin drops the message. That is a platform constraint, not a bug here.
     */
    fun send(payload: Map<String, Any>): Boolean {
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
        return try {
            instance.sendMessage(d, a, payload) { _, _, sendStatus ->
                lastSent = "$sendStatus @ ${System.currentTimeMillis() / 1000}"
                Log.i(TAG, "send -> $sendStatus  $payload")
                onStatusChange?.invoke()
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "sendMessage failed", e)
            setStatus("send failed: ${e.message}")
            false
        }
    }
}
