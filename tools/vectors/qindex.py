#!/usr/bin/env python3
"""A question index, tried offline (Ask R&D, 2026-11-06).

  qindex.py ranks RUN WORK [WORK ...]   # runs/RUN/qindex-ranks.json: where today's dense search puts each answer's chunk
  qindex.py corpus RUN WORK [WORK ...] [--k 200]  # the chunks to write questions for, answers and rivals by rank
  qindex.py generate RUN [--max-priority N]        # a local model writes 3 questions per chunk (llama-server on :8899)
  qindex.py score RUN [--depth 25 100 ...]         # the answer's chunk in the top 4/8/12 under each way of indexing; a
                                                   # depth gives questions only to the answers and each question's top N rivals

The dense search is the phone's: `gvec`'s int8 scan of every vector file, over the Mac embedder's query vectors. An answer's
chunk is the indexed chunk holding its quote, in the answer's own source.
"""
import argparse
import json
import re
import sys
import time
import urllib.request
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import numpy as np

import gvec
import hits

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "eval"))
import coverage  # noqa: E402

RUNS = Path(__file__).resolve().parents[1] / "eval" / "runs"
MODEL = "google/embeddinggemma-2"
QUERY = "task: search result | query: %s"
DIMS = 256


def query_text(item):
    return QUERY % " ".join(x for x in (item.get("priorQuestion"), item["question"]) if x)


def embed_queries(items):
    from sentence_transformers import SentenceTransformer
    model = SentenceTransformer(MODEL, device="mps")
    v = model.encode([query_text(i) for i in items], batch_size=32, normalize_embeddings=True)[:, :DIMS]
    return (v / np.linalg.norm(v, axis=1, keepdims=True)).astype(np.float32)


def read_chunks(path):
    with open(path, encoding="utf-8") as f:
        return [json.loads(line) for line in f]


