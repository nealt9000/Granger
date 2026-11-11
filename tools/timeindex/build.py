#!/usr/bin/env python3
"""Plan H5b Task 1: build a pack's time index (`.gtime`, spec §4) from its ZIM and the filtered Wikidata slice.

  build.py ITEMS CLASSES LANES OUT_DIR ZIM... [--releases DIR]

ITEMS and CLASSES are wdfilter.py's items.jsonl and classes.jsonl; LANES is lanes.json. Writes
OUT_DIR/<zim stem>.gtime per ZIM (read-only SQLite, one row per dated article) and, with --releases, each release
descriptor <pack id>.json beside the vectors' ones. Run with ~/.venvs/granger-pack/bin/python -I.
"""
import argparse
import hashlib
import json
import sqlite3
import sys
from collections import deque
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "sidecar"))
from zim_articles import articles, is_stub  # noqa: E402,F401  (shared with the other sidecar builders)
import meta as sidecar_meta  # noqa: E402  (tools/sidecar/meta.py: the contract every sidecar's meta keeps)

BUILDER_VERSION = 1

FORMAT = 1
POINT, SPAN, LIFE = 0, 1, 2
HUMAN = "Q5"
OCCUPATIONS_USED = 2   # people take lanes from their first two occupations only (Plan H5b, Decision 4)
DEPTH = 10


def astro_year(time: str) -> int:
    """Wikidata's '+1830-10-04T00:00:00Z' / '-0500-00-00T00:00:00Z' to an astronomical year (500 BC = -499)."""
    y = int(time[:time.index("-", 1)])
    return y + 1 if y < 0 else y


def month_day(time: str) -> int:
    body = time[1:] if time[0] in "+-" else time
    _, m, d = body[:body.index("T")].split("-")[:3]
    return int(m) * 100 + int(d)


def _first(rec, prop):
    v = rec.get(prop)
    return v[0] if v else None


def dates(rec: dict):
    """(kind, start, end, precision) per Plan H5b Decision 2, or None. start/end are (year, md); end may be None."""
    def at(v):
        return (astro_year(v[0]), month_day(v[0]) if v[1] >= 10 else 0)

    if HUMAN in rec.get("P31", []):
        b, d = _first(rec, "P569"), _first(rec, "P570")
        if b:
            return LIFE, at(b), at(d) if d else None, b[1]
        return None
    for s_prop, e_prop in (("P580", "P582"), ("P571", "P576")):
        s, e = _first(rec, s_prop), _first(rec, e_prop)
        if s and e:
            return SPAN, at(s), at(e), s[1]
    for p in ("P585", "P577", "P571", "P580"):
        v = _first(rec, p)
        if v:
            return POINT, at(v), None, v[1]
    return None


class Lanes:
    """Maps an item's classes to a lane bitmask by walking P279 up to the lanes' roots (lanes.json)."""

    def __init__(self, spec: dict, parents: dict, labels: dict | None = None):
        self.ids = [l["id"] for l in spec["lanes"]]
        self.version = spec["version"]
        self.parents = parents
        self.root = {}
        self.excl = {}
        for bit, lane in enumerate(spec["lanes"]):
            for q in lane["roots"]:
                self.root.setdefault(q, set()).add(bit)
            for q in lane.get("except", {}):
                self.excl.setdefault(q, set()).add(bit)
        self.drop = set(spec.get("drop", {}))
        if labels is not None:
            bad = [(q, want, labels.get(q)) for lane in spec["lanes"] for q, want in {**lane["roots"], **lane.get("except", {})}.items()
                   if labels.get(q) != want]
            bad += [(q, want, labels.get(q)) for q, want in spec.get("drop", {}).items() if labels.get(q) != want]
            if bad:
                raise ValueError("lane roots whose labels changed: " + "; ".join(f"{q} wants {w!r}, has {h!r}" for q, w, h in bad))
        self._memo = {}

    def _walk(self, q):
        """(lane bits, excluded bits, dropped) for one class, memoised."""
        if q in self._memo:
            return self._memo[q]
        bits, ex, dropped = set(), set(), False
        seen, todo = {q}, deque([(q, 0)])
        while todo:
            c, d = todo.popleft()
            bits |= self.root.get(c, set())
            ex |= self.excl.get(c, set())
            dropped |= c in self.drop
            if d < DEPTH:
                for p in self.parents.get(c, ()):
                    if p not in seen:
                        seen.add(p)
                        todo.append((p, d + 1))
        self._memo[q] = (frozenset(bits), frozenset(ex), dropped)
        return self._memo[q]

    def of(self, rec: dict):
        """Lane bitmask for an item, or None when it should be dropped (a time page, a list)."""
        classes = rec.get("P31", []) + rec.get("P279", []) + rec.get("P136", []) + rec.get("P106", [])[:OCCUPATIONS_USED]
        bits, ex = set(), set()
        for q in rec.get("P31", []):
            if self._walk(q)[2]:
                return None
        for q in classes:
            b, e, _ = self._walk(q)
            bits |= b
            ex |= e
        if HUMAN in rec.get("P31", []):
            bits.add(self.ids.index("people"))
        mask = 0
        for b in bits - ex:
            mask |= 1 << b
        return mask


