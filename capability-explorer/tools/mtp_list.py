#!/usr/bin/env python3
"""List the Connect IQ apps currently installed on the watch."""
import os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from mtp_common import open_device, files, lib

dev = open_device()
try:
    rows = [(f.filename.decode("utf-8", "replace"), f.filesize, f.item_id)
            for f in files(dev)
            if f.filename and f.filename.lower().endswith(b".prg")]
finally:
    lib.LIBMTP_Release_Device(dev)

if not rows:
    print("no .prg files found")
else:
    print(f"{'file':36s} {'bytes':>10s}  item_id")
    for name, size, iid in sorted(rows):
        print(f"{name:36s} {size:>10,}  {iid}")
