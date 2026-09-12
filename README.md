# Garmin Forerunner 745 projects

Two Connect IQ apps and the Android companion that feeds one of them.

| | |
|---|---|
| Watch | Forerunner 745, part `006-B3589-00`, firmware 13.70 |
| SDK device id | `fr745` — API level 3.3.1, 240x240 round MIP, 64 colours |
| Connect IQ SDK | 9.2.0, run from a container (see below) |

## [capability-explorer/](capability-explorer/)

Reports what this watch actually exposes to Connect IQ: screen geometry,
available Toybox modules, live sensors, activity data, system stats. Built to
answer "what can I use here?" by asking the device rather than reading a
compatibility table. Runs on the watch.

Its [docs/findings.md](capability-explorer/docs/findings.md) is the useful
artefact: which modules are fatal to probe, the real permission vocabulary for
a watch-app, and why side-loading cannot go through gvfs on Ubuntu 25.04.

## [maps-relay/](maps-relay/) + [android/](android/)

Shows Google Maps turn-by-turn directions from the phone on the watch.

```
Google Maps (Android)
  └─ ongoing navigation notification
      └─ NavListener (NotificationListenerService, parsing via GMapsParser)
          └─ throttle + dedupe
              └─ Connect IQ SDK ──▶ Garmin Connect Mobile ──BLE──▶ watch
                                                                    └─ NavView
```

Google publishes no API for live guidance, so the notification is the only
source — which also means this is Android-only and inherently fragile to Maps
updates. The `fr745` has no `WatchUi.MapView`, so there is no moving map: turn
arrow, distance, street and ETA, which is all the notification carries anyway.

The maneuver codes in `maps-relay/source/Maneuver.mc` and
`android/app/src/main/java/com/mapsrelay/companion/Maneuver.kt` are the contract
between the two halves and must stay in step.

## Building

**Watch apps** — the SDK runs in a container because the Connect IQ simulator
links `libwebkit2gtk-4.0`, which Ubuntu 25.04 no longer ships:

```bash
cd maps-relay        # or capability-explorer
make build           # compile for fr745, strict type checking
make sim             # launch the simulator
make run             # build + load into the running simulator
make sideload        # build + push onto the watch over MTP
make watch-log       # pull the watch's Connect IQ log
```

Side-loading bypasses gvfs (which cannot write MTP here) and drives `libmtp`
directly — no root needed.

**Android companion** — built by GitHub Actions, not locally. Every push
touching `android/` publishes a debug APK to the `apk` release; download
`maps-relay.apk` on the phone and open it.

## Phone setup

1. Install the APK from the `apk` release.
2. Open Maps Relay, tap **Grant notification access**, enable it.
3. Garmin Connect Mobile must be installed, signed in and paired — it is the
   transport, there is no alternative.
4. Open **Maps Relay** on the watch, then start navigating in Google Maps.

Tap **Send test message to watch** to check the link before trusting it on a
route.
