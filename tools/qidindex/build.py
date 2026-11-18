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
BUILDER_VERSION = 4  # 2: the subjects table (Plan EX Task 1); 3: lenses (many per item) and broad topics' parts (Plan EX T1);
# 4: adult-content flags (roadmap, "Carried forward from EX")

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

# Each item's sub-category in its main subject (Plan EX editorial pass), as the facet's code (its place in meta
# `facets`): one small table keyed for listing a facet, about 6 bytes an item. An extra table; schema 1 kept.
FACETS_SCHEMA = """
CREATE TABLE main.facets(facet INTEGER NOT NULL, qid INTEGER NOT NULL, PRIMARY KEY(facet, qid)) WITHOUT ROWID;
"""


# Plan EX T1: broad topics' page parts (tools/topics/parts.py), for the topics whose article is in this index, from the
# parts file of the index's language. Extra tables; schema 1 kept.
TOPICS_SCHEMA = """
CREATE TABLE main.topics(qid INTEGER PRIMARY KEY, under INTEGER NOT NULL, inlinks INTEGER NOT NULL);
CREATE TABLE main.topic_parts(topic INTEGER NOT NULL, part INTEGER NOT NULL, ord INTEGER NOT NULL, item INTEGER,
    grp INTEGER, text TEXT, note TEXT, PRIMARY KEY(topic, part, ord)) WITHOUT ROWID;
"""

# Adult-content flags (tools/adultflags): kind 1, the topic is never volunteered on a screen that picks by rank; kind 2,
# its plate shows no picture. Search and opened articles aren't filtered. An extra table; schema 1 kept.
FLAGS_SCHEMA = """
CREATE TABLE main.flags(qid INTEGER PRIMARY KEY, kind INTEGER NOT NULL) WITHOUT ROWID;
"""

SUBJECTS_SPEC = Path(__file__).resolve().parents[1] / "subjects" / "subjects.json"


def index_language(db: sqlite3.Connection):
    """The index's language, from its source ZIM's name (wikipedia_<lang>_…); None otherwise."""
    z = db.execute("SELECT value FROM meta WHERE key = 'sourceZim'").fetchone()
    parts = z[0].split("_") if z else []
    return parts[1] if len(parts) > 2 and parts[0] == "wikipedia" else None


def add_topics(db: sqlite3.Connection, topics_files) -> int:
    """Copies the parts of the broad topics whose article is in this index, from the parts file (tools/topics/parts.py)
    of the index's language ({lang: path}); returns how many topics. Without a file for its language: none, no tables."""
    db.execute("DROP TABLE IF EXISTS main.topic_parts")
    db.execute("DROP TABLE IF EXISTS main.topics")
    path = (topics_files or {}).get(index_language(db))
    if not path:
        return 0
    db.execute("ATTACH DATABASE ? AS t", (f"file:{path}?mode=ro",))
    db.executescript(TOPICS_SCHEMA)
    db.execute("INSERT INTO main.topics SELECT qid, under, inlinks FROM t.topics WHERE qid IN (SELECT qid FROM articles)")
    db.execute("INSERT INTO main.topic_parts SELECT p.* FROM t.topic_parts p JOIN main.topics m ON m.qid = p.topic")
    built = db.execute("SELECT value FROM t.meta WHERE key = 'builtAt'").fetchone()[0]
    db.commit()
    db.execute("DETACH DATABASE t")
    n = db.execute("SELECT COUNT(*) FROM main.topics").fetchone()[0]
    db.executemany("INSERT OR REPLACE INTO meta VALUES (?, ?)", [("topicsBuiltAt", built), ("withTopics", str(n))])
    return n


