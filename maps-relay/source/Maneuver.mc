import Toybox.Graphics;
import Toybox.Lang;

//! Maneuver codes and how to draw them.
//!
//! Arrows are drawn with `dc` primitives rather than shipped as PNGs. fr745 is
//! 8 bpp with no alpha blending, so a flat vector arrow renders exactly as
//! intended, scales to whatever space the layout gives it, and costs no
//! resource memory. The codes are assigned here and mirrored by the Android
//! companion — this module is the contract between the two halves.
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

    //! Short label, used as the fallback whenever a maneuver has no drawing
    //! or is not recognised. The text instruction from Maps is always shown
    //! too, so an unknown code degrades to something still useful.
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

    //! Draw the arrow centred on (cx, cy) fitting a box of `size` pixels.
    function draw(dc as Graphics.Dc, m as Number, cx as Number, cy as Number,
                  size as Number, color as Graphics.ColorType) as Void {
        dc.setColor(color, Graphics.COLOR_TRANSPARENT);
        var h = size / 2;
        var t = size / 6;          // shaft half-width
        if (t < 2) { t = 2; }

        switch (m) {
            case STRAIGHT:
            case MERGE:
                shaftUp(dc, cx, cy, h, t);
                headUp(dc, cx, cy - h, t);
                break;

            case LEFT:
            case RIGHT:
            case SLIGHT_LEFT:
            case SLIGHT_RIGHT:
            case SHARP_LEFT:
            case SHARP_RIGHT:
            case FORK_LEFT:
            case FORK_RIGHT:
                turn(dc, m, cx, cy, h, t);
                break;

            case UTURN:
                uturn(dc, cx, cy, h, t);
                break;

            case ROUNDABOUT:
                dc.setPenWidth(t);
                dc.drawCircle(cx, cy - t, h / 2);
                dc.fillRectangle(cx - t / 2, cy + h / 2 - t, t, h / 2);
                dc.setPenWidth(1);
                break;

            case ARRIVE:
                dc.fillCircle(cx, cy, h / 2);
                break;

            default:
                // Nothing sensible to draw; the caller shows the text instead.
                break;
        }
    }

    //! Vertical shaft rising from the bottom of the box.
    function shaftUp(dc as Graphics.Dc, cx as Number, cy as Number,
                     h as Number, t as Number) as Void {
        dc.fillRectangle(cx - t / 2, cy - h, t, 2 * h);
    }

    //! Solid triangular head pointing up, apex at (x, y).
    function headUp(dc as Graphics.Dc, x as Number, y as Number, t as Number) as Void {
        dc.fillPolygon([[x, y - t], [x - 2 * t, y + t], [x + 2 * t, y + t]] as Array<[Numeric, Numeric]>);
    }

    //! An L-shaped turn: up the shaft, then off to one side with the head
    //! pointing that way. Slight/sharp variants change how far up the corner
    //! sits, which reads clearly at a glance without needing distinct icons.
    function turn(dc as Graphics.Dc, m as Number, cx as Number, cy as Number,
                  h as Number, t as Number) as Void {
        var leftward = (m == LEFT) || (m == SLIGHT_LEFT) || (m == SHARP_LEFT) || (m == FORK_LEFT);
        var sign = leftward ? -1 : 1;

        // Corner height: sharp turns break early and low, slight turns late.
        var cornerY = cy;
        if ((m == SHARP_LEFT) || (m == SHARP_RIGHT)) { cornerY = cy + h / 3; }
        if ((m == SLIGHT_LEFT) || (m == SLIGHT_RIGHT) || (m == FORK_LEFT) || (m == FORK_RIGHT)) {
            cornerY = cy - h / 3;
        }

        dc.fillRectangle(cx - t / 2, cornerY, t, cy + h - cornerY);       // shaft
        var tipX = cx + sign * h;
        var x0 = leftward ? tipX : cx;
        dc.fillRectangle(x0, cornerY - t / 2, h, t);                      // arm

        // Head pointing sideways at the end of the arm.
        var y = cornerY;
        dc.fillPolygon([[tipX + sign * t, y],
                        [tipX - sign * t, y - 2 * t],
                        [tipX - sign * t, y + 2 * t]] as Array<[Numeric, Numeric]>);
    }

    function uturn(dc as Graphics.Dc, cx as Number, cy as Number,
                   h as Number, t as Number) as Void {
        dc.setPenWidth(t);
        dc.drawArc(cx, cy, h / 2, Graphics.ARC_COUNTER_CLOCKWISE, 0, 180);
        dc.setPenWidth(1);
        dc.fillRectangle(cx - h / 2 - t / 2, cy, t, h / 2);
        dc.fillRectangle(cx + h / 2 - t / 2, cy, t, h / 3);
        dc.fillPolygon([[cx + h / 2, cy + h / 2],
                        [cx + h / 2 - 2 * t, cy + h / 3],
                        [cx + h / 2 + 2 * t, cy + h / 3]] as Array<[Numeric, Numeric]>);
    }
}
