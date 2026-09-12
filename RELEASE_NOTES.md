## v0.2 — turn alerts

The watch now warns you before a turn instead of waiting to be looked at.

- **Haptic alerts** at 200 m and 50 m from the maneuver, one pulse then two,
  plus the backlight so the instruction is readable at the moment it matters.
  Each threshold fires once per turn, so crawling up to a junction does not
  buzz repeatedly.
- **Arrival screen** — the end of a route is no longer rendered as just
  another turn with a meaningless distance and ETA.
- The phone now sends the distance as a **number** (`dm`) alongside the
  display string, so the watch can decide when to alert without re-parsing a
  localised "0.4 km".
- UP/DOWN on the watch cycles six demo instructions and fires the real alert
  path, so the haptics and arrows can be checked without a phone.

### Install

1. Download `maps-relay.apk` below on the phone and open it.
2. Grant notification access when the app asks.
3. `maps-relay.prg` is the watch app — copy it into `GARMIN/Apps/` over USB,
   or run `make sideload` from the repo.

Garmin Connect Mobile must be installed and paired; it is the transport.
