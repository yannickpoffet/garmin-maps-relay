package com.mapsrelay.companion

import android.app.Notification
import android.service.notification.StatusBarNotification

/**
 * Reads the **cosmetic** fields — street and ETA — out of OsmAnd's ongoing
 * navigation notification.
 *
 * This is deliberately a secondary source. The maneuver and the distance, which
 * are what the arrow and the haptics depend on, come from OsmAnd's AIDL
 * callback in [OsmAndLink] as typed integers. If everything below fails, the
 * watch still shows the right arrow at the right distance and only the street
 * line and ETA go blank. That is the whole reason the split exists: the
 * fragile half can no longer take the useful half down with it.
 *
 * Nothing here assumes a field layout. `dumpsys notification` on the test phone
 * reports OsmAnd's lifetime counts as title=361, text=2, subText=0 — so unlike
 * Google Maps it puts essentially everything in the title, and the shape of
 * that string has not been pinned down on a live route yet. Guessing a layout
 * is exactly what cost v0.7 and v0.8 a release each, so every field is scanned
 * and each value is taken from whichever one actually looks like it.
 */
object OsmAndNotificationParser {

    /** Free, paid and nightly builds. */
    private val PACKAGES = setOf("net.osmand", "net.osmand.plus", "net.osmand.dev")

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

        val fields = listOfNotNull(
            ex.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim(),
            ex.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim(),
            ex.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim(),
            ex.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim(),
        ).filter { it.isNotEmpty() }

        if (fields.isEmpty()) return null

        return NavInfo(
            street = streetOf(fields),
            eta = etaOf(fields),
            arrived = fields.any { looksLikeArrival(it) },
        )
    }

    /**
     * The most instruction-looking field: longest, ignoring anything that is
     * only a distance or only a clock time. Crude, but it cannot be wrong in a
     * way that matters — worst case the watch shows a slightly odd street line.
     */
    private fun streetOf(fields: List<String>): String =
        fields.filterNot { isJustDistance(it) || isJustTime(it) }
            .maxByOrNull { it.length }
            .orEmpty()

    /**
     * Arrival time from any field. Keeps an AM/PM suffix where there is one,
     * since dropping it would make a 12-hour phone ambiguous on the watch.
     */
    private fun etaOf(fields: List<String>): String {
        val re = Regex("""\d{1,2}[:h]\d{2}(\s?[AaPp]\.?[Mm]\.?)?""")
        for (f in fields) {
            val hit = re.find(f) ?: continue
            return hit.value.trim()
        }
        return ""
    }

    private fun isJustDistance(s: String) =
        Regex("""^[\d.,]+\s*(m|km|ft|mi|yd)$""", RegexOption.IGNORE_CASE).matches(s)

    private fun isJustTime(s: String) =
        Regex("""^\d{1,2}[:h]\d{2}(\s?[AaPp]\.?[Mm]\.?)?$""").matches(s)

    /**
     * Best-effort arrival detection, in the languages likely on this phone.
     * OsmAnd's TurnType has no arrival value, so there is no typed signal for
     * it — this is the one field that is still a keyword guess. Getting it
     * wrong costs the arrival buzz, nothing more.
     */
    private fun looksLikeArrival(s: String): Boolean {
        val t = s.lowercase()
        return listOf(
            "arrive", "arrived", "arrival", "destination",
            "arrivé", "arrivee", "destination",
            "ziel", "angekommen",
        ).any { t.contains(it) }
    }
}
