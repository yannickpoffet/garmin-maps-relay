## v0.7 — parse the notification, not its layout

v0.6 got as far as `listener bound: true` and `maps notifs seen: 1
id=1 ongoing=true` — the Maps notification was arriving and being accepted —
yet `navigation active` stayed false and nothing was relayed. The parse was
failing, silently.

**Why it was silent.** GMapsParser logs through Timber but only plants a tree
in its own debug build, so from a released AAR every warning and swallowed
exception goes nowhere at all.

**Why it was failing.** GMapsParser inflates the notification's `RemoteViews`
and walks the view hierarchy. That worked when apps shipped custom
notification layouts, but Maps now uses the standard template, so
`contentView` is null and inflation throws.

- **New extras-based parser.** `EXTRA_TITLE` / `EXTRA_TEXT` / `EXTRA_SUB_TEXT`
  are documented, stable API. Maps puts distance and instruction in the title
  separated by a middot ("750 m · At the roundabout, take the 2nd exit"), and
  states with no distance ("towards Im Heimgarten") are handled too. Distances
  parse in m/km/ft/mi/yd and in both `1.2` and `1,2` decimal forms.
- **The maneuver arrow comes from the notification's large icon**, fed to the
  existing icon classifier — the small icon is just the Maps logo.
- This runs as the **primary** path. GMapsParser is still in place and its
  richer data is used if it ever does succeed; the existing dedupe stops the
  two paths from double-sending.
- **A Timber tree is now planted**, so GMapsParser's internal warnings reach
  logcat and the new `last error` line on the status screen.

### Install

1. Download `maps-relay.apk` below on the phone and open it.
2. Grant notification access, and allow notifications when asked.
3. The watch app is unchanged since v0.5 — no need to reflash it.

Garmin Connect Mobile must be installed and paired; it is the transport.
