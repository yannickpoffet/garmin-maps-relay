## v0.12 — open the watch app instead of failing into it

Every payload was correct and not one of them landed:

```
turns received  : 119
messages relayed: 64
last payload    : {m=2, d=279 m, s=Schleetalstrasse, e=16:32,
                   r=22.3 km, dm=279, m2=5, dm2=128, a1=-59, a2=25}
last error      : send: FAILURE_DURING_TRANSFER
```

`FAILURE_DURING_TRANSFER` does not mean the link is broken. Garmin drops any
message addressed to a Connect IQ app that is not currently running, and the
watch app has to be open for a relay to reach it. The SDK can solve that
itself: `ConnectIQ.openApplication` launches it.

- **A failed send now opens Maps Relay on the watch**, throttled to once a
  minute because it can prompt on the wrist, with a button for doing it
  deliberately. This removes the last manual step: remembering to start the
  watch app before setting off.
- Confirmed working on a live route immediately afterwards — a smooth
  784 → 652 → 576 → 262 → 200 → 139 → 94 → 49 m countdown, every field typed.
- `friendlyName` comes back empty sometimes, which rendered the watch status
  line as a bare `": CONNECTED"`.

**Releases are now automatic.** Publishing used to need a `v*` tag pushed by
hand, and twice the tag was forgotten, so finished work sat in the rolling
`apk` release with no version against it. CI now reads `versionName` from
`build.gradle` and publishes that release if it does not already exist —
bumping the version is the whole of it.

## v0.11 — OsmAnd instead of Google Maps

v0.7 and v0.8 were both spent fixing the same class of bug: Google Maps
publishes no guidance API, so the only way to see a turn was to intercept the
notification it writes for human eyes and reverse-engineer it. v0.7 broke on
`RemoteViews` inflation. v0.8 broke on assuming the title held what the
notification *renders* as. And the maneuver — the single most important field —
was never in there as data at all. It arrived as an icon bitmap, which
`IconClassifier` had to read pixel by pixel.

**OsmAnd has a front door.** `registerForNavigationUpdates` over AIDL pushes
`ADirectionInfo{distanceTo, turnType, isLeftSide}` every time the next turn
changes. The two fields the arrow and the haptics depend on are now integers,
handed over by an app that means to hand them over.

- **Turn and distance are typed.** `Maneuver.fromTurnType` maps OsmAnd's
  `TurnType` onto the existing enum, almost 1:1. No keyword matching, no
  pixel classification. `IconClassifier.kt` and `MapsNotificationParser.kt`
  are gone — about 270 lines.
- **Street and ETA are demoted.** They still come from a notification, but it
  is now the only thing that depends on one, and losing it costs you a street
  line rather than a turn. The parser makes no assumption about which field
  holds what; it scans all of them.
- **Off route** is reported for the first time. Google Maps never exposed it.
  New `OFF_ROUTE` maneuver, a crossed-strokes glyph on the watch, and a
  four-pulse buzz on entering the state — distinct from the proximity
  thresholds (1, 2) and arrival (3).
- **The distance string is formatted on the phone from metres**, not lifted
  from localised notification text, so the watch reads the same units wherever
  the phone is set.
- **The AIDL contract is vendored**, not pulled from
  `net.osmand:android-aidl-lib:master-snapshot` — an unpinned snapshot on a
  third-party host would have put the CI build at its mercy. See
  `android/app/src/main/aidl/README.md`.

### Everything typed, and a second page

`getAppInfo()` returns the whole trip state as data — street name, metres left,
arrival time as an epoch timestamp, and the maneuver *after* the next one. All
of that was previously scraped out of the notification with regexes, so the
notification parser is gone entirely; the notification is now read only for
whether one exists, which is how a route's start and end are noticed. Arrival
comes from the distance left running out rather than looking for the word
"arrive" in three languages.

**UP/DOWN now switch pages.** They used to cycle fourteen fabricated turns
through the real display, which was useful before the relay worked and a hazard
afterwards — a stray press mid-route replaced the live instruction with a
made-up one that looked just as authoritative.

The new page 2 sketches the road ahead from the two upcoming turns and their
angles. It is not a map: the fr745 has no `MapView` and OsmAnd exposes no route
geometry. What it shows that page 1 cannot is whether a second turn follows
immediately — "left in 80 m" reads identically whether the next turn is 60 m or
2.5 km later.

### The trade

Navigation happens in **OsmAnd**, not Google Maps: no traffic-aware ETA, weaker
destination search, and offline maps have to be downloaded first. In exchange
the arrow stops being a guess.

### Known gaps

- `ARRIVE` has no `TurnType`; it is still inferred from the notification.
- `MERGE` is unreachable — no OsmAnd equivalent. The glyph stays for Demo.
- Roundabout exit number is lost: `turnType` is a bare int, so "take the 2nd
  exit" becomes just "roundabout".

### Install

1. Install **OsmAnd** (`net.osmand`) and download your offline maps.
2. Download `maps-relay.apk` below on the phone and open it.
3. Grant notification access, and allow notifications when asked.
4. **In OsmAnd: Menu → Plugins → Maps Relay → enable.** Found the hard way on
   the test phone: OsmAnd gates its API per calling app, registering each one
   *disabled* on first contact and refusing until a human says otherwise —

   ```
   net.osmand: Request AIDL API V2 for registerForNavUpdates
               from com.mapsrelay.companion enabled: false
   ```

   There is no prompt, and the app only appears in that list once it has tried
   at least once. The status screen says `NOT ENABLED` with this path on it
   until the toggle is flipped, then goes to `connected, receiving turns`
   within a few seconds on its own.
5. **Reflash the watch app** — `OFF_ROUTE` changes the maneuver contract, so
   v0.5's watch build no longer matches.

Garmin Connect Mobile must be installed and paired; it is the transport.
