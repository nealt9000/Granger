#!/usr/bin/env python3
"""Plan R1 Task 2: how big the vector set is.

  count.py SOURCES_DIR BOOKS_DIR [--jobs 6] [--out counts.json]

Chunks every source in set.json through the app's chunker and reports chunks and characters per pack, the vector file
size at 768 and 256 dims (int8 plus a float32 scale per vector, plus keys), and the embedding time at RATE chunks a second.
"""
import argparse
import json
import sys
from concurrent.futures import ProcessPoolExecutor
from pathlib import Path

import sources

HERE = Path(__file__).resolve().parent
CATALOG = HERE.parents[1] / "catalog/catalog.json"
RATE = 249391 / (126 * 60)  # R0: 249,391 book chunks in 2 h 6 min on the Mac's GPU


def load_set():
    return json.loads((HERE / "set.json").read_text())


def zim_file(sources_dir, pack):
    return Path(sources_dir) / ("%s_%s.zim" % (pack["id"], pack["snapshotDate"]))


def selected(pack, path):
    """The pack's chunks that set.json keeps: translated, profile and listing pages skipped, at most maxChunksPerArticle per article."""
    skip = tuple(pack.get("skipPathSuffixes", ()))
    prefixes = tuple(pack.get("skipPathPrefixes", ()))
    cap = pack.get("maxChunksPerArticle")
    articles = ((k, t, b) for k, t, b in sources.zim_sources(path)
                if not (skip and k.endswith(skip)) and not (prefixes and k.startswith(prefixes)))
    for title, c in sources.chunk("html", articles):
        if cap is None or c["ordinal"] < cap:
            yield title, c


def count_pack(job):
    pack, path = job
    pid = pack["id"]
    n = chars = keybytes = articles = 0
    seen = set()
    for _, c in selected(pack, path):
        n += 1
        chars += len(c["text"])
        keybytes += len(c["key"].encode()) + 2
        if c["key"] not in seen:
            seen.add(c["key"])
            articles += 1
    return pid, {"articles": articles, "chunks": n, "chars": chars, "keyBytes": keybytes}


def count_books(books):
    def all_sources():
        for path in books:
            for href, title, data in sources.epub_sources(path):
                yield path.stem + "\t" + href, title, data
    n = chars = keybytes = 0
    for _, c in sources.chunk("xhtml", all_sources()):
        n += 1
        chars += len(c["text"])
        keybytes += len(c["key"].encode()) + 2
    return {"articles": len(books), "chunks": n, "chars": chars, "keyBytes": keybytes}


def size(c, dims):
    return c["chunks"] * (dims + 4 + 8) + c["keyBytes"]  # int8 vector + scale + hash, then the key


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("sources")
    ap.add_argument("books")
    ap.add_argument("--jobs", type=int, default=6)
    ap.add_argument("--out", default=str(HERE / "counts.json"))
    a = ap.parse_args(argv)
    catalog = {p["id"]: p for p in json.loads(CATALOG.read_text())["packs"]}
    vset = load_set()
    jobs = []
    for p in vset["packs"]:
        path = zim_file(a.sources, catalog[p["id"]])
        if not path.exists():
            print("missing:", path, file=sys.stderr)
            continue
        jobs.append((p, str(path)))
    out = {}
    with ProcessPoolExecutor(a.jobs) as pool:
        for pid, c in pool.map(count_pack, jobs):
            out[pid] = c
            print("%-45s %9d chunks" % (pid, c["chunks"]), flush=True)
    books = sorted(Path(a.books).glob("*.epub"))
    out["granger-classics"] = count_books(books)
    print("%-45s %9d chunks" % ("granger-classics", out["granger-classics"]["chunks"]))
    domains = {p["id"]: p["domain"] for p in vset["packs"]}
    print()
    print("%-45s %-10s %9s %8s %8s %7s" % ("source", "domain", "chunks", "768 MB", "256 MB", "embed h"))
    tot = {"chunks": 0, 768: 0, 256: 0}
    for pid, c in out.items():
        c["domain"] = domains.get(pid, "literature+thought")
        c["mb768"], c["mb256"] = size(c, 768) / 1e6, size(c, 256) / 1e6
        c["embedHours"] = c["chunks"] / RATE / 3600
        tot["chunks"] += c["chunks"]; tot[768] += c["mb768"]; tot[256] += c["mb256"]
        print("%-45s %-10s %9d %8.0f %8.0f %7.1f" % (pid, c["domain"], c["chunks"], c["mb768"], c["mb256"], c["embedHours"]))
    print("%-45s %-10s %9d %8.0f %8.0f %7.1f" % ("total", "", tot["chunks"], tot[768], tot[256], tot["chunks"] / RATE / 3600))
    Path(a.out).write_text(json.dumps(out, indent=1))
    return 0


if __name__ == "__main__":
    sys.exit(main())
