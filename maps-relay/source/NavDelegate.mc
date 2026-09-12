import Toybox.Lang;
import Toybox.WatchUi;

//! SELECT toggles the raw-payload debug view, which is how the M0 link test is
//! read on the watch without a debugger attached.
class NavDelegate extends WatchUi.BehaviorDelegate {

    private var _view as NavView;
    private var _state as NavState;
    private var _demoIndex as Number = 0;

    function initialize(view as NavView, state as NavState) {
        BehaviorDelegate.initialize();
        _view = view;
        _state = state;
    }

    //! UP/DOWN cycle the canned instructions, so the display can be checked
    //! on the watch itself with no phone in the loop.
    function onNextPage() as Boolean {
        _demoIndex++;
        _state.apply(Demo.sample(_demoIndex));
        WatchUi.requestUpdate();
        return true;
    }

    function onPreviousPage() as Boolean {
        _demoIndex--;
        if (_demoIndex < 0) { _demoIndex = Demo.count() - 1; }
        _state.apply(Demo.sample(_demoIndex));
        WatchUi.requestUpdate();
        return true;
    }

    function onSelect() as Boolean {
        _view.toggleDebug();
        return true;
    }
}
