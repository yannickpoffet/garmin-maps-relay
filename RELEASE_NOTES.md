## v0.29 — "relayed" was counting attempts, not arrivals

The phone display is instant now; the watch sometimes sits still across
several "relayed" messages. Before guessing at why, the counter needs to
stop lying: `relayed` incremented when a payload was handed to the SDK,
not when the watch got it, so it counted attempts that may never have
landed.

`sent / acked` is now shown as a pair. Only the second number means the
watch received a payload and acted on it, since the ack is sent by the
watch app itself. If the two diverge, messages are being lost on the
link; if they track and the screen still lags, the fault is on the watch.

**The watch also stopped stringifying every payload.** `NavState` built
a long string out of all eleven fields on arrival, about once a second,
on a device with a tight memory budget — and threw it away unread unless
the debug view happened to be open. It keeps the object and converts
only when that view asks.

## v0.28 — one update behind, and why

v0.27's send-time refresh also wrote the refreshed distance into the
dedupe state. So the dedupe ran one step ahead of OsmAnd: the refresh
advanced it to 880, OsmAnd's next callback arrived carrying 880, the
dedupe called it a repeat and swallowed it. Every other update vanished
and the display sat exactly one behind — which is precisely how it was
described.

`lastMeters` tracks the callback stream again. The send-time refresh
leaves it alone; the two are separate concerns and conflating them cost
every second update.

**The display is also polled now, four times a second.** OsmAnd's turn
callback fires on route-data updates, roughly once a second, while
OsmAnd's own screen moves more smoothly than that — so waiting for the
callback left this screen a beat behind for no reason. It reads OsmAnd's
state directly instead. The send path is untouched: the watch is paced
by the link, and its payload is refreshed as it leaves.

## v0.27 — send the distance as it is now, not as it was when queued

A review of the whole path for staleness, rather than another symptom fix.

**The payload was built when it was queued and sent up to a round trip
later.** With the link at 0.7–1.6 s, a distance measured at queue time is
already most of a second old before it leaves the phone — on every
update, not occasionally. OsmAnd will give the current distance to the
next turn for the asking (`next_turn_distance` in its turn bundle), so
the payload is now topped up with the live distance, remaining distance
and ETA at the moment of transmission. Off-route and arrival are left
exactly as queued, since neither has a meaningful distance to refresh.

**The ack timeout is derived from the measured round trip** — three
times the last one, floored at 800 ms and capped at the old 2.5 s.
A flat 2.5 s punished a fast link: at 700 ms a single lost ack bought
three and a half round trips of silence.

**A send error no longer outlives the condition.** `last error` kept
showing a failure after the link had visibly recovered; an ack clears it.

## v0.26 — the phone display was waiting on the watch

v0.25 fixed the wrong layer. The real cause: `Status.street`,
`distance`, `eta` and the rest were assigned inside `flush()`, *after*
`WatchRelay.send()` accepted the payload. When the link is busy waiting
for an ack — which at a 0.7–1.6 s round trip is most of the time —
`flush()` returns early and those fields are never written.

So the phone was showing the instruction at the *watch's* rate rather
than at the rate it knew it. The screen was gated by Bluetooth.

The instruction is now recorded where it becomes known, in
`onDirection`, and only the send counters are left in `flush()`. The
watch has to wait for the link; this screen does not.

## v0.25 — the screen was a second behind its own data

The instruction on the phone lagged the instruction on the wire by up to
a second. Nothing was slow: `Relay` updated the fields and never told
the screen, so the only repaints were the once-a-second tick and
whatever `WatchRelay` happened to notify about. The data was already
there; nothing had asked to see it.

`Relay` now notifies on every update — both when a payload goes out and
when a turn arrives, since the counters move on turns that never reach
the link — and the screen repaints on that instead of waiting.

This is the phone display only. The watch is unaffected: its pacing is
the 0.7–1.6 s round trip through Garmin Connect, which is the ceiling.

## v0.24 — the watch was being shown distances it had already passed

Acks work now, and the live log showed the next problem plainly:

```
dropped dm=1188   <- newer
SUCCESS dm=1207   <- older, actually sent
dropped dm=1144   <- newer
SUCCESS dm=1170   <- older again
```

v0.23 made `onDirection` `@Synchronized`, so the Binder threads OsmAnd
delivers on piled up on the lock and each went on to transmit the
snapshot it had taken *before* it started waiting. Serialising the
threads made staleness worse, not better.

**Queue the data, not the threads.** There is now one slot holding the
newest payload. A payload superseded before it goes out is replaced
rather than sent late, so what reaches the watch is always the freshest
thing known. The slot is flushed when a payload is queued, when the
watch acknowledges one — so the next goes the instant the link frees
rather than on the next turn from OsmAnd — and from the status screen's
tick as a backstop against a lost ack.

Measured round trip on this link is 0.7–1.6 s. That is Garmin's BLE
relay and it is the ceiling; the fix is to spend it on current data
rather than on stale data.

## v0.23 — the handshake was punishing its own failure

