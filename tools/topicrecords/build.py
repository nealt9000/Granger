#!/usr/bin/env python3
"""Plan O6 Task 1: topic records, from a built QID index (.gqid), for reading online with one HTTP range read a page.

Each article's topic-page data is pre-joined into one zlib-compressed JSON record, sorted by QID, in blocks of 128
records. A small directory says where each block starts. The app fetches the directory once and then reads one block
(~10 KB) for a page. Design and numbers: docs/superpowers/plans/2026-11-18-plan-o0-online-spike.md, Task 2b.

  build.py GQID OUT_DIR [--id PACK_ID]

Writes OUT_DIR/<gqid stem>.grec and .grdir and prints a size report. With --id, also writes the catalog's descriptor
tools/topicrecords/releases/<PACK_ID>.records.json (catalog_gen.py attaches it to every pack built on that .gqid).

Format v2 (all little-endian):

  .grdir  'GRD2', version u16 (2), flags u16 (0), records u32, blocks u32, source .gqid sha256 (32 bytes),
          then per block: first_qid u32, offset u64, length u32, crc32 u32.
  .grec   per block: n u16, then n x (qid u32, len u32), then the n records, each zlib(JSON).
          A block's crc32 covers all of its bytes, header included.

A record (keys left out when empty):
  p   path            r   rank            o   the other language's title (the topic page's "Read in …")
  s   subjects, main first                f   rooms, as facet keys "subject.facet" (codes are per build)
  c   [lat, lon]      l   [[prop, value], …] (Wikidata links)       x   adult flags (ContentFlags), when set
  t   [under, inlinks], for a broad topic, with
  parts  [[part, item, grp, text, note], …]: item is [qid, path, rank, flags] or null
  h   the topics that lead here, best known first, at most 8, each [qid, path, rank, flags]
  hn  how many topics lead here in all
"""
import hashlib, json, os, sqlite3, struct, sys, zlib
from collections import defaultdict

BLOCK = 128
HOLDERS = 8
VERSION = 2
# topic_parts.part values whose item counts as "held" (QidIndex.HELD: kinds, links, people).
HELD = (2, 4, 6)


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.digest()


def facet_keys(db):
    """Code → "subject.facet", from meta `facets` ({subject: [{id, code, …}]}); empty without one."""
    row = db.execute("SELECT value FROM meta WHERE key = 'facets'").fetchone()
    if not row:
        return {}
    keys = {}
    for subject, facets in json.loads(row[0]).items():
        for f in facets:
            keys[f["code"]] = f"{subject}.{f['id']}"
    return keys


def has_table(db, name):
    return db.execute("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?", (name,)).fetchone() is not None


