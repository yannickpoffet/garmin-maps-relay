# Vendored OsmAnd AIDL contract

This tree is **not our code**. It is OsmAnd's `OsmAnd-api` module, copied in
verbatim — the IPC contract that lets this app receive turn-by-turn updates from
OsmAnd. Do not edit it; refresh it wholesale instead.

| | |
|---|---|
| Upstream | https://github.com/osmandapp/OsmAnd |
| Module | `OsmAnd-api/src/net/osmand/aidlapi/` |
| Commit | `4eeaa24f7de573915e1bda14d531a5f0412dcedf` |
| Licence | GPL-3.0 (same as OsmAnd) |

The module ships `.aidl` and `.java` side by side; Android wants them in
separate source roots, so the copy is split:

    OsmAnd-api/src/net/osmand/aidlapi/**.aidl  ->  src/main/aidl/net/osmand/aidlapi/
    OsmAnd-api/src/net/osmand/aidlapi/**.java  ->  src/main/java/net/osmand/aidlapi/

## Why vendored rather than a dependency

The published artefact is `net.osmand:android-aidl-lib:master-snapshot@aar`, from
an Ivy repository at `https://builder.osmand.net`. That is an unpinned moving
snapshot on a third-party host, and the APK is built by GitHub Actions — a
build that would then fail whenever that host is down or the snapshot moves.
v0.8 removed JitPack for the same reason. This is a stable IPC contract that
changes rarely; a pinned copy costs a large file count once and nothing after.

## The two trees

Upstream has both `net.osmand.aidl` (v1) and `net.osmand.aidlapi` (v2). We bind
`net.osmand.aidl.OsmandAidlServiceV2`, which speaks **`aidlapi`**. The v1 tree
compiles fine and then fails at runtime, so only `aidlapi` is copied here.

## Refreshing

    git clone --filter=blob:none --sparse --depth 1 \
        https://github.com/osmandapp/OsmAnd.git
    cd OsmAnd && git sparse-checkout set OsmAnd-api

then re-split as above and update the commit in this file.
