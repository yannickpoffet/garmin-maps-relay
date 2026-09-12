import Toybox.Application;
import Toybox.Communications;
import Toybox.Lang;
import Toybox.WatchUi;

//! Receives navigation instructions pushed from the Android companion app and
//! hands them to the view.
//!
//! Registration follows the SDK's own Comm sample: the callback is registered
//! in initialize() rather than onStart(), so a message that arrives while the
//! app is still starting is not dropped.
class NavApp extends Application.AppBase {

    private var _phoneMethod as Method(msg as Communications.PhoneAppMessage) as Void;
    private var _state as NavState;
    private var _view as NavView?;

    function initialize() {
        AppBase.initialize();
        _state = new NavState();
        _phoneMethod = method(:onPhone);
        if (Communications has :registerForPhoneAppMessages) {
            Communications.registerForPhoneAppMessages(_phoneMethod);
        }
    }

    function onStart(state as Dictionary?) as Void {
    }

    function onStop(state as Dictionary?) as Void {
    }

    function getInitialView() as [Views] or [Views, InputDelegates] {
        _view = new NavView(_state);
        return [_view, new NavDelegate(_view, _state)];
    }

    //! A message arrived from the phone.
    function onPhone(msg as Communications.PhoneAppMessage) as Void {
        _state.apply(msg.data);
        WatchUi.requestUpdate();
    }
}
