package com.mapsrelay.companion

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Status and setup screen.
 *
 * The previous version printed every counter it had as a monospace block and
 * left you to work out which line mattered. That is exactly backwards: nearly
 * always there is one thing wrong, and the screen's job is to name it.
 *
 * So the top of the screen is a single verdict — working, or not, and why —
 * computed from the pieces, with the button that fixes it right underneath.
 * The detail is still all there, below, for when the verdict is not enough.
 *
 * Styling follows OsmAnd, since that is the app this one lives beside: its
 * amber (taken from the colour its own notification carries, 0xFFFF8F00) on a
 * light grey ground, in cards.
 */
class MainActivity : Activity() {

    private val ui = Handler(Looper.getMainLooper())

    private lateinit var bannerCard: LinearLayout
    private lateinit var bannerTitle: TextView
    private lateinit var bannerDetail: TextView
    private lateinit var fixButton: Button

    private lateinit var connOsmand: StatusRow
    private lateinit var connWatch: StatusRow
    private lateinit var connNotif: StatusRow

    private lateinit var instructionLabel: TextView
    private lateinit var instructionCard: LinearLayout
    private lateinit var instrTurn: TextView
    private lateinit var instrStreet: TextView
    private lateinit var instrTrip: TextView

    private lateinit var detail: TextView

    private val refresh = object : Runnable {
        override fun run() {
            // Enabling us inside OsmAnd sends no signal back, and this screen
            // is where you are standing when you do it. bind() throttles
            // internally, so calling it once a second costs nothing.
            OsmAndLink.bind()
            render()
            ui.postDelayed(this, 1000)
        }
    }

    // ----------------------------------------------------------------- theme

