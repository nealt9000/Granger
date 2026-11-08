#!/usr/bin/env python3
"""Plan H5b Task 1: look over built time indexes before anything depends on them (spec §4 checks).

  check.py GTIME... [--sample N]

For each file: rows by kind and lane, precision spread and the BCE count, then N random rows (default 100) printed as
year, precision, kind, lanes and title for a hand check. Ends with all files merged by QID, as the phone does.
"""
import argparse
import random
import sqlite3
from collections import Counter

KINDS = ("point", "span", "life")
PREC = {11: "day", 10: "month", 9: "year", 8: "decade", 7: "century", 6: "millennium"}


def year_text(y, prec):
    if y is None:
        return "now"
    era = "" if y > 0 else " BC"
    shown = y if y > 0 else 1 - y
    return f"{shown}{era}" if prec >= 9 else f"c.{shown}{era}"


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("files", nargs="+"); p.add_argument("--sample", type=int, default=100)
    a = p.parse_args(argv)
    seen = set()
    for f in a.files:
        db = sqlite3.connect(f"file:{f}?mode=ro", uri=True)
        meta = dict(db.execute("SELECT key, value FROM meta"))
        lanes = meta["lanes"].split(",")
        rows = db.execute("SELECT qid, title, kind, y0, y1, prec, lanes, rank FROM marks").fetchall()
        seen |= {r[0] for r in rows}
        kinds = Counter(KINDS[r[2]] for r in rows)
        per_lane = Counter(l for r in rows for b, l in enumerate(lanes) if r[6] >> b & 1)
        prec = Counter(PREC.get(r[5], "coarser") for r in rows)
        print(f"\n== {meta['zim']}: {len(rows):,} marks; {dict(kinds)}; BCE {sum(r[3] <= 0 for r in rows)}")
        print("   lanes:", {l: per_lane[l] for l in lanes}, "none:", sum(r[6] == 0 for r in rows))
        print("   precision:", dict(prec))
        random.seed(7)
        for r in random.sample(rows, min(a.sample, len(rows))):
            span = year_text(r[3], r[5]) + ("" if r[2] == 0 else "–" + year_text(r[4], r[5]))
            tags = "+".join(l[:5] for b, l in enumerate(lanes) if r[6] >> b & 1) or "-"
            print(f"   {span:>22}  {KINDS[r[2]]:5} {tags:24} {r[7]:4}  {r[1]}")
    print(f"\nall files, one per article: {len(seen):,} marks")


if __name__ == "__main__":
    main()
