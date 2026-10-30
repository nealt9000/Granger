#!/usr/bin/env python3
"""Reads a ZIM's header, mime list, `M/` metadata and single entries with small range reads, local file or HTTP(S).

Nothing is downloaded whole: a pack's metadata costs a few hundred KB of range requests (Plan KB's audit read all 924
catalog packs in under eight minutes). Validated byte for byte against libzim.

  zim_probe.py metadata FILE_OR_URL                 # the pack's metadata and main page, as JSON
  zim_probe.py entry FILE_OR_URL PATH --out FILE    # one entry's bytes (redirects followed), e.g. database.js
  zim_probe.py titles FILE_OR_URL --out FILE [--redirects FILE] [--budget MB]
                                                    # its article paths, one per line, and counts as JSON
  zim_probe.py probe --catalog catalog/catalog.json --out tools/catalog-gen/pack_contents.json \\
                     [--cache probe.jsonl] [--workers 8]
                                                    # every pack's scraper, refreshing pack_contents.json

`probe` keeps one line per pack in the cache (default: next to the output, `.probe.jsonl`), so a rerun asks only for
the packs it hasn't read yet. pack_contents.json maps each ZIM name to its `Scraper` metadata; catalog_gen.py marks a
pack's `content` "books" from it (Plan KB Decision 2).

`titles` reads only the directory entries (Plan WF Decision 1): libzim writes them back to back, in path order, just
before the path-pointer list, so one stream over that region lists every path without touching article content. An
article is a namespace C (A in old ZIMs) text/html entry that isn't a redirect; redirects are listed apart.
"""
import argparse
import concurrent.futures
import json
import lzma
import pathlib
import random
import struct
import sys
import threading
import time
import unicodedata
import urllib.error
import urllib.parse
import urllib.request

BUDGET = 64 * 1024 * 1024
BLOCK = 16 * 1024
WANT = ("Counter", "Scraper", "Title", "Description", "Name", "Tags", "Creator", "Publisher", "Language", "Date",
        "Flavour", "Source")
UA = "Granger-zim-probe/1.0 (metadata only; range requests)"
MAGIC = 0x044D495A


class BudgetExceeded(Exception):
    pass


class FatalHTTP(Exception):
    pass


class Reader:
    """Block-cached random access over a local file or an HTTP(S) URL, with a byte budget."""

    def __init__(self, src, budget=BUDGET, sleep=time.sleep):
        self.src, self.budget, self.sleep = src, budget, sleep
        self.fetched = 0
        self.requests = 0
        self.cache = {}
        self.final_url = None
        self.file = None
        if src.startswith(("http://", "https://")):
            # download.kiwix.org redirects to a mirror; resolve once and reuse the final URL.
            self.final_url = self._get(src, 0, 0)[1]
        else:
            self.file = open(src, "rb")

    def close(self):
        if self.file:
            self.file.close()

    def _get(self, url, a, b):
        last = None
        for attempt in range(5):
            request = urllib.request.Request(url, headers={"Range": f"bytes={a}-{b}", "User-Agent": UA})
            try:
                with urllib.request.urlopen(request, timeout=60) as response:
                    self.requests += 1
                    if response.status != 206:
                        raise IOError(f"server ignored Range ({response.status})")
                    data = response.read()
                    self.fetched += len(data)
                    return data, response.url
            except urllib.error.HTTPError as error:
                self.requests += 1
                if error.code not in (429, 500, 502, 503, 504):
                    raise FatalHTTP(f"HTTP {error.code}") from error
                last = error
            except (OSError, IOError) as error:
                last = error
            self.sleep(min(30, 1.5 * 2 ** attempt + random.random()))
        raise IOError(f"giving up: {last}")

    def raw(self, a, n):
        if n <= 0:
            return b""
        if self.fetched + n > self.budget:
            raise BudgetExceeded(f"would fetch {self.fetched + n} bytes")
        if self.file:
            self.file.seek(a)
            data = self.file.read(n)
            self.fetched += len(data)
            self.requests += 1
            return data
        return self._get(self.final_url, a, a + n - 1)[0]

    def read(self, a, n):
        """[n] bytes at [a], through a cache of BLOCK-sized blocks (pointer lists and dirents are read many times)."""
        b0, b1 = a // BLOCK, (a + n - 1) // BLOCK
        missing = [b for b in range(b0, b1 + 1) if b not in self.cache]
        i = 0
        while i < len(missing):  # consecutive missing blocks in one request
            j = i
            while j + 1 < len(missing) and missing[j + 1] == missing[j] + 1:
                j += 1
            data = self.raw(missing[i] * BLOCK, (missing[j] - missing[i] + 1) * BLOCK)
            for k, b in enumerate(range(missing[i], missing[j] + 1)):
                self.cache[b] = data[k * BLOCK:(k + 1) * BLOCK]
            i = j + 1
        out = b"".join(self.cache[b] for b in range(b0, b1 + 1))
        offset = a - b0 * BLOCK
        return out[offset:offset + n]

    def stream(self, a, b, chunk=64 * 1024):
        """The raw bytes [a, b) in growing chunks."""
        pos = a
        while pos < b:
            n = min(chunk, b - pos)
            yield self.raw(pos, n)
            pos += n
            chunk = min(chunk * 2, 4 * 1024 * 1024)


