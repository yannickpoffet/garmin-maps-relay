import Toybox.Graphics;
import Toybox.Lang;
import Toybox.Math;
import Toybox.WatchUi;

//! Page 2: a sketch of the road ahead.
//!
//! Not a map. The fr745 has no WatchUi.MapView, and OsmAnd's API exposes no
//! route geometry at all — `getActiveGpx` returns GPX files, not the route
//! being navigated. What it does give is two upcoming maneuvers with a
//! distance and a turn angle each, and that is enough to draw the *shape* of
//! what is coming even though the true geometry is unavailable.
//!
//! So this is dead reckoning: start at the bottom, run a leg the length of the
//! distance to the next turn, bend by that turn's angle, run the second leg,
//! bend again. Leg lengths are compressed so a 3 km leg and a 40 m one can
//! share a 240px screen, which means the picture is schematic — correct in
//! order and direction, deliberately not to scale.
//!
//! The thing it shows that page 1 cannot: whether a second turn arrives
//! immediately after the first. A lone arrow saying "left" reads the same
//! whether the next turn is 40 m or 4 km later.
class AheadView {

    //! Draw the whole page.
    function draw(dc as Graphics.Dc, state as NavState, w as Number, h as Number) as Void {
        var stale = state.isStale();
        var fg = stale ? Graphics.COLOR_DK_GRAY : Graphics.COLOR_WHITE;
        var accent = stale ? Graphics.COLOR_DK_GRAY : Graphics.COLOR_GREEN;
        var dim = stale ? Graphics.COLOR_DK_GRAY : Graphics.COLOR_LT_GRAY;

        var cx = w / 2;

        dc.setColor(dim, Graphics.COLOR_TRANSPARENT);
        dc.drawText(cx, (h * 0.04).toNumber(), Graphics.FONT_XTINY, "road ahead",
                    Graphics.TEXT_JUSTIFY_CENTER);

        if (state.meters < 0) {
            dc.setColor(dim, Graphics.COLOR_TRANSPARENT);
            dc.drawText(cx, h / 2, Graphics.FONT_SMALL, "no route",
                        Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
            return;
        }

        // Legs, in metres. The second is the gap between the two turns, not
        // its distance from here, so the drawing spaces them the way they will
        // actually be driven.
        var leg1 = state.meters;
        var gap = (state.afterMeters > state.meters)
                    ? (state.afterMeters - state.meters) : -1;

        var t = 6;                       // stroke width

        // Build the path in arbitrary units first, then scale it to fit. Fixed
        // lengths cannot work here: two 60-degree turns with a long second leg
        // run clean off a 240px round screen, which is exactly what the first
        // cut of this did.
        var u1 = legLength(leg1);
        var u2 = (gap < 0) ? 0.0 : legLength(gap);

        var h1 = -90.0;
        var h2 = h1 + clampAngle(state.angle);

        var x1 = u1 * Math.cos(h1 * Math.PI / 180.0);
        var y1 = u1 * Math.sin(h1 * Math.PI / 180.0);
        var x2 = x1 + u2 * Math.cos(h2 * Math.PI / 180.0);
        var y2 = y1 + u2 * Math.sin(h2 * Math.PI / 180.0);

        var minX = min3(0.0, x1, (u2 > 0) ? x2 : x1);
        var maxX = max3(0.0, x1, (u2 > 0) ? x2 : x1);
        var minY = min3(0.0, y1, (u2 > 0) ? y2 : y1);
        var maxY = max3(0.0, y1, (u2 > 0) ? y2 : y1);

        // Target box: the upper part of the screen, leaving the lower third
        // for the two text lines.
        var boxW = w * 0.66;
        var boxH = h * 0.40;
        var bw = maxX - minX;
        var bh = maxY - minY;
        var sx = (bw < 0.001) ? 1000.0 : boxW / bw;
        var sy = (bh < 0.001) ? 1000.0 : boxH / bh;
        var k = (sx < sy) ? sx : sy;

        var tcx = w / 2.0;
        var tcy = h * 0.38;
        var ox = tcx - ((minX + maxX) / 2.0) * k;
        var oy = tcy - ((minY + maxY) / 2.0) * k;

        var px0 = ox;
        var py0 = oy;
        var px1 = ox + x1 * k;
        var py1 = oy + y1 * k;

        dc.setColor(accent, Graphics.COLOR_TRANSPARENT);
        Maneuver.seg(dc, px0, py0, px1, py1, t);

        if (u2 > 0) {
            var px2 = ox + x2 * k;
            var py2 = oy + y2 * k;
            dc.setColor(dim, Graphics.COLOR_TRANSPARENT);
            Maneuver.seg(dc, px1, py1, px2, py2, t);
            // Vertices as dots, not arrows: a full maneuver glyph at the bend
            // sits on top of both legs and the picture stops reading as a path.
            // The bend itself already says which way you turn.
            dc.fillCircle(px2.toNumber(), py2.toNumber(), 6);
        }

        dc.setColor(fg, Graphics.COLOR_TRANSPARENT);
        dc.fillCircle(px0.toNumber(), py0.toNumber(), 5);
        dc.setColor(accent, Graphics.COLOR_TRANSPARENT);
        dc.fillCircle(px1.toNumber(), py1.toNumber(), 7);

        // Text below the sketch, never across it.
        dc.setColor(fg, Graphics.COLOR_TRANSPARENT);
        dc.drawText(w / 2, (h * 0.68).toNumber(), Graphics.FONT_XTINY,
                    Maneuver.label(state.maneuver) + " in " + state.distance,
                    Graphics.TEXT_JUSTIFY_CENTER);

        dc.setColor((u2 > 0) ? fg : dim, Graphics.COLOR_TRANSPARENT);
        dc.drawText(w / 2, (h * 0.79).toNumber(), Graphics.FONT_XTINY,
                    thenText(state, gap), Graphics.TEXT_JUSTIFY_CENTER);
    }

    function min3(a as Numeric, b as Numeric, c as Numeric) as Numeric {
        var m = (a < b) ? a : b;
        return (m < c) ? m : c;
    }

    function max3(a as Numeric, b as Numeric, c as Numeric) as Numeric {
        var m = (a > b) ? a : b;
        return (m > c) ? m : c;
    }

    //! "then left in 120 m", or a plain note when there is no second turn.
    function thenText(state as NavState, gap as Number) as String {
        if (gap < 0 || state.afterManeuver == Maneuver.UNKNOWN) {
            return "then: clear";
        }
        return "then " + Maneuver.label(state.afterManeuver) + " in " + metres(gap);
    }

    function metres(m as Number) as String {
        if (m < 1000) { return m.toString() + " m"; }
        return (m / 100 / 10.0).format("%.1f") + " km";
    }

    //! Draw one leg and return where it ends.
    //!
    //! Returns the endpoint rather than mutating, because Monkey C has no
    //! out-parameters and a two-element array is cheaper than a class here.
    function step(dc as Graphics.Dc, x as Numeric, y as Numeric, hdgDeg as Numeric,
                  len as Number, t as Number,
                  color as Graphics.ColorType) as [Numeric, Numeric] {
        var r = hdgDeg * Math.PI / 180.0;
        var nx = x + len * Math.cos(r);
        var ny = y + len * Math.sin(r);
        dc.setColor(color, Graphics.COLOR_TRANSPARENT);
        Maneuver.seg(dc, x, y, nx, ny, t);
        return [nx.toFloat(), ny.toFloat()];
    }

    //! Compress wildly different distances into relative leg lengths.
    //!
    //! Square root rather than linear: linearly, a 40 m leg beside a 3 km one
    //! is a single pixel, which hides exactly the case this page exists to
    //! show. The absolute size is decided by the fit above, so these are only
    //! ever compared against each other.
    function legLength(meters as Number) as Numeric {
        if (meters <= 0) { return 0.0; }
        var m = (meters > 3000) ? 3000 : meters;
        var f = Math.sqrt(m.toFloat() / 3000.0);
        return 0.28 + 0.72 * f;
    }

    //! Keep the sketch legible: real angles near 180 degrees fold the second
    //! leg back over the first.
    function clampAngle(a as Number) as Numeric {
        var v = a.toFloat();
        while (v > 180.0) { v -= 360.0; }
        while (v < -180.0) { v += 360.0; }
        if (v > 60.0) { v = 60.0; }
        if (v < -60.0) { v = -60.0; }
        return v;
    }
}
