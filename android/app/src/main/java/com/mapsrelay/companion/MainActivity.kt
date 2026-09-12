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
            text = "Reconnect watch"
            setOnClickListener {
                WatchRelay.start(applicationContext)
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
        WatchRelay.onStatusChange = { ui.post { render() } }
        WatchRelay.start(applicationContext)
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
            appendLine("watch               : ${WatchRelay.status}")
            appendLine("last send result    : ${WatchRelay.lastSent}")
            appendLine("navigation active   : ${Status.navActive}")
            appendLine("messages relayed    : ${Status.sentCount}")
            appendLine("maneuver from icon  : ${Status.iconFallbacks}")
            appendLine("last payload        : ${Status.lastPayload}")
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
