# Capability Explorer — Garmin Forerunner 745

A Connect IQ watch app that reports what your watch actually exposes to
Connect IQ: screen geometry, available Toybox API modules, live sensor values,
activity data and system stats. Written to answer "what can I use here?" by
asking the device rather than consulting a compatibility table.

## Target

Detected from the watch over MTP (`GARMIN/GarminDevice.xml`):

| | |
|---|---|
| Model | Forerunner 745 |
| Part number | `006-B3589-00` |
| SDK device id | `fr745` |
| Firmware | 13.70 |
| App slots | 32 (16 MB) |

## Why Docker

The Connect IQ simulator links against `libwebkit2gtk-4.0`, which Ubuntu 25.04
no longer ships (it has 4.1 only). Ubuntu 22.04 is the last release carrying
4.0, so the container is built on it. The SDK, device targets and signing key
all live on the *host* under `~/.Garmin`, mounted in — the container is
disposable.

Consequence: VS Code's Monkey C extension will report a missing SDK. That is
expected. Use it as an editor; build with `make`.

## Layout

```
manifest.xml        app id, type, min API level, target device, permissions
monkey.jungle       build config
source/
  ExplorerApp.mc    entry point
  ExplorerView.mc   scrollable report rendering, adapts to screen size/shape
  ExplorerDelegate.mc  button and swipe handling
  Probes.mc         the capability probing itself
tools/
  dump-device-caps.py  dumps the SDK's static device spec to docs/
```

## Usage

```bash
make build          # compile for fr745, strict type checking (level 3)
make sim            # launch the simulator (leave it running)
make run            # build + push into the running simulator
make package        # release build
make sideload       # build + copy onto the watch over MTP
make watch-apps     # list the .prg files currently on the watch
make watch-log      # pull the watch's Connect IQ log
make devices        # list installed device targets
make shell          # interactive shell in the SDK container
```

Target another device with `make build DEVICE=fr965`.

## Controls

| Button | Action |
|---|---|
| UP / DOWN | scroll one line |
| SELECT | jump to next section |
| BACK | exit |

Values are colour-coded: green `yes`, grey `no`/`absent`, orange `no data`
(present but currently null — sensor idle or unpaired), red `threw`.

## How the probing works

Three sources, in increasing order of trustworthiness:

1. **`System.getDeviceSettings()`** — what the device declares about itself.
2. **The `has` operator** — Monkey C's runtime reflection. `Toybox has :Sensor`
   asks the VM whether a module exists *on this device*. This is the real
   per-device answer.
3. **Calling the API** — the only proof that counts. Every live read is
   wrapped, so an API that exists but throws degrades to a visible "threw"
   rather than crashing the app.

Permissions are declared broadly in `manifest.xml` on purpose, so the report
reflects the hardware rather than our own manifest omissions.

## Signing key

Generated at `~/.Garmin/ConnectIQ/developer.der` (+ the `.pem` it came from).
Every Connect IQ app must be signed, even for side-loading. Keep the `.pem` —
it is the identity for anything you publish later. It is gitignored here.

## Side-loading

`make sideload` pushes the `.prg` into `GARMIN/Apps/` on the watch.

It does **not** go through gvfs: on Ubuntu 25.04 every gvfs write path to MTP
fails with `Operation not supported`, so `tools/mtp_push.py` drives `libmtp`
directly (no root needed — udev's `uaccess` rule already grants access). The
target stops `gvfsd-mtp` first so libmtp can claim the USB device; replug the
watch or open Files to get the normal mount back.

MTP will accept the same filename twice without complaining, and the device's
file index goes briefly stale right after a write — so an empty listing is not
proof the push failed. Check with `make watch-apps`, and if there are
duplicates:

```bash
python3 tools/mtp_rm.py capability-explorer.prg --keep-one
```

If the app does not appear on the watch, `make watch-log` pulls the Connect IQ
log; signature failures and API-level mismatches both show up there.

## What we learned along the way

`docs/findings.md` records the non-obvious parts: which Toybox modules are
fatal to probe with `has` (and why `try`/`catch` cannot save you), the real
permission vocabulary for a watch-app on this device as derived from the
compiler, and the gvfs/MTP situation. `docs/device-capabilities.md` is the
SDK's static spec for fr745.