    private val bg = Color.parseColor("#F2F2F5")
    private val card = Color.WHITE
    private val ink = Color.parseColor("#1A1A1A")
    private val inkSoft = Color.parseColor("#6E6E73")
    private val amber = Color.parseColor("#FF8F00")   // OsmAnd's own
    private val good = Color.parseColor("#2E7D32")
    private val bad = Color.parseColor("#C62828")
    private val idle = Color.parseColor("#78909C")

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
    ).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }

        root.addView(TextView(this).apply {
            text = "Maps Relay"
            setTextColor(ink)
            textSize = 26f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(4), 0, 0, dp(4))
        })
        root.addView(TextView(this).apply {
            text = "OsmAnd → Forerunner 745"
            setTextColor(inkSoft)
            textSize = 13f
            setPadding(dp(4), 0, 0, dp(16))
        })

        // ------------------------------------------------------- the verdict
        bannerCard = cardView()
        bannerTitle = TextView(this).apply {
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        }
        bannerDetail = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(0, dp(4), 0, 0)
        }
        fixButton = Button(this).apply {
            visibility = View.GONE
        }
        bannerCard.addView(bannerTitle)
        bannerCard.addView(bannerDetail)
        bannerCard.addView(fixButton, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            .apply { topMargin = dp(10) })
        root.addView(bannerCard, cardParams())

        // --------------------------------------------------- what's connected
        root.addView(sectionLabel("CONNECTIONS"))
        val conns = cardView().apply { setBackgroundColor(card) }
        connOsmand = StatusRow(this, "OsmAnd")
        connWatch = StatusRow(this, "Watch")
        connNotif = StatusRow(this, "Notification access")
        conns.addView(connOsmand.view)
        conns.addView(connNotif.view)
        conns.addView(connWatch.view)
        root.addView(conns, cardParams())

        // ---------------------------------------------- the live instruction
        instructionLabel = sectionLabel("CURRENT INSTRUCTION")
        root.addView(instructionLabel)
        instructionCard = cardView()
        instrTurn = TextView(this).apply {
            textSize = 22f; typeface = Typeface.DEFAULT_BOLD; setTextColor(ink)
        }
        instrStreet = TextView(this).apply {
            textSize = 15f; setTextColor(inkSoft); setPadding(0, dp(2), 0, 0)
        }
        instrTrip = TextView(this).apply {
            textSize = 13f; setTextColor(amber); setPadding(0, dp(8), 0, 0)
            typeface = Typeface.DEFAULT_BOLD
        }
        instructionCard.addView(instrTurn)
        instructionCard.addView(instrStreet)
        instructionCard.addView(instrTrip)
        root.addView(instructionCard, cardParams())

        // ------------------------------------------------------------ actions
        root.addView(sectionLabel("ACTIONS"))
        val actions = cardView()
        actions.addView(flatButton("Open Maps Relay on watch") {
            WatchRelay.openOnWatch(force = true); render()
        })
        actions.addView(flatButton("Send test message") {
            val ok = WatchRelay.send(
                mapOf("m" to Maneuver.RIGHT, "d" to "200 m", "dm" to 200,
                      "s" to "Test from phone", "e" to "--:--", "r" to "")
            )
            if (!ok) toast("not sent — ${WatchRelay.notReady.ifEmpty { "see status" }}")
            render()
        })
        actions.addView(flatButton("Reconnect watch and OsmAnd") {
            WatchRelay.start(applicationContext); OsmAndLink.bind(force = true); render()
        })
        root.addView(actions, cardParams())

        // ------------------------------------------------------------- detail
        root.addView(sectionLabel("DETAIL"))
        detail = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextColor(inkSoft)
        }
        val detailCard = cardView().apply { addView(detail) }
        root.addView(detailCard, cardParams())

        setContentView(ScrollView(this).apply {
            setBackgroundColor(bg)
            addView(root)
        })

        requestNotificationPermissionIfNeeded()
        requestListenerRebind()
        WatchRelay.onStatusChange = { ui.post { render() } }
        WatchRelay.start(applicationContext)
        OsmAndLink.onStatusChange = { ui.post { render() } }
        OsmAndLink.start(applicationContext)
    }

    override fun onResume() { super.onResume(); ui.post(refresh) }
    override fun onPause() { super.onPause(); ui.removeCallbacks(refresh) }

    // ------------------------------------------------------------- the verdict

    /** One problem, named, with the thing that fixes it. */
    private class Verdict(
        val colour: Int,
        val title: String,
        val detail: String,
        val fix: String? = null,
        val action: (() -> Unit)? = null,
    )

    /**
     * Order matters: the first thing that is wrong is the thing to report.
     * Anything further down the chain cannot work until it is fixed, so
     * listing them all at once would just be noise.
     */
    private fun verdict(): Verdict {
        if (OsmAndLink.status.startsWith("NOT ENABLED")) {
            return Verdict(bad, "OsmAnd is blocking us",
                "OsmAnd gates its API per app, and registers new ones disabled. " +
                "Enable Maps Relay in OsmAnd: Menu → Plugins.",
                "Open OsmAnd") { openOsmAnd() }
        }
        if (OsmAndLink.status.contains("not installed")) {
            return Verdict(bad, "OsmAnd not installed",
                "This relays OsmAnd's guidance; nothing works without it.")
        }
        if (!OsmAndLink.bound) {
            return Verdict(bad, "Not connected to OsmAnd", OsmAndLink.status,
                "Reconnect") { OsmAndLink.bind(force = true); render() }
        }
        if (WatchRelay.status.startsWith("init failed") ||
            WatchRelay.status.contains("no watch paired")) {
            return Verdict(bad, "No watch", WatchRelay.status)
        }
        if (Status.lastError.contains("FAILURE_DURING_TRANSFER")) {
            return Verdict(amber, "Watch app not open",
                "Garmin drops messages aimed at an app that is not running. " +
                "Open Maps Relay on the watch.",
                "Open on watch") { WatchRelay.openOnWatch(force = true); render() }
        }
        if (!Status.listenerBound) {
            // Two different faults wear the same face here, and they need
            // different fixes: never granted, versus granted and then left
            // unbound by Android after the app was replaced.
            return if (!notificationAccessGranted()) {
                Verdict(amber, "Working, but killable",
                    "Without notification access the relay cannot hold the " +
                    "foreground, so Android may stop it mid-route, and it " +
                    "will not wake by itself when you start navigating. " +
                    "Turns still reach the watch while this screen is open.",
                    "Grant access") {
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }
            } else {
                Verdict(amber, "Notification service not started",
                    "Access is granted but Android has not started the " +
                    "listener — it does this after the app is replaced. " +
                    "Turns still reach the watch.",
                    "Retry") { requestListenerRebind(); render() }
            }
        }
        if (!Status.navActive) {
            return Verdict(idle, "Ready", "Start navigating in OsmAnd.")
        }
        return Verdict(good, "Relaying",
            "${Status.sentCount} messages sent to the watch.")
    }

    private fun render() {
        val v = verdict()
        (bannerCard.background as GradientDrawable).setColor(v.colour)
        bannerTitle.text = v.title
        bannerDetail.text = v.detail
        if (v.fix != null && v.action != null) {
            fixButton.visibility = View.VISIBLE
            fixButton.text = v.fix
            fixButton.setOnClickListener { v.action.invoke() }
        } else {
            fixButton.visibility = View.GONE
        }

        connOsmand.set(
            if (OsmAndLink.bound && !OsmAndLink.status.startsWith("NOT ENABLED")) good else bad,
            OsmAndLink.status)
        connWatch.set(
            if (!WatchRelay.status.startsWith("init failed") &&
                !WatchRelay.status.contains("no watch")) good else bad,
            WatchRelay.status)
        connNotif.set(
            when {
                Status.listenerBound -> good
                notificationAccessGranted() -> amber
                else -> bad
            },
            when {
                Status.listenerBound -> "bound"
                notificationAccessGranted() -> "granted, waiting for Android"
                else -> "not granted"
            })

        val showing = if (Status.navActive) View.VISIBLE else View.GONE
        instructionCard.visibility = showing
        instructionLabel.visibility = showing
        instrTurn.text = "${Maneuver.name(Status.maneuver)}  ${Status.distance}".trim()
        instrStreet.text = Status.street.ifEmpty { "—" }
        instrTrip.text = listOf(Status.remaining, Status.eta)
            .filter { it.isNotEmpty() }.joinToString("  ·  ")

        detail.text = buildString {
            appendLine("turns received  ${Status.turnsReceived}")
            appendLine("relayed         ${Status.sentCount}")
            appendLine("osmand notifs   ${Status.osmandNotifsSeen}")
            appendLine("last turn       ${Status.lastTurnType}")
            appendLine("last send       ${WatchRelay.lastSent}")
            if (WatchRelay.notReady.isNotEmpty()) {
                appendLine("send refused    ${WatchRelay.notReady}")
            }
            appendLine("last error      ${Status.lastError}")
            append("payload         ${Status.lastPayload}")
        }
    }

    // ------------------------------------------------------------- furniture

    private fun cardView() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(14))
        background = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(card)
        }
    }

    private fun cardParams() = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        .apply { bottomMargin = dp(14) }

    private fun sectionLabel(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(inkSoft)
        textSize = 11f
        typeface = Typeface.DEFAULT_BOLD
        letterSpacing = 0.08f
        setPadding(dp(4), 0, 0, dp(6))
    }

    private fun flatButton(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        setTextColor(amber)
        textSize = 15f
        typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(12), 0, dp(12))
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun toast(s: String) =
        android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show()

    /** A row with a coloured dot: green when that link is up, red when not. */
    private class StatusRow(a: MainActivity, label: String) {
        val view: LinearLayout = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, a.dp(7), 0, a.dp(7))
        }
        private val dot = TextView(a).apply { textSize = 16f; text = "●" }
        private val name = TextView(a).apply {
            text = label; textSize = 15f; setTextColor(a.ink)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(a.dp(10), 0, a.dp(10), 0)
        }
        private val value = TextView(a).apply {
            textSize = 13f; setTextColor(a.inkSoft); gravity = Gravity.END
        }
        init {
            view.addView(dot)
            view.addView(name)
            view.addView(value, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }

        fun set(colour: Int, text: String) {
            dot.setTextColor(colour)
            value.text = text
        }
    }

    private fun openOsmAnd() {
        for (p in listOf("net.osmand.plus", "net.osmand", "net.osmand.dev")) {
            val i = packageManager.getLaunchIntentForPackage(p)
            if (i != null) { startActivity(i); return }
        }
        toast("OsmAnd not found")
    }

    /**
     * Android 13+ needs this before the relay can show its ongoing
     * notification, and without that the service cannot go foreground.
     */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val perm = android.Manifest.permission.POST_NOTIFICATIONS
        if (checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(perm), 1)
        }
    }

    /**
     * Replacing the app leaves the listener enabled but unbound — it keeps the
     * grant and never starts. requestRebind is the documented cure and costs
     * nothing when it is already bound.
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
