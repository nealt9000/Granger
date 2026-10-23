"""Per-subject dense hits for a run: each keyword that names something found is embedded on its own (and with the
question), scanned like hits.py, and written to runs/RUN/subq-<variant>.jsonl."""
import json, sys
from pathlib import Path
import numpy as np
T = Path(__file__).resolve().parents[2]
sys.path[:0] = [str(T / "vectors"), str(T / "eval")]
import gvec, hits, merge
from sentence_transformers import SentenceTransformer
RUN, K = sys.argv[1], 8
W = Path.home() / "Developer/Granger/vectors-work/build"
items, corpus, dense = merge.load(RUN)
jobs = []  # (id, subject, variant, text)
for id, c in corpus.items():
    subs = merge.subjects(c["keywords"], c["passages"] + dense.get(id, []))
    if len(subs) < 2:
        continue
    q = items[id]["question"]
    for s in subs:
        jobs += [(id, s, "A", s), (id, s, "B", "%s: %s" % (s, q))]
print(len(jobs), "sub-queries over", len({j[0] for j in jobs}), "questions", flush=True)
m = SentenceTransformer("google/embeddinggemma-2", device="cpu", model_kwargs={"dtype": __import__("torch").float32})
vecs = np.array(m.encode_query([j[3] for j in jobs], batch_size=32), np.float32)
srcs = hits.sources(W)
files = []
for f, _, _, _ in srcs:
    head, qv, scales, _, _ = gvec.read(f)
    files.append((qv, scales))
best = hits.scan(gvec.truncate(vecs, 256), files, K)
wanted = {}
for b in best:
    for _, fi, row in b:
        wanted.setdefault(fi, set()).add(row)
chunks = {}
for fi, rows in wanted.items():
    with srcs[fi][1].open(encoding="utf-8") as f:
        for n, line in enumerate(f):
            if n in rows:
                chunks[(fi, n)] = json.loads(line)
out = {"A": {}, "B": {}}
for (id, s, v, _), b in zip(jobs, best):
    ps = []
    for score, fi, row in b:
        c, (_, _, kind, label) = chunks[(fi, row)], srcs[fi]
        ps.append({"kind": kind, "source": label, "title": c["title"], "location": c["key"], "heading": c.get("heading") or "",
                   "text": c["text"], "cosine": round(score, 4)})
    out[v].setdefault(id, []).append({"subject": s, "passages": ps})
d = T / "eval" / "runs" / RUN
for v, by in out.items():
    with (d / ("subq-%s.jsonl" % v)).open("w", encoding="utf-8") as f:
        for id, subs in by.items():
            f.write(json.dumps({"id": id, "subs": subs}, ensure_ascii=False) + "\n")
print("done", {v: len(by) for v, by in out.items()})
