import Toybox.Lang;

//! One line of the report. A small typed class beats the [kind, label, value]
//! arrays this started as: strict type checking can actually verify it, and
//! the call sites read better.
class Row {
    public var isHeader as Boolean;
    public var label as String;
    public var value as String;

    function initialize(isHeader as Boolean, label as String, value as String) {
        self.isHeader = isHeader;
        self.label = label;
        self.value = value;
    }
}
