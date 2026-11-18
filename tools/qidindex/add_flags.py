#!/usr/bin/env python3
"""Attach adult-content flags (tools/adultflags/flags_build.py) to built QID indexes, leaving every other table as it is.

  add_flags.py FLAGS.sqlite OUT_DIR GQID... [--releases DIR]

Copies each .gqid into OUT_DIR with its items' flags for its language (build.add_flags) and the builder version build.py
writes. The source ZIM, and so sourceSha256, is unchanged. With --releases, rewrites each release descriptor there.
"""
import argparse
import json
import shutil
import sqlite3
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import build  # noqa: E402
import meta  # noqa: E402  (build put tools/sidecar on the path)


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("flags"); p.add_argument("out"); p.add_argument("gqids", nargs="+"); p.add_argument("--releases")
    a = p.parse_args(argv)
    Path(a.out).mkdir(parents=True, exist_ok=True)
    for g in a.gqids:
        src = Path(g)
        out = Path(a.out) / src.name
        shutil.copyfile(src, out)
        db = sqlite3.connect(out)
        n = build.add_flags(db, a.flags)
        db.execute("INSERT OR REPLACE INTO meta VALUES ('builderVersion', ?)", (str(build.BUILDER_VERSION),))
        db.commit()
        db.execute("VACUUM")
        db.close()
        print(f"{out.name}: {n:,} flags, {src.stat().st_size / 1e6:.1f} -> {out.stat().st_size / 1e6:.1f} MB", flush=True)
        if a.releases:
            pack_id = src.stem.rsplit("_", 1)[0]
            Path(a.releases).mkdir(parents=True, exist_ok=True)
            (Path(a.releases) / f"{pack_id}.{build.KIND}.json").write_text(json.dumps(meta.descriptor(out, pack_id), indent=1) + "\n")


if __name__ == "__main__":
    main()