def cmd_ranks(a):
    items = [i for i in json.loads((RUNS / a.run / "dataset.json").read_text(encoding="utf-8"))]
    answerable = [i for i in items if i["answerable"]]
    srcs, seen = [], set()
    for work in a.work:  # later builds of a book win
        for src in reversed(hits.sources(Path(work))):
            sid = src[0].stem.rsplit("_", 1)[0]
            if sid not in seen:
                seen.add(sid)
                srcs.append(src)
    by_label = {}
    for f, chunks, kind, label in srcs:
        by_label.setdefault(label, []).append((f, chunks, kind))
    print("%d vector files" % len(srcs), flush=True)

    # The answer chunks: the indexed chunks holding each quote.
    wanted = {}
    for it in answerable:
        for n, ans in enumerate(it["answers"]):
            wanted.setdefault(ans["source"], []).append((it["id"], n, coverage.fold(ans.get("quote"))))
    gold = {}  # (item id, answer n) -> [(vector file, row)]
    status = {}
    for label, needs in wanted.items():
        if label not in by_label:
            for id, n, _ in needs:
                status[(id, n)] = "source not in the vector set"
            continue
        for f, chunks, _ in by_label[label]:
            rows = read_chunks(chunks)
            folded = [coverage.fold(r.get("text")) for r in rows]
            for id, n, q in needs:
                found = [(f.name, row) for row, t in enumerate(folded) if q and q in t]
                if found:
                    gold.setdefault((id, n), []).extend(found)
        print("  %s: %d answers looked for" % (label, len(needs)), flush=True)
    for it in answerable:
        for n in range(len(it["answers"])):
            status.setdefault((it["id"], n), "in index" if (it["id"], n) in gold else "quote in no chunk")
    print(Counter(status.values()), flush=True)

    # Every query's score against every chunk: the top k, and how many chunks beat each answer chunk.
    q = embed_queries(answerable)
    qq, qs = gvec.quantize(q)
    qf = qq.astype(np.float32)
    index = {it["id"]: k for k, it in enumerate(answerable)}
    gold_scores = {key: [] for key in gold}
    by_file = {}
    for key, places in gold.items():
        for name, row in places:
            by_file.setdefault(name, []).append((key, row))
    edges = np.linspace(-1.0, 1.2, 4401)
    hist = np.zeros((len(answerable), len(edges) + 1), dtype=np.int64)
    for f, chunks, kind, label in srcs:
        data = gvec.read(f)
        vq, scales = data[1], data[2]
        here = by_file.get(f.name, [])
        for at in range(0, len(vq), 65536):
            s = (qf @ vq[at:at + 65536].astype(np.float32).T) * scales[at:at + 65536][None, :] * qs[:, None]
            for i in range(len(answerable)):
                hist[i] += np.bincount(np.searchsorted(edges, s[i]), minlength=len(edges) + 1)
            for key, row in here:
                if at <= row < at + 65536:
                    gold_scores[key].append(float(s[index[key[0]], row - at]))
    print("chunks scoring out of range (NaN or above 1.2), left out of the ranks:", hist[0, -1], flush=True)
    hist[:, -1] = 0
    out = {}
    for it in answerable:
        i = index[it["id"]]
        cum = np.cumsum(hist[i][::-1])[::-1]  # chunks at or above each edge
        answers = []
        for n, ans in enumerate(it["answers"]):
            key = (it["id"], n)
            scores = gold_scores.get(key) or []
            top = max(scores) if scores else None
            rank = int(cum[np.searchsorted(edges, top)]) if top is not None else None
            answers.append(dict(source=ans["source"], status=status[key], chunks=len(scores), cosine=top, rank=rank))
        out[it["id"]] = dict(domain=it["domain"], multi=bool(it.get("multiStep")), answers=answers)
    (RUNS / a.run / "qindex-ranks.json").write_text(json.dumps(out, indent=1), encoding="utf-8")

    best_rank = []
    for id, e in out.items():
        rs = [x["rank"] for x in e["answers"] if x["rank"] is not None]
        if e["multi"]:
            rs = [max(rs)] if len(rs) == len(e["answers"]) else []
        best_rank.append((min(rs) if rs else None, e["domain"], e["multi"]))
    bands = [(1, 4), (5, 12), (13, 24), (25, 100), (101, 1000), (1001, 10 ** 9)]
    print("answer chunk's rank in the dense list, of %d items:" % len(best_rank))
    for lo, hi in bands:
        print("  %d-%s: %d" % (lo, hi if hi < 10 ** 9 else "", sum(r is not None and lo <= r <= hi for r, _, _ in best_rank)))
    print("  not indexed: %d" % sum(r is None for r, _, _ in best_rank))
    doms = sorted({d for _, d, _ in best_rank})
    for d in doms:
        rs = [r for r, dd, _ in best_rank if dd == d]
        print("  %-11s n=%2d top24=%2d top1000=%2d none=%2d" % (d, len(rs), sum(r is not None and r <= 24 for r in rs),
              sum(r is not None and r <= 1000 for r in rs), sum(r is None for r in rs)))
    return 0


PROMPT = """Below is a passage from a library. Write 3 questions a curious person might ask that this passage directly answers.

Rules:
- Ask as an ordinary person would, in everyday words, not in the passage's wording. Use different words from each other too.
- Each question must make sense by itself: name the thing it asks about, never "this passage" or "the author".
- Ask about the specific facts here, the things someone would look this passage up for.
- One question per line, nothing else.

Source: {title}
Passage:
{text}"""


def scan_top(q, srcs, k):
    """Each query's k best (score, file name, row) over every vector file; bad scores (NaN) never win."""
    qq, qs = gvec.quantize(q)
    qf = qq.astype(np.float32)
    best = [[] for _ in range(len(q))]
    for f, _, _, _ in srcs:
        _, vq, scales, _, _ = gvec.read(f)
        for at in range(0, len(vq), 65536):
            s = (qf @ vq[at:at + 65536].astype(np.float32).T) * scales[at:at + 65536][None, :] * qs[:, None]
            s = np.nan_to_num(s, nan=-1.0, posinf=-1.0, neginf=-1.0)
            n = min(k, s.shape[1] - 1)
            if n < 1:
                continue
            top = np.argpartition(-s, n, axis=1)[:, :n]
            for i in range(len(q)):
                best[i].extend((float(s[i, j]), f.name, at + int(j)) for j in top[i])
        best = [sorted(b, reverse=True)[:k] for b in best]
    return best


