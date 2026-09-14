import Toybox.Graphics;
import Toybox.Lang;
import Toybox.System;
import Toybox.Timer;
import Toybox.WatchUi;

//! Renders the current instruction: a big turn arrow, the distance to it, the
//! street, and the ETA.
//!
//! There is deliberately no map. fr745 does not ship WatchUi.MapView (it is
//! absent from the SDK's MapSample product list), and the phone notification
//! carries no geometry anyway — only the next maneuver.
class NavView extends WatchUi.View {

    private var _state as NavState;
    private var _debug as Boolean = false;
    private var _page as Number = 0;
    private var _ahead as AheadView = new AheadView();

    public static const PAGES = 2;
    private var _timer as Timer.Timer?;
    private var _w as Number = 240;
    private var _h as Number = 240;

    function initialize(state as NavState) {
        View.initialize();
        _state = state;
    }

    function onLayout(dc as Graphics.Dc) as Void {
        _w = dc.getWidth();
        _h = dc.getHeight();
    }

    function onShow() as Void {
        // Staleness is time-based, so the display has to repaint even when no
        // message arrives — that is precisely the case it needs to catch.
        _timer = new Timer.Timer();
        _timer.start(method(:onTick), 1000, true);
    }

    function onHide() as Void {
        if (_timer != null) {
            _timer.stop();
            _timer = null;
        }
    }

    function onTick() as Void {
        WatchUi.requestUpdate();
    }

    function toggleDebug() as Void {
        _debug = !_debug;
        WatchUi.requestUpdate();
    }

    //! UP/DOWN move between pages. These buttons used to cycle fabricated
    //! instructions; a real second page is a better use of them.
    function nextPage() as Void {
        _page = (_page + 1) % PAGES;
        WatchUi.requestUpdate();
    }

    function prevPage() as Void {
        _page = (_page + PAGES - 1) % PAGES;
        WatchUi.requestUpdate();
    }

    function onUpdate(dc as Graphics.Dc) as Void {
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_BLACK);
        dc.clear();

