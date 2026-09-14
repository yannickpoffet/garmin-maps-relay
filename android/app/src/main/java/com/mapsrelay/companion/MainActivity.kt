package com.mapsrelay.companion

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.mapsrelay.companion.Ui.ACCENT
import com.mapsrelay.companion.Ui.BG
import com.mapsrelay.companion.Ui.DIM
import com.mapsrelay.companion.Ui.LINE
import com.mapsrelay.companion.Ui.ON_ACCENT
import com.mapsrelay.companion.Ui.S_BAD
import com.mapsrelay.companion.Ui.S_OFF
import com.mapsrelay.companion.Ui.S_OK
import com.mapsrelay.companion.Ui.S_WARN
import com.mapsrelay.companion.Ui.SURFACE
import com.mapsrelay.companion.Ui.SURFACE2
import com.mapsrelay.companion.Ui.TEXT
import com.mapsrelay.companion.Ui.col

/**
 * Status and setup screen.
 *
 * It used to print every counter it had and leave you to work out which line
 * mattered. There is nearly always exactly one thing wrong, so the top of the
 * screen is a single verdict with the button that fixes it, and the detail
 * lives underneath for when that is not enough.
 *
 * The watch gets a pill in the header that is visible from every scroll
 * position, because "is the watch actually connected" is the question this
 * screen is opened to answer. It separates two states the old free-text status
 * ran together: the watch being out of range, and the watch being right there
 * with our app closed on it. Those need entirely different things done.
 */
class MainActivity : Activity() {

    private val ui = Handler(Looper.getMainLooper())

    private lateinit var watchPill: TextView
    private lateinit var heroCard: LinearLayout
    private lateinit var heroWord: TextView
    private lateinit var heroSub: TextView
    private lateinit var heroFix: Button

    private lateinit var rowOsmand: Row
    private lateinit var rowWatch: Row
    private lateinit var rowApp: Row
    private lateinit var rowNotif: Row

    private lateinit var instrLabel: TextView
    private lateinit var instrCard: LinearLayout
    private lateinit var instrTurn: TextView
    private lateinit var instrStreet: TextView
    private lateinit var instrTrip: TextView

    private lateinit var detail: TextView

    private fun dpi(v: Float) = Ui.dpi(this, v)

    /** Fast tick for the display, slow tick for anything that costs. */
    private var ticks = 0

    private val refresh = object : Runnable {
        override fun run() {
            // Read the instruction straight from OsmAnd rather than waiting for
            // its next turn callback, so this screen is as current as OsmAnd's.
            Relay.pollDisplay()
            render()

            // The rest is housekeeping and does not need four times a second.
            if (ticks++ % 4 == 0) {
                // Enabling us inside OsmAnd sends no signal back, and this
                // screen is where you stand when you do it. bind() throttles.
                OsmAndLink.bind()
                // The watch can come back without the SDK telling us, so ask.
                WatchRelay.refreshDeviceStatus()
                // Backstop: if an ack was lost, the queued payload still goes.
                Relay.flush()
            }
            ui.postDelayed(this, 250)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(col(BG))
            setPadding(dpi(16f), dpi(18f), dpi(16f), dpi(24f))
        }

        // header -----------------------------------------------------------
        watchPill = Ui.pill(this, "WATCH ?", S_OFF)
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(Ui.condensed(this@MainActivity, "MAPS RELAY", 22f, TEXT),
                LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(watchPill)
        })
        root.addView(Ui.mono(this, "osmand → forerunner 745", 11.5f, DIM).apply {
            setPadding(0, dpi(2f), 0, dpi(16f))
        })

