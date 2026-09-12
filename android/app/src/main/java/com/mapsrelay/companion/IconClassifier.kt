package com.mapsrelay.companion

import android.graphics.Bitmap
import kotlin.math.abs

/**
 * Works out the turn direction from Google Maps' maneuver icon.
 *
 * Text keyword matching only works in languages someone thought to add, and
 * silently degrades to UNKNOWN in every other one. The icon carries the same
 * information in a form that does not care what language the phone is in, so
 * it is the better signal for the coarse left/right/straight decision.
 *
 * Deliberately simple: no template matching, no reference icons to keep in
 * step with Google's redesigns. It measures where the arrow's ink actually
 * sits, which survives restyling far better than pixel comparison would.
 */
object IconClassifier {

    /** Below this the ink distribution is too close to centred to call. */
    private const val SIDE_BIAS = 0.06f

    /**
     * @return one of Maneuver.LEFT / RIGHT / STRAIGHT / UTURN, or UNKNOWN when
     *         the icon is unreadable or too ambiguous to be worth trusting.
     */
    fun classify(bitmap: Bitmap?): Int {
        val bmp = bitmap ?: return Maneuver.UNKNOWN
        if (bmp.width < 8 || bmp.height < 8) return Maneuver.UNKNOWN

        val w = bmp.width
        val h = bmp.height
        val pixels = IntArray(w * h)
        try {
            bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        } catch (e: Exception) {
            return Maneuver.UNKNOWN
        }

        var mass = 0.0
        var sumX = 0.0
        // Mass in the top third, where an arrowhead ends up once it has turned.
        var topMass = 0.0
        var topSumX = 0.0

        for (y in 0 until h) {
            for (x in 0 until w) {
                val p = pixels[y * w + x]
                if (!isInk(p)) continue
                mass += 1.0
                sumX += x
                if (y < h / 3) {
                    topMass += 1.0
                    topSumX += x
                }
            }
        }

        // An icon that is nearly empty, or nearly solid, is not an arrow we
        // can read - bail rather than guess.
        val coverage = mass / (w * h)
        if (coverage < 0.02 || coverage > 0.9) return Maneuver.UNKNOWN

        // Normalised horizontal centre of the ink: 0 = hard left, 1 = hard right.
        val centre = (sumX / mass) / (w - 1)
        val bias = centre - 0.5f

        // The head is the strongest cue when there is enough of it up top.
        val headBias = if (topMass > mass * 0.12) {
            (topSumX / topMass) / (w - 1) - 0.5f
        } else {
            bias
        }

        val combined = (bias + headBias) / 2.0

        return when {
            combined < -SIDE_BIAS -> Maneuver.LEFT
            combined > SIDE_BIAS -> Maneuver.RIGHT
            // Centred: straight ahead, or a u-turn. Tell them apart by how
            // wide the ink spreads - a u-turn's loop is much wider than a
            // straight arrow's shaft.
            else -> if (spread(pixels, w, h) > 0.55) Maneuver.UTURN else Maneuver.STRAIGHT
        }
    }

    /** Fraction of the width between the leftmost and rightmost ink pixel. */
    private fun spread(pixels: IntArray, w: Int, h: Int): Double {
        var minX = w
        var maxX = -1
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (!isInk(pixels[y * w + x])) continue
                if (x < minX) minX = x
                if (x > maxX) maxX = x
            }
        }
        if (maxX < minX) return 0.0
        return (maxX - minX).toDouble() / w
    }

    /**
     * Maps draws these icons as a light glyph on transparency. Accept a pixel
     * as ink if it is meaningfully opaque; for fully opaque icons on a dark
     * background, fall back to brightness.
     */
    private fun isInk(p: Int): Boolean {
        val a = (p ushr 24) and 0xFF
        if (a < 32) return false
        if (a < 255) return true
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val b = p and 0xFF
        val lum = (r * 299 + g * 587 + b * 114) / 1000
        // Opaque icon: treat anything clearly off mid-grey as the glyph.
        return abs(lum - 128) > 48
    }
}