From a live route: no ack ever arrived, so every payload waited out the
full 2.5 s timeout and the display crawled at one update per 2.5 s —
considerably worse than the fixed interval the handshake replaced.

- **A watch that has never acked is paced at 500 ms instead of waiting
  for a timeout.** It is not doing flow control, so there is nothing to
  wait for. The handshake takes over the moment a first ack arrives.
- **The ack path had no logging at all**, which is why it could fail
  silently. Registration and every ack received are now logged.
- **`reconnect` stacked a listener per press.** It cleared the
  registration guard but only unregistered *device* events, so
  `pickDevice` added a second application-event listener each time — and
  a stacked listener makes the SDK deliver each message status twice.
  That is visible in the log as the same payload and sequence number
  "sent" twice, and as stale distances reaching the watch after newer
  ones were dropped.
- **The send path is serialised.** OsmAnd delivers turns on several
  Binder threads and they were racing `nextSeq`, `lastMeters` and the
  in-flight slot.

## v0.22 — a real handshake, and roundabouts that mean it

**Every change is sent now.** The send test keyed on the *rendered*
string, which looked sensible and was not: above a kilometre the display
reads "2.4 km" and only moves every 100 m, so the watch sat still for a
hundred metres at a time while OsmAnd counted down beside it. It now
sends on any change at all, down to a single metre.

**The watch acknowledges each payload, and the phone waits for it.** One
payload out, wait for the ack, send the next — so the link runs at
exactly the rate the round trip allows instead of at an interval guessed
in advance. Every previous value was such a guess: 1000 ms, then 400 ms.
The floor is now zero.

This also answers a question nothing else could. Connect IQ's send status
says the message left the phone, not that an app was there to take it —
which is why `FAILURE_DURING_TRANSFER` was so confusing. An ack is sent
by the watch app itself, so it is proof the app is running and
processing. The status screen shows the measured round trip, and
`watch app` reads "acking, 180 ms" rather than the guess it was making
before.

**Roundabouts drew the wrong instruction.** The exit arrow was nailed to
3 o'clock, so a roundabout you drive straight through rendered as "take
the right-hand exit". The exit now follows the turn angle, which was
already in the payload and unused: 12 o'clock for straight through, and
round from there.

## v0.21 — the reconnect button now reconnects

It called `WatchRelay.start()`, which opens with `if (ciq != null) return`
— and the SDK is initialised at launch, so the watch half of "reconnect
watch and osmand" returned immediately and did nothing at all. It only
ever helped in one case, after Garmin Connect had shut the SDK down.
Notably it never re-picked the watch, which is the one thing you press a
reconnect button for.

`WatchRelay.reconnect()` drops the current pick and rebuilds it from
`knownDevices`, unregistering the old device-event listener first so a
second one does not stack inside the SDK, then re-queries the live
status. The OsmAnd half already did something real — it retries a
refused subscription, which is how the app recovers after you enable it
in OsmAnd's plugin list.

Both the actions-list button and the NO WATCH fix button use it.

## v0.20 — "waiting" instead of three em-dashes

While OsmAnd is still calculating a route there is nothing to show yet,
and the instruction card filled all three of its fields with em-dashes,
which reads as a fault. It now says it is waiting for the first turn.

Confirmed in the same session that the watch app does open by itself
when a route starts — `openApplication -> PROMPT_SHOWN_ON_DEVICE`,
three-quarters of a second after the route began. Note the status: this
watch asks on the wrist rather than launching silently, so it is one tap
at route start, not none. That is Garmin's behaviour for a remote open
and there is no way around it from here.

## v0.19 — a running OsmAnd is not a running route

The watch app is meant to open by itself when navigation starts, and it
was opening whenever OsmAnd merely launched.

The check was "is there an ongoing OsmAnd notification". OsmAnd posts
one whenever it is alive at all — its background service uses the same
channel as the navigation one — so the answer was yes long before any
route existed. The same mistake left the phone claiming to be
navigating when it was not, which is why the instruction card sat there
full of em-dashes.

`getAppInfo()` knows the truth, so route start and route end are now
decided by whether a route is actually calculated and has distance left,
not by a notification being present. A route that ends without its
notification disappearing is now noticed too.

## v0.18 — "NO WATCH" while the watch was right there

v0.17 read `IQDevice.status` to decide whether the watch was reachable.
That is a field the SDK stamps on the objects it hands out, not a live
query: on one from `knownDevices` it is whatever it was last set to,
routinely NOT_CONNECTED for a watch sitting connected on your wrist.

And once the flag went false nothing revisited it — the only other
writer was the device-event callback — so the screen stayed on NO WATCH
after the watch came back, and pressing any button merely repainted that
stale answer. Which is exactly what it looked like: press a button, get
NO WATCH.

- `ConnectIQ.getDeviceStatus()` is the live query, and it is now polled
  once a second alongside the rest of the screen.
- A failed query keeps the last known answer instead of inventing a
  disconnection.
- Device-event registration happens once per device, since the poll can
  reach `pickDevice` and re-registering stacks listeners in the SDK.

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
