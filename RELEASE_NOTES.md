## v0.8 — parsing the fields Maps actually uses

Tested against a live route on the phone over adb, which finally made the real
notification visible:

```
android.title   = "0 m"                            <- distance only
android.text    = "toward Im Holeeletten"          <- instruction
android.subText = "8 min · 2.8 km · 10:04 AM ETA"  <- duration · remaining · ETA
```

v0.7 assumed the title held `"750 m · instruction"`. It does not — that is the
*system* rendering title and text together, and no single extra holds it. So
v0.7 relayed the distance string as if it were the instruction.

- **Fields read correctly**: distance from the title, instruction from the
  text, ETA from the subtext. The route-start case, where the title carries the
  instruction and there is no distance at all ("Head towards Im Heimgarten"),
  still works, so neither field is trusted positionally.
- **ETA keeps its AM/PM**, which a 12-hour phone needs to be unambiguous.

**GMapsParser is gone.** It failed on every notification of the test route with
`Impossible to parse navigation time Arrive 10:36`, because it inflates the
notification's RemoteViews and Maps now uses the standard template. Its errors
were also crowding out ours in `last error`. Dropping it removes a dependency,
about 4 MB, and the JitPack repository.

Confirmed working on the test route: `navigation active: true`, a relayed
payload of `{m=1, d=, s=Head towards Im Heimgarten, e=10:36, dm=-1}`, and the
icon classifier correctly reading the up-arrow as STRAIGHT.

### Install

1. Download `maps-relay.apk` below on the phone and open it.
2. Grant notification access, and allow notifications when asked.
3. The watch app is unchanged since v0.5.

Garmin Connect Mobile must be installed and paired; it is the transport.
