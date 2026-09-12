# What the watch actually told us

Notes from getting Capability Explorer running on a Forerunner 745 (CIQ 3.3.1).
These are the non-obvious things, recorded so they don't have to be rediscovered.

## `has` is not total — some modules are fatal to probe

The natural way to ask "is this module here?" is `Toybox has :Media`. On a
permission-gated module that does not return `false` — it raises
**Permission Required**, and `try`/`catch` does **not** intercept it. It is a
hard VM error that kills the app:

```
Error: Permission Required
Details: Module 'Toybox.Media' not available to 'Watch App'
  - hasModule() at Probes.mc:122
```

So the probe has three outcomes, not two, and the third has to be decided at
build time rather than discovered at runtime:

| module | why it can't be probed from a watch-app |
|---|---|
| `Toybox.Media` | audio-content-provider apps only |
| `Toybox.Complications` | needs `ComplicationSubscriber`, invalid for watch-app |
| `Toybox.Background` | needs the `Background` permission — see below |

Everything else becomes probeable once the matching permission is declared.
`Ant`, `BluetoothLowEnergy` and `PersistedContent` all moved from fatal to
`yes` purely by adding their permission to the manifest.

## The `Background` permission is a trap for a UI app

Declaring it without annotating any source with `(:background)` produces:

```
WARNING: The 'Background' permission was enabled but no source code was
annotated. The entire application will be loaded as a background process.
```

The app is then reclassified as a background process and every UI symbol
disappears — `View`, `Graphics`, `Timer` all fail to resolve. Not worth it just
to probe one module.

## Valid permissions for a watch-app on fr745

Derived by compiling a minimal app once per candidate and reading the
compiler's verdict, not from documentation.

**Accepted:** `Sensor`, `SensorHistory`, `SensorLogging`, `Fit`,
`FitContributor`, `Positioning`, `Communications`, `UserProfile`, `Ant`,
`BluetoothLowEnergy`, `PersistedContent`, `PersistedLocations`,
`Notifications`, `PushNotification`, `Background`, `ComplicationPublisher`

**Rejected for this app type:** `DataFieldAlert`, `ComplicationSubscriber`

**Not permissions at all** (the compiler says "Invalid permission provided"):
`Media`, `Steps`, `Heartrate`, `Audio`, `Telephony`, `Contacts`, `Calendar`

Note `FitnessData`, which older material mentions, is *not* valid in SDK 9.2 —
`Fit` is the current spelling.

## Simulator vs hardware disagree on `firmwareVersion`

`System.getDeviceSettings().firmwareVersion` is a plain `Number` on hardware
but an `Array` in the simulator, which is why `verStr()` in `Probes.mc` handles
both shapes. A good reminder that the simulator is an approximation.

## Side-loading: gvfs cannot write MTP here

On Ubuntu 25.04 every write path through gvfs fails with `Operation not
supported` — plain `cp` to the FUSE mount, `gio copy`, and `gio save` to the
`mtp://` URI alike, even though `gio info` reports `access::can-write: TRUE`.

The workaround is to bypass gvfs and call **libmtp** directly, which is already
installed as a shared library. No root is needed: udev's `uaccess` rule grants
the logged-in user rw on the USB node. See `tools/mtp_common.py`.

Two gotchas found the hard way:

- `gvfsd-mtp` must not hold the device, but kill it with `pkill -x`, never
  `pkill -f` — the `-f` pattern also matches the shell running the command,
  which kills your own script mid-run.
- The device's file index is **stale for tens of seconds** after a write. A
  listing that omits a file you just pushed is not evidence the push failed —
  wait and list again before concluding anything. Several apparent failures
  here were nothing but this.
- A file that has been overwritten shows up as **two entries with the same
  name and size but different item ids**. They are not two files. Deleting
  either one removes the file outright, and the next listing shows neither —
  so "tidying up the duplicate" uninstalls the app you just installed. This
  cost several rounds of confusion before the pattern was clear.

  Consequently `tools/mtp_push.py` pushes over an existing file and leaves the
  index alone, and `tools/mtp_rm.py --keep-one` carries a warning. Trust the
  watch's own app list over anything MTP reports.
