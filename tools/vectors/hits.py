#!/usr/bin/env python3
"""Plan R1 Task 7: the dense hits each question of a run would get, for merging offline.

  hits.py RUN PHONE_QUERIES WORK_DIR [--k 24] [--mode prior]

PHONE_QUERIES is ResearchBenchmarkActivity's `<file>.out.json` (`recall.py queries` made its input). Every vector file in
WORK_DIR/out is scanned as the phone scans: the query cut to the files' dims, renormalized and quantized, int8 dot
products times both scales. The k best are turned back into passages from WORK_DIR's chunks.jsonl, in found.jsonl's
shape plus `cosine`, and written to runs/RUN/dense.jsonl.
"""
import argparse
import json
import sys
from pathlib import Path

import numpy as np

import gvec

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "eval"))
import replay  # noqa: E402

FLAVOURS = ("_maxi", "_nopic", "_mini")


def zim_name(source_id):
    """The ZIM `Name` the app records as an article's source: the pack id without its flavour."""
    return next((source_id[:-len(f)] for f in FLAVOURS if source_id.endswith(f)), source_id)


def sources(work):
    """[(file stem, chunks.jsonl, kind, source label)] for every vector file in WORK/out."""
    out = []
    for f in sorted((work / "out").glob("*.gvec")):
        sid = f.stem.rsplit("_", 1)[0]
        if sid.startswith("gc-"):
            out.append((f, work / "books" / sid / "chunks.jsonl", "BOOK", "book:granger:" + sid))
        else:
            out.append((f, work / sid / "chunks.jsonl", "ARTICLE", zim_name(sid)))
    return out


def scan(queries, files, k):
    """Top k (score, file index, row) per query over every file, scored as `gvec.int8_scores` does."""
    qq, qs = gvec.quantize(queries)
    qf = qq.astype(np.float32)  # int8 products summed over 256 dims stay exact in float32
    best = [[] for _ in queries]
    for fi, (q, scales) in enumerate(files):
        for at in range(0, len(q), 65536):
            block = q[at:at + 65536].astype(np.float32)
            s = (qf @ block.T) * scales[at:at + 65536][None, :] * qs[:, None]
            top = np.argpartition(-s, min(k, s.shape[1] - 1), axis=1)[:, :k]
            for i in range(len(queries)):
                best[i].extend((float(s[i, j]), fi, at + int(j)) for j in top[i])
                best[i] = sorted(best[i], reverse=True)[:k]
    return best


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("run"); p.add_argument("phone_queries"); p.add_argument("work")
    p.add_argument("--k", type=int, default=24); p.add_argument("--mode", default="prior", choices=("raw", "prior"))
    a = p.parse_args(argv)
    work = Path(a.work)
    d, _, corpus, _ = replay.load(a.run)
    phone = {r["id"]: r["vector"] for r in json.loads(Path(a.phone_queries).read_text()) if r["mode"] == a.mode}
    ids = [i for i in corpus if i in phone]

    srcs = sources(work)
    files, dims = [], None
    for f, _, _, _ in srcs:
        head, q, scales, _, keys = gvec.read(f)
        dims = dims or head["dims"]
        assert head["dims"] == dims, f
        files.append((q, scales))
    queries = gvec.truncate(np.array([phone[i] for i in ids], np.float32), dims)
    best = scan(queries, files, a.k)

    wanted = {}
    for hits in best:
        for _, fi, row in hits:
            wanted.setdefault(fi, set()).add(row)
    chunks = {}
    for fi, rows in wanted.items():
        with srcs[fi][1].open(encoding="utf-8") as f:
            for n, line in enumerate(f):
                if n in rows:
                    chunks[(fi, n)] = json.loads(line)

    with (d / "dense.jsonl").open("w", encoding="utf-8") as out:
        for i, hits in zip(ids, best):
            passages = []
            for score, fi, row in hits:
                c, (_, _, kind, label) = chunks[(fi, row)], srcs[fi]
                passages.append({"kind": kind, "source": label, "title": c["title"], "location": c["key"],
                                 "heading": c.get("heading") or "", "text": c["text"], "cosine": round(score, 4)})
            out.write(json.dumps({"id": i, "passages": passages}, ensure_ascii=False) + "\n")
    print("%d questions, %d files, %d rows -> %s" % (len(ids), len(files), sum(len(q) for q, _ in files), d / "dense.jsonl"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
