#!/usr/bin/env python3
"""Plan R1 Task 4: build vector files for the set.

  build.py SOURCES_DIR BOOKS_DIR WORK_DIR [--only ID ...] [--dims 256]

For each source in set.json (packs, then the Classics EPUBs) this:
  1. chunks it with the app's chunker (WORK/<id>/chunks.jsonl; set.json's caps and skips applied),
  2. embeds the chunks titled ("title: <title — heading> | text: …") in shards of SHARD (WORK/<id>/shard-NNNN.npy),
     so an interrupted run resumes where it stopped,
  3. truncates, renormalizes and quantizes them, and writes WORK/out/<id>_<snapshot>.gvec plus a descriptor in
     tools/vectors/releases/<id>.json (url is filled in when the file is uploaded).
"""
import argparse
import hashlib
import json
import sys
from pathlib import Path

import numpy as np

import count
import gvec
import sources

HERE = Path(__file__).resolve().parent
CATALOG = json.loads((HERE.parents[1] / "catalog/catalog.json").read_text())
MODEL = "google/embeddinggemma-2"
SHARD = 20000
EMBEDDER_SHA256 = next(m["sha256"] for m in CATALOG["models"] if m["role"] == "embedder")
CHUNKER_VERSION = 1  # Chunkers.VERSION in :core:text; checked against the CLI below


def doc_text(title, heading, text):
    """R0's titled format (rerank.py), which embeds better than untitled text."""
    title, heading = (title or "").strip(), (heading or "").strip()
    if heading and heading.lower() != title.lower():
        title = "%s — %s" % (title, heading) if title else heading
    return "title: %s | text: %s" % (title or "none", text)


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def write_chunks(work, rows):
    tmp = work / "chunks.jsonl.part"
    with tmp.open("w", encoding="utf-8") as f:
        for title, c in rows:
            f.write(json.dumps({"title": title, "key": c["key"], "ordinal": c["ordinal"], "heading": c["heading"],
                                "text": c["text"], "hash": c["hash"]}, ensure_ascii=False) + "\n")
    tmp.rename(work / "chunks.jsonl")


def embed(work, model_box):
    chunks = [json.loads(l) for l in (work / "chunks.jsonl").open(encoding="utf-8")]
    shards = []
    for n, start in enumerate(range(0, len(chunks), SHARD)):
        path = work / ("shard-%04d.npy" % n)
        shards.append(path)
        if path.exists():
            continue
        if model_box[0] is None:
            from sentence_transformers import SentenceTransformer
            model_box[0] = SentenceTransformer(MODEL, device="mps")
        part = chunks[start:start + SHARD]
        texts = [doc_text(c["title"], c["heading"], c["text"]) for c in part]
        order = sorted(range(len(texts)), key=lambda i: -len(texts[i]))  # similar lengths batch faster
        vecs = model_box[0].encode([texts[i] for i in order], batch_size=32, normalize_embeddings=True)
        out = np.empty_like(vecs)
        out[order] = vecs
        np.save(path.with_suffix(".tmp.npy"), out.astype(np.float16))
        path.with_suffix(".tmp.npy").rename(path)
        print("  %s shard %d: %d of %d chunks" % (work.name, n, min(start + SHARD, len(chunks)), len(chunks)), flush=True)
    full = np.concatenate([np.load(p) for p in shards]) if shards else np.zeros((0, 768), np.float16)
    return chunks, full


def finish(sid, snapshot, work, out_dir, chunks, full, dims, source_sha256, kind):
    q, scales = gvec.quantize(gvec.truncate(full.astype(np.float32), dims)) if len(chunks) else (np.zeros((0, dims), np.int8), [])
    name = "%s_%s.gvec" % (sid, snapshot)
    path = out_dir / name
    gvec.write(path, q, scales, [c["hash"] for c in chunks], [(c["key"], c["ordinal"]) for c in chunks],
               chunker_version=CHUNKER_VERSION, embedder_sha256=EMBEDDER_SHA256, source_sha256=source_sha256)
    desc = {"id": sid, "kind": kind, "fileName": name, "sizeBytes": path.stat().st_size, "sha256": sha256(path),
            "count": len(chunks), "dims": dims, "dtype": "int8", "chunkerVersion": CHUNKER_VERSION,
            "embedderSha256": EMBEDDER_SHA256, "sourceSha256": source_sha256, "url": None}
    rel = HERE / "releases"
    rel.mkdir(exist_ok=True)
    (rel / ("%s.json" % sid)).write_text(json.dumps(desc, indent=1) + "\n")
    print("%-45s %8d chunks %7.1f MB" % (sid, len(chunks), desc["sizeBytes"] / 1e6), flush=True)


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("sources")
    ap.add_argument("books")
    ap.add_argument("work")
    ap.add_argument("--only", nargs="*", help="these sources only, in this order (granger-classics for the books)")
    ap.add_argument("--dims", type=int, default=256)
    a = ap.parse_args(argv)
    import subprocess, os
    version = subprocess.run([str(sources.CHUNKER), "--version"], capture_output=True, text=True,
                             env=dict(os.environ, JAVA_HOME=sources.JAVA_HOME)).stdout.strip()
    assert version == str(CHUNKER_VERSION), "chunker is version %s, build.py expects %d" % (version, CHUNKER_VERSION)
    work_root, out_dir = Path(a.work), Path(a.work) / "out"
    out_dir.mkdir(parents=True, exist_ok=True)
    packs = {p["id"]: p for p in CATALOG["packs"]}
    vset = count.load_set()
    model_box = [None]
    order = {pid: i for i, pid in enumerate(a.only or [])}
    for p in sorted(vset["packs"], key=lambda p: order.get(p["id"], len(order))):
        if a.only and p["id"] not in a.only:
            continue
        entry = packs[p["id"]]
        zim = count.zim_file(a.sources, entry)
        work = work_root / p["id"]
        work.mkdir(parents=True, exist_ok=True)
        if not (work / "chunks.jsonl").exists():
            write_chunks(work, count.selected(p, str(zim)))
        chunks, full = embed(work, model_box)
        finish(p["id"], entry["snapshotDate"], work, out_dir, chunks, full, a.dims, entry["sha256"], "pack")
    books = {b["id"]: b for b in CATALOG["books"] if b.get("collection") == vset["books"]["collection"]}
    for path in sorted(Path(a.books).glob("*.epub")):
        bid = path.stem.split("_")[0]
        if bid not in books or (a.only and bid not in a.only and "granger-classics" not in a.only):
            continue
        entry = books[bid]
        actual = sha256(path)
        if actual != entry["sha256"]:
            print("skipped %s: its sha256 isn't the catalog's" % bid, file=sys.stderr)
            continue
        work = work_root / "books" / bid
        work.mkdir(parents=True, exist_ok=True)
        if not (work / "chunks.jsonl").exists():
            rows = ((t, c) for t, c in sources.chunk("xhtml", sources.epub_sources(path)))
            write_chunks(work, rows)
        chunks, full = embed(work, model_box)
        finish(bid, entry["releaseDate"], work, out_dir, chunks, full, a.dims, actual, "book")
    return 0


if __name__ == "__main__":
    sys.exit(main())