def build(gqid, out):
    db = sqlite3.connect(f"file:{gqid}?mode=ro", uri=True)
    os.makedirs(out, exist_ok=True)
    base = os.path.join(out, os.path.basename(gqid).rsplit(".", 1)[0])

    # The best-known path wins when a QID has more than one (ORDER BY rank: the last one written stays).
    path_rank, other = {}, {}
    for p, q, r, o in db.execute("SELECT path, qid, rank, other FROM articles ORDER BY rank"):
        path_rank[q] = (p, r)
        if o:
            other[q] = o
    flags = dict(db.execute("SELECT qid, kind FROM flags")) if has_table(db, "flags") else {}
    subjects = defaultdict(list)
    if has_table(db, "subjects"):
        for q, s in db.execute("SELECT qid, subject FROM subjects ORDER BY qid, ord"):
            subjects[q].append(s)
    keys = facet_keys(db)
    rooms = defaultdict(list)
    if has_table(db, "facets"):
        for f, q in db.execute("SELECT facet, qid FROM facets ORDER BY facet"):
            if f in keys:
                rooms[q].append(keys[f])
    coords = {}
    if has_table(db, "coords"):
        coords = {q: [round(a, 4), round(b, 4)] for q, a, b in db.execute("SELECT qid, lat, lon FROM coords")}
    links = defaultdict(list)
    if has_table(db, "links"):
        for q, p, v in db.execute("SELECT qid, prop, value FROM links ORDER BY qid, prop, value"):
            links[q].append([p, v])
    topics, parts, held = {}, defaultdict(list), defaultdict(set)
    if has_table(db, "topics"):
        topics = {q: [u, i] for q, u, i in db.execute("SELECT qid, under, inlinks FROM topics")}
        for t, part, item, grp, text, note in db.execute("SELECT topic, part, item, grp, text, note FROM topic_parts ORDER BY topic, part, ord"):
            parts[t].append((part, item, grp, text, note))
            if item and part in HELD and item != t:
                held[item].add(t)

    def ref(q):
        p, r = path_rank.get(q, (None, 0))
        return [q, p, r, flags.get(q, 0)]

    def record(q):
        p, r = path_rank[q]
        rec = {"p": p, "r": r}
        if q in other: rec["o"] = other[q]
        if q in subjects: rec["s"] = subjects[q]
        if q in rooms: rec["f"] = rooms[q]
        if q in coords: rec["c"] = coords[q]
        if q in links: rec["l"] = links[q]
        if q in flags: rec["x"] = flags[q]
        if q in topics:
            rec["t"] = topics[q]
            rec["parts"] = [[part, ref(item) if item else None, grp, text, note] for part, item, grp, text, note in parts[q]]
        if q in held:
            hs = sorted(held[q], key=lambda t: (-path_rank.get(t, ("", 0))[1], t))
            rec["h"] = [ref(t) for t in hs[:HOLDERS]]
            rec["hn"] = len(hs)
        return zlib.compress(json.dumps(rec, separators=(",", ":"), ensure_ascii=False).encode(), 9)

    qids = sorted(path_rank)
    entries, sizes = [], []
    with open(base + ".grec", "wb") as f:
        for i in range(0, len(qids), BLOCK):
            chunk = qids[i:i + BLOCK]
            recs = [record(q) for q in chunk]
            sizes += [len(r) for r in recs]
            block = struct.pack("<H", len(chunk)) + b"".join(struct.pack("<II", q, len(r)) for q, r in zip(chunk, recs)) + b"".join(recs)
            entries.append((chunk[0], f.tell(), len(block), zlib.crc32(block)))
            f.write(block)
    with open(base + ".grdir", "wb") as f:
        f.write(b"GRD2" + struct.pack("<HHII", VERSION, 0, len(qids), len(entries)) + sha256(gqid))
        for first, off, ln, crc in entries:
            f.write(struct.pack("<IQII", first, off, ln, crc))

    sizes.sort()
    if sizes:
        print(f"records {len(qids):,}  .grec {os.path.getsize(base + '.grec') / 1e6:.1f} MB  .grdir {os.path.getsize(base + '.grdir') / 1e3:.0f} KB")
        print(f"record bytes: median {sizes[len(sizes) // 2]}, p99 {sizes[int(len(sizes) * .99)]}, max {sizes[-1]}")
        print(f"blocks {len(entries):,}, largest {max(e[2] for e in entries) / 1e3:.1f} KB")
    return base


class Reader:
    """What the app does, for tests and checks: the directory once, then one block per lookup."""

    def __init__(self, base):
        d = open(base + ".grdir", "rb").read()
        assert d[:4] == b"GRD2", "not a v2 directory"
        version, _, self.records, n = struct.unpack_from("<HHII", d, 4)
        assert version == VERSION
        self.source = d[16:48]
        self.blocks = [struct.unpack_from("<IQII", d, 48 + 20 * i) for i in range(n)]
        self.base = base

    def get(self, qid):
        import bisect
        i = bisect.bisect_right([b[0] for b in self.blocks], qid) - 1
        if i < 0:
            return None
        _, off, ln, crc = self.blocks[i]
        with open(self.base + ".grec", "rb") as f:
            f.seek(off)
            block = f.read(ln)
        if zlib.crc32(block) != crc:
            raise ValueError(f"block {i}: crc mismatch")
        n = struct.unpack_from("<H", block)[0]
        pos = 2 + 8 * n
        for j in range(n):
            q, rl = struct.unpack_from("<II", block, 2 + 8 * j)
            if q == qid:
                return json.loads(zlib.decompress(block[pos:pos + rl]))
            pos += rl
        return None


def descriptor(base, gqid, pack_id, out_dir=None):
    """The catalog's record of one records pair (catalog_gen.py RECORD_KEYS); written as releases/<pack_id>.records.json."""
    d = {
        "id": pack_id,
        "fileName": os.path.basename(base) + ".grec",
        "dirFileName": os.path.basename(base) + ".grdir",
        "sizeBytes": os.path.getsize(base + ".grec"),
        "dirSizeBytes": os.path.getsize(base + ".grdir"),
        "dirSha256": sha256(base + ".grdir").hex(),
        "gqidSha256": sha256(gqid).hex(),
        "version": VERSION,
    }
    out_dir = out_dir or os.path.join(os.path.dirname(os.path.abspath(__file__)), "releases")
    os.makedirs(out_dir, exist_ok=True)
    with open(os.path.join(out_dir, f"{pack_id}.records.json"), "w") as f:
        json.dump(d, f, indent=1)
        f.write("\n")
    return d


if __name__ == "__main__":
    args = sys.argv[1:]
    pack_id = None
    if "--id" in args:
        i = args.index("--id"); pack_id = args[i + 1]; del args[i:i + 2]
    if len(args) != 2:
        sys.exit(__doc__)
    base = build(args[0], args[1])
    if pack_id:
        print(descriptor(base, args[0], pack_id))
