## v0.6 — the listener was switched off

v0.5 could send to the watch, but a live Google Maps route produced
`navigation active: false` and `messages relayed: 0`. The notification was
never being looked at.

GMapsParser's `NavigationListener` starts **disabled**: its
`isGoogleMapsNotification()` short-circuits on a `protected var enabled` that
defaults to `false`, and the subclass is expected to turn it on. Nothing warns
you — notifications simply never match, which looks identical to the listener
not being bound at all.

- **`enabled = true`** on service creation. This also re-scans notifications
  already on screen, so opening the app mid-route picks up the current turn
  instead of waiting for the next one.
- **`listener bound`** is now shown in the status panel. Granting notification
  access in settings is not the same as Android having bound the service, and
  the difference was invisible.
- **`maps notifs seen`** counts Maps notifications reaching the app, with the
  last one's id and ongoing flag. GMapsParser only accepts `id == 1`, which is
  an assumption about Maps' internals; if guidance stops being picked up
  again, this distinguishes "Maps changed its id" from "the listener is not
  bound".
- Fixed the watch name rendering as `watch: watch: Forerunner 745`.

### Install

1. Download `maps-relay.apk` below on the phone and open it.
2. Grant notification access, and allow notifications when asked.
3. `maps-relay-v0.6.prg` is unchanged from v0.5 — no need to reflash the watch
   if you already have it.

Garmin Connect Mobile must be installed and paired; it is the transport.
