#!/usr/bin/env python3
"""Push a file onto a Garmin watch over MTP, replacing any existing copy.

Ubuntu's gvfs MTP backend refuses writes on this setup ("Operation not
supported") through both the FUSE mount and the mtp:// URI, so this talks to
libmtp directly. No root needed: udev's uaccess rule already grants the
logged-in user rw on the USB node.
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from mtp_common import open_device, files, find_folder, unknown_filetype, lib


def main():
    if len(sys.argv) < 2:
        sys.exit("usage: mtp_push.py <file> [target-folder-name]")
    src = os.path.abspath(sys.argv[1])
    target = sys.argv[2] if len(sys.argv) > 2 else "Apps"
    if not os.path.isfile(src):
        sys.exit(f"no such file: {src}")

    dev = open_device()
    try:
        path, folder = find_folder(dev, f"GARMIN/{target}")
        if folder is None:
            sys.exit(f"could not find GARMIN/{target} on the device")

        name = os.path.basename(src)
        size = os.path.getsize(src)
        print(f"target : {path} (id={folder.folder_id} storage={folder.storage_id})")

        # Deliberately does NOT delete an existing copy first.
        #
        # This device reports a file that has been overwritten as *two* entries
        # with the same name and size but different item ids. They are not two
        # files: deleting either one removes the file outright, and the next
        # listing shows neither. A "tidy up the duplicate" step therefore
        # uninstalls the app you just pushed, which is exactly the trap this
        # comment exists to stop someone walking into again.
        #
        # Pushing over an existing file works, so the simplest correct
        # behaviour is to push and leave the index alone.

        print(f"sending: {name} ({size:,} bytes)")
        fi = lib.LIBMTP_new_file_t()
        fi.contents.filename = name.encode()
        fi.contents.filesize = size
        fi.contents.parent_id = folder.folder_id
        fi.contents.storage_id = folder.storage_id
        fi.contents.filetype = unknown_filetype()

        rc = lib.LIBMTP_Send_File_From_File(dev, src.encode(), fi, None, None)
        if rc != 0:
            lib.LIBMTP_Dump_Errorstack(dev)
            sys.exit(f"send failed (rc={rc})")
        print(f"OK: item_id={fi.contents.item_id}")
    finally:
        lib.LIBMTP_Release_Device(dev)


if __name__ == "__main__":
    main()
