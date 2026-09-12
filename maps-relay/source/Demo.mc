import Toybox.Lang;

//! Canned instructions for exercising the display without a phone attached.
//!
//! Worth keeping in the shipped app rather than hiding behind a build flag:
//! it is the only way to check arrow rendering and text fitting on the actual
//! watch, where the simulator's fonts and the real panel differ.
module Demo {

    function count() as Number {
        return 6;
    }

    //! Returns the same compact dictionary shape the phone companion sends,
    //! so this exercises the real parsing path rather than bypassing it.
    function sample(i as Number) as Dictionary {
        var n = i % count();
        switch (n) {
            case 0: return { "m" => Maneuver.RIGHT,        "d" => "200 m", "dm" => 200,
                             "s" => "Rue de Lausanne",     "e" => "12:34" };
            case 1: return { "m" => Maneuver.SLIGHT_LEFT,  "d" => "50 m",  "dm" => 50,
                             "s" => "Avenue de la Gare",   "e" => "12:36" };
            case 2: return { "m" => Maneuver.ROUNDABOUT,   "d" => "400 m", "dm" => 400,
                             "s" => "Route de Berne exit 3", "e" => "12:41" };
            case 3: return { "m" => Maneuver.UTURN,        "d" => "80 m",  "dm" => 80,
                             "s" => "Chemin des Fleurettes", "e" => "12:45" };
            case 4: return { "m" => Maneuver.STRAIGHT,     "d" => "1.2 km","dm" => 1200,
                             "s" => "Boulevard de Perolles very long name here",
                             "e" => "12:52" };
        }
        return { "m" => Maneuver.ARRIVE, "d" => "", "dm" => 0, "s" => "Destination", "e" => "12:58" };
    }
}
