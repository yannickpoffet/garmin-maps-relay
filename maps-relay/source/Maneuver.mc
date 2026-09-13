import Toybox.Graphics;
import Toybox.Lang;
import Toybox.Math;

//! Maneuver codes and how to draw them.
//!
//! Arrows are drawn with `dc` primitives rather than shipped as PNGs: fr745 is
//! 8 bpp with no alpha blending, so flat vector shapes render exactly as
//! intended, scale to whatever space the layout gives them, and cost no
//! resource memory.
//!
//! The codes are mirrored by the Android companion in Maneuver.kt — the two
//! halves share no code, so this enum is the contract between them.
//!
//! Geometry here is kept in step with `tools/preview.py`, which renders the
//! same shapes offline. That preview is how these were actually made legible:
//! the Connect IQ simulator's device panel does not render in this
//! environment, so the arrows were otherwise unverifiable.
module Maneuver {

    enum {
        UNKNOWN      = 0,
        STRAIGHT     = 1,
        LEFT         = 2,
        RIGHT        = 3,
        SLIGHT_LEFT  = 4,
        SLIGHT_RIGHT = 5,
        SHARP_LEFT   = 6,
        SHARP_RIGHT  = 7,
        UTURN        = 8,
        ROUNDABOUT   = 9,
        MERGE        = 10,
        FORK_LEFT    = 11,
        FORK_RIGHT   = 12,
        ARRIVE       = 13
    }

    //! Arrowhead proportions, in multiples of the stroke width. At icon sizes
    //! this small a chunkier head swallows the shape it sits on.
    const HEAD_LEN = 1.7;
    const HEAD_HALF = 1.35;

    //! Short label, used as the fallback whenever a maneuver has no drawing or
    //! is not recognised. The instruction text from Maps is always shown too,
    //! so an unknown code still leaves something useful on screen.
    function label(m as Number) as String {
        switch (m) {
            case STRAIGHT:     return "straight";
            case LEFT:         return "left";
            case RIGHT:        return "right";
            case SLIGHT_LEFT:  return "slight left";
            case SLIGHT_RIGHT: return "slight right";
            case SHARP_LEFT:   return "sharp left";
            case SHARP_RIGHT:  return "sharp right";
            case UTURN:        return "u-turn";
            case ROUNDABOUT:   return "roundabout";
            case MERGE:        return "merge";
            case FORK_LEFT:    return "fork left";
            case FORK_RIGHT:   return "fork right";
            case ARRIVE:       return "arrive";
        }
        return "";
    }

    // ------------------------------------------------------------- primitives

    //! Thick line as a filled quad. Using a polygon rather than setPenWidth
    //! keeps the thickness exact and the joins flush with the arrowheads.
    //! Coordinates are Numeric, not Float: Monkey C's checker widens Float
    //! arithmetic to Double, so a Float-typed parameter rejects its own
    //! callers' expressions.
    function seg(dc as Graphics.Dc, x0 as Numeric, y0 as Numeric,
                 x1 as Numeric, y1 as Numeric, t as Number) as Void {
        var dx = x1 - x0;
        var dy = y1 - y0;
        var len = Math.sqrt(dx * dx + dy * dy);
        if (len < 0.001) { len = 0.001; }
        var nx = -dy / len * (t / 2.0);
        var ny = dx / len * (t / 2.0);
        dc.fillPolygon([
            [(x0 + nx).toNumber(), (y0 + ny).toNumber()],
            [(x1 + nx).toNumber(), (y1 + ny).toNumber()],
            [(x1 - nx).toNumber(), (y1 - ny).toNumber()],
            [(x0 - nx).toNumber(), (y0 - ny).toNumber()]
        ] as Array<[Numeric, Numeric]>);
    }

    //! Triangle whose BASE is centred on (x, y), pointing along (dx, dy).
    //! Shafts must end exactly on that base point, or the head floats free of
    //! its shaft — which is precisely what the first version of this did.
    function head(dc as Graphics.Dc, x as Numeric, y as Numeric,
                  dx as Numeric, dy as Numeric, t as Number) as Void {
        var len = Math.sqrt(dx * dx + dy * dy);
        if (len < 0.001) { len = 0.001; }
        var ux = dx / len;
        var uy = dy / len;
        var px = -uy;
        var py = ux;
        dc.fillPolygon([
            [(x + ux * HEAD_LEN * t).toNumber(), (y + uy * HEAD_LEN * t).toNumber()],
            [(x + px * HEAD_HALF * t).toNumber(), (y + py * HEAD_HALF * t).toNumber()],
            [(x - px * HEAD_HALF * t).toNumber(), (y - py * HEAD_HALF * t).toNumber()]
        ] as Array<[Numeric, Numeric]>);
    }

    //! Final leg of a path plus its arrowhead, with the TIP landing exactly
    //! on (tipX, tipY). Callers draw any earlier legs with seg().
    //!
    //! Kept to eight arguments: Monkey C allows at most nine per method.
    function arrowTo(dc as Graphics.Dc, fromX as Numeric, fromY as Numeric,
                     tipX as Numeric, tipY as Numeric, dx as Numeric, dy as Numeric,
                     t as Number) as Void {
        var len = Math.sqrt(dx * dx + dy * dy);
        if (len < 0.001) { len = 0.001; }
        var ux = dx / len;
        var uy = dy / len;
        var bx = tipX - ux * HEAD_LEN * t;
        var by = tipY - uy * HEAD_LEN * t;
        seg(dc, fromX, fromY, bx, by, t);
        head(dc, bx, by, ux, uy, t);
    }

