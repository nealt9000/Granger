#!/usr/bin/env python3
"""Plan LL1 Task 7: check built `.glink` files, and re-score the graded sample so a rebuild can't lose precision unseen.

  check.py GLINK_DIR [--graded tools/links/graded-2026-11-12.json] [--bar 0.95] [--epub-dir DIR]

Every file: the sidecar contract (tools/sidecar/meta.py; with --epub-dir, built from that exact EPUB), its tables, and
sane rows (tier 1 or 2, a QID, a quote as long as `len`).

The graded sample: each graded link whose book was built is looked up among the book's Light mentions (same quote, the
same words before it). Precision = graded-right still linked to the graded QID / all of them still linked; a link now
pointing elsewhere counts as wrong until it is graded again. Fails under --bar for any book with 20 or more of them
still linked, or for all books together. Prints how many graded-right links were lost (a recall drift worth a look).
"""
import argparse
import collections
import json
import sqlite3
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "sidecar"))
import meta  # noqa: E402

MIN_PER_BOOK = 20


def file_problems(path, epub_dir=None):
    src = None
    if epub_dir:
        m = meta.read_meta(path) if path.exists() else {}
        src = Path(epub_dir) / m.get("sourceZim", "")
        src = src if src.is_file() else None
    problems = meta.check(path, src)
    if problems:
        return problems
    db = sqlite3.connect(f"file:{path}?mode=ro", uri=True)
    try:
        if db.execute("SELECT COUNT(*) FROM book").fetchone()[0] != 1:
            problems.append(f"{path.name}: book table must hold one row")
        bad = db.execute("SELECT COUNT(*) FROM mentions WHERE tier NOT IN (1, 2) OR qid <= 0 OR quote = '' "
                         "OR length(quote) != len OR conf < 0 OR conf > 1").fetchone()[0]
        if bad:
            problems.append(f"{path.name}: {bad} bad mention rows")
    except sqlite3.Error as e:
        problems.append(f"{path.name}: {e}")
    finally:
        db.close()
    return problems


def light_mentions(path):
    db = sqlite3.connect(f"file:{path}?mode=ro", uri=True)
    try:
        book = db.execute("SELECT id FROM book").fetchone()[0]
        return book, db.execute("SELECT quote, before, qid FROM mentions WHERE tier = 1").fetchall()
    finally:
        db.close()


def same_place(g, quote, before):
    """The graded link's words before it (cut at its paragraph) end the mention's 40 characters, or the reverse."""
    if quote != g["quote"]:
        return False
    k = min(20, len(g["before"]), len(before))
    return before.endswith(g["before"][-k:]) if k else True


def score(graded, books):
    """{book: Counter(right, wrong, moved, lost)} for the graded links of the books built."""
    out = collections.defaultdict(collections.Counter)
    for g in graded:
        if g["book"] not in books:
            continue
        hits = [q for quote, before, q in books[g["book"]] if same_place(g, quote, before)]
        c = out[g["book"]]
        if not hits:
            c["lost" if g["grade"] == "right" else "dropped-wrong"] += 1
        elif g["qid"] not in hits:
            c["moved"] += 1
        elif g["grade"] == "right":
            c["right"] += 1
        elif g["grade"] == "wrong":
            c["wrong"] += 1
        else:
            c["unsure"] += 1
    return out


def precision(c):
    linked = c["right"] + c["wrong"] + c["moved"]
    return (c["right"] / linked if linked else None), linked


def main(argv=None):
    a = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    a.add_argument("dir"); a.add_argument("--graded", default=str(HERE / "graded-2026-11-12.json"))
    a.add_argument("--bar", type=float, default=0.95); a.add_argument("--epub-dir")
    args = a.parse_args(argv)
    files = sorted(Path(args.dir).glob("*.glink"))
    problems, books = [], {}
    for f in files:
        p = file_problems(f, args.epub_dir)
        problems += p
        if not p:
            book, rows = light_mentions(f)
            books[book] = rows
    for p in problems:
        print("FAIL", p)
    graded = json.load(open(args.graded, encoding="utf8"))["links"]
    scores = score(graded, books)
    failed = bool(problems)
    total = collections.Counter()
    for book, c in sorted(scores.items()):
        total.update(c)
        p, n = precision(c)
        ok = p is None or n < MIN_PER_BOOK or p >= args.bar
        failed |= not ok
        print(f"{'ok  ' if ok else 'FAIL'} {book}: precision {p if p is None else f'{p:.1%}'} of {n} still linked; "
              + ", ".join(f"{k} {v}" for k, v in sorted(c.items())))
    p, n = precision(total)
    if n:
        failed |= p < args.bar
        print(f"{'ok  ' if p >= args.bar else 'FAIL'} all graded: precision {p:.1%} of {n}; "
              f"{total['lost']} graded-right links no longer at Light")
    print(f"{len(files)} files, {len(problems)} problems")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
