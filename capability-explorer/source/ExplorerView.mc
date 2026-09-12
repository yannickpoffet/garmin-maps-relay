import Toybox.Graphics;
import Toybox.Lang;
import Toybox.System;
import Toybox.Timer;
import Toybox.WatchUi;

//! Renders the capability report as one scrollable list.
//!
//! Layout is derived from the device's own reported screen size rather than
//! hardcoded, so this adapts to whatever Forerunner it lands on. On round
//! screens the usable width narrows toward the top and bottom, so rows are
//! centred and the extreme rows are inset.
class ExplorerView extends WatchUi.View {

    private var _rows as Array<Row>;
    private var _top as Number = 0;        // index of first visible row
    private var _visible as Number = 1;    // rows that fit on screen
    private var _lineH as Number = 16;
    private var _font as Graphics.FontDefinition = Graphics.FONT_XTINY;
    private var _timer as Timer.Timer?;
    private var _round as Boolean = false;

    function initialize() {
        View.initialize();
        _rows = Probes.buildAll();
    }

    function onLayout(dc as Graphics.Dc) as Void {
        _lineH = dc.getFontHeight(_font);
        // Leave a margin top and bottom; round screens waste the corners.
        _visible = ((dc.getHeight() - 2 * _lineH) / _lineH).toNumber();
        if (_visible < 1) { _visible = 1; }
        _round = (System.getDeviceSettings().screenShape == System.SCREEN_SHAPE_ROUND);
    }

    function onShow() as Void {
        // Live values (HR, battery, memory) are only meaningful if they update.
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
        _rows = Probes.buildAll();
        WatchUi.requestUpdate();
    }

    function onUpdate(dc as Graphics.Dc) as Void {
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_BLACK);
        dc.clear();

        var w = dc.getWidth();
        var cx = w / 2;
        var y = _lineH / 2;

        var end = _top + _visible;
        if (end > _rows.size()) { end = _rows.size(); }

        for (var i = _top; i < end; i++) {
            var row = _rows[i];
            var label = row.label;
            var value = row.value;

            if (row.isHeader) {
                dc.setColor(Graphics.COLOR_BLUE, Graphics.COLOR_TRANSPARENT);
                dc.drawText(cx, y, _font, label, Graphics.TEXT_JUSTIFY_CENTER);
            } else {
                // Two columns, pulled in from the edges. On a round screen the
                // inset grows for rows near the top and bottom of the list.
                var inset = _round ? roundInset(i - _top, w) : 8;
                dc.setColor(Graphics.COLOR_LT_GRAY, Graphics.COLOR_TRANSPARENT);
                dc.drawText(inset, y, _font, label, Graphics.TEXT_JUSTIFY_LEFT);
                dc.setColor(valueColor(value), Graphics.COLOR_TRANSPARENT);
                dc.drawText(w - inset, y, _font, value, Graphics.TEXT_JUSTIFY_RIGHT);
            }
            y += _lineH;
        }

        drawScrollbar(dc);
    }

    //! Crude chord approximation: rows further from the vertical centre of a
    //! round display have less horizontal room, so inset them more.
    function roundInset(slot as Number, w as Number) as Number {
        var mid = _visible / 2.0;
        var d = (slot - mid).abs() / mid;      // 0 at centre, 1 at the edges
        return (8 + d * d * (w * 0.18)).toNumber();
    }

    function valueColor(value as String) as Graphics.ColorType {
        if (value.equals("yes"))                             { return Graphics.COLOR_GREEN; }
        if (value.equals("no") || value.equals("absent"))    { return Graphics.COLOR_DK_GRAY; }
        if (value.equals("threw"))                           { return Graphics.COLOR_RED; }
        if (value.equals("gated"))                           { return Graphics.COLOR_YELLOW; }
        if (value.equals("no data") || value.equals("-"))    { return Graphics.COLOR_ORANGE; }
        return Graphics.COLOR_WHITE;
    }

    function drawScrollbar(dc as Graphics.Dc) as Void {
        var total = _rows.size();
        if (total <= _visible) { return; }
        var h = dc.getHeight();
        var barH = (h * _visible) / total;
        var barY = (h * _top) / total;
        dc.setColor(Graphics.COLOR_DK_GRAY, Graphics.COLOR_TRANSPARENT);
        dc.fillRectangle(0, barY, 3, barH);
    }

    // ------------------------------------------------------------ navigation

    function scroll(delta as Number) as Void {
        var maxTop = _rows.size() - _visible;
        if (maxTop < 0) { maxTop = 0; }
        _top += delta;
        if (_top < 0)      { _top = 0; }
        if (_top > maxTop) { _top = maxTop; }
        WatchUi.requestUpdate();
    }

    //! SELECT jumps to the next section header, so a long report stays
    //! navigable without holding a button down.
    function jumpToNextSection() as Void {
        for (var i = _top + 1; i < _rows.size(); i++) {
            if (_rows[i].isHeader) {
                _top = i;
                var maxTop = _rows.size() - _visible;
                if (maxTop < 0) { maxTop = 0; }
                if (_top > maxTop) { _top = maxTop; }
                WatchUi.requestUpdate();
                return;
            }
        }
        _top = 0;   // wrap
        WatchUi.requestUpdate();
    }
}
