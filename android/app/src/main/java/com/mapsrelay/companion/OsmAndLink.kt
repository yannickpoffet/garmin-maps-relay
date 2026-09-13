package com.mapsrelay.companion

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.KeyEvent
import net.osmand.aidlapi.IOsmAndAidlCallback
import net.osmand.aidlapi.IOsmAndAidlInterface
import net.osmand.aidlapi.gpx.AGpxBitmap
import net.osmand.aidlapi.logcat.OnLogcatMessageParams
import net.osmand.aidlapi.navigation.ADirectionInfo
import net.osmand.aidlapi.navigation.ANavigationUpdateParams
import net.osmand.aidlapi.navigation.OnVoiceNavigationParams
import net.osmand.aidlapi.search.SearchResult

/**
 * The link to OsmAnd.
 *
 * This replaces reading Google Maps' navigation notification. Maps publishes no
 * API for live guidance, so the only way to see a turn was to intercept a
 * notification written for human eyes and reverse-engineer it -- which broke in
 * v0.7 (RemoteViews inflation) and again in v0.8 (the title does not hold what
 * it renders as). The maneuver was never in there as data at all; it arrived as
 * an icon bitmap, which is why IconClassifier had to guess it from pixels.
 *
 * OsmAnd offers a front door instead. Register once, and it calls us with
 * `ADirectionInfo{distanceTo, turnType}` every time the next turn changes --
 * the two fields that matter, as integers, from an app that means to give them.
 *
 * A process-wide singleton for the same reason as [WatchRelay]: both the UI and
 * the listener service need it, and there should only ever be one binding.
 */
object OsmAndLink {

    /** Free, paid and nightly builds, in the order they are worth trying. */
    private val PACKAGES = arrayOf("net.osmand.plus", "net.osmand", "net.osmand.dev")

    /** The v2 service. Note it speaks the `net.osmand.aidlapi` contract, not
     *  the older `net.osmand.aidl` one -- see src/main/aidl/README.md. */
    private const val SERVICE_ACTION = "net.osmand.aidl.OsmandAidlServiceV2"

    private var ctx: Context? = null
    private var iface: IOsmAndAidlInterface? = null
    private var binding = false
    private var lastBindAttempt = 0L

    /** Bound is not the same as subscribed: OsmAnd accepts the bind from any
     *  app and only then decides whether to honour the subscription. */
    @Volatile private var subscribed = false

    private const val REBIND_INTERVAL_MS = 5000L

    /** Shown until the user approves us inside OsmAnd. Names the exact screen,
     *  because nothing about "subscribe failed" suggests where to look. */
    const val NOT_ENABLED = "NOT ENABLED — in OsmAnd: Menu > Plugins > Maps Relay > enable"

    @Volatile
    var bound: Boolean = false
        private set

    /** Which OsmAnd we found, or why we did not. Shown on the status screen. */
    @Volatile
    var status: String = "not started"
        private set

    /** Set by the UI so it can repaint when status changes. */
    @Volatile
    var onStatusChange: (() -> Unit)? = null

    private fun setStatus(s: String) {
        status = s
        Log.i(WatchRelay.TAG, "osmand: $s")
        onStatusChange?.invoke()
    }

    fun start(context: Context) {
        ctx = context.applicationContext
        bind()
    }

    /**
     * Bind, or do nothing if already bound or attempted too recently.
     *
     * Safe to call repeatedly, and NavListener does exactly that on every
     * OsmAnd notification: if OsmAnd was installed, updated or force-stopped
     * after we gave up, the next route re-establishes the link on its own
     * rather than needing the user to notice and press something.
     *
     * A *transient* drop needs none of this -- BIND_AUTO_CREATE keeps the
     * ServiceConnection registered, so Android calls onServiceConnected again
     * by itself and the subscription is renewed there.
     */
    fun bind(force: Boolean = false) {
        val c = ctx ?: return
        if (binding) return

        // The throttle exists so the send path can call this on every
        // instruction; a button press is a deliberate act and skips it.
        val now = System.currentTimeMillis()
        if (!force && now - lastBindAttempt < REBIND_INTERVAL_MS) return
        lastBindAttempt = now

        // Already connected but refused. The user has most likely just gone to
        // enable us in OsmAnd, and the toggle sends no signal here -- so retry
        // the subscription rather than making them restart the app.
        val existing = iface
        if (existing != null) {
            if (!subscribed) subscribe(existing)
            return
        }

        val pkg = installedPackage(c)
        if (pkg == null) {
            setStatus("OsmAnd not installed")
            return
        }

        val intent = Intent(SERVICE_ACTION).setPackage(pkg)
        var flags = Context.BIND_AUTO_CREATE
        if (Build.VERSION.SDK_INT >= 34) {
            flags = flags or Context.BIND_ALLOW_ACTIVITY_STARTS
        }
        binding = true
        val ok = try {
            c.bindService(intent, connection, flags)
        } catch (e: Exception) {
            Log.w(WatchRelay.TAG, "bindService threw", e)
            false
        }
        if (!ok) {
            binding = false
            setStatus("$pkg found but service refused the bind")
        } else {
            setStatus("binding to $pkg")
        }
    }

