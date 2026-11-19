"""Plan O0, question 2b: instead of reading SQLite over HTTP, pre-join what a topic page needs per article into one
compressed record, so a page costs two range reads (a block's header, then the record) after a small directory that the
app fetches once.

File layout (all little-endian):
  directory file (.grdir):  'GRD1', count u32, then per block: first_qid u32, offset u64  (one block per BLOCK records)
  records file (.grec):     per block: n u16, then n x (qid u32, len u32), then the n zlib-compressed JSON records

Builds from a local .gqid and reports sizes; `--probe` times reads against a local file to check the arithmetic.

  ~/.venvs/granger-online/bin/python records.py /Volumes/X10\\ Pro/granger-ll/gqid-v6/wikipedia_en_top1m_mini_2026-04.gqid out/
"""
import json, os, sqlite3, struct, sys, zlib
from collections import defaultdict

BLOCK = 128
HOLDERS = 8
ROOM_TOP = 60


def build(gqid, out):
    db = sqlite3.connect(gqid)
    os.makedirs(out, exist_ok=True)
    base = os.path.join(out, os.path.basename(gqid).rsplit(".", 1)[0])

    path_rank = {q: (p, r) for p, q, r in db.execute("SELECT path, qid, rank FROM articles ORDER BY rank")}
    subjects = defaultdict(list)
    for q, s in db.execute("SELECT qid, subject FROM subjects ORDER BY qid, ord"):
        subjects[q].append(s)
    rooms = defaultdict(list)
    for f, q in db.execute("SELECT facet, qid FROM facets"):
        rooms[q].append(f)
    coords = {q: (round(a, 4), round(b, 4)) for q, a, b in db.execute("SELECT qid, lat, lon FROM coords")}
    links = defaultdict(list)
    for q, p, v in db.execute("SELECT qid, prop, value FROM links"):
        links[q].append((p, v))
    topics = {q: (u, i) for q, u, i in db.execute("SELECT qid, under, inlinks FROM topics")}
    parts = defaultdict(list)
    held = defaultdict(set)
    for t, part, item, grp, text, note in db.execute("SELECT topic, part, item, grp, text, note FROM topic_parts ORDER BY topic, part, ord"):
        parts[t].append((part, item, grp, text, note))
        if item and part in (2, 4, 6) and item != t:
            held[item].add(t)

    def item_ref(q):
        pr = path_rank.get(q)
        return [q, pr[0], pr[1]] if pr else [q, None, 0]

    def record(q):
        p, r = path_rank[q]
        rec = {"p": p, "r": r}
        if q in subjects: rec["s"] = subjects[q]
        if q in rooms: rec["f"] = rooms[q]
        if q in coords: rec["c"] = coords[q]
        if q in links: rec["l"] = links[q]
        if q in topics:
            rec["t"] = topics[q]
            rec["parts"] = [[part, item_ref(item) if item else None, grp, text, note] for part, item, grp, text, note in parts[q]]
        if q in held:
            hs = sorted(held[q], key=lambda t: -path_rank.get(t, ("", 0))[1])
            rec["h"] = [item_ref(t) for t in hs[:HOLDERS]]
            rec["hn"] = len(hs)
        return zlib.compress(json.dumps(rec, separators=(",", ":"), ensure_ascii=False).encode(), 9)

    qids = sorted(path_rank)
    dir_entries = []
    sizes = []
    with open(base + ".grec", "wb") as f:
        for i in range(0, len(qids), BLOCK):
            chunk = qids[i:i + BLOCK]
            recs = [record(q) for q in chunk]
            sizes += [len(r) for r in recs]
            dir_entries.append((chunk[0], f.tell()))
            f.write(struct.pack("<H", len(chunk)))
            for q, r in zip(chunk, recs):
                f.write(struct.pack("<II", q & 0xFFFFFFFF, len(r)))
            for r in recs:
                f.write(r)
    with open(base + ".grdir", "wb") as f:
        f.write(b"GRD1" + struct.pack("<I", len(dir_entries)))
        for q, off in dir_entries:
            f.write(struct.pack("<IQ", q & 0xFFFFFFFF, off))

    # Room lists: each room's best-known articles, one record per facet (the room spreads online).
    by_room = defaultdict(list)
    for q, fs in rooms.items():
        for fc in fs:
            by_room[fc].append(q)
    room_bytes = 0
    for fc, qs in by_room.items():
        qs.sort(key=lambda q: -path_rank[q][1])
        room_bytes += len(zlib.compress(json.dumps([item_ref(q) for q in qs[:ROOM_TOP]]).encode(), 9))

    sizes.sort()
    rec_size = os.path.getsize(base + ".grec")
    print(f"records: {len(qids):,}  file {rec_size/1e6:.1f} MB  directory {os.path.getsize(base + '.grdir')/1e3:.0f} KB")
    print(f"record bytes: median {sizes[len(sizes)//2]}, p90 {sizes[int(len(sizes)*.9)]}, p99 {sizes[int(len(sizes)*.99)]}, max {sizes[-1]}")
    print(f"block header: {2 + 8*BLOCK} bytes; rooms: {len(by_room)} lists, {room_bytes/1e3:.0f} KB compressed")
    big = [q for q in topics]
    tb = sorted(len(record(q)) for q in big)
    if tb:
        print(f"broad-topic records: {len(tb)}, median {tb[len(tb)//2]} B, max {tb[-1]} B")
    return base


def read(base, qid):
    """What the app would do: directory (cached) → block header (one range read) → record (one range read)."""
    import bisect
    d = open(base + ".grdir", "rb").read()
    n = struct.unpack_from("<I", d, 4)[0]
    firsts = [struct.unpack_from("<IQ", d, 8 + 12 * i) for i in range(n)]
    i = bisect.bisect_right([f[0] for f in firsts], qid) - 1
    off = firsts[i][1]
    with open(base + ".grec", "rb") as f:
        f.seek(off)
        head = f.read(2 + 8 * BLOCK)  # range read 1
        cnt = struct.unpack_from("<H", head)[0]
        pos = off + 2 + 8 * cnt
        for j in range(cnt):
            q, ln = struct.unpack_from("<II", head, 2 + 8 * j)
            if q == qid:
                f.seek(pos)
                return json.loads(zlib.decompress(f.read(ln)))  # range read 2
            pos += ln
    return None


if __name__ == "__main__":
    b = build(sys.argv[1], sys.argv[2])
    for q in (197, 517, 6505, 387015):
        r = read(b, q)
        print(q, r["p"] if r else None, "parts" if r and "parts" in r else "", len(json.dumps(r)) if r else 0)
