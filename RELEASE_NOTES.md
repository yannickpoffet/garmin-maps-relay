## v0.17 — KnogScout's console, and an honest watch indicator

Restyled after the KnogScout console so the two tools read as one: dark
ground, hairline-stroked cards, monospace for anything that is data, one
accent. The accent is amber rather than teal, since this app lives beside
OsmAnd and that is the colour OsmAnd puts on its own notification.

**The watch now has a pill in the header, visible from any scroll
position**, and it distinguishes three states the old free-text status ran
together:

| | |
|---|---|
| `WATCH OFFLINE` | not paired, or out of range |
| `APP CLOSED` | watch is right there, Maps Relay is not open on it |
| `WATCH LIVE` | connected and taking messages |

That distinction is the whole point. "Watch connected" was being reported
while every message was dropped, because a connected watch and a running
watch app are different things and only the second one delivers anything.
`deviceConnected`, `deviceName` and `appRunning` are now tracked as typed
state rather than parsed back out of a status string, and connections are
four rows instead of three.

## v0.16 — tell the truth about the unbound listener

v0.15 offered a "Retry" that called `requestRebind`. That is the
documented cure for a listener left enabled-but-unbound after an app
update, it is already called on every launch, and on this phone it does
not take — the service stays unbound either way.

What does work is toggling the grant off and on. So the button now opens
notification-access settings and says to do exactly that, rather than
offering a retry that quietly achieves nothing.

## v0.15 — the screen no longer contradicts itself

Two bugs visible in the first screenshot of the new layout.

- **A red dot under a green verdict.** The banner tested whether
  notification access was granted; the row tested whether the service
  had actually bound. Both now report on the binding, which is the thing
  that matters, and the two faults that look identical are separated:
  never granted, versus granted and then left unbound by Android after
  the app was replaced. Each says so, and offers a different fix.
- **"CURRENT INSTRUCTION" hung over nothing** when no route was
  running — the card was hidden and its heading was not.

Connection dots gained a third state, so "granted, waiting for Android"
reads as amber rather than as a hard failure.

## v0.14 — say what is wrong, and open the watch when a route starts

The status screen printed every counter it had as a monospace block and
left you to work out which line mattered. That is backwards: there is
nearly always exactly one thing wrong, and naming it is the screen's job.

- **A single verdict at the top**, computed from the parts, with the
  button that fixes it directly underneath — "OsmAnd is blocking us"
  with a button into OsmAnd, "Watch app not open" with a button that
  opens it. Failures are reported in dependency order, since anything
  downstream of a broken link cannot work until it is fixed.
- **The current instruction** is shown as an instruction rather than as
  a serialised map, with the trip total and ETA beside it.
- Connections are three rows with coloured dots. The raw counters are
  still there, at the bottom, for when the verdict is not enough.
- Styled after OsmAnd, since that is the app this one lives beside —
  its amber, taken from the colour its own notification carries.

**The watch app now opens when a route starts.** Garmin only delivers to
a Connect IQ app that is running, and this is the one moment where doing
it unprompted is clearly right. v0.12 did it on every failed send
instead, which on this watch means a prompt on the wrist, repeatedly,
mid-drive.

## v0.13 — stop prompting the wrist, and say why a send was refused

v0.12 opened the watch app on every failed send. On this watch that does
not launch anything: it answers `PROMPT_SHOWN_ON_DEVICE` and asks the
wearer. With the watch app closed, a route became a stream of prompts.

- **Opening the watch app is back to being a button only.** It is a
  decision, not something to do automatically mid-drive.
- **"relay not ready" now says which of four reasons it was** — SDK not
  started, no watch picked, no app id, or a previous send still in
  flight. The bare message named none of them.

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
