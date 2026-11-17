"""For candidate packs: every PDF/EPUB entry (path, title, bytes from its cluster), html count. One JSON per pack."""
import sys, json, struct, pathlib, concurrent.futures
sys.path.insert(0, "/Users/s.nealthompson/Developer/Granger/Granger/tools/catalog-gen")
import zim_probe as zp
DOC = {"application/pdf": "pdf", "application/epub+zip": "epub"}
def parse(buf, pos):
    if len(buf) - pos < 8: return None
    mime, extra, ns = struct.unpack_from("<HBB", buf, pos)
    p = pos + (12 if mime == 0xFFFF else 8 if mime in (0xFFFE, 0xFFFD) else 16)
    z = buf.find(b"\x00", p)
    if z < 0: return None
    z2 = buf.find(b"\x00", z + 1)
    if z2 < 0 or z2 + 1 + extra > len(buf): return None
    cl, bl = struct.unpack_from("<II", buf, pos + 8) if p == pos + 16 else (None, None)
    return (chr(ns), buf[p:z].decode("utf-8", "replace"), buf[z+1:z2].decode("utf-8", "replace"), mime, cl), z2 + 1 + extra
def run(rec, outdir):
    out = outdir / f"{rec['name']}__{rec['flavour'] or 'x'}.json"
    if out.exists(): return out.name, "cached"
    r = zp.Reader(rec["url"], budget=512 << 20)
    try:
        z = zp.Zim(r)
        start, = struct.unpack("<Q", r.read(z.path_ptr, 8))
        ptrs = struct.unpack(f"<{z.cluster_count}Q", r.read(z.cluster_ptr, 8 * z.cluster_count))
        ends = ptrs[1:] + (z.checksum_pos,)
        docs, html, buf, pos = [], 0, b"", 0
        clusters = {}
        for chunk in r.stream(start, z.path_ptr, chunk=1 << 20):
            buf += chunk
            while (p := parse(buf, pos)) is not None:
                (ns, path, title, mime, cl), pos = p
                m = z.mimes[mime] if mime < len(z.mimes) else None
                if m in DOC and ns in "CA-I":
                    docs.append({"path": path, "title": title, "kind": DOC[m], "cluster": cl})
                    clusters[cl] = clusters.get(cl, 0) + 1
                elif m and m.startswith("text/html") and ns in "CA": html += 1
            buf, pos = buf[pos:], 0
        for d in docs:  # a cluster holding one doc: its size; shared clusters: split evenly
            c = d.pop("cluster"); d["bytes"] = (ends[c] - ptrs[c]) // clusters[c]
        res = {"name": rec["name"], "flavour": rec["flavour"], "title": rec["title"], "summary": rec["summary"],
               "length": rec["length"], "scraper": rec["scraper"], "url": rec["url"], "html": html, "docs": docs,
               "docBytes": sum(d["bytes"] for d in docs), "fetched": r.fetched}
    except Exception as e:
        res = {"name": rec["name"], "error": f"{type(e).__name__}: {e}"}
    finally:
        r.close()
    out.write_text(json.dumps(res, ensure_ascii=False))
    return out.name, res.get("error") or f"{len(res['docs'])} docs, {res['docBytes']>>20} MB of {res['length']>>20} MB"
if __name__ == "__main__":
    recs = [json.loads(l) for l in open(sys.argv[1])]
    outdir = pathlib.Path(sys.argv[2]); outdir.mkdir(exist_ok=True)
    with concurrent.futures.ThreadPoolExecutor(6) as pool:
        for name, msg in pool.map(lambda r: run(r, outdir), recs):
            print(name, msg, flush=True)
