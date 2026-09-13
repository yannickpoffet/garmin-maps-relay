package com.mapsrelay.companion

import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.service.notification.StatusBarNotification

/**
 * Reads the Google Maps navigation notification from its **extras**.
 *
 * GMapsParser takes a different route: it inflates the notification's
 * RemoteViews and walks the view hierarchy. That worked when apps shipped
 * custom notification layouts, but Maps now uses the standard template, so
 * `contentView` is null, inflation throws, and the exception is swallowed into
 * a Timber log with no tree planted — which is exactly the silent nothing we
 * were seeing.
 *
 * EXTRA_TITLE and friends are documented, stable API. The trade-off is that
 * the maneuver arrives as an icon rather than structured data, which
 * IconClassifier already handles.
 */
object MapsNotificationParser {

    const val MAPS_PACKAGE = "com.google.android.apps.maps"

    data class NavInfo(
        val instruction: String,
        val distanceText: String,
        val meters: Double,
        val eta: String,
        val icon: Bitmap?,
    )

    fun isNavigation(sbn: StatusBarNotification?): Boolean {
        val s = sbn ?: return false
        return s.isOngoing && s.packageName.contains("apps.maps")
    }

    /**
     * Field layout, read off a live route on Android 14:
     *
     *     android.title   = "0 m"                            <- distance only
     *     android.text    = "toward Im Holeeletten"          <- instruction
     *     android.subText = "8 min . 2.8 km . 10:04 AM ETA"  <- duration/remaining/ETA
     *
     * The notification *renders* as "0 m - toward Im Holeeletten", which is
     * the system combining title and text; that combined string is not stored
     * in any single extra. Assuming it was cost a release.
     *
     * At the start of a route, before a maneuver exists, the title carries the
     * instruction instead ("Head towards Im Heimgarten") and there is no
     * distance at all, so neither field can be relied on positionally.
     */
    fun parse(context: Context, sbn: StatusBarNotification): NavInfo? {
        val n = sbn.notification ?: return null
        val ex = n.extras ?: return null

        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().trim()
        val text = ex.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty().trim()
        val sub = ex.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty().trim()

        if (title.isEmpty() && text.isEmpty()) return null

        var distanceText = ""
        var instruction: String

        val dot = title.indexOf('\u00b7')
        when {
            // Usual case mid-route: title is purely the distance.
            looksLikeDistance(title) -> {
                distanceText = title
                instruction = text.ifEmpty { title }
            }
            // Older/combined form, kept because it costs nothing to support.
            dot > 0 && looksLikeDistance(title.substring(0, dot).trim()) -> {
                distanceText = title.substring(0, dot).trim()
                instruction = title.substring(dot + 1).trim()
            }
            // Route start: the title is the instruction and there is no distance.
            else -> instruction = title.ifEmpty { text }
        }

        if (instruction.isEmpty()) instruction = text
        if (instruction.isEmpty()) return null

        return NavInfo(
            instruction = instruction,
            distanceText = distanceText,
            meters = metersOf(distanceText),
            eta = etaOf(sub, text),
            icon = largeIcon(context, n),
        )
    }

    private fun looksLikeDistance(s: String): Boolean =
        Regex("""^[\d.,]+\s*(m|km|ft|mi|yd)\b""", RegexOption.IGNORE_CASE).containsMatchIn(s)

    /** @return distance in metres, or -1 when it cannot be read. */
    fun metersOf(s: String): Double {
        val m = Regex("""([\d.,]+)\s*(m|km|ft|mi|yd)""", RegexOption.IGNORE_CASE)
            .find(s) ?: return -1.0
        // Both "1.2" and "1,2" appear depending on locale.
        val value = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return -1.0
        return when (m.groupValues[2].lowercase()) {
            "km" -> value * 1000.0
            "m" -> value
            "mi" -> value * 1609.34
            "yd" -> value * 0.9144
            "ft" -> value * 0.3048
            else -> -1.0
        }
    }

    /**
     * Arrival time out of e.g. "8 min . 2.8 km . 10:04 AM ETA" or "Arrive 10:23".
     * Keeps the AM/PM suffix where there is one, since dropping it would make
     * a 12-hour phone ambiguous on the watch.
     */
    private fun etaOf(vararg candidates: String): String {
        val re = Regex("""\d{1,2}[:h]\d{2}(\s?[AaPp]\.?[Mm]\.?)?""")
        for (c in candidates) {
            val hit = re.find(c) ?: continue
            return hit.value.trim()
        }
        return ""
    }

    /**
     * The maneuver arrow. Maps puts it in the large icon; the small icon is
     * the Maps logo and useless for direction.
     */
    private fun largeIcon(context: Context, n: Notification): Bitmap? {
        return try {
            val icon = n.getLargeIcon() ?: return null
            toBitmap(icon.loadDrawable(context))
        } catch (e: Exception) {
            null
        }
    }

    private fun toBitmap(d: Drawable?): Bitmap? {
        val drawable = d ?: return null
        if (drawable is BitmapDrawable) return drawable.bitmap
        val w = drawable.intrinsicWidth.coerceAtLeast(1)
        val h = drawable.intrinsicHeight.coerceAtLeast(1)
        if (w > 512 || h > 512) return null
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bmp
    }
}
