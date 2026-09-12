import Toybox.Lang;
import Toybox.WatchUi;

//! Maps the physical buttons (and touch swipes, where present) onto
//! scrolling the report. UP/DOWN move a line, SELECT jumps to the next
//! section header so long lists stay navigable.
class ExplorerDelegate extends WatchUi.BehaviorDelegate {

    private var _view as ExplorerView;

    function initialize(view as ExplorerView) {
        BehaviorDelegate.initialize();
        _view = view;
    }

    function onNextPage() as Boolean {
        _view.scroll(1);
        return true;
    }

    function onPreviousPage() as Boolean {
        _view.scroll(-1);
        return true;
    }

    function onSelect() as Boolean {
        _view.jumpToNextSection();
        return true;
    }
}
