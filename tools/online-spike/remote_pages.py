"""Plan O0, question 2: how many remote reads does a topic page need if the app reads a .gqid straight from the GitHub
release with HTTP range requests (a read-only SQLite VFS over HTTP)?

Replays the topic page's index queries (QidIndex.kt, TopicSource.kt) through an apsw VFS that serves the bytes from a
local copy but records every read. A read of a page not yet fetched is a round trip; reads inside one query are
sequential (a B-tree descent), so round trips x RTT estimates the wait. `--remote URL` fetches for real instead.

  ~/.venvs/granger-online/bin/python remote_pages.py /Volumes/X10\\ Pro/granger-ll/gqid-v6/wikipedia_en_top1m_mini_2026-04.gqid
"""
import argparse, random, time
import apsw

PAGE = 4096


class Recorder:
    def __init__(self, path, remote=None, chunk=1):
        self.f = open(path, "rb") if path else None
        self.remote = remote
        self.chunk = chunk  # pages fetched per miss (read-ahead)
        self.have = set()
        self.trips = 0
        self.bytes = 0
        self.wait = 0.0
        if remote:
            import requests
            self.s = requests.Session()
            self.s.headers["User-Agent"] = "Granger-spike"
            r = self.s.head(remote, allow_redirects=False)
            self.url = r.headers.get("location", remote)
            self.size = int(self.s.head(self.url).headers["content-length"])

    def read(self, n, off):
        first, last = off // PAGE, (off + n - 1) // PAGE
        need = [p for p in range(first, last + 1) if p not in self.have]
        if need:
            start = need[0] - (need[0] % self.chunk)
            pages = range(start, max(last + 1, start + self.chunk))
            self.trips += 1
            self.bytes += len(pages) * PAGE
            if self.remote:
                t = time.time()
                r = self.s.get(self.url, headers={"Range": f"bytes={pages[0]*PAGE}-{(pages[-1]+1)*PAGE-1}"})
                assert r.status_code == 206, r.status_code
                self.wait += time.time() - t
                self.cache = getattr(self, "cache", {})
                for i, p in enumerate(pages):
                    self.cache[p] = r.content[i * PAGE:(i + 1) * PAGE]
            self.have.update(pages)
        if self.remote:
            buf = b"".join(self.cache.get(p, b"\0" * PAGE) for p in range(first, last + 1))
            s = off - first * PAGE
            return buf[s:s + n]
        self.f.seek(off)
        return self.f.read(n)


REC = None


class VFS(apsw.VFS):
    def __init__(self):
        super().__init__("rec", "")

    def xOpen(self, name, flags):
        return File(name, flags)


class File:
    def __init__(self, name, flags):
        self.name = name

    def xRead(self, amount, offset):
        return REC.read(amount, offset)

    def xFileSize(self):
        return REC.size if REC.remote else __import__("os").fstat(REC.f.fileno()).st_size

    def xClose(self): pass
    def xLock(self, level): pass
    def xUnlock(self, level): pass
    def xCheckReservedLock(self): return False
    def xSectorSize(self): return PAGE
    def xDeviceCharacteristics(self): return 0
    def xFileControl(self, op, ptr): return False
    def xSync(self, flags): pass
    def xWrite(self, data, offset): raise apsw.ReadOnlyError()
    def xTruncate(self, n): raise apsw.ReadOnlyError()


def q(db, sql, *args):
    return list(db.execute(sql, args))


def step(db, label, fn, log):
    t0, b0 = REC.trips, REC.bytes
    out = fn()
    log.append((label, REC.trips - t0, REC.bytes - b0))
    return out


def topic_page(db, path, broad_extras):
    """The queries a topic page makes against the index, in order (header first, then parts, then rooms)."""
    log = []
    row = step(db, "qid of path", lambda: q(db, "SELECT qid FROM articles WHERE path = ?", path), log)
    if not row:
        return None, log
    qid = row[0][0]
    parts = step(db, "topic_parts", lambda: q(db, "SELECT part, item FROM topic_parts WHERE topic = ? ORDER BY part, ord", qid), log)
    step(db, "topics row", lambda: q(db, "SELECT under, inlinks FROM topics WHERE qid = ?", qid), log)
    items = [i for p, i in parts if i][:40]
    step(db, f"paths of {len(items)} items", lambda: [q(db, "SELECT path, rank FROM articles WHERE qid = ?", i) for i in items], log)
    subj = step(db, "subjects", lambda: q(db, "SELECT subject FROM subjects WHERE qid = ? ORDER BY ord", qid), log)
    step(db, "coords", lambda: q(db, "SELECT lat, lon FROM coords WHERE qid = ?", qid), log)
    step(db, "links (dates, kinds)", lambda: q(db, "SELECT prop, value FROM links WHERE qid = ?", qid), log)
    step(db, "rooms (facets of qid)", lambda: q(db, "SELECT facet FROM facets WHERE qid = ?", qid), log)
    if broad_extras:
        step(db, "holders (scan)", lambda: q(db, f"SELECT DISTINCT topic FROM topic_parts WHERE item = {qid} AND topic != {qid}"), log)
        f = q(db, "SELECT facet FROM facets WHERE qid = ? LIMIT 1", qid)
        if f:
            step(db, "topInFacet (sort)", lambda: q(db, "SELECT a.qid, a.path FROM facets f JOIN articles a ON a.qid = f.qid WHERE f.facet = ? ORDER BY a.rank DESC LIMIT 24", f[0][0]), log)
    return qid, log


def main():
    global REC
    ap = argparse.ArgumentParser()
    ap.add_argument("gqid")
    ap.add_argument("--remote")
    ap.add_argument("--chunk", type=int, default=1)
    ap.add_argument("--n", type=int, default=12)
    ap.add_argument("--rtt", type=float, default=0.15)
    ap.add_argument("--titles", default="Airplane,Napoleon,Photosynthesis,Jazz,London,Concorde")
    a = ap.parse_args()

    # Sample: named broad topics plus random articles (the long tail).
    plain = apsw.Connection(a.gqid, flags=apsw.SQLITE_OPEN_READONLY)
    n_art = plain.execute("SELECT count(*) FROM articles").fetchone()[0]
    random.seed(7)
    tail = [plain.execute("SELECT path FROM articles LIMIT 1 OFFSET ?", (random.randrange(n_art),)).fetchone()[0] for _ in range(a.n)]
    plain.close()

    vfs = VFS()
    REC = Recorder(None if a.remote else a.gqid, a.remote, a.chunk)
    db = apsw.Connection("x", flags=apsw.SQLITE_OPEN_READONLY, vfs="rec")
    step(db, "open + schema", lambda: q(db, "SELECT name FROM sqlite_master"), [])
    print(f"open: {REC.trips} trips")

    for title in a.titles.split(",") + tail:
        cold = (REC.trips, REC.bytes)
        qid, log = topic_page(db, title, broad_extras=title in a.titles.split(","))
        trips = REC.trips - cold[0]
        print(f"\n{title!r} qid={qid}: {trips} trips, {(REC.bytes-cold[1])/1024:.0f} KB, ~{trips*a.rtt:.1f} s at {a.rtt*1000:.0f} ms")
        for label, t, b in log:
            print(f"   {label:28s} {t:5d} trips {b/1024:8.0f} KB")
    print(f"\ntotal {REC.trips} trips, {REC.bytes/1e6:.1f} MB fetched" + (f", measured wait {REC.wait:.1f} s" if a.remote else ""))


if __name__ == "__main__":
    main()