SCHEMA = """
CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT NOT NULL);
CREATE TABLE marks(qid INTEGER NOT NULL, path TEXT NOT NULL, title TEXT NOT NULL, kind INTEGER NOT NULL,
  y0 INTEGER NOT NULL, md0 INTEGER NOT NULL, y1 INTEGER, md1 INTEGER, prec INTEGER NOT NULL,
  lanes INTEGER NOT NULL, rank INTEGER NOT NULL, lat REAL, lon REAL);
"""


def build(pairs, items: dict, lanes: Lanes, out: Path, meta: dict, last_year: int = 2026) -> dict:
    """Write the .gtime file from (path, title) pairs; returns counts."""
    if out.exists():
        out.unlink()
    db = sqlite3.connect(out)
    db.executescript(SCHEMA)
    counts = {"articles": 0, "matched": 0, "rows": 0, "dropped": 0}
    rows = []
    for path, title in pairs:
        counts["articles"] += 1
        rec = items.get(path)
        if not rec:
            continue
        counts["matched"] += 1
        d = dates(rec)
        if not d:
            continue
        mask = lanes.of(rec)
        if mask is None:
            counts["dropped"] += 1
            continue
        kind, (y0, md0), end, prec = d
        if y0 > last_year:   # the timeline ends at the present: predictions (2032 Olympics) and errors (X-Men in 6767) go
            counts["future"] = counts.get("future", 0) + 1
            continue
        y1, md1 = end if end else (None, None)
        lat, lon = rec.get("P625", (None, None))
        rows.append((int(rec["q"][1:]), path, title, kind, y0, md0, y1, md1, prec, mask, rec.get("sl", 0), lat, lon))
    db.executemany("INSERT INTO marks VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)", sorted(rows, key=lambda r: (r[4], -r[10])))
    counts["rows"] = len(rows)
    db.execute("CREATE INDEX marks_y0 ON marks(y0)")
    db.execute("CREATE INDEX marks_rank ON marks(rank DESC)")
    db.executemany("INSERT INTO meta VALUES (?,?)", [(k, str(v)) for k, v in
                                                     {"format": FORMAT, "lanesVersion": lanes.version, "lanes": ",".join(lanes.ids), **meta}.items()])
    db.commit()
    db.execute("VACUUM")
    db.close()
    return counts


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def load_classes(path):
    parents, labels = {}, {}
    with open(path, encoding="utf8") as f:
        for line in f:
            r = json.loads(line)
            parents[r["q"]] = r["p279"]
            labels[r["q"]] = r.get("l")
    return parents, labels


def load_items(path):
    with open(path, encoding="utf8") as f:
        return {r["t"]: r for r in map(json.loads, f)}


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("items"); p.add_argument("classes"); p.add_argument("lanes"); p.add_argument("out"); p.add_argument("zims", nargs="+")
    p.add_argument("--releases", help="write the release descriptor here")
    p.add_argument("--dump-date", default="2026-11-06")
    a = p.parse_args(argv)
    from libzim.reader import Archive

    spec = json.load(open(a.lanes, encoding="utf8"))
    parents, labels = load_classes(a.classes)
    lanes = Lanes(spec, parents, labels)
    items = load_items(a.items)
    print(f"classes {len(parents):,} items {len(items):,}", file=sys.stderr, flush=True)
    for z in a.zims:
        zim = Path(z)
        out = Path(a.out) / (zim.stem + ".gtime")
        out.parent.mkdir(parents=True, exist_ok=True)
        source = sha256(zim)
        counts = build(articles(Archive(zim)), items, lanes, out, {"sourceSha256": source, "dumpDate": a.dump_date, "zim": zim.name},
                       last_year=int(a.dump_date[:4]))
        # The shared sidecar keys beside the time index's own (Linked Library spec §5.1); the phone reads only "format".
        db = sqlite3.connect(out)
        sidecar_meta.write_meta(db, "gtime", FORMAT, zim, "tools/timeindex/build.py", BUILDER_VERSION, source_sha256=source)
        db.commit()
        db.close()
        print(zim.name, counts, f"{out.stat().st_size:,} bytes", file=sys.stderr, flush=True)
        if a.releases:
            pack_id = zim.stem.rsplit("_", 1)[0]
            desc = {"id": pack_id, "kind": "pack", "fileName": out.name, "sizeBytes": out.stat().st_size, "sha256": sha256(out),
                    "count": counts["rows"], "format": FORMAT, "lanesVersion": lanes.version, "sourceSha256": source, "url": None}
            Path(a.releases).mkdir(parents=True, exist_ok=True)
            (Path(a.releases) / f"{pack_id}.json").write_text(json.dumps(desc, indent=1) + "\n")


if __name__ == "__main__":
    main()
