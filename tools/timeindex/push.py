#!/usr/bin/env python3
"""Plan H5b Task 3: push built time indexes to the phone, until the catalog lists them.

  push.py GTIME_DIR [--dry-run]

Every .gtime in GTIME_DIR goes to the app's files/timeindex/ with an index.tsv (`<file>\\t<ZIM Name>`, read by the app's
directory lookup), the pack's ZIM Name being its id without the flavour. Files already on the phone with the same MD5 are
skipped (not the same size: a rebuilt SQLite file often keeps its size); the index goes last, so the app never lists a
file that isn't all there. ANDROID_SERIAL picks the device.
"""
import argparse
import hashlib
import subprocess
import sys
import tempfile
from pathlib import Path

REMOTE = "/sdcard/Android/data/app.granger/files/timeindex"
FLAVOURS = ("_maxi", "_nopic", "_mini")


def zim_name(stem: str) -> str:
    """wikipedia_en_top_maxi_2026-09-14 -> wikipedia_en_top (the Name the library records)."""
    pack_id = stem.rsplit("_", 1)[0]
    return next((pack_id[:-len(f)] for f in FLAVOURS if pack_id.endswith(f)), pack_id)


def adb(*args, capture=False):
    r = subprocess.run(["adb", *args], check=True, capture_output=capture, text=True)
    return r.stdout if capture else None


def remote_md5s():
    sums = {}
    for line in adb("shell", "cd %s 2>/dev/null && md5sum *.gtime 2>/dev/null || true" % REMOTE, capture=True).splitlines():
        parts = line.split()
        if len(parts) == 2:
            sums[parts[1]] = parts[0]
    return sums


def md5(path):
    h = hashlib.md5()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("dir"); p.add_argument("--dry-run", action="store_true")
    a = p.parse_args(argv)
    files = sorted(Path(a.dir).glob("*.gtime"))
    index = "".join("%s\t%s\n" % (f.name, zim_name(f.stem)) for f in files)
    print("%d files, %.1f MB" % (len(files), sum(f.stat().st_size for f in files) / 1e6))
    if a.dry_run:
        sys.stdout.write(index)
        return 0
    adb("shell", "mkdir -p %s" % REMOTE)
    have = remote_md5s()
    for f in files:
        if have.get(f.name) != md5(f):
            adb("push", str(f), "%s/%s" % (REMOTE, f.name), capture=True)
            print("  pushed", f.name, flush=True)
    with tempfile.NamedTemporaryFile("w", suffix=".tsv", delete=False) as t:
        t.write(index)
    adb("push", t.name, "%s/index.tsv" % REMOTE, capture=True)
    Path(t.name).unlink()
    print("index: %d entries" % len(files))
    return 0


if __name__ == "__main__":
    sys.exit(main())
