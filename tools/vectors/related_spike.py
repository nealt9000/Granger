"""LL2 Task 1 prototype: Related from the shipped vectors (Mac, quality first).

The article's own chunk rows are averaged into one query, which is searched over every file with the phone's int8
scoring and IVF probes; hits are grouped by article (or book) and the page itself dropped.
"""
import struct, sys, time, glob, os
from collections import defaultdict
import numpy as np

D = "/Volumes/X10 Pro/granger-ll/ll2-spike"
PROBES = 1024
K = 256


class G:
    def __init__(self, path):
        self.path = path
        self.name = os.path.basename(path)
        data = open(path, "rb").read()
        self.data = data
        assert data[:4] == b"GVEC"
        self.version, _, self.dims, self.count = struct.unpack_from("<HHII", data, 4)
        (keys_at,) = struct.unpack_from("<Q", data, 80)
        o = 128
        self.q = np.frombuffer(data, np.int8, self.count * self.dims, o).reshape(self.count, self.dims)
        o += self.count * self.dims
        self.scales = np.frombuffer(data, "<f4", self.count, o)
        self.offsets = np.frombuffer(data, "<u4", self.count, keys_at)
        self.blob = keys_at + 4 * self.count + 4
        self.lists = None
        if self.version >= 2:
            (lists_at,) = struct.unpack_from("<Q", data, 88)
            (n,) = struct.unpack_from("<I", data, lists_at)
            c = lists_at + 4
            self.cents = np.frombuffer(data, np.int8, n * self.dims, c).reshape(n, self.dims)
            self.cscales = np.frombuffer(data, "<f4", n, c + n * self.dims)
            self.starts = np.frombuffer(data, "<u4", n + 1, c + n * self.dims + 4 * n)
            self.lists = n
        self._keys = None

    def key(self, row):
        at = self.blob + int(self.offsets[row])
        ordinal, n = struct.unpack_from("<IH", self.data, at)
        return self.data[at + 6:at + 6 + n].decode("utf-8"), ordinal

    def keymap(self):
        if self._keys is None:
            m = defaultdict(list)
            for r in range(self.count):
                m[self.key(r)[0]].append(r)
            self._keys = m
        return self._keys


def quantize(v):
    v = v / max(np.linalg.norm(v), 1e-12)
    s = max(np.abs(v).max(), 1e-12) / 127.0
    return np.clip(np.rint(v / s), -127, 127).astype(np.int32), s


def search(query, files, k=K, probes=PROBES):
    qq, qs = quantize(query)
    # nearest lists over all v2 files
    cand = []
    for f in files:
        if f.lists:
            sc = (f.cents.astype(np.int32) @ qq) * f.cscales * qs
            for l in np.argsort(-sc)[:probes]:
                cand.append((sc[l], f, l))
    cand.sort(key=lambda t: -t[0])
    ranges = [(f, int(f.starts[l]), int(f.starts[l + 1])) for _, f, l in cand[:probes]]
    ranges += [(f, 0, f.count) for f in files if not f.lists]
    hits = []
    byfile = defaultdict(list)
    for f, a, b in ranges:
        byfile[f].append(np.arange(a, b))
    for f, rs in byfile.items():
        rows = np.concatenate(rs)
        if len(rows) == 0:
            continue
        s = (f.q[rows].astype(np.int32) @ qq) * f.scales[rows] * qs
        s = np.nan_to_num(s, nan=-9, posinf=-9, neginf=-9)
        top = np.argsort(-s)[:k]
        hits += [(float(s[i]), f, int(rows[i])) for i in top]
    hits.sort(key=lambda t: -t[0])
    return hits[:k]


def related(path, home, files, k=K):
    rows = home.keymap().get(path)
    if not rows:
        return None
    v = (home.q[rows].astype(np.float32) * home.scales[rows][:, None]).mean(axis=0)
    wiki = [f for f in files if not f.name.startswith("gc-")]
    bks = [f for f in files if f.name.startswith("gc-")]
    hits = search(v, wiki, 128) + search(v, bks, 64)
    hits.sort(key=lambda t: -t[0])
    groups = {}
    for s, f, r in hits:
        key, ordinal = f.key(r)
        is_book = f.name.startswith("gc-") or f.name.startswith("cl-")
        g = (f.name, None if is_book else key)
        if key == path and not is_book:
            continue
        lang = "es" if ("_es_" in f.name or f.name.startswith("gc-es-")) else "en"
        if lang != LANG(home):
            continue
        if g not in groups:
            groups[g] = (s, f.name, key, ordinal, 1)
        else:
            b = groups[g]
            groups[g] = (b[0], b[1], b[2], b[3], b[4] + 1)
    seen, out = set(), []
    for g in sorted(groups.values(), key=lambda t: -t[0]):
        t = g[2] if not g[1].startswith("gc-") else g[1]
        if t in seen: continue
        seen.add(t); out.append(g)
    arts = [g for g in out if not g[1].startswith("gc-")][:8]
    books = [g for g in out if g[1].startswith("gc-")][:4]
    return len(rows), sorted(arts + books, key=lambda t: -t[0])


def LANG(f):
    return "es" if "_es_" in f.name else "en"


def main():
    t = time.time()
    wiki = [G(p) for p in sorted(glob.glob(D + "/wikipedia_*.gvec"))]
    books = [G(p) for p in sorted(glob.glob(D + "/books/*.gvec"))]
    books = [b for b in books if b.dims == wiki[0].dims]
    files = wiki + books
    print(f"loaded {len(wiki)} wiki + {len(books)} books in {time.time()-t:.1f}s", file=sys.stderr)
    for w in wiki:
        t = time.time(); m = w.keymap()
        print(f"{w.name}: {w.count} rows, {len(m)} keys, v{w.version}, lists {w.lists}, keymap {time.time()-t:.1f}s; sample keys {list(m)[:3]}", file=sys.stderr)
    en = next(w for w in wiki if "en_top" in w.name)
    es = next(w for w in wiki if "es_top" in w.name)
    for title, home in [("London", en), ("Napoleon", en), ("Photosynthesis", en), ("Jane_Austen", en), ("Don_Quixote", en),
                        ("Zaragoza", es), ("Insulin", en), ("Byzantine_Empire", en), ("Jazz", en), ("Volcano", en),
                        ("Don_Quijote_de_la_Mancha", es), ("Miguel_de_Cervantes", es)]:
        m = home.keymap()
        path = next((p for p in (title, "A/" + title) if p in m), None)
        if path is None:
            print(f"\n## {title}: not found"); continue
        t = time.time()
        n, groups = related(path, home, files)
        print(f"\n## {title} ({home.name[:15]}, {n} chunks, {time.time()-t:.2f}s)")
        for s, fname, key, ordinal, c in groups:
            label = key if not fname.startswith("gc-") else f"[book] {fname.rsplit('_',1)[0]} · {key}#{ordinal}"
            print(f"  {s:.3f}  {label}  ({fname[:18]}, {c} hits)")


if __name__ == "__main__":
    main()
