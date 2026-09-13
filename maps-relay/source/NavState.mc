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
    public var distance as String = "";
    public var eta as String = "";

    //! Distance still to travel to the destination, e.g. "8.8 km". Distinct
    //! from `distance`, which is the distance to the next maneuver.
    public var remaining as String = "";

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

    //! Raw payload as received, kept for the M0 debug view.
    public var raw as String = "";

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
        raw = (data == null) ? "null" : data.toString();
        everReceived = true;
        _lastUpdate = now();

        if (data instanceof Lang.Dictionary) {
            var d = data as Dictionary;
            maneuver = asNumber(d.get("m"), Maneuver.UNKNOWN);
            street   = asString(d.get("s"));
            distance = asString(d.get("d"));
            eta      = asString(d.get("e"));
            remaining = asString(d.get("r"));
            afterManeuver = asNumber(d.get("m2"), Maneuver.UNKNOWN);
            afterMeters   = asNumber(d.get("dm2"), -1);
            afterStreet   = asString(d.get("s2"));
            angle         = asNumber(d.get("a1"), 0);
            afterAngle    = asNumber(d.get("a2"), 0);
            meters   = asNumber(d.get("dm"), -1);
            arrived  = (maneuver == Maneuver.ARRIVE);
            offRoute = (maneuver == Maneuver.OFF_ROUTE);
        } else {
            // Not a nav payload — show it as the street line so M0 can be
            // verified without the parser existing yet.
            maneuver = Maneuver.UNKNOWN;
            street = raw;
            distance = "";
            eta = "";
            remaining = "";
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