        if (_debug) {
            drawDebug(dc);
            return;
        }
        if (!_state.everReceived) {
            drawCentered(dc, "waiting for phone", Graphics.COLOR_DK_GRAY);
            return;
        }
        if (_state.arrived && !_state.isStale()) {
            drawArrival(dc);
            return;
        }
        if (_page == 1) {
            _ahead.draw(dc, _state, _w, _h);
            drawPageDots(dc);
            return;
        }
        drawNav(dc);
        drawPageDots(dc);
    }

    //! Grey everything once the feed goes quiet. A stale turn shown in
    //! confident white is worse than an obvious warning.
    function drawNav(dc as Graphics.Dc) as Void {
        var stale = _state.isStale();
        var fg = stale ? Graphics.COLOR_DK_GRAY : Graphics.COLOR_WHITE;
        var accent = stale ? Graphics.COLOR_DK_GRAY : Graphics.COLOR_GREEN;

        var cx = _w / 2;

        if (_state.maneuver == Maneuver.UNKNOWN) {
            // No arrow to draw, so give the instruction the space instead of
            // truncating it. This is the fallback for every language the
            // companion's keyword list does not cover, so it has to stay
            // readable rather than degrade to "Unrecognised m...".
            var lines = wrap(dc, _state.street, Graphics.FONT_XTINY,
                             (_w * 0.72).toNumber(), 3);
            var lh = dc.getFontHeight(Graphics.FONT_XTINY);
            var ty = (_h * 0.22).toNumber() - (lines.size() - 1) * lh / 2;
            dc.setColor(fg, Graphics.COLOR_TRANSPARENT);
            for (var i = 0; i < lines.size(); i++) {
                dc.drawText(cx, ty, Graphics.FONT_XTINY, lines[i],
                            Graphics.TEXT_JUSTIFY_CENTER);
                ty += lh;
            }
        } else {
            // Arrow occupies the upper half.
            var arrowSize = (_h * 0.34).toNumber();
            Maneuver.draw(dc, _state.maneuver, cx, (_h * 0.30).toNumber(),
                          arrowSize, accent, _state.angle);
        }

        // Distance to the maneuver is the number you actually act on, so it
        // gets the largest type on the screen.
        if (!_state.distance.equals("")) {
            drawDistance(dc, cx, (_h * 0.46).toNumber(), _state.distance, fg);
        }

        // The instruction was already shown above when there is no arrow.
        if (_state.maneuver != Maneuver.UNKNOWN) {
            dc.setColor(fg, Graphics.COLOR_TRANSPARENT);
            dc.drawText(cx, (_h * 0.68).toNumber(), Graphics.FONT_SMALL,
                        fit(dc, _state.street, Graphics.FONT_SMALL,
                            (_w * 0.82).toNumber()),
                        Graphics.TEXT_JUSTIFY_CENTER);
        }

        // Trip summary: how far is left overall, and when you get there. Both
        // are about the journey rather than the next turn, so they share the
        // footer and the smallest type.
        var footer = stale ? "no signal" : joinFooter(_state.remaining, _state.eta);
        dc.setColor(stale ? Graphics.COLOR_ORANGE : Graphics.COLOR_LT_GRAY,
                    Graphics.COLOR_TRANSPARENT);
        dc.drawText(cx, (_h * 0.80).toNumber(), Graphics.FONT_XTINY,
                    footer, Graphics.TEXT_JUSTIFY_CENTER);
    }

    //! "8.8 km" + "12:58" -> "8.8 km · 12:58", skipping either if absent so a
    //! lone separator never appears.
    function joinFooter(left as String, right as String) as String {
        if (left.equals("")) { return right; }
        if (right.equals("")) { return left; }
        return left + " · " + right;
    }

    //! Draw "348 m" as a big number with a small unit beside it.
    //!
    //! FONT_NUMBER_MEDIUM is a digits-and-separators font: it has no letters at
    //! all, so drawing "348 m" through it silently renders "348" and the unit
    //! just disappears. That is why the watch showed no m/km while the offline
    //! preview, which draws everything in DejaVu, looked perfectly fine.
    //!
    //! So the two halves are drawn in different fonts and their bottoms lined
    //! up, which reads better than a single smaller font anyway.
    function drawDistance(dc as Graphics.Dc, cx as Number, y as Number,
                          text as String, fg as Graphics.ColorType) as Void {
        var num = text;
        var unit = "";
        var sp = text.find(" ");
        if (sp != null) {
            num = text.substring(0, sp) as String;
            unit = text.substring(sp + 1, text.length()) as String;
        }

        var nf = Graphics.FONT_NUMBER_MEDIUM;
        var uf = Graphics.FONT_SMALL;
        var nw = dc.getTextWidthInPixels(num, nf);
        var gap = unit.equals("") ? 0 : dc.getTextWidthInPixels(" ", uf);
        var uw = unit.equals("") ? 0 : dc.getTextWidthInPixels(unit, uf);

        var x = cx - (nw + gap + uw) / 2;
        dc.setColor(fg, Graphics.COLOR_TRANSPARENT);
        dc.drawText(x, y, nf, num, Graphics.TEXT_JUSTIFY_LEFT);
        if (!unit.equals("")) {
            // Sit the unit on the number's baseline rather than its top.
            var drop = dc.getFontHeight(nf) - dc.getFontHeight(uf);
            dc.drawText(x + nw + gap, y + drop, uf, unit,
                        Graphics.TEXT_JUSTIFY_LEFT);
        }
    }

    //! Which page you are on, as dots down the right edge. Without them a
    //! second page is invisible until you press a button by accident.
    function drawPageDots(dc as Graphics.Dc) as Void {
        var x = _w - 8;
        var gap = 10;
        var top = _h / 2 - ((PAGES - 1) * gap) / 2;
        for (var i = 0; i < PAGES; i++) {
            dc.setColor(i == _page ? Graphics.COLOR_LT_GRAY : Graphics.COLOR_DK_GRAY,
                        Graphics.COLOR_TRANSPARENT);
            dc.fillCircle(x, top + i * gap, (i == _page) ? 3 : 2);
        }
    }

    //! Arrival gets its own screen rather than being one more turn: it is the
    //! end of the route, and the distance/ETA lines are meaningless here.
    function drawArrival(dc as Graphics.Dc) as Void {
        var cx = _w / 2;
        Maneuver.draw(dc, Maneuver.ARRIVE, cx, (_h * 0.32).toNumber(),
                      (_h * 0.26).toNumber(), Graphics.COLOR_GREEN, 0);
        dc.setColor(Graphics.COLOR_GREEN, Graphics.COLOR_TRANSPARENT);
        dc.drawText(cx, (_h * 0.52).toNumber(), Graphics.FONT_MEDIUM, "Arrived",
                    Graphics.TEXT_JUSTIFY_CENTER);
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_TRANSPARENT);
        dc.drawText(cx, (_h * 0.68).toNumber(), Graphics.FONT_XTINY,
                    fit(dc, _state.street, Graphics.FONT_XTINY, (_w * 0.82).toNumber()),
                    Graphics.TEXT_JUSTIFY_CENTER);
    }

    //! Raw payload plus age — the M0 acceptance test is read from this screen.
    function drawDebug(dc as Graphics.Dc) as Void {
        var lh = dc.getFontHeight(Graphics.FONT_XTINY);
        var y = lh;
        dc.setColor(Graphics.COLOR_BLUE, Graphics.COLOR_TRANSPARENT);
        dc.drawText(_w / 2, y, Graphics.FONT_XTINY, "RAW", Graphics.TEXT_JUSTIFY_CENTER);
        y += lh;

        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_TRANSPARENT);
        var text = _state.everReceived ? _state.rawText() : "(nothing received)";
        var lines = wrap(dc, text, Graphics.FONT_XTINY, (_w * 0.78).toNumber(), 7);
        for (var i = 0; i < lines.size(); i++) {
            dc.drawText(_w / 2, y, Graphics.FONT_XTINY, lines[i], Graphics.TEXT_JUSTIFY_CENTER);
            y += lh;
        }

        dc.setColor(Graphics.COLOR_LT_GRAY, Graphics.COLOR_TRANSPARENT);
        var age = _state.ageSec();
        dc.drawText(_w / 2, _h - 2 * lh, Graphics.FONT_XTINY,
                    (age < 0) ? "age -" : ("age " + age.toString() + "s"),
                    Graphics.TEXT_JUSTIFY_CENTER);
    }

    function drawCentered(dc as Graphics.Dc, text as String, color as Graphics.ColorType) as Void {
        dc.setColor(color, Graphics.COLOR_TRANSPARENT);
        dc.drawText(_w / 2, _h / 2, Graphics.FONT_SMALL, text,
                    Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
    }

    //! Truncate with an ellipsis so a long street name cannot overflow the
    //! round screen and collide with the bezel.
    function fit(dc as Graphics.Dc, text as String, font as Graphics.FontDefinition,
                 maxW as Number) as String {
        if (dc.getTextWidthInPixels(text, font) <= maxW) { return text; }
        var s = text;
        while (s.length() > 1 && dc.getTextWidthInPixels(s + "...", font) > maxW) {
            s = s.substring(0, s.length() - 1) as String;
        }
        return s + "...";
    }

    //! Greedy word wrap, capped at maxLines.
    function wrap(dc as Graphics.Dc, text as String, font as Graphics.FontDefinition,
                  maxW as Number, maxLines as Number) as Array<String> {
        var out = [] as Array<String>;
        var line = "";
        var words = split(text, " ");
        for (var i = 0; i < words.size(); i++) {
            var candidate = line.equals("") ? words[i] : (line + " " + words[i]);
            if (dc.getTextWidthInPixels(candidate, font) <= maxW) {
                line = candidate;
            } else {
                if (!line.equals("")) { out.add(line); }
                line = words[i];
                if (out.size() >= maxLines) { return out; }
            }
        }
        if (!line.equals("") && out.size() < maxLines) { out.add(line); }
        return out;
    }

    function split(text as String, sep as String) as Array<String> {
        var out = [] as Array<String>;
        var cur = "";
        for (var i = 0; i < text.length(); i++) {
            var ch = text.substring(i, i + 1) as String;
            if (ch.equals(sep)) {
                out.add(cur);
                cur = "";
            } else {
                cur += ch;
            }
        }
        out.add(cur);
        return out;
    }
}