def pick_sources(works):
    srcs, seen = [], set()
    for work in works:  # later builds of a book win
        for src in reversed(hits.sources(Path(work))):
            sid = src[0].stem.rsplit("_", 1)[0]
            if sid not in seen:
                seen.add(sid)
                srcs.append(src)
    return srcs


def cmd_corpus(a):
    """runs/RUN/qindex-corpus.jsonl: the chunks to write questions for, the ones that matter most first, and each
    question's dense list. Priority: the answer chunks (0), then each question's chunks by rank."""
    d = RUNS / a.run
    items = json.loads((d / "dataset.json").read_text(encoding="utf-8"))
    srcs = pick_sources(a.work)
    q = embed_queries(items)
    tops = scan_top(q, srcs, a.k)
    (d / "qindex-tops.json").write_text(json.dumps({it["id"]: t for it, t in zip(items, tops)}), encoding="utf-8")
    prio = {}
    for t in tops:
        for rank, (_, name, row) in enumerate(t):
            prio[(name, row)] = min(prio.get((name, row), 10 ** 9), rank + 1)
    wanted = {}
    for it in items:
        for ans in it.get("answers") or []:
            wanted.setdefault(ans["source"], []).append(coverage.fold(ans.get("quote")))
    rows_out = []
    for f, chunks, kind, label in srcs:
        need = {row: p for (name, row), p in prio.items() if name == f.name}
        quotes = [x for x in wanted.get(label, []) if x]
        if not need and not quotes:
            continue
        with open(chunks, encoding="utf-8") as fh:
            for row, line in enumerate(fh):
                r = json.loads(line)
                p = need.get(row)
                if quotes and any(x in coverage.fold(r.get("text")) for x in quotes):
                    p = 0
                if p is not None:
                    rows_out.append(dict(cid="%s:%d" % (f.name, row), source=label, priority=p, title=r.get("title"),
                                         heading=r.get("heading") or "", key=r.get("key"), text=r.get("text")))
    rows_out.sort(key=lambda r: r["priority"])
    with open(d / "qindex-corpus.jsonl", "w", encoding="utf-8") as fh:
        for r in rows_out:
            fh.write(json.dumps(r, ensure_ascii=False) + "\n")
    print("%d chunks; answers %d, rank<=25 %d, <=100 %d" % (len(rows_out), sum(r["priority"] == 0 for r in rows_out),
          sum(r["priority"] <= 25 for r in rows_out), sum(r["priority"] <= 100 for r in rows_out)))
    return 0


def clean_questions(reply):
    out = []
    for line in reply.splitlines():
        line = re.sub(r"^\s*(?:[-*•]|\d+[.)])\s*", "", line).strip()
        if line.endswith("?") and len(line) > 8:
            out.append(line)
    return out[:3]


