"""Would a cross-encoder reranker put the answer in the prompt? (Plan R2 ranking, 2026-11-03; offline, CPU)

  rerank_ce.py RUN MODEL [MODEL ...] [--pool app|all] [--top 4]

For each answerable tuning item of runs/RUN: the candidates are every passage the phone's search found (found.jsonl's
keyword passages and dense hits, one per text, cut to 1,200 characters as the prompt cuts them). Each MODEL (a
sentence-transformers CrossEncoder name) scores (question, passage); the best --top go in the "prompt". Recall by quote
(metrics.quote_in) against the app's own choice (results.jsonl's `chosen`), with the items gained and lost.

--pool app reranks only the app's chosen passages plus its next-ranked candidates as recorded (`chosen` + `candidates`),
which is what an in-app reranker over the existing pool could do. --pool all reranks everything found.
"""
import argparse
import json
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(HERE))
import metrics  # noqa: E402

ap = argparse.ArgumentParser()
ap.add_argument("run"); ap.add_argument("models", nargs="+")
ap.add_argument("--pool", default="all", choices=["all", "app"]); ap.add_argument("--top", type=int, default=4)
a = ap.parse_args()
RUN = HERE / "runs" / a.run
items = json.loads((RUN / "dataset.json").read_text())
items = items["items"] if isinstance(items, dict) else items
assert not any(str(it.get("split", "")).startswith("heldout") for it in items), "tuning runs only"
found = {r["id"]: r for r in map(json.loads, (RUN / "found.jsonl").open()) if r}
recs = {r["id"]: r for r in map(json.loads, (RUN / "results.jsonl").open()) if r}


def text(p):
    return (p.get("text") or "")[:1200]


def doc(p):
    head = " · ".join(x for x in (p.get("title"), p.get("heading")) if x)
    return (head + "\n" if head else "") + text(p)


work = []
for it in items:
    answers = [x for x in it.get("answers") or [] if x.get("quote")]
    if not it.get("answerable") or not answers or it["id"] not in found:
        continue
    f, rec = found[it["id"]], recs.get(it["id"], {})
    if a.pool == "all":
        cands = f.get("passages", []) + f.get("dense", [])
    else:
        cands = rec.get("chosen", []) + rec.get("candidates", [])
    seen, pool = set(), []
    for p in cands:
        k = metrics.fold_words(text(p))[:200]
        if k and k not in seen:
            seen.add(k); pool.append(p)
    q = it["question"] if not it.get("priorQuestion") else it["priorQuestion"] + " " + it["question"]
    app = any(metrics.quote_in(p, answers) for p in rec.get("chosen", []))
    work.append((it["id"], q, answers, pool, app))

print("items %d, candidates per item %.0f, app %d" % (len(work), sum(len(w[3]) for w in work) / len(work), sum(w[4] for w in work)), flush=True)
from sentence_transformers import CrossEncoder  # noqa: E402

out = {}
for name in a.models:
    model = CrossEncoder(name, device="cpu", max_length=512)
    start = time.time()
    got, rows = 0, []
    for iid, q, answers, pool, app in work:
        if not pool:
            rows.append((iid, False, app)); continue
        scores = model.predict([(q, doc(p)) for p in pool], batch_size=32, show_progress_bar=False)
        top = [p for _, p in sorted(zip(scores, pool), key=lambda x: -float(x[0]))[: a.top]]
        hit = any(metrics.quote_in(p, answers) for p in top)
        got += hit; rows.append((iid, hit, app))
    pairs = sum(len(w[3]) for w in work)
    out[name] = {"inPrompt": got, "gained": [r[0] for r in rows if r[1] and not r[2]], "lost": [r[0] for r in rows if r[2] and not r[1]],
                 "msPerPair": round(1000 * (time.time() - start) / max(1, pairs), 2)}
    print(name, json.dumps(out[name]), flush=True)
(RUN.parent / ("rerank-ce-%s-%s.json" % (a.run, a.pool))).write_text(json.dumps(out, indent=1))
