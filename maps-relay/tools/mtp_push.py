#!/usr/bin/env python3
"""Push a file onto a Garmin watch over MTP using libmtp directly.

Ubuntu's gvfs MTP backend refuses writes on this setup ("Operation not
supported"), both through the FUSE mount and the native mtp:// URI. libmtp
itself is already present as a shared library (gvfs links it), so we call it
through ctypes and skip gvfs entirely. No root required: udev's uaccess rule
already grants the logged-in user rw on the USB node.
"""
import ctypes as C
import os
import sys

lib = C.CDLL("libmtp.so.9")


class Folder(C.Structure):
    pass


Folder._fields_ = [
    ("folder_id", C.c_uint32),
    ("parent_id", C.c_uint32),
    ("storage_id", C.c_uint32),
    ("name", C.c_char_p),
    ("sibling", C.POINTER(Folder)),
    ("child", C.POINTER(Folder)),
]


class File(C.Structure):
    pass


File._fields_ = [
    ("item_id", C.c_uint32),
    ("parent_id", C.c_uint32),
    ("storage_id", C.c_uint32),
    ("filename", C.c_char_p),
    ("filesize", C.c_uint64),
    ("modificationdate", C.c_long),
    ("filetype", C.c_int),
    ("next", C.POINTER(File)),
]

lib.LIBMTP_Get_First_Device.restype = C.c_void_p
lib.LIBMTP_Get_Folder_List.restype = C.POINTER(Folder)
lib.LIBMTP_Get_Folder_List.argtypes = [C.c_void_p]
lib.LIBMTP_new_file_t.restype = C.POINTER(File)
lib.LIBMTP_Send_File_From_File.restype = C.c_int
lib.LIBMTP_Send_File_From_File.argtypes = [
    C.c_void_p, C.c_char_p, C.POINTER(File), C.c_void_p, C.c_void_p
]
lib.LIBMTP_Get_Filetype_Description.restype = C.c_char_p
lib.LIBMTP_Get_Filetype_Description.argtypes = [C.c_int]
lib.LIBMTP_Dump_Errorstack.argtypes = [C.c_void_p]
lib.LIBMTP_Release_Device.argtypes = [C.c_void_p]


def unknown_filetype():
    """The enum value for UNKNOWN moves between libmtp releases, so find it by
    its description rather than hardcoding a number."""
    for i in range(0, 128):
        d = lib.LIBMTP_Get_Filetype_Description(i)
        if d and b"nknown" in d:
            return i
    return 0


def walk(node, path=""):
    """Yield (path, folder) for every folder in the tree."""
    while node:
        f = node.contents
        name = f.name.decode("utf-8", "replace") if f.name else "?"
        full = f"{path}/{name}"
        yield full, f
        if f.child:
            yield from walk(f.child, full)
        node = f.sibling


def main():
    if len(sys.argv) < 2:
        sys.exit("usage: mtp_push.py <file> [target-folder-name]")
    src = os.path.abspath(sys.argv[1])
    target = sys.argv[2] if len(sys.argv) > 2 else "Apps"
    if not os.path.isfile(src):
        sys.exit(f"no such file: {src}")

    lib.LIBMTP_Init()
    dev = lib.LIBMTP_Get_First_Device()
    if not dev:
        sys.exit("no MTP device. Unplug/replug, unlock the watch, and make sure "
                 "gvfsd-mtp is not holding it (pkill -f gvfsd-mtp).")

    try:
        folders = list(walk(lib.LIBMTP_Get_Folder_List(dev)))
        match = [(p, f) for p, f in folders
                 if p.upper().endswith(f"/GARMIN/{target.upper()}")]
        if not match:
            print("Folders found:", file=sys.stderr)
            for p, _ in folders[:60]:
                print("  ", p, file=sys.stderr)
            sys.exit(f"could not find GARMIN/{target}")

        path, folder = match[0]
        size = os.path.getsize(src)
        print(f"target : {path} (id={folder.folder_id} storage={folder.storage_id})")
        print(f"sending: {os.path.basename(src)} ({size:,} bytes)")

        fi = lib.LIBMTP_new_file_t()
        name = os.path.basename(src).encode()
        fi.contents.filename = name
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
