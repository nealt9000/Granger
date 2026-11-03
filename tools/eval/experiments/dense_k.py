"""Does a deeper dense search reach the prompt? (after dims-256v768, 2026-11-03; offline, CPU)

  dense_k.py BASE_RUN VECTORS_WORK [K ...]

For each tuning item of runs/BASE_RUN: the query is embedded on CPU as the phone embeds it, searched over every pack and
book build at 256 dims (the 768-d float16 shards truncated, renormalized and int8-quantized as build.py does), and the
global top max(K) kept. Writes runs/BASE_RUN-k<K>/ for each K: BASE_RUN's dataset and records, with found.jsonl's dense
hits replaced by the offline top K. `PipelineReplayTest` (GRANGER_REPLAY=<run>) then ranks them as the app does and says
whether an answer quote reaches the prompt. Compare the K runs with each other, not with BASE_RUN: books without a
768-d build (four Bibles among them) are missing from every offline list.

The query: a follow-up gets its prior question first when its offline hits match the phone's recorded ones better
(FollowUp.refersBack is Kotlin), as dims.py chose.
"""
import heapq
import json
import shutil
import sys
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent / "vectors"))
import gvec  # noqa: E402
import metrics  # noqa: E402

BASE, WORK = sys.argv[1], Path(sys.argv[2])
KS = [int(k) for k in sys.argv[3:]] or [24, 48, 100]
KEEP = max(KS)
DIM = 256
QUERY = "task: search result | query: %s"
FLAVOURS = ("_maxi", "_nopic", "_mini")
RUN = HERE / "runs" / BASE


def pack_name(d):
    for f in FLAVOURS:
        if d.endswith(f):
            return d[: -len(f)]
    return d


items = json.loads((RUN / "dataset.json").read_text())
items = items["items"] if isinstance(items, dict) else items
assert not any(str(it.get("split", "")).startswith("heldout") for it in items), "tuning runs only"
found = [json.loads(l) for l in (RUN / "found.jsonl").open() if l.strip()]
by_id = {r["id"]: r for r in found}
recorded = {(h["kind"], h["source"]) for r in found for h in r.get("dense", [])}

dirs = []
for root in (WORK / "build", WORK / "build-es"):
    for d in sorted(root.iterdir()):
        if not d.is_dir() or d.name in ("out", "books"):
            continue
        name = pack_name(d.name)
        if root.name == "build-es" and ("ARTICLE", name) not in recorded:
            continue
        if list(d.glob("shard-*.npy")):
            dirs.append(("ARTICLE", name, d))
    if (root / "books").is_dir():
        for d in sorted((root / "books").iterdir()):
            if root.name == "build-es" and ("BOOK", "book:granger:" + d.name) not in recorded:
                continue
            if list(d.glob("shard-*.npy")) and (d / "chunks.jsonl").exists():
                dirs.append(("BOOK", "book:granger:" + d.name, d))
print("sources", len(dirs), flush=True)

texts, owners = [], []
for i, it in enumerate(items):
    texts.append(QUERY % it["question"]); owners.append(i)
    if it.get("priorQuestion"):
        texts.append(QUERY % (it["priorQuestion"] + " " + it["question"])); owners.append(i)

from sentence_transformers import SentenceTransformer  # noqa: E402

model = SentenceTransformer("google/embeddinggemma-2", device="cpu")
queries = gvec.truncate(model.encode(texts, normalize_embeddings=False, convert_to_numpy=True, batch_size=16).astype(np.float32), DIM)
del model

heaps = [[] for _ in texts]
for si, (kind, name, d) in enumerate(dirs):
    offset = 0
    for shard in sorted(d.glob("shard-*.npy")):
        q, scale = gvec.quantize(gvec.truncate(np.load(shard).astype(np.float32), DIM))
        scores = (queries @ q.T.astype(np.float32)) * scale[None, :]
        top = np.argpartition(-scores, min(KEEP, scores.shape[1] - 1), axis=1)[:, :KEEP]
        for qi in range(len(texts)):
            h = heaps[qi]
            for r in top[qi]:
                item = (float(scores[qi, r]), si, offset + int(r))
                if len(h) < KEEP:
                    heapq.heappush(h, item)
                elif item > h[0]:
                    heapq.heapreplace(h, item)
        offset += len(q)
    print("  %s %s" % (kind, name), flush=True)

need = {}
for h in heaps:
    for _, si, r in h:
        need.setdefault(si, set()).add(r)
chunk = {}
for si, rows in need.items():
    with (dirs[si][2] / "chunks.jsonl").open() as f:
        for r, line in enumerate(f):
            if r in rows:
                c = json.loads(line)
                chunk[(si, r)] = {"kind": dirs[si][0], "source": dirs[si][1], "title": c.get("title") or "",
                                  "location": c.get("key") or c.get("location") or "", "heading": c.get("heading") or "",
                                  "searchScore": 0, "text": c.get("text") or ""}


def hits(qi):
    return [dict(chunk[(si, r)], cosine=s) for s, si, r in sorted(heaps[qi], reverse=True)]


def key(p):
    return metrics.fold_words(p.get("text") or "")[:200]


chosen = {}
for qi, i in enumerate(owners):
    rec = by_id.get(items[i]["id"], {}).get("dense", [])
    mine = {key(p) for p in hits(qi)[:24]}
    o = len(mine & {key(p) for p in rec}) / max(1, len(rec)) if rec else 0.0
    if i not in chosen or o > chosen[i][1]:
        chosen[i] = (qi, o)
dense = {items[i]["id"]: hits(qi) for i, (qi, _) in chosen.items()}

for k in KS:
    out = HERE / "runs" / ("%s-k%d" % (BASE, k))
    out.mkdir(parents=True, exist_ok=True)
    for f in ("dataset.json", "results.jsonl"):
        shutil.copy(RUN / f, out / f)
    with (out / "found.jsonl").open("w") as f:
        for r in found:
            f.write(json.dumps(dict(r, dense=dense.get(r["id"], [])[:k]), ensure_ascii=False) + "\n")
    print("wrote", out, flush=True)