class Zim:
    """A ZIM file's header, read through a [Reader]."""

    def __init__(self, reader):
        self.r = reader
        h = reader.read(0, 80)
        magic, self.major, self.minor = struct.unpack_from("<IHH", h, 0)
        if magic != MAGIC:
            raise ValueError("not a ZIM (bad magic)")
        self.uuid = h[8:24].hex()
        (self.entry_count, self.cluster_count, self.path_ptr, self.title_ptr, self.cluster_ptr, self.mime_pos,
         self.main_page, self.layout_page, self.checksum_pos) = struct.unpack_from("<IIQQQQIIQ", h, 24)
        buf = b""
        while b"\x00\x00" not in buf:  # zero-terminated strings, ending with an empty one
            buf += reader.read(self.mime_pos + len(buf), 4096)
            if len(buf) > 1 << 20:
                raise ValueError("mime list too long")
        self.mimes = [m.decode("utf-8", "replace") for m in buf[:buf.index(b"\x00\x00")].split(b"\x00")]

    def dirent_at(self, off):
        d = self.r.read(off, 512)
        mime, = struct.unpack_from("<H", d, 0)
        ns = chr(d[3])
        if mime == 0xFFFF:
            target, = struct.unpack_from("<I", d, 8)
            p, e = 12, {"mime": None, "ns": ns, "redirect": target}
        elif mime in (0xFFFE, 0xFFFD):  # link target / deleted, in old formats
            p, e = 8, {"mime": None, "ns": ns}
        else:
            cluster, blob = struct.unpack_from("<II", d, 8)
            p = 16
            e = {"mime": self.mimes[mime] if mime < len(self.mimes) else mime, "ns": ns, "cluster": cluster,
                 "blob": blob}
        while d.find(b"\x00", p) < 0 or d.find(b"\x00", d.find(b"\x00", p) + 1) < 0:
            d += self.r.read(off + len(d), 512)
        z = d.index(b"\x00", p)
        e["path"] = d[p:z].decode("utf-8", "replace")
        z2 = d.index(b"\x00", z + 1)
        e["title"] = d[z + 1:z2].decode("utf-8", "replace")
        return e

    def dirent(self, index):
        off, = struct.unpack("<Q", self.r.read(self.path_ptr + 8 * index, 8))
        return self.dirent_at(off)

    def lower_bound(self, ns, path):
        """The index of the first entry at or after ([ns], [path]) in path order."""
        lo, hi = 0, self.entry_count
        target = (ns.encode(), path.encode())
        while lo < hi:
            mid = (lo + hi) // 2
            e = self.dirent(mid)
            if (e["ns"].encode(), e["path"].encode()) < target:
                lo = mid + 1
            else:
                hi = mid
        return lo

    def find(self, path, ns="C"):
        """The dirent at [path] in namespace [ns] (redirects followed), or None."""
        i = self.lower_bound(ns, path)
        if i >= self.entry_count:
            return None
        e = self.dirent(i)
        if e["ns"] != ns or e["path"] != path:
            return None
        return self.follow(e)

    def dirents(self):
        """
        Every dirent as (ns, path, kind, cluster, blob) in path order, kind "article", "redirect" or "other" (cluster
        and blob None for a redirect), streamed from the
        directory region [first dirent, path-pointer list). Falls back to one read per path pointer when the region
        doesn't parse to exactly [entry_count] dirents.
        """
        start, = struct.unpack("<Q", self.r.read(self.path_ptr, 8))
        end = self.path_ptr
        last, = struct.unpack("<Q", self.r.read(self.path_ptr + 8 * (self.entry_count - 1), 8))
        if not start <= last < end:
            yield from self._dirents_by_pointer()
            return
        out = []
        buf, pos = b"", 0
        for chunk in self.r.stream(start, end, chunk=1 << 20):
            buf += chunk
            while True:
                parsed = _parse_dirent(buf, pos)
                if parsed is None:
                    break
                e, pos = parsed
                out.append(e)
            buf, pos = buf[pos:], 0
            if len(out) > self.entry_count:
                break
        # Index i must be the i-th entry in path order, as the path-pointer list (and redirect targets) count them.
        if buf or len(out) != self.entry_count or any(
                (a[0], a[1].encode()) >= (b[0], b[1].encode()) for a, b in zip(out, out[1:])):
            yield from self._dirents_by_pointer()
            return
        for ns, path, mime, cluster, blob in out:
            yield ns, path, self._kind(ns, mime), cluster, blob

    def _kind(self, ns, mime):
        if mime == 0xFFFF:
            return "redirect"
        if ns in ("C", "A") and mime < len(self.mimes) and self.mimes[mime].startswith("text/html"):
            return "article"
        return "other"

    def _dirents_by_pointer(self):
        for i in range(self.entry_count):
            e = self.dirent(i)
            mime = 0xFFFF if "redirect" in e else (self.mimes.index(e["mime"]) if e["mime"] in self.mimes else -1)
            yield (e["ns"], e["path"], self._kind(e["ns"], mime) if mime >= 0 else "other", e.get("cluster"),
                   e.get("blob"))

    def follow(self, e, hops=5):
        while "redirect" in e and hops > 0:
            e = self.dirent(e["redirect"])
            hops -= 1
        return e

    def metadata_entries(self):
        lo, hi = self.lower_bound("M", ""), self.lower_bound("N", "")
        return [self.dirent(i) for i in range(lo, min(hi, lo + 2000))]

    def cluster_bounds(self, c):
        if c + 1 < self.cluster_count:
            return struct.unpack("<QQ", self.r.read(self.cluster_ptr + 8 * c, 16))
        return struct.unpack("<Q", self.r.read(self.cluster_ptr + 8 * c, 8))[0], self.checksum_pos

    def blobs(self, c, wanted):
        """{blob index: bytes} for the [wanted] blobs of cluster [c], decompressing no further than needed."""
        a, b = self.cluster_bounds(c)
        info = self.r.read(a, 1)[0]
        comp, extended = info & 0x0F, bool(info & 0x10)
        size = 8 if extended else 4
        fmt = "<%dQ" if extended else "<%dI"
        last = max(wanted)
        if comp in (0, 1):  # stored
            first = struct.unpack("<Q" if extended else "<I", self.r.read(a + 1, size))[0]
            if last + 1 >= first // size:
                raise ValueError("blob index out of range")
            offs = struct.unpack(fmt % (last + 2), self.r.read(a + 1, size * (last + 2)))
            return {w: self.r.raw(a + 1 + offs[w], offs[w + 1] - offs[w]) for w in wanted}
        if comp == 4:
            feed = lzma.LZMADecompressor().decompress
        elif comp == 5:
            import zstandard  # only zstd-compressed clusters need it
            feed = zstandard.ZstdDecompressor().decompressobj().decompress
        else:
            raise ValueError(f"unsupported compression {comp}")
        data = bytearray()
        offs = None
        for chunk in self.r.stream(a + 1, b):
            data += feed(chunk)
            if offs is None and len(data) >= size:
                first = struct.unpack_from("<Q" if extended else "<I", data, 0)[0]
                if len(data) >= first:
                    offs = struct.unpack_from(fmt % (first // size), data, 0)
            if offs is not None and len(data) >= offs[last + 1]:
                break
        if offs is None:
            raise ValueError("cluster truncated")
        return {w: bytes(data[offs[w]:offs[w + 1]]) for w in wanted}

    def content(self, e):
        """The bytes of dirent [e] (not a redirect)."""
        return self.blobs(e["cluster"], [e["blob"]])[e["blob"]]

    def metadata(self, keys=WANT):
        ents = [e for e in self.metadata_entries() if e["path"] in keys and "cluster" in e]
        by_cluster = {}
        for e in ents:
            by_cluster.setdefault(e["cluster"], []).append(e)
        md = {}
        for c, es in by_cluster.items():
            got = self.blobs(c, [e["blob"] for e in es])
            for e in es:
                md[e["path"]] = got[e["blob"]].decode("utf-8", "replace")
        return md

    def main_entry(self):
        if self.main_page == 0xFFFFFFFF:
            return None
        return self.follow(self.dirent(self.main_page))


def _parse_dirent(buf, pos):
    """
    ((ns, path, mime index, cluster, blob), next position) for the dirent at [pos] in [buf], or None when it runs off
    the end. Cluster and blob are None for a redirect.
    """
    if len(buf) - pos < 8:
        return None
    mime, extra, ns = struct.unpack_from("<HBB", buf, pos)
    p = pos + (12 if mime == 0xFFFF else 8 if mime in (0xFFFE, 0xFFFD) else 16)
    z = buf.find(b"\x00", p)
    if z < 0:
        return None
    z2 = buf.find(b"\x00", z + 1)
    if z2 < 0 or z2 + 1 + extra > len(buf):
        return None
    cluster, blob = struct.unpack_from("<II", buf, pos + 8) if p == pos + 16 else (None, None)
    return (chr(ns), buf[p:z].decode("utf-8", "replace"), mime, cluster, blob), z2 + 1 + extra


def normalise_path(path):
    """A path as Plan WF compares them: URL-decoded, Unicode NFC, spaces as underscores."""
    return unicodedata.normalize("NFC", urllib.parse.unquote(path)).replace(" ", "_")


# Older mwoffliner writes a redirect to a section ("Abyssinian lion" -> Lion#Subspecies) as a ~220-byte HTML page with
# a meta refresh, after the articles, thousands to a cluster. A cluster that averages under this many stored bytes a
# blob, over at least STUB_MIN_BLOBS blobs, holds those pages, not articles (real articles, even mini ones, run to
# kilobytes).
STUB_BYTES_PER_BLOB = 120
STUB_MIN_BLOBS = 64


def stub_clusters(z, blobs):
    """
    The clusters, of [blobs] ({cluster: blob count}), of at least STUB_MIN_BLOBS blobs averaging under
    STUB_BYTES_PER_BLOB stored bytes.
    """
    if not blobs:
        return set()
    n = z.cluster_count
    ptrs = struct.unpack(f"<{n}Q", z.r.read(z.cluster_ptr, 8 * n))
    ends = ptrs[1:] + (z.checksum_pos,)
    return {c for c, k in blobs.items()
            if c < n and k >= STUB_MIN_BLOBS and (ends[c] - ptrs[c]) / k < STUB_BYTES_PER_BLOB}


def titles(src, budget=BUDGET, reader=Reader, max_region=None):
    """
    The pack at [src]'s article paths and redirect paths (each sorted), with counts and bytes fetched. Section
    redirect pages (see STUB_BYTES_PER_BLOB) are counted as `stubCount` and listed with the redirects. An "error"
    instead when the directory region is bigger than [max_region] bytes (checked before it is read) or the budget runs
    out.
    """
    t = time.time()
    r = reader(src, budget)
    out = {"src": src}
    try:
        z = Zim(r)
        start, = struct.unpack("<Q", r.read(z.path_ptr, 8))
        out.update(version=f"{z.major}.{z.minor}", entryCount=z.entry_count, region=z.path_ptr - start)
        if max_region is not None and z.path_ptr - start > max_region:
            raise BudgetExceeded(f"directory region is {z.path_ptr - start} bytes, over {max_region}")
        html, redirects, other = [], [], 0
        blobs = {}  # cluster -> blobs in it, from the highest blob index any entry names
        for ns, path, kind, cluster, blob in z.dirents():
            if cluster is not None:
                blobs[cluster] = max(blobs.get(cluster, 0), blob + 1)
            if kind == "article":
                html.append((path, cluster))
            elif kind == "redirect" and ns in ("C", "A"):
                redirects.append(path)
            else:
                other += 1
        tiny = stub_clusters(z, blobs)
        articles = [path for path, c in html if c not in tiny]
        stubs = [path for path, c in html if c in tiny]
        out.update(articles=articles, redirects=sorted(redirects + stubs), articleCount=len(articles),
                   redirectCount=len(redirects), stubCount=len(stubs), otherCount=other)
    except BudgetExceeded as error:
        out["error"] = f"budget: {error}"
    except Exception as error:  # noqa: BLE001
        out["error"] = f"{type(error).__name__}: {error}"
    finally:
        r.close()
    out.update(bytes=r.fetched, requests=r.requests, seconds=round(time.time() - t, 2), mirror=r.final_url)
    return out


def parse_counter(text):
    """A ZIM's `Counter` metadata ("text/html=12;image/png=3") as {mime: count}."""
    counts = {}
    for part in (text or "").split(";"):
        if "=" in part:
            mime, n = part.rsplit("=", 1)
            try:
                counts[mime] = int(n)
            except ValueError:
                pass
    return counts


def probe(src, budget=BUDGET, reader=Reader):
    """The pack at [src]: its version, entry count, mime list, metadata and main page; an "error" when it can't."""
    t = time.time()
    r = reader(src, budget)
    out = {"src": src}
    try:
        z = Zim(r)
        out.update(version=f"{z.major}.{z.minor}", entryCount=z.entry_count, clusterCount=z.cluster_count,
                   mimes=z.mimes)
        out["metadata"] = z.metadata()
        main = z.main_entry()
        if main:
            out["mainPage"] = {"path": main["path"], "mime": main.get("mime"), "title": main["title"]}
    except BudgetExceeded as error:
        out["error"] = f"budget: {error}"
    except Exception as error:  # noqa: BLE001 - one bad pack mustn't stop a catalog run
        out["error"] = f"{type(error).__name__}: {error}"
    finally:
        r.close()
    out.update(bytes=r.fetched, requests=r.requests, seconds=round(time.time() - t, 2), mirror=r.final_url)
    return out


def read_entry(src, path, budget=BUDGET, reader=Reader):
    """The bytes of the entry at [path] (namespace C, redirects followed); raises KeyError when there's none."""
    r = reader(src, budget)
    try:
        z = Zim(r)
        e = z.find(path)
        if e is None or "cluster" not in e:
            raise KeyError(path)
        return z.content(e)
    finally:
        r.close()


def scraper_of(record):
    return (record.get("metadata") or {}).get("Scraper") or ""


def probe_catalog(catalog, cache, workers=8, probe_one=probe):
    """
    {zimName: scraper} for every pack in [catalog], from [cache] (a JSON-lines file, one probe per pack id) where it
    already has a pack and from a fresh probe of the pack's URLs, at most [workers] at once, where it hasn't.
    """
    packs = catalog["packs"]
    done = {}
    cache = pathlib.Path(cache)
    if cache.exists():
        for line in cache.read_text(encoding="utf-8").splitlines():
            if line.strip():
                record = json.loads(line)
                if not record.get("error"):
                    done[record["id"]] = record
    todo = [p for p in packs if p["id"] not in done]
    lock = threading.Lock()

    def one(pack):
        result = {"error": "no urls"}
        for url in pack["urls"]:
            result = probe_one(url)
            if not result.get("error") or result["error"].startswith("budget"):
                break
        md = result.get("metadata", {})
        return {"id": pack["id"], "zimName": pack.get("zimName"), "scraper": md.get("Scraper"),
                "counter": parse_counter(md.get("Counter")),
                "metadata": {k: v for k, v in md.items() if k != "Counter"},
                "entryCount": result.get("entryCount"), "error": result.get("error")}

    failed = []
    with cache.open("a", encoding="utf-8") as out, concurrent.futures.ThreadPoolExecutor(workers) as pool:
        for record in pool.map(one, todo):
            with lock:
                out.write(json.dumps(record) + "\n")
            if record.get("error"):
                failed.append(f"{record['id']}: {record['error']}")
            else:
                done[record["id"]] = record
    contents = {}
    for p in packs:
        record = done.get(p["id"])
        if record is not None:
            name = p.get("zimName") or p["id"]
            contents[name] = contents.get(name) or scraper_of(record)
    return dict(sorted(contents.items())), failed


def write_contents(path, contents):
    pathlib.Path(path).write_text(json.dumps({
        "about": "Each Kiwix pack's ZIM name and the Scraper its metadata names, read by zim_probe.py probe. "
                 "catalog_gen.py marks gutenberg2zim and nautiluszim packs as books (Plan KB).",
        "packs": contents,
    }, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def main(argv):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)
    p = sub.add_parser("metadata"); p.add_argument("src")
    p = sub.add_parser("entry"); p.add_argument("src"); p.add_argument("path"); p.add_argument("--out", required=True)
    p = sub.add_parser("titles"); p.add_argument("src"); p.add_argument("--out", required=True)
    p.add_argument("--redirects"); p.add_argument("--budget", type=int, default=BUDGET >> 20, help="MB")
    p = sub.add_parser("probe"); p.add_argument("--catalog", required=True); p.add_argument("--out", required=True)
    p.add_argument("--cache"); p.add_argument("--workers", type=int, default=8)
    args = parser.parse_args(argv)
    if args.command == "metadata":
        print(json.dumps(probe(args.src), indent=1, ensure_ascii=False))
    elif args.command == "entry":
        data = read_entry(args.src, args.path)
        pathlib.Path(args.out).write_bytes(data)
        print(f"{args.path}: {len(data)} bytes -> {args.out}")
    elif args.command == "titles":
        result = titles(args.src, budget=args.budget << 20)
        if result.get("error"):
            print(json.dumps(result, indent=1))
            raise SystemExit(1)
        pathlib.Path(args.out).write_text("".join(p + "\n" for p in result.pop("articles")), encoding="utf-8")
        redirects = result.pop("redirects")
        if args.redirects:
            pathlib.Path(args.redirects).write_text("".join(p + "\n" for p in redirects), encoding="utf-8")
        print(json.dumps(result, indent=1))
    else:
        catalog = json.loads(pathlib.Path(args.catalog).read_text(encoding="utf-8"))
        cache = args.cache or str(pathlib.Path(args.out).with_suffix(".probe.jsonl"))
        contents, failed = probe_catalog(catalog, cache, min(args.workers, 8))
        write_contents(args.out, contents)
        print(f"{len(contents)} packs -> {args.out}; {len(failed)} failed")
        for f in failed:
            print(f"  {f}")
        if failed:
            raise SystemExit(1)


if __name__ == "__main__":
    main(sys.argv[1:])