    /**
     * Package visibility is filtered on Android 11+, so all three candidates
     * are declared in <queries> -- without that this returns null even when
     * OsmAnd is plainly installed.
     */
    private fun installedPackage(c: Context): String? {
        val pm = c.packageManager
        for (p in PACKAGES) {
            try {
                pm.getPackageInfo(p, 0)
                return p
            } catch (e: Exception) {
                // Not this one.
            }
        }
        return null
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            binding = false
            val i = IOsmAndAidlInterface.Stub.asInterface(service)
            iface = i
            bound = true
            subscribe(i)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            subscribed = false
            // OsmAnd died or was force-stopped. Drop the handle so the next
            // bind() call re-establishes rather than firing into a dead binder.
            iface = null
            bound = false
            binding = false
            setStatus("disconnected")
        }
    }

    /**
     * Ask OsmAnd to push turn updates.
     *
     * OsmAnd gates its API per calling app: on first contact it registers us
     * with enabled=false, persists that, and refuses. A negative return is
     * therefore the expected result of a first run rather than a fault, and
     * the only cure is a human enabling us in OsmAnd -- which sends no signal
     * back here, hence the retry from [bind].
     */
    private fun subscribe(i: IOsmAndAidlInterface) {
        try {
            val params = ANavigationUpdateParams().apply {
                setSubscribeToUpdates(true)
                setCallbackId(0L)
            }
            val id = i.registerForNavigationUpdates(params, callback)
            subscribed = id >= 0
            // "subscribe failed" on its own sends you hunting through the
            // wrong half of the system, so name the screen instead.
            setStatus(if (subscribed) "connected, receiving turns" else NOT_ENABLED)
        } catch (e: Exception) {
            subscribed = false
            Status.lastError = "subscribe: ${e.message}"
            setStatus("connected, subscribe threw: ${e.message}")
        }
    }

    /**
     * Only [updateNavigationInfo] carries anything we want. The rest of the
     * interface must still be implemented -- AIDL has no partial stubs -- so
     * they are deliberately empty.
     */
    private val callback = object : IOsmAndAidlCallback.Stub() {

        override fun updateNavigationInfo(directionInfo: ADirectionInfo?) {
            val d = directionInfo ?: return
            try {
                // Straight into Relay, deliberately. This used to go through a
                // nullable handler that NavListener assigned in onCreate -- and
                // when a reinstall left that service enabled but unbound, every
                // turn landed in a null and vanished without a trace.
                Relay.onDirection(d)
            } catch (e: Exception) {
                // A throw here crosses back over the binder and would take
                // OsmAnd's caller with it.
                Status.lastError = "onDirection: ${e.message}"
                Log.w(WatchRelay.TAG, "onDirection threw", e)
            }
        }

        override fun onSearchComplete(resultSet: MutableList<SearchResult>?) {}
        override fun onUpdate() {}
        override fun onAppInitialized() {}
        override fun onGpxBitmapCreated(bitmap: AGpxBitmap?) {}
        override fun onVoiceRouterNotify(params: OnVoiceNavigationParams?) {}
        override fun onContextMenuButtonClicked(buttonId: Int, pointId: String?, layerId: String?) {}
        override fun onKeyEvent(params: KeyEvent?) {}
        override fun onLogcatMessage(params: OnLogcatMessageParams?) {}
    }
}
