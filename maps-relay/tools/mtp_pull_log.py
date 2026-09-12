#!/usr/bin/env python3
"""Pull the watch's Connect IQ log. Signature failures and API-level
mismatches are recorded here, so this is where to look when a side-loaded
app refuses to appear or start."""
import os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from mtp_common import open_device, files, lib

out_dir = sys.argv[1] if len(sys.argv) > 1 else "bin"
os.makedirs(out_dir, exist_ok=True)

dev = open_device()
got = []
try:
    for f in files(dev):
        if not f.filename:
            continue
        name = f.filename.decode("utf-8", "replace")
        if name.upper().startswith("CIQ_LOG"):
            dest = os.path.join(out_dir, name)
            rc = lib.LIBMTP_Get_File_To_File(dev, f.item_id, dest.encode(), None, None)
            if rc == 0:
                got.append((dest, f.filesize))
finally:
    lib.LIBMTP_Release_Device(dev)

if not got:
    print("no CIQ log on the watch yet (it appears once an app has run)")
for dest, size in got:
    print(f"pulled {dest} ({size:,} bytes)")
    with open(dest, encoding="utf-8", errors="replace") as fh:
        body = fh.read().strip()
    print(body[-4000:] if body else "(empty)")
