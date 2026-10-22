#!/usr/bin/env python3
"""Dense retrieval over the library's books, offline (Plan R0, Task 4, the optional step).

  dense.py chunks BOOKS_DIR OUT_DIR        # EPUBs pulled from the phone -> OUT_DIR/chunks.jsonl
  dense.py embed OUT_DIR                   # OUT_DIR/chunks.npy (titled passages, EmbeddingGemma 2)
  dense.py eval OUT_DIR RUN [--k 4,12,50]  # questions with a book answer: dense top-k against keyword search

Keyword search can't find "wine is a mocker" for "what does the bible say about alcohol". This asks whether a vector index
of the books, the "knowing your library" idea, would. Chunks follow XhtmlText (core/books): block text joined up to
400–2000 characters per spine document. Needs numpy, beautifulsoup4, lxml; `embed` and `eval` also sentence-transformers.
"""
import argparse
import json
import re
import sys
import zipfile
from pathlib import Path
from posixpath import dirname, join, normpath

import numpy as np

import replay
from dataset import location_matches

MIN_CHUNK, MAX_CHUNK = 400, 2000
BLOCKS = ["p", "h1", "h2", "h3", "h4", "h5", "h6", "li", "blockquote", "pre", "td", "dd", "dt", "div"]
WHITESPACE = re.compile(r"\s+")


def cut_at_spaces(text, limit):
    out = []
    while len(text) > limit:
        cut = text.rfind(" ", 0, limit + 1)
        cut = cut if cut > 0 else limit
        out.append(text[:cut].strip())
        text = text[cut:].strip()
    if text:
        out.append(text)
    return out


def block_text(el):
    from bs4 import NavigableString, Tag
    if len(el.find_all(BLOCKS)) == 0:
        return WHITESPACE.sub(" ", el.get_text()).strip()
    parts = []
    for node in el.children:
        if isinstance(node, NavigableString):
            parts.append(str(node))
        elif isinstance(node, Tag) and node.find(BLOCKS) is None and node.name not in BLOCKS:
            parts.append(node.get_text())
    return WHITESPACE.sub(" ", "".join(parts)).strip()


def chapter_chunks(xhtml):
    from bs4 import BeautifulSoup
    doc = BeautifulSoup(xhtml, "lxml-xml")
    texts, buffer = [], ""
    for block in doc.find_all(BLOCKS):
        own = block_text(block)
        if not own:
            continue
        for piece in cut_at_spaces(own, MAX_CHUNK):
            buffer = piece if not buffer else buffer + "\n" + piece
            if len(buffer) > MAX_CHUNK:
                parts = cut_at_spaces(buffer, MAX_CHUNK)
                texts.extend(parts[:-1])
                buffer = parts[-1]
            if len(buffer) >= MIN_CHUNK:
                texts.append(buffer)
                buffer = ""
    if buffer:
        texts.append(buffer)
    return texts


def book(path):
    """(title, [(href, text)]) in spine order."""
    from bs4 import BeautifulSoup
    with zipfile.ZipFile(path) as z:
        container = BeautifulSoup(z.read("META-INF/container.xml"), "lxml-xml")
        opf_path = container.find("rootfile")["full-path"]
        opf = BeautifulSoup(z.read(opf_path), "lxml-xml")
        title = (opf.find("dc:title") or opf.find("title"))
        title = title.get_text().strip() if title else path.stem
        manifest = {i["id"]: i["href"] for i in opf.find_all("item")}
        out = []
        for ref in opf.find("spine").find_all("itemref"):
            href = manifest.get(ref["idref"])
            if not href:
                continue
            full = normpath(join(dirname(opf_path), href))
            try:
                data = z.read(full)
            except KeyError:
                continue
            out.extend((href, t) for t in chapter_chunks(data))
        return title, out


def source_id(path):
    """book:granger:gc-<slug> from gc-<slug>_<date>.epub; book:archive-org:<identifier> from <identifier>.epub."""
    origin = path.parent.name
    stem = path.stem.split("_")[0] if origin == "granger" else path.stem
    return "book:%s:%s" % (origin, stem)


