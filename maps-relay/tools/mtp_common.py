"""Shared ctypes bindings for libmtp.

Ubuntu's gvfs MTP backend refuses writes on this setup, so the side-load path
talks to libmtp directly. libmtp is already installed as a shared library
(gvfs links against it); no root is needed because udev's uaccess rule grants
the logged-in user rw on the USB node.
"""
import ctypes as C
import os
import sys

lib = C.CDLL("libmtp.so.9")


class Folder(C.Structure):
    pass


Folder._fields_ = [
    ("folder_id", C.c_uint32), ("parent_id", C.c_uint32),
    ("storage_id", C.c_uint32), ("name", C.c_char_p),
    ("sibling", C.POINTER(Folder)), ("child", C.POINTER(Folder)),
]


class File(C.Structure):
    pass


File._fields_ = [
    ("item_id", C.c_uint32), ("parent_id", C.c_uint32),
    ("storage_id", C.c_uint32), ("filename", C.c_char_p),
    ("filesize", C.c_uint64), ("modificationdate", C.c_long),
    ("filetype", C.c_int), ("next", C.POINTER(File)),
]

lib.LIBMTP_Get_First_Device.restype = C.c_void_p
lib.LIBMTP_Release_Device.argtypes = [C.c_void_p]
lib.LIBMTP_Get_Folder_List.restype = C.POINTER(Folder)
lib.LIBMTP_Get_Folder_List.argtypes = [C.c_void_p]
lib.LIBMTP_Get_Filelisting_With_Callback.restype = C.POINTER(File)
lib.LIBMTP_Get_Filelisting_With_Callback.argtypes = [C.c_void_p, C.c_void_p, C.c_void_p]
lib.LIBMTP_new_file_t.restype = C.POINTER(File)
lib.LIBMTP_Send_File_From_File.restype = C.c_int
lib.LIBMTP_Send_File_From_File.argtypes = [C.c_void_p, C.c_char_p, C.POINTER(File),
                                           C.c_void_p, C.c_void_p]
lib.LIBMTP_Get_File_To_File.restype = C.c_int
lib.LIBMTP_Get_File_To_File.argtypes = [C.c_void_p, C.c_uint32, C.c_char_p,
                                        C.c_void_p, C.c_void_p]
lib.LIBMTP_Delete_Object.restype = C.c_int
lib.LIBMTP_Delete_Object.argtypes = [C.c_void_p, C.c_uint32]
lib.LIBMTP_Get_Filetype_Description.restype = C.c_char_p
lib.LIBMTP_Get_Filetype_Description.argtypes = [C.c_int]
lib.LIBMTP_Dump_Errorstack.argtypes = [C.c_void_p]


def open_device():
    lib.LIBMTP_Init()
    dev = lib.LIBMTP_Get_First_Device()
    if not dev:
        sys.exit("No MTP device found. Plug in and unlock the watch, and make "
                 "sure gvfs has released it:  pkill -x gvfsd-mtp")
    return dev


def unknown_filetype():
    """The UNKNOWN enum value shifts between libmtp releases; find it by name."""
    for i in range(128):
        d = lib.LIBMTP_Get_Filetype_Description(i)
        if d and b"nknown" in d:
            return i
    return 0


def walk_folders(node, path=""):
    while node:
        f = node.contents
        name = f.name.decode("utf-8", "replace") if f.name else "?"
        full = f"{path}/{name}"
        yield full, f
        if f.child:
            yield from walk_folders(f.child, full)
        node = f.sibling


def files(dev):
    n = lib.LIBMTP_Get_Filelisting_With_Callback(dev, None, None)
    while n:
        f = n.contents
        yield f
        n = f.next


def find_folder(dev, suffix):
    """suffix like 'GARMIN/Apps' (case-insensitive)."""
    want = "/" + suffix.upper()
    for p, f in walk_folders(lib.LIBMTP_Get_Folder_List(dev)):
        if p.upper().endswith(want):
            return p, f
    return None, None