def add_flags(db: sqlite3.Connection, flags_path) -> int:
    """Copies the adult-content flags of the index's items, for its language, from tools/adultflags/flags_build.py's file;
    returns how many. An index of no known language gets the table, empty."""
    db.execute("DROP TABLE IF EXISTS main.flags")
    db.executescript(FLAGS_SCHEMA)
    db.execute("ATTACH DATABASE ? AS f", (f"file:{flags_path}?mode=ro",))
    db.execute("INSERT INTO main.flags SELECT qid, kind FROM f.flags WHERE lang = ? AND qid IN (SELECT qid FROM articles)",
               (index_language(db),))
    version = db.execute("SELECT value FROM f.meta WHERE key = 'flagsVersion'").fetchone()[0]
    db.commit()
    db.execute("DETACH DATABASE f")
    n = db.execute("SELECT COUNT(*) FROM main.flags").fetchone()[0]
    db.executemany("INSERT OR REPLACE INTO meta VALUES (?, ?)", [("flagsVersion", version), ("withFlags", str(n))])
    return n


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
    # Sub-categories (Plan EX editorial pass), when the subjects file has them: the items' facets and the facet list.
    db.execute("DROP TABLE IF EXISTS main.facets")
    facets_meta = None
    row = db.execute("SELECT value FROM s.meta WHERE key = 'facets'").fetchone() \
        if db.execute("SELECT 1 FROM s.sqlite_master WHERE name = 'facets'").fetchone() else None
    if row:
        # Codes in the list's order: {subject: [{id, en, es, code}]}.
        listed = json.loads(row[0])
        code, n = {}, 0
        for subject, fs in listed.items():
            for f in fs:
                f["code"] = n
                code[f"{subject}.{f['id']}"] = n
                n += 1
        facets_meta = json.dumps(listed, ensure_ascii=False, separators=(",", ":"))
        db.executescript(FACETS_SCHEMA)
        rows = db.execute("SELECT qid, facet FROM s.facets WHERE qid IN (SELECT qid FROM articles)").fetchall()
        db.executemany("INSERT INTO main.facets VALUES (?, ?)", ((code[f], q) for q, f in rows if f in code))
    db.commit()
    db.execute("DETACH DATABASE s")
    default = pack_subject(db.execute("SELECT value FROM meta WHERE key = 'sourceZim'").fetchone()[0])
    if default:
        db.execute("INSERT INTO subjects SELECT DISTINCT qid, 0, ? FROM articles WHERE qid NOT IN (SELECT qid FROM subjects)", (default,))
    n = db.execute("SELECT COUNT(DISTINCT qid) FROM subjects").fetchone()[0]
    db.executemany("INSERT OR REPLACE INTO meta VALUES (?, ?)",
                   [("subjectsVersion", version), ("subjectsBuiltAt", built), ("withSubjects", str(n)), ("packSubject", default or "")])
    if facets_meta:
        db.execute("INSERT OR REPLACE INTO meta VALUES ('facets', ?)", (facets_meta,))
    return n


def rows_of(rec: dict):
    """(article row without path, link rows, coord row or None) for one wdfilter record."""
    qid = int(rec["q"][1:])
    other = rec.get("es") or rec.get("en")
    links = [(qid, p, v) for p in LINK_PROPS for v in rec.get(p, ())]
    c = rec.get("P625")
    return (qid, rec.get("sl", 0), other), links, ((qid, c[0], c[1]) if c else None)


def build(paths, items, out: Path, source: Path, source_sha256: str, subjects=None, topics=None, flags=None) -> dict:
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
    if topics:
        counts["topics"] = add_topics(db, topics)
        db.commit()
    if flags:
        counts["flags"] = add_flags(db, flags)
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
    p.add_argument("--topics", action="append", default=[], help="LANG=tools/topics/parts.py's file: attach broad topics' parts")
    p.add_argument("--flags", help="tools/adultflags/flags_build.py's file: attach adult-content flags")
    a = p.parse_args(argv)
    from libzim.reader import Archive

    for z in a.zims:
        zim = Path(z)
        out = Path(a.out) / f"{zim.stem}.{KIND}"
        out.parent.mkdir(parents=True, exist_ok=True)
        paths = [path for path, _ in articles(Archive(zim))]
        topics = dict(x.split("=", 1) for x in a.topics)
        counts = build(paths, read_items(a.items, set(paths)), out, zim, meta.sha256(zim), a.subjects, topics, a.flags)
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
