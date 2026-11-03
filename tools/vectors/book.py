#!/usr/bin/env python3
"""Plan R2 Task 7: vectors for one EPUB that isn't in the catalog (an imported book, such as the Classics book notes).

  book.py EPUB BOOK_ID WORK_DIR [--dims 256] [--device cpu|mps]

Chunks the EPUB with the app's chunker, embeds the chunks as build.py does, and writes WORK_DIR/out/<file>.gvec with
an index.tsv line (`<file>\\tBOOK\\t<BOOK_ID>`) for push.py's directory. BOOK_ID is the id the phone gave the book when
it was imported (`book:import:<sha256 of the file>`, printed by `eval.py import`). A small book embeds in seconds on the CPU, so the shared
GPU isn't needed.
"""
import argparse
import sys
from pathlib import Path

import numpy as np

import build
import gvec
import sources


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("epub"); ap.add_argument("book_id"); ap.add_argument("work")
    ap.add_argument("--dims", type=int, default=256); ap.add_argument("--device", default="cpu", choices=["cpu", "mps"])
    a = ap.parse_args(argv)
    epub, work = Path(a.epub), Path(a.work)
    out = work / "out"
    out.mkdir(parents=True, exist_ok=True)
    rows = list(sources.chunk("xhtml", sources.epub_sources(epub)))
    from sentence_transformers import SentenceTransformer
    model = SentenceTransformer(build.MODEL, device=a.device)
    model.max_seq_length = 2048
    texts = [build.doc_text(t, c["heading"], c["text"]) for t, c in rows]
    full = model.encode(texts, batch_size=8, normalize_embeddings=True).astype(np.float32)
    q, scales = gvec.quantize(gvec.truncate(full, a.dims))
    name = epub.stem + ".gvec"
    gvec.write(out / name, q, scales, [c["hash"] for _, c in rows], [(c["key"], c["ordinal"]) for _, c in rows],
               chunker_version=build.CHUNKER_VERSION, embedder_sha256=build.EMBEDDER_SHA256,
               source_sha256=build.sha256(epub))
    (out / "index.tsv").write_text("%s\tBOOK\t%s\n" % (name, a.book_id))
    lengths = sorted(len(c["text"]) for _, c in rows)
    print("%s: %d chunks (%d–%d characters) -> %s" % (a.book_id, len(rows), lengths[0], lengths[-1], out / name))
    return 0


if __name__ == "__main__":
    sys.exit(main())
