## v0.5 — arrows you can actually read

The turn arrows had never been seen. The Connect IQ simulator's device panel
does not render in this environment, so everything shipped up to v0.4 was drawn
blind. This release fixes that, and then fixes what it exposed.

**`tools/preview.py`** mirrors the `Maneuver.draw` and `NavView` arithmetic and
renders the same screens offline at the real 240x240. The sequence matches what
UP/DOWN cycles on the watch, so the two can be compared side by side.

What it caught, all of it real:

- **Arrowheads floated free of their shafts.** Heads and segment endpoints were
  computed independently and did not meet.
- **Sharp turns were a blob** — the head swallowed the turn and they read the
  same as a plain left or right. Now a proper hairpin, with heads slimmed from
  2× to 1.7× the stroke width, which improved every other glyph too.
- **The roundabout was the Mars symbol.** A ring, a stem below and a 45° arrow
  is unmistakably ♂. The exit now leaves at 3 o'clock.
- **Merge was identical to straight.** It now shows a lane joining from the
  right.
- **Fork was identical to a slight turn.** Both branches are now drawn, the one
  not taken dimmed — a fork is a choice, and showing one branch loses that.
- **U-turn and arrive** were an unreadable smudge and a bare dot; now a proper
  u-turn and a map pin.
- **An unknown maneuver truncated its own instruction.** With no arrow to draw,
  the text now gets that space and wraps over up to three lines. This is the
  fallback for every language the keyword list does not cover, so it matters
  more than it looks.

UP/DOWN on the watch now cycles all thirteen cases, including an over-long
street name and an unknown code, so truncation and the text fallback can be
checked on the real panel.

### Install

1. Download `maps-relay.apk` below on the phone and open it.
2. Grant notification access, and allow notifications when asked.
3. `maps-relay-v0.5.prg` is the watch app — copy it into `GARMIN/Apps/` over
   USB, or run `make sideload` from the repo.

Garmin Connect Mobile must be installed and paired; it is the transport.
