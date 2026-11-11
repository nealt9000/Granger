#!/usr/bin/env python3
"""Plan LL0 Task 3: build a Wikipedia pack's QID index (`.gqid`, Linked Library spec §2, plan Decision 3).

  build.py ITEMS OUT_DIR ZIM... [--releases DIR]

ITEMS is wdfilter.py's items-<site>.jsonl for the pack's language (every item with an article on that site). Writes
OUT_DIR/<zim stem>.gqid per ZIM: a read-only SQLite sidecar (tools/sidecar/meta.py) mapping every real article to its
Wikidata item, its rank (sitelink count), its title in the other language, and the join properties the Linked Library
needs. With --releases, writes each release descriptor there. Run with ~/.venvs/granger-pack/bin/python -I.
"""
import argparse
import json
import sqlite3
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "sidecar"))
import meta  # noqa: E402
from zim_articles import articles  # noqa: E402

KIND = "gqid"
SCHEMA_VERSION = 1
BUILDER = "tools/qidindex/build.py"
BUILDER_VERSION = 1

# The joins the phone reads (plan Decision 3): author, notable work, edition of, main subject, composer, performer
# (item-valued, stored as "Q123"); Gutenberg, LibriVox, MusicBrainz artist and work, archive.org (strings).
LINK_PROPS = ("P50", "P800", "P629", "P921", "P86", "P175", "P2034", "P1899", "P434", "P435", "P724")

SCHEMA = """
CREATE TABLE articles(path TEXT PRIMARY KEY, qid INTEGER NOT NULL, rank INTEGER NOT NULL, other TEXT) WITHOUT ROWID;
CREATE TABLE links(qid INTEGER NOT NULL, prop TEXT NOT NULL, value TEXT NOT NULL);
CREATE TABLE coords(qid INTEGER PRIMARY KEY, lat REAL NOT NULL, lon REAL NOT NULL);
"""


def rows_of(rec: dict):
    """(article row without path, link rows, coord row or None) for one wdfilter record."""
    qid = int(rec["q"][1:])
    other = rec.get("es") or rec.get("en")
    links = [(qid, p, v) for p in LINK_PROPS for v in rec.get(p, ())]
    c = rec.get("P625")
    return (qid, rec.get("sl", 0), other), links, ((qid, c[0], c[1]) if c else None)


def build(paths, items, out: Path, source: Path, source_sha256: str) -> dict:
    """Write the .gqid from the ZIM's real article [paths] and an iterable of wdfilter records; returns counts."""
    wanted = set(paths)
    if out.exists():
        out.unlink()
    db = sqlite3.connect(out)
    db.executescript(SCHEMA)
    counts = {"articles": len(wanted), "matched": 0, "links": 0, "coords": 0}
    seen_q = set()
    for rec in items:
        path = rec.get("t")
        if path not in wanted:
            continue
        wanted.discard(path)   # one item per article: the first wins (wdfilter writes one line per item)
        (qid, rank, other), links, coord = rows_of(rec)
        db.execute("INSERT INTO articles VALUES (?,?,?,?)", (path, qid, rank, other))
        counts["matched"] += 1
        if qid in seen_q:      # two articles, one item (rare): its links are already in
            continue
        seen_q.add(qid)
        db.executemany("INSERT INTO links VALUES (?,?,?)", links)
        counts["links"] += len(links)
        if coord:
            db.execute("INSERT INTO coords VALUES (?,?,?)", coord)
            counts["coords"] += 1
    db.execute("CREATE INDEX articles_qid ON articles(qid)")
    db.execute("CREATE INDEX links_qid ON links(qid)")
    db.execute("CREATE INDEX links_value ON links(prop, value)")
    m = meta.write_meta(db, KIND, SCHEMA_VERSION, source, BUILDER, BUILDER_VERSION, source_sha256=source_sha256)
    db.executemany("INSERT OR REPLACE INTO meta VALUES (?, ?)", [("articles", str(counts["articles"])), ("matched", str(counts["matched"]))])
    db.commit()
    db.execute("VACUUM")
    db.close()
    counts["meta"] = m
    return counts


def read_items(path):
    with open(path, encoding="utf8") as f:
        for line in f:
            yield json.loads(line)


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("items"); p.add_argument("out"); p.add_argument("zims", nargs="+")
    p.add_argument("--releases", help="write the release descriptors here")
    a = p.parse_args(argv)
    from libzim.reader import Archive

    for z in a.zims:
        zim = Path(z)
        out = Path(a.out) / f"{zim.stem}.{KIND}"
        out.parent.mkdir(parents=True, exist_ok=True)
        paths = [path for path, _ in articles(Archive(zim))]
        counts = build(paths, read_items(a.items), out, zim, meta.sha256(zim))
        share = counts["matched"] / max(1, counts["articles"])
        print(f"{zim.name}: {counts['matched']:,}/{counts['articles']:,} articles ({share:.1%}), {counts['links']:,} links, "
              f"{counts['coords']:,} coords, {out.stat().st_size / 1e6:.1f} MB", file=sys.stderr, flush=True)
        if a.releases:
            pack_id = zim.stem.rsplit("_", 1)[0]
            d = meta.descriptor(out, pack_id)
            Path(a.releases).mkdir(parents=True, exist_ok=True)
            (Path(a.releases) / f"{pack_id}.{KIND}.json").write_text(json.dumps(d, indent=1) + "\n")


if __name__ == "__main__":
    main()
