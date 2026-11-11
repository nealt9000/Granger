#!/usr/bin/env python3
"""Plan LL0 Task 3: check a QID index (`.gqid`) before it ships, and on every upkeep rebuild (spec §5.5).

  check.py GQID [--source ZIM] [--min-share 0.98] [--sample 50]

Fails (exit 1) when the meta table breaks the sidecar contract, the file was built from another ZIM than --source, or
fewer than --min-share of the pack's real articles have an item (living Home §11 measured 99.9% for English). Prints
the counts per join property and a random sample for a hand check.
"""
import argparse
import sqlite3
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "sidecar"))
import meta  # noqa: E402


def report(path, sample=50):
    db = sqlite3.connect(f"file:{path}?mode=ro", uri=True)
    try:
        m = dict(db.execute("SELECT key, value FROM meta"))
        articles, matched = int(m.get("articles", 0)), int(m.get("matched", 0))
        props = db.execute("SELECT prop, COUNT(*), COUNT(DISTINCT qid) FROM links GROUP BY prop ORDER BY prop").fetchall()
        coords = db.execute("SELECT COUNT(*) FROM coords").fetchone()[0]
        other = db.execute("SELECT COUNT(*) FROM articles WHERE other IS NOT NULL").fetchone()[0]
        rows = db.execute("SELECT path, qid, rank, other FROM articles ORDER BY RANDOM() LIMIT ?", (sample,)).fetchall()
    finally:
        db.close()
    return {"articles": articles, "matched": matched, "share": matched / max(1, articles), "props": props,
            "coords": coords, "other": other, "sample": rows}


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("gqid"); p.add_argument("--source"); p.add_argument("--min-share", type=float, default=0.98)
    p.add_argument("--sample", type=int, default=50)
    a = p.parse_args(argv)
    problems = meta.check(a.gqid, a.source)
    r = report(a.gqid, a.sample)
    print(f"{Path(a.gqid).name}: {r['matched']:,}/{r['articles']:,} articles have an item ({r['share']:.2%}); "
          f"{r['other']:,} with the other language's title; {r['coords']:,} with coordinates")
    for prop, n, items in r["props"]:
        print(f"  {prop}: {n:,} values on {items:,} items")
    for path, qid, rank, other in r["sample"]:
        print(f"  Q{qid:<10} {rank:>4}  {path}" + (f"  ↔ {other}" if other else ""))
    if r["share"] < a.min_share:
        problems.append(f"{a.gqid}: only {r['share']:.2%} of articles have an item (floor {a.min_share:.0%})")
    for line in problems:
        print("FAIL", line)
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
