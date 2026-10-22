#!/usr/bin/env python3
"""Plan R1 Task 9: push built vector files to the phone for dense retrieval, until the catalog lists them (Task 10).

  push.py WORK_DIR [--dry-run]

Every file in WORK_DIR/out goes to the app's files/vectors/ with an index.tsv (`<file>\\t<ARTICLE|BOOK>\\t<target>`,
read by DirectoryVectorSources): a pack's target is its ZIM Name, a Classics book's its book id. Files already on the
phone at the same size are skipped. ANDROID_SERIAL picks the device.
"""
import argparse
import subprocess
import sys
import tempfile
from pathlib import Path

from hits import sources

REMOTE = "/sdcard/Android/data/app.granger/files/vectors"


def adb(*args, capture=False):
    r = subprocess.run(["adb", *args], check=True, capture_output=capture, text=True)
    return r.stdout if capture else None


def remote_sizes():
    out = adb("shell", "ls -l %s 2>/dev/null || true" % REMOTE, capture=True)
    sizes = {}
    for line in out.splitlines():
        parts = line.split()
        if len(parts) >= 8 and parts[-1].endswith(".gvec"):
            sizes[parts[-1]] = int(parts[4])
    return sizes


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("work"); p.add_argument("--dry-run", action="store_true")
    a = p.parse_args(argv)
    srcs = sources(Path(a.work))
    index = "".join("%s\t%s\t%s\n" % (f.name, kind, label) for f, _, kind, label in srcs)
    total = sum(f.stat().st_size for f, _, _, _ in srcs)
    print("%d files, %.0f MB" % (len(srcs), total / 1e6))
    if a.dry_run:
        sys.stdout.write(index)
        return 0
    adb("shell", "mkdir -p %s" % REMOTE)
    have = remote_sizes()
    todo = [f for f, _, _, _ in srcs if have.get(f.name) != f.stat().st_size]
    for n, f in enumerate(todo, 1):
        adb("push", str(f), "%s/%s" % (REMOTE, f.name), capture=True)
        if n % 50 == 0 or n == len(todo):
            print("  pushed %d of %d" % (n, len(todo)), flush=True)
    with tempfile.NamedTemporaryFile("w", suffix=".tsv", delete=False) as t:
        t.write(index)
    # The index goes last, so the app never lists a file that isn't all there.
    adb("push", t.name, "%s/index.tsv" % REMOTE, capture=True)
    Path(t.name).unlink()
    print("index: %d entries" % len(srcs))
    return 0


if __name__ == "__main__":
    sys.exit(main())
