#!/usr/bin/env python3
"""Plan I1 Task 3: the chunker on Ksoup makes the same chunks as the jsoup-era builds.

  ksoup_check.py zim BUILD_DIR FILE.zim        # one pack: BUILD_DIR/chunks.jsonl against FILE.zim
  ksoup_check.py books BUILD_BOOKS_DIR EPUB_DIR # every <id>/chunks.jsonl against EPUB_DIR/<id>_<date>.epub

Each build's chunks.jsonl is what its .gvec was made from (build.py). For every key in it, the same source is chunked
again with today's chunker (tools/chunker, now on Ksoup) and the (ordinal, hash) lists must be equal (for an article the
build capped, its kept chunks must be the first of today's). Keys the build
skipped (set.json caps) aren't fed. Prints counts and the first mismatches with their text; exits 1 on any mismatch.
"""
import argparse
import collections
import json
import sys
from pathlib import Path

import sources


def recorded(chunks_path):
    by_key = collections.OrderedDict()
    text = {}
    for line in Path(chunks_path).open(encoding="utf-8"):
        c = json.loads(line)
        by_key.setdefault(c["key"], []).append((c["ordinal"], c["hash"]))
        text[(c["key"], c["ordinal"])] = c["text"]
    return by_key, text


def compare(label, kind, srcs, by_key, text, report):
    fresh = collections.defaultdict(list)
    fresh_text = {}
    for _, c in sources.chunk(kind, srcs):
        fresh[c["key"]].append((c["ordinal"], c["hash"]))
        fresh_text[(c["key"], c["ordinal"])] = c["text"]
    keys = chunks = bad = capped = 0
    for key, want in by_key.items():
        keys += 1
        chunks += len(want)
        got = fresh.get(key, [])
        # A capped build (set.json's per-article cap) holds only an article's first chunks: those must match.
        if len(got) > len(want) and got[:len(want)] == want:
            capped += 1
            continue
        if got != want:
            bad += 1
            if len(report) < 10:
                diff = next((i for i, (a, b) in enumerate(zip(want, got)) if a != b), min(len(want), len(got)))
                old = text.get((key, want[diff][0])) if diff < len(want) else None
                new = fresh_text.get((key, got[diff][0])) if diff < len(got) else None
                report.append("%s %s: %d chunks before, %d now; first difference at %d\n  before: %r\n  now:    %r"
                              % (label, key, len(want), len(got), diff, (old or "")[:300], (new or "")[:300]))
    return keys, chunks, bad, capped


def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="mode", required=True)
    z = sub.add_parser("zim")
    z.add_argument("build_dir")
    z.add_argument("zim")
    b = sub.add_parser("books")
    b.add_argument("build_books")
    b.add_argument("epub_dir")
    a = ap.parse_args()

    report = []
    totals = [0, 0, 0, 0]
    if a.mode == "zim":
        by_key, text = recorded(Path(a.build_dir) / "chunks.jsonl")
        wanted = set(by_key)
        srcs = ((k, t, d) for k, t, d in sources.zim_sources(a.zim) if k in wanted)
        r = compare(Path(a.build_dir).name, "html", srcs, by_key, text, report)
        totals = [x + y for x, y in zip(totals, r)]
        units = 1
    else:
        epubs = {p.name.rsplit("_", 1)[0]: p for p in Path(a.epub_dir).glob("*.epub")}
        units = 0
        for d in sorted(Path(a.build_books).iterdir()):
            chunks = d / "chunks.jsonl"
            if not chunks.exists() or d.name not in epubs:
                continue
            units += 1
            by_key, text = recorded(chunks)
            r = compare(d.name, "xhtml", sources.epub_sources(epubs[d.name]), by_key, text, report)
            totals = [x + y for x, y in zip(totals, r)]
    keys, chunks, bad, capped = totals
    print("%s: %d sources, %d keys, %d chunks; %d keys differ (%d capped in the build, their kept chunks equal)"
          % (a.mode, units, keys, chunks, bad, capped))
    for r in report:
        print(r)
    sys.exit(1 if bad else 0)


if __name__ == "__main__":
    main()
