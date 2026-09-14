import Toybox.Lang;
import Toybox.System;
import Toybox.Time;

//! Holds the most recent instruction from the phone, and — just as important —
//! how long ago it arrived.
//!
//! Staleness is a safety property here, not a nicety. If the phone link drops
//! mid-route the watch would otherwise keep showing a turn that was passed
//! minutes ago, which is worse than showing nothing at all.
class NavState {

    public var maneuver as Number = Maneuver.UNKNOWN;
    public var street as String = "";
    public var eta as String = "";

    //! Derived from `meters`, not sent. The phone used to transmit the
    //! rendered string alongside the number it was rendered from, which is
    //! about a dozen bytes of pure duplication on a link where the payload
    //! size is what the update rate is made of.
    public var distance as String = "";

    //! Distance still to travel to the destination, e.g. "8.8 km". Distinct
    //! from `distance`, which is the distance to the next maneuver.
    public var remaining as String = "";

    //! Metres still to travel, from which `remaining` is rendered.
    public var remainingMeters as Number = -1;

    //! The maneuver *after* the next one, and how far away it is in metres.
    //! Google Maps never offered this; OsmAnd reports it in the same breath as
    //! the next turn, and knowing a second turn follows immediately is exactly
    //! what a single arrow cannot tell you.
    public var afterManeuver as Number = Maneuver.UNKNOWN;
    public var afterMeters as Number = -1;
    public var afterStreet as String = "";

    //! Turn angles in degrees, used to sketch the road ahead on page 2.
    public var angle as Number = 0;
    public var afterAngle as Number = 0;

    //! Distance to the maneuver in metres, or -1 when unknown. Sent
    //! separately from the display string because the alert thresholds need a
    //! number, and re-parsing a localised "0.4 km" on the watch would be both
    //! fragile and pointless when the phone already knows the value.
    public var meters as Number = -1;

    //! True when this is the final "you have arrived" instruction.
    public var arrived as Boolean = false;

    //! True when the route has been left. Distinct from staleness: the link is
    //! fine and the instruction is current, it just says you are not on the
    //! route any more.
    public var offRoute as Boolean = false;

    //! Raw payload as received, for the debug view.
    //!
    //! Held as the object, not as text. Stringifying it on arrival meant
    //! building a long string out of eleven fields on every message, about
    //! once a second, on a watch with a tight memory budget — all of it thrown
    //! away unread unless the debug view happened to be open.
    private var _raw as Object? = null;

    //! True once any message has ever arrived.
    public var everReceived as Boolean = false;

    private var _lastUpdate as Number = 0;

    //! Seconds after which the display is considered untrustworthy.
    public static const STALE_AFTER_SEC = 10;

    function initialize() {
    }

    //! Apply a payload from the phone. Accepts the compact dictionary the
    //! companion sends, and degrades to showing a bare string unchanged, which
    //! is what makes the M0 "send any text" test possible.
    function apply(data as Object?) as Void {
        _raw = data;
        everReceived = true;
        _lastUpdate = now();

        if (data instanceof Lang.Dictionary) {
            var d = data as Dictionary;

            // Absent means unchanged, not empty. The phone sends only the
            // fields that moved, because on this link the payload size *is*
            // the update rate: a full one is 117 bytes of which two values
            // are actually new, and a transfer costs about three seconds.
            if (d.hasKey("m"))   { maneuver      = asNumber(d.get("m"), maneuver); }
            if (d.hasKey("s"))   { street        = asString(d.get("s")); }
            if (d.hasKey("e"))   { eta           = asString(d.get("e")); }
            if (d.hasKey("m2"))  { afterManeuver = asNumber(d.get("m2"), afterManeuver); }
            if (d.hasKey("dm2")) { afterMeters   = asNumber(d.get("dm2"), afterMeters); }
            if (d.hasKey("s2"))  { afterStreet   = asString(d.get("s2")); }
            if (d.hasKey("a1"))  { angle         = asNumber(d.get("a1"), angle); }
            if (d.hasKey("a2"))  { afterAngle    = asNumber(d.get("a2"), afterAngle); }
            if (d.hasKey("dm"))  { meters        = asNumber(d.get("dm"), meters); }
            if (d.hasKey("rm"))  { remainingMeters = asNumber(d.get("rm"), remainingMeters); }

            distance  = fmt(meters);
            remaining = fmt(remainingMeters);
            arrived  = (maneuver == Maneuver.ARRIVE);
            offRoute = (maneuver == Maneuver.OFF_ROUTE);
        } else {
            // Not a nav payload — show it as the street line so M0 can be
            // verified without the parser existing yet.
            maneuver = Maneuver.UNKNOWN;
            street = rawText();
            distance = "";
            eta = "";
            remaining = "";
            remainingMeters = -1;
            afterManeuver = Maneuver.UNKNOWN;
            afterMeters = -1;
            afterStreet = "";
            angle = 0;
            afterAngle = 0;
            meters = -1;
            arrived = false;
            offRoute = false;
        }
    }

    //! Metres as the watch shows them: "348 m" below a kilometre, "2.4 km"
    //! above it. Done here so the phone never has to send the rendered form
    //! beside the number it was rendered from.
    static function fmt(m as Number) as String {
        if (m < 0) { return ""; }
        if (m < 1000) { return m.toString() + " m"; }
        return (m / 100 / 10.0).format("%.1f") + " km";
    }

    //! The last payload as text. Only the debug view asks, so this is the
    //! one place that pays for the conversion.
    function rawText() as String {
        return (_raw == null) ? "null" : _raw.toString();
    }

    //! Identifies the current turn, so alerts can re-arm when it changes.
    function key() as String {
        return maneuver.toString() + "|" + street;
    }

    function ageSec() as Number {
        if (!everReceived) { return -1; }
        var a = now() - _lastUpdate;
        return (a < 0) ? 0 : a;
    }

    function isStale() as Boolean {
        return everReceived && (ageSec() > STALE_AFTER_SEC);
    }

    private function now() as Number {
        return Time.now().value();
    }

    private function asNumber(v as Object?, fallback as Number) as Number {
        if (v == null) { return fallback; }
        if (v instanceof Lang.Number) { return v as Number; }
        if (v instanceof Lang.String) {
            var n = (v as String).toNumber();
            return (n == null) ? fallback : n;
        }
        return fallback;
    }

    private function asString(v as Object?) as String {
        if (v == null) { return ""; }
        return v.toString();
    }
}