def ask_model(row, url):
    title = row["title"] or ""
    if row["heading"] and row["heading"] != title:
        title += " — " + row["heading"]
    body = json.dumps({"messages": [{"role": "user", "content": PROMPT.format(title=title, text=(row["text"] or "")[:1400])}],
                       "temperature": 0.3, "max_tokens": 120, "chat_template_kwargs": {"enable_thinking": False}}).encode()
    req = urllib.request.Request(url, body, {"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=600) as r:
        return clean_questions(json.load(r)["choices"][0]["message"]["content"])


def cmd_generate(a):
    """Appends to runs/RUN/qindex-questions.jsonl; safe to stop and start again."""
    d = RUNS / a.run
    out = d / "qindex-questions.jsonl"
    done = {json.loads(l)["cid"] for l in open(out, encoding="utf-8")} if out.exists() else set()
    rows = [json.loads(l) for l in open(d / "qindex-corpus.jsonl", encoding="utf-8")]
    rows = [r for r in rows if r["cid"] not in done and r["priority"] <= a.max_priority]
    print("%d to write, %d done" % (len(rows), len(done)), flush=True)
    t0, n = time.time(), 0
    with open(out, "a", encoding="utf-8") as fh, ThreadPoolExecutor(a.workers) as pool:
        for at in range(0, len(rows), a.workers * 8):
            batch = rows[at:at + a.workers * 8]
            for r, qs in zip(batch, pool.map(lambda x: ask_model(x, a.url), batch)):
                fh.write(json.dumps(dict(cid=r["cid"], questions=qs), ensure_ascii=False) + "\n")
                n += 1
            fh.flush()
            if n % (a.workers * 80) == 0:
                print("%d in %.0f s (%.2f s each)" % (n, time.time() - t0, (time.time() - t0) / n), flush=True)
    print("done: %d" % n, flush=True)
    return 0


DOC = "title: %s | text: %s"


def titled(row, text):
    title, heading = (row.get("title") or "").strip(), (row.get("heading") or "").strip()
    if heading and heading.lower() != title.lower():
        title = "%s — %s" % (title, heading) if title else heading
    return DOC % (title or "none", text)


def unit(v):
    return v / np.maximum(np.linalg.norm(v, axis=-1, keepdims=True), 1e-9)


def embed_rows(d, rows):
    """Each row's vectors, kept in runs/RUN/qindex-emb.npz so a later checkpoint embeds only the new rows:
    text (the chunk as built), asked (text with its questions in front), qdoc and qquery (each question, 3 per row)."""
    from sentence_transformers import SentenceTransformer
    cache = d / "qindex-emb.npz"
    old = dict(np.load(cache, allow_pickle=False)) if cache.exists() else {}
    known = {c: i for i, c in enumerate(old["cids"].tolist())} if old else {}
    new = [r for r in rows if r["cid"] not in known]
    if new:
        model = SentenceTransformer(MODEL, device="mps")

        def enc(texts):
            import torch
            parts = []
            for at in range(0, len(texts), 2048):  # pieces, so the GPU's memory is given back between them
                v = model.encode(texts[at:at + 2048], batch_size=16, normalize_embeddings=True, show_progress_bar=False)[:, :DIMS]
                parts.append(unit(v).astype(np.float32))
                torch.mps.empty_cache()
            return np.concatenate(parts)
        def three(r):
            return (r["questions"] + [r["questions"][-1] if r["questions"] else ""] * 3)[:3]
        got = dict(cids=np.array([r["cid"] for r in new]),
                   text=enc([titled(r, r["text"]) for r in new]),
                   asked=enc([titled(r, "%s\n%s" % (" ".join(r["questions"]), r["text"])) for r in new]),
                   qdoc=enc([titled(r, q) for r in new for q in three(r)]).reshape(len(new), 3, DIMS),
                   qquery=enc([QUERY % q for r in new for q in three(r)]).reshape(len(new), 3, DIMS))
        old = {k: np.concatenate([old[k], got[k]]) for k in got} if old else got
        np.savez(cache, **old)
        known = {c: i for i, c in enumerate(old["cids"].tolist())}
    at = [known[r["cid"]] for r in rows]
    return {k: old[k][at] for k in ("text", "asked", "qdoc", "qquery")}


def int8_matrix(q, v):
    """Scores as the phone computes them, for every query against every vector."""
    qq, qs = gvec.quantize(q)
    vq, vs = gvec.quantize(v)
    return (qq.astype(np.float32) @ vq.astype(np.float32).T) * vs[None, :] * qs[:, None]


def covered(item, top):
    marks = [coverage.mark(a, top) for a in item["answers"]]
    return all(m == "passage" for m in marks) if item.get("multiStep") else "passage" in marks


def cmd_score(a):
    import merge
    import replay
    from dataclasses import replace
    d = RUNS / a.run
    done = {}
    for line in open(d / "qindex-questions.jsonl", encoding="utf-8"):
        r = json.loads(line)
        done[r["cid"]] = r["questions"]
    rows = []
    for line in open(d / "qindex-corpus.jsonl", encoding="utf-8"):
        r = json.loads(line)
        r["kind"] = "BOOK" if r["source"].startswith("book:") else "ARTICLE"
        rows.append(r)
    meta = {r["cid"]: r for r in rows}
    asked = [r for r in rows if r["cid"] in done]
    for r in asked:
        r["questions"] = done[r["cid"]]
    print("%d chunks with questions of %d" % (len(asked), len(rows)), flush=True)
    emb = embed_rows(d, asked)
    data = json.loads((d / "dataset.json").read_text(encoding="utf-8"))
    q = embed_queries(data)
    tops = json.loads((d / "qindex-tops.json").read_text(encoding="utf-8"))
    n = len(asked)
    text = int8_matrix(q, emb["text"])
    per_q = lambda v: int8_matrix(q, v.reshape(n * 3, DIMS)).reshape(len(q), n, 3).max(axis=2)
    variants = {
        "text only (today)": text,
        "A questions as extra vectors, doc form": np.maximum(text, per_q(emb["qdoc"])),
        "A2 questions as extra vectors, query form": np.maximum(text, per_q(emb["qquery"])),
        "B questions inside the chunk's one vector": int8_matrix(q, emb["asked"]),
        "C chunk vector + question mean, one vector": int8_matrix(q, unit(emb["text"] + emb["qdoc"].mean(axis=1))),
    }
    cids = [r["cid"] for r in asked]
    prio = np.array([r["priority"] for r in asked])
    items, corpus, _ = merge.load(a.run)
    ranker = merge.gated(merge.hybrid(0.1, 0.74), 0.78, 1.0)
    answerable = [it["id"] for it in data if it["answerable"]]
    unans = [it["id"] for it in data if not it["answerable"]]

    def passage(cid, score):
        r = meta[cid]
        return {"kind": r["kind"], "source": r["source"], "title": r["title"], "location": r["key"],
                "heading": r["heading"], "text": r["text"], "cosine": round(score, 4)}

    def dense_lists(matrix, depth):
        live = prio <= depth
        out = {}
        for i, it in enumerate(data):
            scores = {c: s for s, name, row in tops[it["id"]] for c in ["%s:%d" % (name, row)] if c in meta}
            for j in np.nonzero(live)[0]:
                scores[cids[j]] = float(matrix[i, j])
            best = sorted(scores.items(), key=lambda kv: -kv[1])[:a.k]
            out[it["id"]] = [passage(c, s) for c, s in best]
        return out

    baseline = None
    print("%-44s %5s | %3s %3s %3s | in-24 | unanswerable passing the gate" % ("variant", "depth", "@4", "@8", "@12"), flush=True)
    for name, matrix in variants.items():
        for depth in ([10 ** 9] if name.startswith("text") else a.depth):
            dense = dense_lists(matrix, depth)
            res = {}
            for size in (4, 8, 12):
                cfg = replace(replay.Config(), small=size, large=max(size, 8))
                res[size] = {id: ranker(items[id]["question"], corpus[id], cfg, dense.get(id, [])) for id in corpus}
            hit = {size: {i for i in answerable if i in res[size] and covered(items[i], res[size][i])} for size in res}
            in24 = sum(covered(items[i], dense[i]) for i in answerable)
            passing = sum(bool(res[4][i]) for i in unans if i in res[4])
            line = "%-44s %5s | %3d %3d %3d | %3d   | %d/%d" % (name, "all" if depth > 10 ** 8 else depth, len(hit[4]), len(hit[8]), len(hit[12]), in24, passing, len(unans))
            if baseline:
                line += "  (@4 +%d -%d)" % (len(hit[4] - baseline), len(baseline - hit[4]))
            else:
                baseline = hit[4]
            print(line, flush=True)
    return 0


def main(argv=None):
    ap = argparse.ArgumentParser()
    s = ap.add_subparsers(dest="cmd", required=True)
    r = s.add_parser("ranks"); r.add_argument("run"); r.add_argument("work", nargs="+"); r.set_defaults(f=cmd_ranks)
    c = s.add_parser("corpus"); c.add_argument("run"); c.add_argument("work", nargs="+"); c.add_argument("--k", type=int, default=200)
    c.set_defaults(f=cmd_corpus)
    g = s.add_parser("generate"); g.add_argument("run"); g.add_argument("--url", default="http://localhost:8899/v1/chat/completions")
    g.add_argument("--workers", type=int, default=6); g.add_argument("--max-priority", type=int, default=10 ** 9); g.set_defaults(f=cmd_generate)
    x = s.add_parser("score"); x.add_argument("run"); x.add_argument("--k", type=int, default=24)
    x.add_argument("--depth", type=int, nargs="+", default=[25, 10 ** 9]); x.set_defaults(f=cmd_score)
    a = ap.parse_args(argv)
    return a.f(a)


if __name__ == "__main__":
    sys.exit(main())