        // verdict ----------------------------------------------------------
        heroWord = Ui.condensed(this, "—", 34f, TEXT).apply { gravity = Gravity.CENTER }
        heroSub = Ui.mono(this, "", 12.5f, DIM).apply {
            gravity = Gravity.CENTER
            setPadding(dpi(4f), dpi(6f), dpi(4f), 0)
        }
        heroFix = Button(this).apply {
            visibility = View.GONE
            isAllCaps = true
            textSize = 12f
            letterSpacing = 0.06f
            typeface = Typeface.MONOSPACE
            setTextColor(col(ON_ACCENT))
            background = GradientDrawable().apply {
                setColor(col(ACCENT)); cornerRadius = Ui.dp(this@MainActivity, 999f)
            }
            stateListAnimator = null
        }
        heroCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.cardBg(this@MainActivity)
            setPadding(dpi(20f), dpi(20f), dpi(20f), dpi(18f))
            addView(Ui.label(this@MainActivity, "STATUS").apply { gravity = Gravity.CENTER })
            addView(heroWord)
            addView(heroSub)
            addView(heroFix, LinearLayout.LayoutParams(MATCH_PARENT, dpi(44f))
                .apply { topMargin = dpi(14f) })
        }
        root.addView(heroCard, gap())

        // connections ------------------------------------------------------
        root.addView(Ui.label(this, "CONNECTIONS").apply { setPadding(dpi(4f), 0, 0, dpi(6f)) })
        rowOsmand = Row(this, "osmand")
        rowWatch = Row(this, "watch link")
        rowApp = Row(this, "watch app")
        rowNotif = Row(this, "notifications")
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.cardBg(this@MainActivity)
            setPadding(dpi(16f), dpi(10f), dpi(16f), dpi(10f))
            addView(rowOsmand.view); addView(rowWatch.view)
            addView(rowApp.view); addView(rowNotif.view)
        }, gap())

        // live instruction --------------------------------------------------
        instrLabel = Ui.label(this, "CURRENT INSTRUCTION")
            .apply { setPadding(dpi(4f), 0, 0, dpi(6f)) }
        root.addView(instrLabel)
        instrTurn = Ui.condensed(this, "—", 26f, TEXT)
        instrStreet = Ui.mono(this, "", 13f, DIM).apply { setPadding(0, dpi(2f), 0, 0) }
        instrTrip = Ui.mono(this, "", 12.5f, ACCENT).apply { setPadding(0, dpi(10f), 0, 0) }
        instrCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.cardBg(this@MainActivity)
            setPadding(dpi(16f), dpi(14f), dpi(16f), dpi(14f))
            addView(instrTurn); addView(instrStreet); addView(instrTrip)
        }
        root.addView(instrCard, gap())

        // actions ------------------------------------------------------------
        root.addView(Ui.label(this, "ACTIONS").apply { setPadding(dpi(4f), 0, 0, dpi(6f)) })
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.cardBg(this@MainActivity)
            setPadding(dpi(16f), dpi(4f), dpi(16f), dpi(4f))
            addView(action("open maps relay on watch") {
                WatchRelay.openOnWatch(force = true); render()
            })
            addView(action("send test message") {
                val ok = WatchRelay.send(mapOf(
                    "m" to Maneuver.RIGHT, "d" to "200 m", "dm" to 200,
                    "s" to "Test from phone", "e" to "--:--", "r" to ""))
                if (!ok) toast("not sent — ${WatchRelay.notReady.ifEmpty { "see status" }}")
                render()
            })
            addView(action("reconnect watch and osmand") {
                WatchRelay.reconnect(applicationContext)
                OsmAndLink.bind(force = true)
                render()
            })
        }, gap())

        // detail --------------------------------------------------------------
        root.addView(Ui.label(this, "DETAIL").apply { setPadding(dpi(4f), 0, 0, dpi(6f)) })
        detail = Ui.mono(this, "", 10.5f, DIM)
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.cardBg(this@MainActivity, LINE, SURFACE2)
            setPadding(dpi(14f), dpi(12f), dpi(14f), dpi(12f))
            addView(detail)
        }, gap())

        setContentView(ScrollView(this).apply {
            setBackgroundColor(col(BG)); addView(root)
        })

        requestNotificationPermissionIfNeeded()
        requestListenerRebind()
        WatchRelay.onStatusChange = { ui.post { render() } }
        WatchRelay.start(applicationContext)
        OsmAndLink.onStatusChange = { ui.post { render() } }
        // Repaint the moment an instruction changes, rather than on the tick.
        Relay.onUpdate = { ui.post { render() } }
        OsmAndLink.start(applicationContext)
    }

    override fun onResume() { super.onResume(); ui.post(refresh) }
    override fun onPause() { super.onPause(); ui.removeCallbacks(refresh) }

    // --------------------------------------------------------------- verdict

    private class Verdict(
        val colour: String, val word: String, val sub: String,
        val fix: String? = null, val action: (() -> Unit)? = null,
    )

    /**
     * Reported in dependency order, stopping at the first failure: everything
     * downstream of a broken link is unfixable until that one is fixed, so
     * listing them together would only bury the one that matters.
     */
    private fun verdict(): Verdict {
        if (OsmAndLink.status.startsWith("NOT ENABLED")) {
            return Verdict(S_BAD, "BLOCKED",
                "osmand gates its api per app and registers new ones disabled.\n" +
                "enable maps relay in osmand: menu › plugins",
                "open osmand") { openOsmAnd() }
        }
        if (OsmAndLink.status.contains("not installed")) {
            return Verdict(S_BAD, "NO OSMAND",
                "this relays osmand's guidance. nothing works without it")
        }
        if (!OsmAndLink.bound) {
            return Verdict(S_BAD, "NO OSMAND", OsmAndLink.status,
                "reconnect") { OsmAndLink.bind(force = true); render() }
        }
        if (!WatchRelay.deviceConnected) {
            return Verdict(S_BAD, "NO WATCH",
                if (WatchRelay.deviceName.isEmpty())
                    "no watch paired in garmin connect"
                else "${WatchRelay.deviceName} is paired but out of range.\n" +
                     "check bluetooth and that garmin connect is running",
                "reconnect") { WatchRelay.reconnect(applicationContext); render() }
        }
        if (Status.navActive && !WatchRelay.appRunning) {
            return Verdict(S_WARN, "APP CLOSED",
                "the watch is connected but nothing is acknowledging.\n" +
                "maps relay is not open on it, and garmin drops messages\n" +
                "aimed at an app that is not running",
                "open on watch") { WatchRelay.openOnWatch(force = true); render() }
        }
        if (!Status.listenerBound) {
            return if (!notificationAccessGranted()) {
                Verdict(S_WARN, "KILLABLE",
                    "without notification access the relay cannot hold the\n" +
                    "foreground, so android may stop it mid-route",
                    "grant access") {
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }
            } else {
                Verdict(S_WARN, "KILLABLE",
                    "android left the listener enabled but not running.\n" +
                    "turn notification access off and on again",
                    "open settings") {
                    requestListenerRebind()
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }
            }
        }
        if (!Status.navActive) {
            return Verdict(S_OFF, "READY", "start navigating in osmand")
        }
        return Verdict(S_OK, "RELAYING",
            "${Status.ackedCount} acknowledged by the watch" +
            if (WatchRelay.lastRoundTripMs >= 0) ", ${WatchRelay.lastRoundTripMs} ms round trip" else "")
    }

    private fun render() {
        val v = verdict()
        heroWord.text = v.word
        heroWord.setTextColor(col(v.colour))
        heroSub.text = v.sub
        (heroCard.background as GradientDrawable)
            .setStroke(dpi(1f), col(v.colour))
        if (v.fix != null && v.action != null) {
            heroFix.visibility = View.VISIBLE
            heroFix.text = v.fix
            heroFix.setOnClickListener { v.action.invoke() }
        } else heroFix.visibility = View.GONE

        // The watch pill answers three different questions, not one.
        when {
            !WatchRelay.deviceConnected ->
                Ui.setPill(watchPill, this, "WATCH OFFLINE", S_BAD)
            !WatchRelay.appRunning ->
                Ui.setPill(watchPill, this, "APP CLOSED", S_WARN)
            else -> Ui.setPill(watchPill, this, "WATCH LIVE", S_OK)
        }

        rowOsmand.set(
            if (OsmAndLink.bound && !OsmAndLink.status.startsWith("NOT ENABLED")) S_OK else S_BAD,
            OsmAndLink.status)
        rowWatch.set(
            if (WatchRelay.deviceConnected) S_OK else S_BAD,
            if (WatchRelay.deviceConnected) "${WatchRelay.deviceName} connected"
            else WatchRelay.status)
        // An ack comes from the watch app itself, so it is the only positive
        // proof the app is running — and the round trip is how fast it is.
        rowApp.set(
            if (WatchRelay.appRunning) S_OK else S_WARN,
            when {
                !WatchRelay.appRunning -> "no ack — not open"
                WatchRelay.lastRoundTripMs >= 0 -> "acking, ${WatchRelay.lastRoundTripMs} ms"
                else -> "open"
            })
        rowNotif.set(
            when {
                Status.listenerBound -> S_OK
                notificationAccessGranted() -> S_WARN
                else -> S_BAD
            },
            when {
                Status.listenerBound -> "bound"
                notificationAccessGranted() -> "granted, not started"
                else -> "not granted"
            })

        val showing = if (Status.navActive) View.VISIBLE else View.GONE
        instrCard.visibility = showing
        instrLabel.visibility = showing
        // Navigating but nothing relayed yet — OsmAnd is still calculating, or
        // the first turn has not arrived. Em-dashes in three fields read as a
        // fault; saying so does not.
        if (Status.sentCount == 0 && Status.maneuver == Maneuver.UNKNOWN) {
            instrTurn.text = "waiting"
            instrStreet.text = "for the first turn from osmand"
            instrTrip.text = ""
        } else {
            instrTurn.text = "${Maneuver.name(Status.maneuver)}  ${Status.distance}".trim()
            instrStreet.text = Status.street.ifEmpty { "—" }
            instrTrip.text = listOf(Status.remaining, Status.eta)
                .filter { it.isNotEmpty() }.joinToString("  ·  ")
        }

        detail.text = buildString {
            appendLine("turns received  ${Status.turnsReceived}")
            appendLine("sent / acked    ${Status.sentCount} / ${Status.ackedCount}")
            appendLine("osmand notifs   ${Status.osmandNotifsSeen}")
            appendLine("last turn       ${Status.lastTurnType}")
            appendLine("last send       ${WatchRelay.lastSent}")
            appendLine("round trip      ${
                if (WatchRelay.lastRoundTripMs >= 0) "${WatchRelay.lastRoundTripMs} ms" else "-"
            }")
            if (WatchRelay.notReady.isNotEmpty()) {
                appendLine("send refused    ${WatchRelay.notReady}")
            }
            appendLine("last error      ${Status.lastError}")
            append("payload         ${Status.lastPayload}")
        }
    }

    // ------------------------------------------------------------- furniture

    private fun gap() = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        .apply { bottomMargin = dpi(14f) }

    private fun action(label: String, onClick: () -> Unit) =
        Ui.mono(this, label, 13.5f, ACCENT).apply {
            setPadding(0, dpi(13f), 0, dpi(13f))
            isClickable = true
            setOnClickListener { onClick() }
        }

    private fun toast(s: String) =
        android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show()

    /** name on the left, value on the right, with a dot that carries the state. */
    private class Row(a: MainActivity, name: String) {
        private val dot = Ui.mono(a, "●", 13f, S_OFF)
        private val value = Ui.mono(a, "", 11.5f, DIM).apply { gravity = Gravity.END }
        val view: LinearLayout = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, a.dpi(8f), 0, a.dpi(8f))
            addView(dot)
            addView(Ui.mono(a, name, 12.5f, TEXT).apply {
                setPadding(a.dpi(10f), 0, a.dpi(10f), 0)
            })
            addView(value, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }

        fun set(colour: String, text: String) {
            dot.setTextColor(col(colour))
            value.text = text
        }
    }

    private fun openOsmAnd() {
        for (p in listOf("net.osmand.plus", "net.osmand", "net.osmand.dev")) {
            val i = packageManager.getLaunchIntentForPackage(p)
            if (i != null) { startActivity(i); return }
        }
        toast("osmand not found")
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val perm = android.Manifest.permission.POST_NOTIFICATIONS
        if (checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(perm), 1)
        }
    }

    /**
     * Replacing the app leaves the listener enabled but unbound. requestRebind
     * is the documented cure; on this phone it does not take, which is why the
     * verdict above also tells you to toggle the grant.
     */
    private fun requestListenerRebind() {
        try {
            android.service.notification.NotificationListenerService.requestRebind(
                ComponentName(this, NavListener::class.java))
        } catch (e: Exception) {
            android.util.Log.w(WatchRelay.TAG, "requestRebind failed", e)
        }
    }

    /** The only reliable way to check: read the system's own list. */
    private fun notificationAccessGranted(): Boolean {
        val flat = Settings.Secure.getString(
            contentResolver, "enabled_notification_listeners") ?: return false
        if (TextUtils.isEmpty(flat)) return false
        return flat.split(":").any { it.contains(packageName) }
    }
}
