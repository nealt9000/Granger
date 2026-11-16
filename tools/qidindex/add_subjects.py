#!/usr/bin/env python3
"""Plan EX Task 1: attach the subjects table to built QID indexes, without rebuilding them from their ZIMs.

  add_subjects.py SUBJECTS.sqlite OUT_DIR GQID... [--releases DIR]

Copies each .gqid into OUT_DIR with the subjects of its items (build.add_subjects) and builderVersion 2. The source ZIM,
and so sourceSha256, is unchanged. With --releases, rewrites each release descriptor there. The upkeep loop's rebuilds
pass --subjects to build.py instead.
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
    p.add_argument("subjects"); p.add_argument("out"); p.add_argument("gqids", nargs="+"); p.add_argument("--releases")
    a = p.parse_args(argv)
    Path(a.out).mkdir(parents=True, exist_ok=True)
    for g in a.gqids:
        src = Path(g)
        out = Path(a.out) / src.name
        shutil.copyfile(src, out)
        db = sqlite3.connect(out)
        n = build.add_subjects(db, a.subjects)
        db.execute("INSERT OR REPLACE INTO meta VALUES ('builderVersion', ?)", (str(build.BUILDER_VERSION),))
        matched = db.execute("SELECT COUNT(DISTINCT qid) FROM articles").fetchone()[0]
        db.commit()
        db.execute("VACUUM")
        db.close()
        print(f"{out.name}: {n:,}/{matched:,} items with subjects ({n / max(1, matched):.1%}), "
              f"{src.stat().st_size / 1e6:.1f} -> {out.stat().st_size / 1e6:.1f} MB", flush=True)
        if a.releases:
            pack_id = src.stem.rsplit("_", 1)[0]
            old = Path(a.releases) / f"{pack_id}.{build.KIND}.json"
            d = meta.descriptor(out, pack_id)
            old.write_text(json.dumps(d, indent=1) + "\n")


if __name__ == "__main__":
    main()
