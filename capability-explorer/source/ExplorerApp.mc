import Toybox.Application;
import Toybox.Lang;
import Toybox.WatchUi;

//! Entry point. The app exists to answer one question: what does THIS
//! watch actually expose to Connect IQ? See Probes.mc for the interesting part.
class ExplorerApp extends Application.AppBase {

    function initialize() {
        AppBase.initialize();
    }

    function onStart(state as Dictionary?) as Void {
    }

    function onStop(state as Dictionary?) as Void {
    }

    function getInitialView() as [Views] or [Views, InputDelegates] {
        var view = new ExplorerView();
        return [view, new ExplorerDelegate(view)];
    }
}
