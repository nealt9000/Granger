"""Sums up render.py results and says whether they pass.

  python verse_check/compare.py render-390-1.0.json render-360-1.0.json render-390-1.25.json [--limit 2]

A book fails when more than --limit percent of its verse lines wrap flush (the continuation starts at the
left edge, so it reads as a new line), counting only books with at least --min-lines verse lines; a `<pre>`
row past its source lines counts as a flush wrap. Any paragraph whose centring or right alignment Readium
took away fails the run too. Exits 1 on a failure.
"""
import argparse
import json
import sys
from pathlib import Path


def stats(r: dict) -> tuple[int, int, int, int]:
    """(verse lines, flush continuation rows, lines that wrapped, paragraphs whose alignment was lost)."""
    lines = r["lines"] + r["pre_src"]
    flush = r["wrapped"] - r["wrapped_hang"] + max(0, r["pre_rows"] - r["pre_src"])
    return lines, flush, r["wrapped"], r.get("align_lost", 0)


def report(name: str, results: dict, limit: float, min_lines: int, excepted: set[str]) -> bool:
    errors = sorted(slug for slug, r in results.items() if "error" in r)
    books = {slug: stats(r) for slug, r in results.items() if "error" not in r}
    big = {slug: s for slug, s in books.items() if s[0] >= min_lines}
    pct = {slug: 100 * s[1] / s[0] for slug, s in big.items()}
    over = sorted((slug for slug, p in pct.items() if p > limit and slug not in excepted), key=lambda s: -pct[s])
    lost = {slug: s[3] for slug, s in books.items() if s[3]}
    total = [sum(s[i] for s in books.values()) for i in range(4)]
    print(f"== {name}: {len(books)} books ({len(big)} with >= {min_lines} lines), {total[0]} verse lines, "
          f"{total[2]} wrapped, {total[1]} flush continuation rows, alignment lost {total[3]}")
    for slug in sorted(pct, key=lambda s: -pct[s])[:5]:
        if pct[slug] > 0:
            print(f"   {slug:48} {big[slug][1]:6} flush of {big[slug][0]:6} lines ({pct[slug]:.2f}%)")
    for slug in over:
        print(f"   FAIL {slug}: {pct[slug]:.2f}% flush (> {limit}%)")
    for slug, n in lost.items():
        print(f"   FAIL {slug}: alignment lost on {n} paragraphs {results[slug].get('align_lost_cls')}")
    for slug in errors:
        print(f"   FAIL {slug}: {results[slug]['error'][:200]}")
    return not (over or lost or errors)


def main(argv: list[str]) -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("results", nargs="+")
    parser.add_argument("--limit", type=float, default=2.0, help="highest flush percentage that passes")
    parser.add_argument("--min-lines", type=int, default=100)
    parser.add_argument("--except", dest="excepted", action="append", default=[], help="a slug allowed over the limit")
    args = parser.parse_args(argv)
    ok = all([report(Path(f).name, json.loads(Path(f).read_text()), args.limit, args.min_lines, set(args.excepted))
              for f in args.results])
    print("PASS" if ok else "FAIL")
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main(sys.argv[1:])
