#!/usr/bin/env python3
"""Delete a file from the watch by name.

WARNING about --keep-one: on this Forerunner the same filename showing twice
is an index artifact of ONE file, not two files. Deleting the "duplicate"
removes the file itself. Only use --keep-one if you have confirmed against the
watch's own app list that there really are two apps; otherwise you will
uninstall what you just installed.

The device's file index is also stale for tens of seconds after a write, so a
listing that omits a file is not evidence the file is gone."""
import os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from mtp_common import open_device, files, lib

args = [a for a in sys.argv[1:] if not a.startswith("--")]
keep_one = "--keep-one" in sys.argv
if not args:
    sys.exit("usage: mtp_rm.py <filename> [--keep-one]")
target = args[0]

dev = open_device()
try:
    ids = sorted(f.item_id for f in files(dev)
                 if f.filename and f.filename.decode("utf-8", "replace") == target)
    if not ids:
        print(f"{target}: not on device")
    else:
        doomed = ids[1:] if keep_one else ids
        if not doomed:
            print(f"{target}: 1 copy, nothing to remove")
        for iid in doomed:
            rc = lib.LIBMTP_Delete_Object(dev, iid)
            print(f"deleted item {iid}: {'ok' if rc == 0 else 'FAILED rc=%d' % rc}")
finally:
    lib.LIBMTP_Release_Device(dev)
