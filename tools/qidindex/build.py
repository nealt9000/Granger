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
BUILDER_VERSION = 2  # 2: the subjects table (Plan EX Task 1)

# The joins the phone reads (plan Decision 3): author, notable work, edition of, main subject, composer, performer
# (item-valued, stored as "Q123"); Gutenberg, LibriVox, MusicBrainz artist and work, archive.org (strings).
LINK_PROPS = ("P50", "P800", "P629", "P921", "P86", "P175", "P2034", "P1899", "P434", "P435", "P724")

SCHEMA = """
CREATE TABLE articles(path TEXT PRIMARY KEY, qid INTEGER NOT NULL, rank INTEGER NOT NULL, other TEXT) WITHOUT ROWID;
CREATE TABLE links(qid INTEGER NOT NULL, prop TEXT NOT NULL, value TEXT NOT NULL);
CREATE TABLE coords(qid INTEGER PRIMARY KEY, lat REAL NOT NULL, lon REAL NOT NULL);
"""

# Plan EX Task 1: each item's subjects (tools/subjects/subjects.json ids; ord 0 the main one), from tools/subjects/build.py.
# A table of its own, so schemaVersion stays 1 and apps that don't read subjects open the file as before.
SUBJECTS_SCHEMA = """
CREATE TABLE subjects(qid INTEGER NOT NULL, ord INTEGER NOT NULL, subject TEXT NOT NULL, PRIMARY KEY(qid, ord)) WITHOUT ROWID;
CREATE INDEX subjects_subject ON subjects(subject, qid);
"""


SUBJECTS_SPEC = Path(__file__).resolve().parents[1] / "subjects" / "subjects.json"


def pack_subject(source_zim: str):
    """The subject of a Wikipedia subject pack (subjects.json `packs`, by the topic in wikipedia_<lang>_<topic>_…)."""
    parts = source_zim.split("_")
    packs = json.loads(SUBJECTS_SPEC.read_text(encoding="utf8")).get("packs", {})
    return packs.get(parts[2]) if len(parts) > 3 and parts[0] == "wikipedia" else None


def add_subjects(db: sqlite3.Connection, subjects_path) -> int:
    """Copies the subjects of the index's items from tools/subjects/build.py's file; in a subject pack, an item the rules
    leave without one takes the pack's subject. Returns how many items have subjects."""
    db.execute("DROP TABLE IF EXISTS subjects")
    db.executescript(SUBJECTS_SCHEMA)
    db.execute("ATTACH DATABASE ? AS s", (f"file:{subjects_path}?mode=ro",))
    db.execute("INSERT INTO subjects SELECT qid, ord, subject FROM s.subjects WHERE qid IN (SELECT qid FROM articles)")
    version = db.execute("SELECT value FROM s.meta WHERE key = 'subjectsVersion'").fetchone()[0]
    built = db.execute("SELECT value FROM s.meta WHERE key = 'builtAt'").fetchone()[0]
    db.commit()
    db.execute("DETACH DATABASE s")
    default = pack_subject(db.execute("SELECT value FROM meta WHERE key = 'sourceZim'").fetchone()[0])
    if default:
        db.execute("INSERT INTO subjects SELECT DISTINCT qid, 0, ? FROM articles WHERE qid NOT IN (SELECT qid FROM subjects)", (default,))
    n = db.execute("SELECT COUNT(DISTINCT qid) FROM subjects").fetchone()[0]
    db.executemany("INSERT OR REPLACE INTO meta VALUES (?, ?)",
                   [("subjectsVersion", version), ("subjectsBuiltAt", built), ("withSubjects", str(n)), ("packSubject", default or "")])
    return n


def rows_of(rec: dict):
    """(article row without path, link rows, coord row or None) for one wdfilter record."""
    qid = int(rec["q"][1:])
    other = rec.get("es") or rec.get("en")
    links = [(qid, p, v) for p in LINK_PROPS for v in rec.get(p, ())]
    c = rec.get("P625")
    return (qid, rec.get("sl", 0), other), links, ((qid, c[0], c[1]) if c else None)


def build(paths, items, out: Path, source: Path, source_sha256: str, subjects=None) -> dict:
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
    if subjects:
        counts["subjects"] = add_subjects(db, subjects)
        db.commit()
    db.execute("VACUUM")
    db.close()
    counts["meta"] = m
    return counts


def path_of(line: str):
    """The "t" field of a wdfilter line without parsing it all: wdfilter writes q, then t, first (json.dumps order)."""
    i = line.find('"t": "')
    if i < 0:
        return None
    j = i + 6
    while True:
        k = line.find('"', j)
        if k < 0:
            return None
        if line[k - 1] != "\\" or line[k - 2] == "\\":
            return json.loads(line[i + 5:k + 1])
        j = k + 1


def read_items(path, wanted=None):
    """wdfilter records; with [wanted], only the lines whose path is in it are parsed (~7M English lines otherwise)."""
    with open(path, encoding="utf8") as f:
        for line in f:
            if wanted is not None and path_of(line) not in wanted:
                continue
            yield json.loads(line)


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("items"); p.add_argument("out"); p.add_argument("zims", nargs="+")
    p.add_argument("--releases", help="write the release descriptors here")
    p.add_argument("--subjects", help="tools/subjects/build.py's file: attach each item's subjects")
    a = p.parse_args(argv)
    from libzim.reader import Archive

    for z in a.zims:
        zim = Path(z)
        out = Path(a.out) / f"{zim.stem}.{KIND}"
        out.parent.mkdir(parents=True, exist_ok=True)
        paths = [path for path, _ in articles(Archive(zim))]
        counts = build(paths, read_items(a.items, set(paths)), out, zim, meta.sha256(zim), a.subjects)
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
