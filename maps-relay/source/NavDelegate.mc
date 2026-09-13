import Toybox.Lang;
import Toybox.WatchUi;

//! SELECT toggles the raw-payload debug view, which is how the link is read on
//! the watch without a debugger attached.
//!
//! UP/DOWN used to cycle a set of canned instructions so the arrows could be
//! checked with no phone in the loop. That was scaffolding from before the
//! relay worked, and on a real route it is a hazard: a stray button press
//! replaces the live instruction with a fabricated one that looks exactly as
//! authoritative. `tools/preview.py` covers the same ground offline, where a
//! fake turn cannot be mistaken for a real one.
class NavDelegate extends WatchUi.BehaviorDelegate {

    private var _view as NavView;

    function initialize(view as NavView) {
        BehaviorDelegate.initialize();
        _view = view;
    }

    function onSelect() as Boolean {
        _view.toggleDebug();
        return true;
    }
}
