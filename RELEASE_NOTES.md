## v0.10 — the foreground service can actually start

Verified against a live route on a Xiaomi phone over adb. Parsing is confirmed
working:

```
last payload : {m=1, d=0 m, s=toward Neuweilerpl., e=10:32 AM, dm=0}
```

Instruction from `android.text`, distance from `android.title`, ETA from
`android.subText` with the AM/PM kept, distance parsed to a number, and the
maneuver read from the notification icon. `maps notifs seen: 7`,
`messages relayed: 4`, `last error: -`.

What logcat exposed along the way:

```
SecurityException: Starting FGS with type connectedDevice ... requires
  allOf=[FOREGROUND_SERVICE_CONNECTED_DEVICE]
  anyOf=[BLUETOOTH_ADVERTISE, BLUETOOTH_CONNECT, BLUETOOTH_SCAN,
         CHANGE_NETWORK_STATE, ...]
```

`connectedDevice` looked like the honest type, but Android 14 also demands one
of those `anyOf` permissions, and this app holds none of them — the Bluetooth
link belongs to Garmin Connect, not to us. Claiming `BLUETOOTH_CONNECT` to
satisfy a type check would have been a permission grab for something the app
never does.

- **The service now declares `specialUse`** with a subtype property stating
  what it is for, which needs no permission the app has no business holding.
- **Foreground failures are surfaced** in `last error` instead of only logcat.
  This failed on every notification and was invisible from the phone.

The relay kept working throughout — `startForeground` failing only means the
process is more killable, not that relaying stops.

### Install

Installs straight over v0.9; no uninstall needed.

Garmin Connect Mobile must be installed and paired; it is the transport.