def cmd_chunks(a):
    out = Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    n = 0
    with (out / "chunks.jsonl").open("w", encoding="utf-8") as f:
        for path in sorted(Path(a.books).rglob("*.epub")):
            title, chunks = book(path)
            for href, text in chunks:
                f.write(json.dumps({"source": source_id(path), "title": title, "location": href, "text": text}, ensure_ascii=False) + "\n")
                n += 1
    print("%d chunks" % n)
    return 0


def load_chunks(out):
    return [json.loads(l) for l in (Path(out) / "chunks.jsonl").read_text(encoding="utf-8").splitlines() if l.strip()]


def cmd_embed(a):
    from sentence_transformers import SentenceTransformer
    import rerank
    chunks = load_chunks(a.out)
    model = SentenceTransformer(rerank.MODEL, device="mps")
    texts = ["title: %s | text: %s" % (c["title"], c["text"]) for c in chunks]
    order = sorted(range(len(texts)), key=lambda i: -len(texts[i]))
    vecs = model.encode([texts[i] for i in order], batch_size=32, normalize_embeddings=True, show_progress_bar=True)
    out = np.empty_like(vecs)
    out[order] = vecs
    np.save(Path(a.out) / "chunks.npy", out.astype(np.float16))
    print("embedded %d chunks" % len(chunks))
    return 0


def hit(p, answers):
    return any(p["source"] == x["source"] and location_matches(x["source"], x["location"], p.get("location") or "") for x in answers) \
        or replay.quote_hit([p], answers)


def cmd_eval(a):
    from sentence_transformers import SentenceTransformer
    import rerank
    chunks = load_chunks(a.out)
    vecs = np.load(Path(a.out) / "chunks.npy").astype(np.float32)
    _, items, corpus, _ = replay.load(a.run)
    ks = [int(k) for k in a.k.split(",")]
    ids = [id for id in corpus if items[id]["answerable"] and any(x["source"].startswith("book:") for x in items[id]["answers"])]
    model = SentenceTransformer(rerank.MODEL, device="mps")
    qs = model.encode([rerank.QUERY["search"] % items[id]["question"] for id in ids], normalize_embeddings=True)
    cfg = replay.CONFIGS["baseline"]
    dense = {k: 0 for k in ks}
    keyword_top4 = keyword_found = either = 0
    rows = []
    for id, q in zip(ids, qs):
        answers = [x for x in items[id]["answers"] if x["source"].startswith("book:")]
        order = np.argsort(-(vecs @ q))[:max(ks)]
        first = next((r for r, i in enumerate(order) if hit(chunks[i], answers)), None)
        for k in ks:
            dense[k] += first is not None and first < k
        top4 = any(hit(p, answers) for p in replay.rank(items[id]["question"], corpus[id], cfg))
        found = any(hit(p, answers) for p in corpus[id]["passages"])
        keyword_top4 += top4
        keyword_found += found
        either += top4 or (first is not None and first < 4)
        rows.append((id, top4, found, first))
    n = len(ids)
    print("%d questions with a book answer (tuning split)" % n)
    print("keyword search: answer in the top 4 %d, found at all %d" % (keyword_top4, keyword_found))
    print("dense over %d book chunks: %s" % (len(chunks), ", ".join("top %d: %d" % (k, dense[k]) for k in ks)))
    print("keyword top 4 or dense top 4: %d" % either)
    for id, top4, found, first in rows:
        print("  %-16s keyword top4 %-5s found %-5s dense rank %s" % (id, top4, found, "-" if first is None else first + 1))
    return 0


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    s = p.add_subparsers(dest="cmd", required=True)
    c = s.add_parser("chunks"); c.add_argument("books"); c.add_argument("out"); c.set_defaults(f=cmd_chunks)
    e = s.add_parser("embed"); e.add_argument("out"); e.set_defaults(f=cmd_embed)
    v = s.add_parser("eval"); v.add_argument("out"); v.add_argument("run"); v.add_argument("--k", default="4,12,50"); v.set_defaults(f=cmd_eval)
    a = p.parse_args(argv)
    return a.f(a)


if __name__ == "__main__":
    sys.exit(main())