    // ----------------------------------------------------------------- shapes

    //! Draw the maneuver centred on (cx, cy) fitting a box of `size` pixels.
    function draw(dc as Graphics.Dc, m as Number, cx as Number, cy as Number,
                  size as Number, color as Graphics.ColorType) as Void {
        dc.setColor(color, Graphics.COLOR_TRANSPARENT);

        var h = size / 2;
        var t = size / 6;
        if (t < 2) { t = 2; }

        var fcx = cx.toFloat();
        var fcy = cy.toFloat();
        var fh = h.toFloat();
        var baseY = fcy + fh;          // where the route enters the icon
        var sign = 1.0;

        switch (m) {
            case STRAIGHT:
                arrowTo(dc, fcx, baseY, fcx, fcy - fh, 0.0, -1.0, t);
                break;

            case MERGE:
                arrowTo(dc, fcx, baseY, fcx, fcy - fh, 0.0, -1.0, t);
                // Joining lane from the lower right, in two segments so it
                // reads as a merge rather than a stray tick beside the arrow.
                seg(dc, fcx + fh, baseY, fcx + fh - t, fcy + fh / 3, t);
                seg(dc, fcx + fh - t, fcy + fh / 3, fcx + t / 2.0, fcy - fh / 4, t);
                break;

            case LEFT:
            case RIGHT:
                sign = (m == LEFT) ? -1.0 : 1.0;
                seg(dc, fcx, baseY, fcx, fcy, t);
                arrowTo(dc, fcx, fcy, fcx + sign * fh, fcy, sign, 0.0, t);
                break;

            case SLIGHT_LEFT:
            case SLIGHT_RIGHT:
                // A diagonal, not an L: that is what makes it read as "bear
                // left" rather than "turn left" at a glance.
                sign = (m == SLIGHT_LEFT) ? -1.0 : 1.0;
                var knee = fcy + fh / 4;
                seg(dc, fcx, baseY, fcx, knee, t);
                arrowTo(dc, fcx, knee, fcx + sign * fh, fcy - fh + t, sign, -1.0, t);
                break;

            case SHARP_LEFT:
            case SHARP_RIGHT:
                // A hairpin: up, then back down and out. Both legs have to be
                // long relative to the head or it collapses into a blob that
                // is indistinguishable from a plain turn.
                sign = (m == SHARP_LEFT) ? -1.0 : 1.0;
                var kneeY = fcy - fh * 0.85;
                var tipX = fcx + sign * fh;
                var tipY = fcy + fh * 0.75;
                seg(dc, fcx, baseY, fcx, kneeY, t);
                arrowTo(dc, fcx, kneeY, tipX, tipY, tipX - fcx, tipY - kneeY, t);
                break;

            case FORK_LEFT:
            case FORK_RIGHT:
                sign = (m == FORK_LEFT) ? -1.0 : 1.0;
                var splitY = fcy + fh / 3;
                seg(dc, fcx, baseY, fcx, splitY, t);
                // The branch not taken, dimmed. A fork is a choice, and
                // drawing only the chosen side makes it a slight turn.
                dc.setColor(Graphics.COLOR_DK_GRAY, Graphics.COLOR_TRANSPARENT);
                var thin = t * 2 / 3;
                if (thin < 2) { thin = 2; }
                seg(dc, fcx, splitY, fcx - sign * fh, fcy - fh + 2 * t, thin);
                dc.setColor(color, Graphics.COLOR_TRANSPARENT);
                arrowTo(dc, fcx, splitY, fcx + sign * fh, fcy - fh + 2 * t,
                        sign, -1.0, t);
                break;

            case UTURN:
                // Up the right side, over the top, back down the left with the
                // head pointing down.
                var r = fh / 2;
                seg(dc, fcx + r, baseY, fcx + r, fcy, t);
                dc.setPenWidth(t);
                dc.drawArc(cx, cy, r.toNumber(), Graphics.ARC_COUNTER_CLOCKWISE, 0, 180);
                dc.setPenWidth(1);
                arrowTo(dc, fcx - r, fcy, fcx - r, fcy + fh - t, 0.0, 1.0, t);
                break;

            case ROUNDABOUT:
                // Ring, entry stub at the bottom, exit at 3 o'clock. The exit
                // must not leave at 45 degrees: a ring with a diagonal arrow
                // and a stem below is, unfortunately, the Mars symbol.
                var rr = fh * 0.62;
                var ringY = fcy - t / 2.0;
                dc.setPenWidth(ringWidth(t));
                dc.drawCircle(cx, ringY.toNumber(), rr.toNumber());
                dc.setPenWidth(1);
                seg(dc, fcx, baseY, fcx, ringY + rr, t);
                arrowTo(dc, fcx + rr, ringY, fcx + rr + 3 * t, ringY, 1.0, 0.0, t);
                break;

            case ARRIVE:
                // Pin: ring on a stem, which reads as a destination marker.
                var pr = fh * 0.45;
                var py = fcy - fh / 4;
                dc.setPenWidth(ringWidth(t));
                dc.drawCircle(cx, py.toNumber(), pr.toNumber());
                dc.setPenWidth(1);
                dc.fillCircle(cx, py.toNumber(), (pr / 3).toNumber());
                seg(dc, fcx, py + pr, fcx, baseY, ringWidth(t));
                break;

            default:
                // Nothing sensible to draw; the caller shows the text instead.
                break;
        }
    }

    function ringWidth(t as Number) as Number {
        var w = t / 2;
        return (w < 2) ? 2 : w;
    }
}
