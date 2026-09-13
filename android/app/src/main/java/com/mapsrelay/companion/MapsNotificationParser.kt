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

    fun parse(context: Context, sbn: StatusBarNotification): NavInfo? {
        val n = sbn.notification ?: return null
        val ex = n.extras ?: return null

        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().trim()
        val text = ex.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty().trim()
        val sub = ex.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty().trim()

        if (title.isEmpty() && text.isEmpty()) return null

        // Maps puts both the distance and the instruction in the title,
        // separated by a middot: "750 m · At the roundabout, take the 2nd exit".
        // Fall back to treating the whole title as the instruction, because
        // some states ("towards Im Heimgarten") carry no distance at all.
        var distanceText = ""
        var instruction = title
        val dot = title.indexOf('·')
        if (dot > 0) {
            val head = title.substring(0, dot).trim()
            if (looksLikeDistance(head)) {
                distanceText = head
                instruction = title.substring(dot + 1).trim()
            }
        }

        // The ETA lives in whichever of text/subtext looks like a clock time.
        val eta = firstTimeLike(sub, text)

        return NavInfo(
            instruction = if (instruction.isEmpty()) text else instruction,
            distanceText = distanceText,
            meters = metersOf(distanceText),
            eta = eta,
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

    /** First candidate containing something clock-shaped, e.g. "Arrive 10:23". */
    private fun firstTimeLike(vararg candidates: String): String {
        val re = Regex("""\d{1,2}[:h]\d{2}""")
        for (c in candidates) {
            val hit = re.find(c) ?: continue
            return hit.value
        }
        return candidates.firstOrNull { it.isNotEmpty() }.orEmpty()
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
