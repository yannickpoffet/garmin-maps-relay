## v0.9 — builds that can update in place

Installing v0.8 over v0.7 failed:

```
INSTALL_FAILED_UPDATE_INCOMPATIBLE: Existing package signatures do not match
```

Gradle's default debug key is generated fresh on every clean CI runner, so
**every build had a different signature**. Updating meant uninstalling first —
which also throws away the notification-access grant, the one permission that
is awkward to restore.

Builds are now signed with a stable key held in a repository secret, so from
here on the APK upgrades in place and the grant survives. Builds without the
secret (a local build, a fork) fall back to the default debug key and still
work.

No functional changes to the relay itself; v0.8's parsing is unchanged.

### Install

This is the last release that needs an uninstall first, because it is the one
introducing the new key:

1. Uninstall Maps Relay if present.
2. Download `maps-relay.apk` below and open it.
3. Grant notification access, and allow notifications when asked.

Later versions will install straight over the top.
