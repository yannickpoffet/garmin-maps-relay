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
    private var _ackListener as AckListener;

    function initialize() {
        AppBase.initialize();
        _state = new NavState();
        _alerts = new Alerts();
        _ackListener = new AckListener();
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
        ack(msg.data);
    }

    //! Tell the phone this payload landed.
    //!
    //! The phone waits for this before sending the next one, which is what
    //! paces the link: as fast as the round trip allows and no faster. It also
    //! answers a question nothing else could — the Connect IQ send status says
    //! the message left the phone, not that an app was there to take it, so
    //! only an ack from up here proves this app is running and processing.
    //!
    //! The sequence number is echoed back so the phone can tell a fresh ack
    //! from a late one for a payload it has already given up on.
    function ack(data as Object?) as Void {
        if (!(Communications has :transmit)) { return; }
        var seq = 0;
        if (data instanceof Lang.Dictionary) {
            var v = (data as Dictionary).get("n");
            if (v instanceof Lang.Number) { seq = v as Number; }
        }
        try {
            Communications.transmit({ "ack" => seq }, null, _ackListener);
        } catch (e) {
            // Nothing useful to do: the phone's own timeout covers it.
        }
    }
}

//! transmit() insists on a listener. There is nothing to do with the outcome —
//! a dropped ack simply becomes a timeout on the phone, which is the same
//! recovery path as a dropped payload.
class AckListener extends Communications.ConnectionListener {
    function initialize() {
        ConnectionListener.initialize();
    }

    function onComplete() as Void {
    }

    function onError() as Void {
    }
}
