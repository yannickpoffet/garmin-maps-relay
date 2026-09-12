package com.mapsrelay.companion

/**
 * Maneuver codes. These MUST stay in step with `maps-relay/source/Maneuver.mc`
 * on the watch — the two halves share no code, so this enum is the contract.
 */
object Maneuver {
    const val UNKNOWN = 0
    const val STRAIGHT = 1
    const val LEFT = 2
    const val RIGHT = 3
    const val SLIGHT_LEFT = 4
    const val SLIGHT_RIGHT = 5
    const val SHARP_LEFT = 6
    const val SHARP_RIGHT = 7
    const val UTURN = 8
    const val ROUNDABOUT = 9
    const val MERGE = 10
    const val FORK_LEFT = 11
    const val FORK_RIGHT = 12
    const val ARRIVE = 13

    /**
     * Best-effort maneuver from the instruction text.
     *
     * Google Maps ships the maneuver as an icon bitmap, not as a code, so
     * there is no clean way to read it — classifying the bitmap would be the
     * rigorous approach and is the obvious later improvement. Until then this
     * matches keywords in the languages most likely on this phone (en/fr/de).
     *
     * Returning UNKNOWN is safe and expected: the watch then shows the
     * instruction text alone, which is still perfectly usable.
     */
    fun fromText(textIn: String?): Int {
        val t = textIn?.lowercase() ?: return UNKNOWN

        // Order matters: "slight left" must be tested before plain "left".
        return when {
            has(t, "arrive", "arrivé", "arrivee", "destination", "ziel") -> ARRIVE
            has(t, "u-turn", "make a u", "demi-tour", "wenden") -> UTURN
            has(t, "roundabout", "rond-point", "giratoire", "kreisverkehr") -> ROUNDABOUT
            has(t, "merge", "insérez", "inserez", "einfädeln", "einfadeln") -> MERGE
            has(t, "slight left", "légèrement à gauche", "legerement a gauche") -> SLIGHT_LEFT
            has(t, "slight right", "légèrement à droite", "legerement a droite") -> SLIGHT_RIGHT
            has(t, "sharp left", "franchement à gauche", "scharf links") -> SHARP_LEFT
            has(t, "sharp right", "franchement à droite", "scharf rechts") -> SHARP_RIGHT
            has(t, "keep left", "fork left", "serrez à gauche", "links halten") -> FORK_LEFT
            has(t, "keep right", "fork right", "serrez à droite", "rechts halten") -> FORK_RIGHT
            has(t, "left", "gauche", "links") -> LEFT
            has(t, "right", "droite", "rechts") -> RIGHT
            has(t, "straight", "continue", "tout droit", "geradeaus") -> STRAIGHT
            else -> UNKNOWN
        }
    }

    private fun has(haystack: String, vararg needles: String) =
        needles.any { haystack.contains(it) }
}
