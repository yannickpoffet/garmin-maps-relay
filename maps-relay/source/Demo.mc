import Toybox.Lang;

//! Canned instructions for exercising the display without a phone.
//!
//! Kept in the shipped app rather than hidden behind a build flag: with the
//! Connect IQ simulator's device panel not rendering in this environment,
//! cycling these on the watch is the only way to check the arrows and the
//! haptics against the real panel and the real fonts.
//!
//! Covers every drawn maneuver, and deliberately includes an over-long street
//! name and an unknown code so the truncation and text-fallback paths get
//! exercised too.
module Demo {

    function count() as Number {
        return 14;
    }

    //! Same compact dictionary the phone sends, so this drives the real
    //! parsing path rather than bypassing it.
    function sample(i as Number) as Dictionary {
        var n = i % count();
        switch (n) {
            case 0:  return { "m" => Maneuver.RIGHT,        "d" => "200 m",  "dm" => 200,
                              "s" => "Rue de Lausanne",      "e" => "12:34", "r" => "8.8 km" };
            case 1:  return { "m" => Maneuver.LEFT,         "d" => "80 m",   "dm" => 80,
                              "s" => "Avenue de la Gare",    "e" => "12:36", "r" => "8.6 km" };
            case 2:  return { "m" => Maneuver.SLIGHT_LEFT,  "d" => "400 m",  "dm" => 400,
                              "s" => "Route de Berne",       "e" => "12:41", "r" => "7.9 km" };
            case 3:  return { "m" => Maneuver.SLIGHT_RIGHT, "d" => "1.2 km", "dm" => 1200,
                              "s" => "Boulevard de Perolles very long name",
                              "e" => "12:45", "r" => "6.4 km" };
            case 4:  return { "m" => Maneuver.SHARP_LEFT,   "d" => "50 m",   "dm" => 50,
                              "s" => "Chemin des Fleurettes","e" => "12:47", "r" => "5.1 km" };
            case 5:  return { "m" => Maneuver.SHARP_RIGHT,  "d" => "30 m",   "dm" => 30,
                              "s" => "Rue du Pont",          "e" => "12:48", "r" => "4.8 km" };
            case 6:  return { "m" => Maneuver.STRAIGHT,     "d" => "2.4 km", "dm" => 2400,
                              "s" => "Autoroute A12",        "e" => "12:55", "r" => "3.2 km" };
            case 7:  return { "m" => Maneuver.UTURN,        "d" => "90 m",   "dm" => 90,
                              "s" => "Route Cantonale",      "e" => "12:58", "r" => "2.6 km" };
            case 8:  return { "m" => Maneuver.ROUNDABOUT,   "d" => "300 m",  "dm" => 300,
                              "s" => "Giratoire, 3e sortie", "e" => "13:02", "r" => "1.9 km" };
            case 9:  return { "m" => Maneuver.MERGE,        "d" => "600 m",  "dm" => 600,
                              "s" => "A1 direction Bern",    "e" => "13:09", "r" => "1.4 km" };
            case 10: return { "m" => Maneuver.FORK_LEFT,    "d" => "700 m",  "dm" => 700,
                              "s" => "Sortie 12",            "e" => "13:12", "r" => "980 m" };
            // No drawing for UNKNOWN: checks that the text alone still reads.
            case 11: return { "m" => Maneuver.UNKNOWN,      "d" => "150 m",  "dm" => 150,
                              "s" => "Unrecognised maneuver","e" => "13:15", "r" => "700 m" };
            // dm = -1 deliberately: off route has no distance to a turn, and
            // the thresholds in Alerts must not fire on it.
            case 12: return { "m" => Maneuver.OFF_ROUTE,    "d" => "",       "dm" => -1,
                              "s" => "Off route",            "e" => "", "r" => "" };
        }
        return { "m" => Maneuver.ARRIVE, "d" => "", "dm" => 0,
                 "s" => "Destination", "e" => "13:20", "r" => "0 m" };
    }
}
