#!/usr/bin/env python3
"""Plan R1 Task 1's gate: the Mac's chunks equal the phone's.

  parity.py RUN_DIR --books BOOKS_DIR [--zim PACK_ID=FILE.zim ...]

RUN_DIR's found.jsonl holds every passage the phone found, with its text. Each BOOK passage (source, href, text) and each
ARTICLE passage from a given pack (path, heading, text) must equal a chunk the Mac makes from the same file.
"""
import argparse
import collections
import json
import sys
from pathlib import Path

import sources


def book_id(path):
    """book:granger:gc-<slug> from gc-<slug>_<date>.epub; book:<origin>:<stem> otherwise (dense.py's rule)."""
    origin = path.parent.name
    stem = path.stem.split("_")[0] if origin == "granger" else path.stem
    return "book:%s:%s" % (origin, stem)


def recorded(run_dir):
    books, articles = set(), collections.defaultdict(set)
    for line in (Path(run_dir) / "found.jsonl").open(encoding="utf-8"):
        for p in json.loads(line)["passages"]:
            if p["kind"] == "BOOK":
                books.add((p["source"], p["location"], p["text"]))
            elif p["kind"] == "ARTICLE":
                articles[p["source"]].add((p["location"], p["heading"], p["text"]))
    return books, articles


def mac_books(books_dir, wanted):
    def all_sources():
        for path in sorted(Path(books_dir).rglob("*.epub")):
            bid = book_id(path)
            if bid not in wanted:
                continue
            for href, title, data in sources.epub_sources(path):
                yield bid + "\t" + href, title, data
    out = set()
    for _, c in sources.chunk("xhtml", all_sources()):
        bid, href = c["key"].split("\t", 1)
        out.add((bid, href, c["text"]))
    return out


def mac_articles(zim, wanted_paths):
    def picked():
        for key, title, data in sources.zim_sources(zim):
            if key in wanted_paths:
                yield key, title, data
    return {(c["key"], c["heading"] or "", c["text"]) for _, c in sources.chunk("html", picked())}


def report(name, phone, mac):
    missing = phone - mac
    print("%s: %d of %d phone passages match a Mac chunk" % (name, len(phone) - len(missing), len(phone)))
    for m in sorted(missing)[:5]:
        print("   missing:", m[0], m[1], repr(m[2][:100]))
    return not missing


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("run")
    ap.add_argument("--books")
    ap.add_argument("--zim", action="append", default=[], help="PACK_ID=FILE.zim")
    a = ap.parse_args(argv)
    books, articles = recorded(a.run)
    ok = True
    if a.books:
        ok &= report("books", books, mac_books(a.books, {b for b, _, _ in books}))
    for spec in a.zim:
        pack, zim = spec.split("=", 1)
        phone = articles[pack]
        ok &= report(pack, phone, mac_articles(zim, {p for p, _, _ in phone}))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
