import Toybox.Lang;
import Toybox.WatchUi;

//! UP/DOWN move between pages, SELECT toggles the raw-payload debug view.
//!
//! These buttons used to cycle a set of canned instructions so the arrows
//! could be checked with no phone in the loop. That was scaffolding from
//! before the relay worked, and on a real route it was a hazard: a stray press
//! replaced the live instruction with a fabricated one that looked exactly as
//! authoritative. `tools/preview.py` covers that ground offline instead, and
//! the buttons now do something worth doing.
class NavDelegate extends WatchUi.BehaviorDelegate {

    private var _view as NavView;

    function initialize(view as NavView) {
        BehaviorDelegate.initialize();
        _view = view;
    }

    function onNextPage() as Boolean {
        _view.nextPage();
        return true;
    }

    function onPreviousPage() as Boolean {
        _view.prevPage();
        return true;
    }

    function onSelect() as Boolean {
        _view.toggleDebug();
        return true;
    }
}
