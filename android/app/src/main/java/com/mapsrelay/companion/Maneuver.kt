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
    const val OFF_ROUTE = 14

    /**
     * OsmAnd's `net.osmand.router.TurnType` constants.
     *
     * Repeated here rather than imported: they live in OsmAnd's routing core,
     * which is not part of the AIDL contract we vendor. They are stable — the
     * values are persisted in OsmAnd's own route data — but if a turn ever
     * renders as the wrong arrow, check this list against upstream first.
     */
    private const val T_C = 1        // continue straight
    private const val T_TL = 2       // turn left
    private const val T_TSLL = 3     // turn slightly left
    private const val T_TSHL = 4     // turn sharply left
    private const val T_TR = 5       // turn right
    private const val T_TSLR = 6     // turn slightly right
    private const val T_TSHR = 7     // turn sharply right
    private const val T_KL = 8       // keep left
    private const val T_KR = 9       // keep right
    private const val T_TU = 10      // U-turn
    private const val T_TRU = 11     // right-hand U-turn
    private const val T_OFFR = 12    // off route
    private const val T_RNDB = 13    // roundabout
    private const val T_RNLB = 14    // roundabout, left-hand traffic

    /**
     * Map an OsmAnd turn type onto our code.
     *
     * This is the whole point of moving to OsmAnd: the maneuver arrives as an
     * integer that means something, rather than being guessed from keyword
     * matching on localised prose and then, failing that, from the pixels of an
     * icon bitmap. Both of those are gone.
     *
     * `MERGE` has no TurnType and is unreachable; the code and its arrow stay
     * because the offline preview still exercises them. `ARRIVE` is not a
     * TurnType either — it is inferred from the distance running out.
     */
    fun fromTurnType(turnType: Int): Int = when (turnType) {
        T_C -> STRAIGHT
        T_TL -> LEFT
        T_TSLL -> SLIGHT_LEFT
        T_TSHL -> SHARP_LEFT
        T_TR -> RIGHT
        T_TSLR -> SLIGHT_RIGHT
        T_TSHR -> SHARP_RIGHT
        T_KL -> FORK_LEFT
        T_KR -> FORK_RIGHT
        T_TU, T_TRU -> UTURN
        T_OFFR -> OFF_ROUTE
        T_RNDB, T_RNLB -> ROUNDABOUT
        else -> UNKNOWN
    }

    /**
     * The same mapping again, from the XML spelling `TurnType.toXmlString()`
     * uses inside the turn-info bundle rather than the integer constant.
     *
     * Roundabouts are matched by prefix: OsmAnd appends the exit number there,
     * so the value arrives as "RNDB3" rather than a bare "RNDB".
     */
    fun fromTurnXml(xml: String?): Int {
        if (xml == null) return UNKNOWN
        if (xml.startsWith("RNDB") || xml.startsWith("RNLB")) return ROUNDABOUT
        return when (xml) {
            "C" -> STRAIGHT
            "TL" -> LEFT
            "TSLL" -> SLIGHT_LEFT
            "TSHL" -> SHARP_LEFT
            "TR" -> RIGHT
            "TSLR" -> SLIGHT_RIGHT
            "TSHR" -> SHARP_RIGHT
            "KL" -> FORK_LEFT
            "KR" -> FORK_RIGHT
            "TU", "TRU" -> UTURN
            "OFFR" -> OFF_ROUTE
            else -> UNKNOWN
        }
    }
}
