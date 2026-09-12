## v0.4 — turn direction that works in any language

Until now the maneuver was read by matching keywords in the instruction text,
with English, French and German wired in. In any other language every turn
silently came out as UNKNOWN and the watch fell back to showing bare text.

The icon Maps draws next to the instruction carries the same information and
does not care what language the phone is set to, so it is now used as the
fallback.

- **Icon-based direction detection.** Measures where the arrow's ink actually
  sits — horizontal centre of mass, weighted toward the arrowhead — rather
  than comparing against reference images. That means no bundled icon set to
  keep in step with Google's redesigns, and it survives restyling.
- Text still runs first, because it distinguishes the finer cases the icon
  cannot: slight vs sharp, roundabout, merge, fork.
- The status screen now counts how often the icon rescued a turn the keywords
  could not name. If that number climbs on your phone, the keyword list is not
  pulling its weight for your language.
- Ambiguous or unreadable icons return UNKNOWN rather than guessing; the watch
  shows the instruction text, which is still perfectly usable.

### Install

1. Download `maps-relay.apk` below on the phone and open it.
2. Grant notification access, and allow notifications when asked.
3. `maps-relay-v0.4.prg` is the watch app — copy it into `GARMIN/Apps/` over
   USB, or run `make sideload` from the repo.

Garmin Connect Mobile must be installed and paired; it is the transport.
