## v0.3 — surviving a real route

v0.2 worked in principle; this is about it still working forty minutes into a
drive.

- **Foreground service while navigating.** Android is free to kill a
  background process between notifications, and on a long route it eventually
  will — the relay would then stop silently, mid-route, which is the worst
  failure mode this app has. It now shows an ongoing notification for the
  duration of a route and stops when navigation ends.
- **Automatic reconnection.** If the watch drops off (out of range, a
  Bluetooth blip) the relay forgets the dead handle and re-picks the device on
  the next instruction, rather than needing you to notice and press
  *Reconnect* while driving.
- Requests the Android 13+ notification permission, which the foreground
  service depends on.

### Install

1. Download `maps-relay.apk` below on the phone and open it.
2. Grant notification access, and allow notifications when asked.
3. `maps-relay-v0.3.prg` is the watch app — copy it into `GARMIN/Apps/` over
   USB, or run `make sideload` from the repo.

Garmin Connect Mobile must be installed and paired; it is the transport.
