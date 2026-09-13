import Toybox.Attention;
import Toybox.Lang;
import Toybox.System;

//! Haptic warning as a turn approaches.
//!
//! This is what makes the app usable while actually moving: you feel the turn
//! coming instead of having to raise your wrist and read. Without it the watch
//! is only useful when you already know to look at it, which rather defeats
//! the point.
//!
//! Fires at most once per threshold per maneuver, so a slow approach to a
//! junction does not buzz repeatedly.
class Alerts {

    //! Distances (metres) at which to warn, far to near.
    private const THRESHOLDS = [200, 50] as Array<Number>;

    private var _armed as Array<Boolean> = [true, true] as Array<Boolean>;
    private var _lastKey as String = "";

    //! Off route fires once on entry, not on every repaint while it persists.
    //! The key does not change for as long as you stay off route, so this
    //! flag is what stops it repeating; it clears when the key does.
    private var _offRouteFired as Boolean = false;

    function initialize() {
    }

    //! Call on every state update. `key` identifies the current maneuver
    //! (maneuver code + street); when it changes the thresholds re-arm for the
    //! new turn.
    function update(key as String, meters as Number, isArrival as Boolean,
                    isOffRoute as Boolean) as Void {
        if (!key.equals(_lastKey)) {
            _lastKey = key;
            for (var i = 0; i < _armed.size(); i++) {
                _armed[i] = true;
            }
            _offRouteFired = false;
        }

        // Before the distance thresholds: leaving the route is the one event
        // worth feeling immediately, and `meters` is meaningless here anyway.
        // Four pulses, distinct from the thresholds (1, 2) and arrival (3).
        if (isOffRoute) {
            if (!_offRouteFired) {
                _offRouteFired = true;
                fire(4);
            }
            return;
        }

        if (isArrival) {
            fire(3);
            // Prevent re-firing on subsequent updates for the same arrival.
            for (var i = 0; i < _armed.size(); i++) {
                _armed[i] = false;
            }
            return;
        }

        if (meters < 0) { return; }

        for (var i = 0; i < THRESHOLDS.size(); i++) {
            if (_armed[i] && meters <= THRESHOLDS[i]) {
                _armed[i] = false;
                fire(i + 1);      // nearer threshold -> more pulses
            }
        }
    }

    //! Vibrate, and light the screen so the instruction is actually readable
    //! at the moment it matters. Both are guarded: neither is present on every
    //! device, and the user can disable vibration system-wide.
    private function fire(pulses as Number) as Void {
        var settings = System.getDeviceSettings();

        if ((Attention has :vibrate) && settings.vibrateOn) {
            var pattern = [] as Array<Attention.VibeProfile>;
            for (var i = 0; i < pulses; i++) {
                pattern.add(new Attention.VibeProfile(75, 250));
                pattern.add(new Attention.VibeProfile(0, 120));
            }
            Attention.vibrate(pattern);
        }

        if (Attention has :backlight) {
            // Can throw if the system is already mid-backlight-request.
            try {
                Attention.backlight(true);
            } catch (e) {
            }
        }
    }
}
