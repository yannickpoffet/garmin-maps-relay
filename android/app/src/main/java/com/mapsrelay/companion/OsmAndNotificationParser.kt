package com.mapsrelay.companion

import android.app.Notification
import android.service.notification.StatusBarNotification

/**
 * Reads the **cosmetic** fields — street and ETA — out of OsmAnd's ongoing
 * navigation notification.
 *
 * Deliberately a secondary source. The maneuver and the distance, which the
 * arrow and the haptics depend on, come from OsmAnd's AIDL callback in
 * [OsmAndLink] as typed integers. If everything below fails the watch still
 * shows the right arrow at the right distance, and only the street line and
 * ETA go blank.
 *
 * ## The actual layout
 *
 * Read off a live route on the test phone, not guessed — the v0.8 lesson:
 *
 *     android.title    = "371 m • Turn left and go"
 *     android.text     = null
 *     android.subText  = null
 *     android.infoText = null
 *     android.bigText  = "Turn left and go B 34 Hörnle 60 m
 *                         5.59 km • 23 min • 12:51 PM • 101 km/h"
 *
 * So everything lives in `title` and a two-line `bigText`, and `text`/`subText`
 * — where Google Maps put things — are both null.
 *
 * The useful property is that **bigText's first line opens with exactly the
 * instruction the title carries**, which the title then truncates ("Turn left
 * and go", with the street cut off). Strip that known prefix from line 1 and
 * drop the trailing leg distance, and what is left is the street name on its
 * own: "B 34 Hörnle". No keyword lists, no language assumptions — the prefix is
 * taken from the notification itself.
 *
 * Line 2 is `remaining • duration • ETA • speed`; the ETA is the only clock
 * time anywhere in the notification, so matching the first one is unambiguous.
 */
object OsmAndNotificationParser {

    /** Free, paid and nightly builds. */
    private val PACKAGES = setOf("net.osmand", "net.osmand.plus", "net.osmand.dev")

    /** OsmAnd's separator. U+2022, not an ASCII asterisk or a middot. */
    private const val BULLET = '•'

    data class NavInfo(
        val street: String,
        val eta: String,
        val arrived: Boolean,
    )

    fun isNavigation(sbn: StatusBarNotification?): Boolean {
        val s = sbn ?: return false
        return s.isOngoing && PACKAGES.contains(s.packageName)
    }

    fun parse(sbn: StatusBarNotification): NavInfo? {
        val ex = sbn.notification?.extras ?: return null

        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val bigText = ex.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim().orEmpty()
        if (title.isEmpty() && bigText.isEmpty()) return null

        val lines = bigText.lines().map { it.trim() }.filter { it.isNotEmpty() }

        // Which line is which is decided by content, not position. When there
        // is no upcoming maneuver OsmAnd drops the instruction line entirely
        // and bigText is just the summary, so taking line 1 on faith puts
        // "2.90 km - 12 min - 12:41 PM - 48 km/h" in the street field.
        // The summary is bullet-separated; an instruction line never is.
        val instructionLine = lines.firstOrNull { !it.contains(BULLET) }.orEmpty()

        return NavInfo(
            street = streetOf(title, instructionLine),
            eta = etaOf(lines + title),
            arrived = looksLikeArrival(title) || looksLikeArrival(instructionLine),
        )
    }

    /**
     * The street, with the instruction prefix and the trailing leg distance
     * removed.
     *
     * The arrow already says what the maneuver is, so repeating "Turn left and
     * go" on a 240px screen would only crowd out the one thing the watch
     * cannot show any other way.
     */
    private fun streetOf(title: String, instructionLine: String): String {
        // "371 m • Turn left and go" -> "Turn left and go", and a title with no
        // bullet at all (route start) is itself the instruction.
        val instruction = title.substringAfter(BULLET, title).trim()

        var s = instructionLine
        if (instruction.isNotEmpty() && s.startsWith(instruction)) {
            s = s.removePrefix(instruction).trim()
        }
        s = TRAILING_DISTANCE.replace(s, "").trim()

        // Fall back to the instruction, but never to the raw title: between
        // maneuvers that reads "0 m • ", which is worse than showing nothing.
        return s.ifEmpty { instruction }
    }

    /** A distance at the very end of the line: "60 m", "1.64 km", "500 ft". */
    private val TRAILING_DISTANCE =
        Regex("""\s*[\d.,]+\s*(m|km|ft|mi|yd)\s*$""", RegexOption.IGNORE_CASE)

    /**
     * Arrival time. The only clock time OsmAnd puts in the notification is the
     * ETA on bigText's second line, so the first match wins. The AM/PM suffix
     * is kept — a 12-hour phone would be ambiguous on the watch without it.
     */
    private fun etaOf(candidates: List<String>): String {
        val re = Regex("""\d{1,2}[:h]\d{2}(\s?[AaPp]\.?[Mm]\.?)?""")
        for (c in candidates) {
            val hit = re.find(c) ?: continue
            return hit.value.trim()
        }
        return ""
    }

    /**
     * Best-effort arrival detection. OsmAnd's TurnType has no arrival value, so
     * this is the one field still decided by keywords. Getting it wrong costs
     * the arrival buzz, nothing more.
     */
    private fun looksLikeArrival(s: String): Boolean {
        val t = s.lowercase()
        return listOf(
            "arrive", "arrived", "arrival", "destination",
            "arrivé", "arrivee", "ziel", "angekommen",
        ).any { t.contains(it) }
    }
}
