package com.mapsrelay.companion

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Status and setup screen. There is nothing to drive here during a route —
 * the notification listener does the work in the background — so this exists
 * to grant permissions and to prove the link end to end.
 */
class MainActivity : Activity() {

    private lateinit var statusView: TextView
    private lateinit var accessButton: Button
    private val ui = Handler(Looper.getMainLooper())

    private val refresh = object : Runnable {
        override fun run() {
            // Enabling us inside OsmAnd sends no signal back, and this screen
            // is where you are standing when you do it — so retry from here
            // and let it go green on its own. bind() throttles internally, so
            // calling it once a second costs nothing.
            OsmAndLink.bind()
            render()
            ui.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }

        root.addView(TextView(this).apply {
            text = "Maps Relay"
            textSize = 24f
            setPadding(0, 0, 0, 24)
        })

        accessButton = Button(this).apply {
            text = "Grant notification access"
            // Reading another app's notifications can only be granted in
            // system settings; there is no runtime permission dialog for it.
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
        }
        root.addView(accessButton, lp())

        root.addView(Button(this).apply {
            text = "Send test message to watch"
            // The M0 acceptance test: if this lands on the watch, the whole
            // phone -> Garmin Connect -> BLE -> watch path works.
            setOnClickListener {
                val ok = WatchRelay.send(
                    mapOf("m" to Maneuver.RIGHT, "d" to "200 m", "dm" to 200,
                          "s" to "Test from phone", "e" to "--:--")
                )
                if (!ok) toastStatus("relay not ready — see status below")
                render()
            }
        }, lp())

        root.addView(Button(this).apply {
            text = "Reconnect watch and OsmAnd"
            setOnClickListener {
                WatchRelay.start(applicationContext)
                OsmAndLink.bind(force = true)
                render()
            }
        }, lp())

        statusView = TextView(this).apply {
            setPadding(0, 32, 0, 0)
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 13f
        }
        root.addView(statusView)

        setContentView(ScrollView(this).apply { addView(root) })

        requestNotificationPermissionIfNeeded()
        requestListenerRebind()
        WatchRelay.onStatusChange = { ui.post { render() } }
        WatchRelay.start(applicationContext)
        // Also started by NavListener; harmless twice, and this way the status
        // screen is useful before notification access has been granted.
        OsmAndLink.onStatusChange = { ui.post { render() } }
        OsmAndLink.start(applicationContext)
    }

    override fun onResume() {
        super.onResume()
        ui.post(refresh)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(refresh)
    }

    private fun lp() = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        .apply { topMargin = 16 }

    private fun toastStatus(s: String) {
        android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun render() {
        val granted = notificationAccessGranted()
        accessButton.isEnabled = !granted

        statusView.setTextColor(if (granted) Color.DKGRAY else Color.RED)
        statusView.text = buildString {
            appendLine("notification access : ${if (granted) "granted" else "NOT GRANTED"}")
            appendLine("listener bound      : ${if (Status.listenerBound) "true" else "FALSE (street/ETA only)"}")
            appendLine("watch               : ${WatchRelay.status}")
            appendLine("last send result    : ${WatchRelay.lastSent}")
            appendLine("osmand              : ${OsmAndLink.status}")
            appendLine("osmand notifs seen  : ${Status.osmandNotifsSeen}")
            appendLine("navigation active   : ${Status.navActive}")
            appendLine("turns received      : ${Status.turnsReceived}")
            appendLine("last turn           : ${Status.lastTurnType}")
            appendLine("messages relayed    : ${Status.sentCount}")
            appendLine("last payload        : ${Status.lastPayload}")
            appendLine("last error          : ${Status.lastError}")
        }
    }

    /**
     * Ask Android to bind the notification listener.
     *
     * Replacing the app leaves the service *enabled but unbound* — it keeps the
     * grant and simply never starts, so the street and ETA quietly stop
     * arriving and nothing says why. requestRebind is the documented cure and
     * costs nothing when it is already bound.
     */
    private fun requestListenerRebind() {
        try {
            android.service.notification.NotificationListenerService.requestRebind(
                android.content.ComponentName(this, NavListener::class.java)
            )
        } catch (e: Exception) {
            android.util.Log.w(WatchRelay.TAG, "requestRebind failed", e)
        }
    }

    /**
     * Android 13+ needs this before the relay can show its ongoing
     * notification, and without that notification the service cannot go
     * foreground and is liable to be killed mid-route.
     */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val perm = android.Manifest.permission.POST_NOTIFICATIONS
        if (checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(perm), 1)
        }
    }

    /** The only reliable way to check: read the system's own list. */
    private fun notificationAccessGranted(): Boolean {
        val flat = Settings.Secure.getString(
            contentResolver, "enabled_notification_listeners"
        ) ?: return false
        if (TextUtils.isEmpty(flat)) return false
        return flat.split(":").any { it.contains(packageName) }
    }
}
