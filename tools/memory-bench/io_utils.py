# SPDX-License-Identifier: Apache-2.0
"""Kleine, netzfreie I/O-Primitiven fuer den Memory-Benchmark."""

from __future__ import annotations

import ctypes
import errno
import os
import secrets
import shutil
import sys
from datetime import datetime, timezone
from pathlib import Path

from schema import canonical_json, jsonl_bytes


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


def write_new_bytes(path: Path, payload: bytes, mode: int = 0o600) -> None:
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, mode)
    try:
        with os.fdopen(descriptor, "wb", closefd=False) as handle:
            handle.write(payload)
            handle.flush()
            os.fsync(handle.fileno())
    finally:
        os.close(descriptor)
    os.chmod(path, mode)


def write_new_json(path: Path, value: dict, mode: int = 0o600) -> None:
    write_new_bytes(path, (canonical_json(value) + "\n").encode("utf-8"), mode)


def write_new_jsonl(path: Path, rows: list[dict], mode: int = 0o600) -> None:
    write_new_bytes(path, jsonl_bytes(rows), mode)


def atomic_replace_jsonl(path: Path, rows: list[dict], mode: int = 0o600) -> None:
    temporary = path.with_name(f".{path.name}.tmp-{secrets.token_hex(8)}")
    try:
        write_new_jsonl(temporary, rows, mode)
        os.replace(temporary, path)
        parent_fd = os.open(path.parent, os.O_RDONLY)
        try:
            os.fsync(parent_fd)
        finally:
            os.close(parent_fd)
    finally:
        if temporary.exists():
            temporary.unlink()


def publish_directory_no_replace(temporary: Path, output: Path) -> None:
    """Atomare Verzeichnis-Publikation ohne vorhandenes Ziel zu ersetzen."""

    libc = ctypes.CDLL(None, use_errno=True)
    source = os.fsencode(temporary)
    destination = os.fsencode(output)
    if sys.platform == "darwin":
        rename = libc.renamex_np
        rename.argtypes = [ctypes.c_char_p, ctypes.c_char_p, ctypes.c_uint]
        rename.restype = ctypes.c_int
        result = rename(source, destination, 0x00000004)  # RENAME_EXCL
    elif sys.platform.startswith("linux"):
        rename = libc.renameat2
        rename.argtypes = [
            ctypes.c_int,
            ctypes.c_char_p,
            ctypes.c_int,
            ctypes.c_char_p,
            ctypes.c_uint,
        ]
        rename.restype = ctypes.c_int
        result = rename(-100, source, -100, destination, 0x00000001)
    else:
        raise ValueError("Atomarer No-replace-Publish wird auf dieser Plattform nicht unterstuetzt")
    if result != 0:
        error = ctypes.get_errno()
        if error in {errno.EEXIST, errno.ENOTEMPTY}:
            raise ValueError("Output-Verzeichnis existiert; unveraenderlicher Stand wird nie ersetzt")
        raise OSError(error, os.strerror(error), str(output))
    parent_fd = os.open(output.parent, os.O_RDONLY)
    try:
        os.fsync(parent_fd)
    finally:
        os.close(parent_fd)


def remove_private_tree(path: Path) -> None:
    if path.exists():
        shutil.rmtree(path)
