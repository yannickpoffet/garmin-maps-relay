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
    private var _alerts as Alerts;

    function initialize() {
        AppBase.initialize();
        _state = new NavState();
        _alerts = new Alerts();
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
        return [_view, new NavDelegate(_view)];
    }

    //! A message arrived from the phone.
    function onPhone(msg as Communications.PhoneAppMessage) as Void {
        _state.apply(msg.data);
        // Alerting belongs here rather than in the view: onUpdate runs on a
        // timer and would re-trigger the haptics on every repaint.
        _alerts.update(_state.key(), _state.meters, _state.arrived, _state.offRoute);
        WatchUi.requestUpdate();
    }
}
