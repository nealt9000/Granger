#!/usr/bin/env python3
"""EmbeddingGemma rerank, offline (Plan R0, Task 2). Needs numpy; `embed` also needs sentence-transformers and torch.

  rerank.py embed RUN [--doc plain|titled] [--query search|qa]   # caches vectors in runs/RUN/emb-*.npy
  rerank.py sweep RUN [--doc ...] [--query ...]                   # recall@4 and empty rate per config
  rerank.py phone RUN                                             # the phone's cosines against the Mac's (app formats)

Vectors come from the Hugging Face weights of the model the app ships (google/embeddinggemma-2; the phone runs its int4
text-only build). `plain` and `search` are the app's formats (LiteRtTextEmbedder): "title: none | text: <text>" and
"task: search result | query: <question>". `titled` gives the passage its title and heading; `qa` is the card's
question-answering task. Each config is reported twice: with no threshold (ordering only), and at the threshold that
empties at least as many unanswerable questions as the baseline does (13/76).
"""
import argparse
import hashlib
import json
import sys
from dataclasses import replace

import numpy as np

import replay

MODEL = "google/embeddinggemma-2"
QUERY = {"search": "task: search result | query: %s", "qa": "task: question answering | query: %s"}


def doc_text(p, doc):
    if doc == "titled":
        title, heading = (p.get("title") or "").strip(), (p.get("heading") or "").strip()
        if heading and heading.lower() != title.lower():
            title = "%s — %s" % (title, heading) if title else heading
        return "title: %s | text: %s" % (title or "none", p.get("text") or "")
    return "title: none | text: %s" % (p.get("text") or "")


def _h(s):
    return hashlib.sha1(s.encode("utf-8")).hexdigest()


def _paths(d, doc, query):
    return d / ("emb-doc-%s.npy" % doc), d / ("emb-doc-%s.json" % doc), d / ("emb-query-%s.npy" % query), d / ("emb-query-%s.json" % query)


def embed(run, doc, query, batch=32):
    from sentence_transformers import SentenceTransformer
    d, items, corpus, _ = replay.load(run)
    model = SentenceTransformer(MODEL, device="mps")
    dv, dk, qv, qk = _paths(d, doc, query)
    for vec_path, key_path, texts in (
        (qv, qk, [QUERY[query] % items[id]["question"] for id in corpus]),
        (dv, dk, [doc_text(p, doc) for c in corpus.values() for p in c["passages"]]),
    ):
        if vec_path.exists():
            continue
        texts = list(dict.fromkeys(texts))
        # Longest first, so each batch pads little.
        order = sorted(range(len(texts)), key=lambda i: -len(texts[i]))
        vecs = model.encode([texts[i] for i in order], batch_size=batch, normalize_embeddings=True, show_progress_bar=True)
        out = np.empty_like(vecs)
        out[order] = vecs
        np.save(vec_path, out.astype(np.float32))
        key_path.write_text(json.dumps([_h(t) for t in texts]))
    print("vectors in", d)


class Vectors:
    def __init__(self, d, doc, query):
        dv, dk, qv, qk = _paths(d, doc, query)
        self.doc, self.query = doc, query
        self.d = dict(zip(json.loads(dk.read_text()), np.load(dv)))
        self.q = dict(zip(json.loads(qk.read_text()), np.load(qv)))

    def cos(self, question, p):
        return float(self.q[_h(QUERY[self.query] % question)] @ self.d[_h(doc_text(p, self.doc))])


def ranker(vectors, n, alpha, threshold):
    """Preselect `n` (per-document cap as the app), score alpha·lexical + (1−alpha)·cosine, keep >= threshold."""
    def rank(question, corpus, cfg):
        asked = replay.media_asked(question)
        found = replay.preselect(corpus["passages"], corpus["keywords"], replace(cfg, preselect=n))
        lex = replay.scorer(corpus["keywords"])
        scored = [(p, alpha * lex(p) + (1 - alpha) * vectors.cos(question, p)) for p in found]
        kept = [p for p, s in sorted(scored, key=lambda x: -x[1]) if s >= threshold]
        return replay.cap_media(replay.lift_media(kept, found, asked), asked, cfg)[:cfg.large][:cfg.small]
    return rank


def prepare(items, corpus, vectors, n, cfg):
    """Per item: preselect `n`, each passage's lexical and cosine score, and whether media was asked. Scored once,
    so a sweep over alpha and threshold only re-sorts."""
    out = {}
    for id, c in corpus.items():
        q = items[id]["question"]
        found = replay.preselect(c["passages"], c["keywords"], replace(cfg, preselect=n))
        lex = replay.scorer(c["keywords"])
        out[id] = (found, [lex(p) for p in found], [vectors.cos(q, p) for p in found], replay.media_asked(q))
    return out


def fast_ranker(prepared, alpha, threshold):
    """`ranker` over `prepare`'s scores."""
    def rank(question, corpus, cfg):
        found, lex, cos, asked = prepared[corpus["id"]]
        scored = sorted(((p, alpha * l + (1 - alpha) * c) for p, l, c in zip(found, lex, cos)), key=lambda x: -x[1])
        kept = [p for p, s in scored if s >= threshold]
        return replay.cap_media(replay.lift_media(kept, found, asked), asked, cfg)[:cfg.large][:cfg.small]
    return rank


def sweep(run, doc, query):
    d, items, corpus, _ = replay.load(run)
    v = Vectors(d, doc, query)
    cfg = replay.CONFIGS["baseline"]
    base = replay.evaluate(items, corpus, cfg)
    print("baseline (app): %s" % replay.summary(base))
    print("doc=%s query=%s; cells are location / quote recall@4 (of %d) / unanswerable empty (of %d)" % (
        doc, query, base["answerable"], base["unanswerable"]))
    print("preselect\talpha\tno threshold\tthreshold\tat baseline empty")
    for n in (12, 24, 10_000):
        prepared = prepare(items, corpus, v, n, cfg)
        for alpha in [round(a * 0.1, 1) for a in range(0, 11)]:
            free = replay.evaluate(items, corpus, cfg, fast_ranker(prepared, alpha, -1.0))
            best = None
            for t in np.arange(0.20, 1.40, 0.01):
                r = replay.evaluate(items, corpus, cfg, fast_ranker(prepared, alpha, float(t)))
                if r["empty"] >= base["empty"]:
                    best = (float(t), r)
                    break
            row = "%s\t%.1f\t%s\t" % ("all" if n == 10_000 else n, alpha, _cells(free))
            row += ("%.2f\t%s" % (best[0], _cells(best[1]))) if best else "-\t-"
            print(row, flush=True)


def phone_check(run, n_answerable=7, n_unanswerable=3):
    """Pushes 10 questions with their 12 preselected passages, has ResearchBenchmarkActivity embed them on the phone
    (CPU and GPU), and compares its cosines with the Mac's: Pearson r, and whether the top 4 by cosine agree."""
    d, items, corpus, _ = replay.load(run)
    cfg = replay.CONFIGS["baseline"]
    picked = {True: [], False: []}
    for id in sorted(corpus):
        found = replay.preselect(corpus[id]["passages"], corpus[id]["keywords"], cfg)
        want = n_answerable if items[id]["answerable"] else n_unanswerable
        if len(found) == cfg.preselect and len(picked[items[id]["answerable"]]) < want and not items[id].get("priorQuestion"):
            picked[items[id]["answerable"]].append((id, found))
    sample = picked[True] + picked[False]
    name = "r0-cosines.json"
    local = d / name
    local.write_text(json.dumps([{"id": id, "question": items[id]["question"], "passages": [p["text"] for p in found]} for id, found in sample]))
    import adb
    run_adb = adb.real_runner
    out = d / (name + ".out.json")
    if not out.exists():
        _phone_cosines(run_adb, local, name, out)
    phone = json.loads(out.read_text())
    from sentence_transformers import SentenceTransformer
    model = SentenceTransformer(MODEL, device="mps")
    mac = {}
    for id, found in sample:
        q = model.encode([QUERY["search"] % items[id]["question"]], normalize_embeddings=True)[0]
        mac[id] = list(model.encode([doc_text(p, "plain") for p in found], normalize_embeddings=True) @ q)
    for backend in sorted({r["backend"] for r in phone}):
        rows = [r for r in phone if r["backend"] == backend]
        a = np.array([c for r in rows for c in mac[r["id"]]])
        b = np.array([c for r in rows for c in r["cosines"]])
        top = sum(set(np.argsort(mac[r["id"]])[-4:]) == set(np.argsort(r["cosines"])[-4:]) for r in rows)
        print("%s: %d pairs, Pearson r %.4f, mean |diff| %.4f, same top 4 on %d of %d questions" % (
            backend, len(a), np.corrcoef(a, b)[0, 1], np.abs(a - b).mean(), top, len(rows)))


def _phone_cosines(run_adb, local, name, out):
    import time
    import adb
    adb.check_front(run_adb)
    remote = "/sdcard/Android/data/%s/files/%s" % (adb.PKG, name)
    run_adb(["shell", "rm", "-f", remote + ".out.json"])
    run_adb(["push", str(local), remote])
    # A fresh process: launched over a stale instance, the screen has run without the extra.
    run_adb(["shell", "am", "force-stop", adb.PKG])
    run_adb(["shell", "am", "start", "-W", "-n", adb.PKG + "/.research.ResearchBenchmarkActivity", "--es", "cosines", name])
    for _ in range(120):
        time.sleep(5)
        code, _ = run_adb(["pull", remote + ".out.json", str(out)])
        if code == 0 and out.exists():
            break
    else:
        raise SystemExit("the phone wrote no cosines in 10 minutes; see logcat tag Granger")


def _cells(r):
    return "%d / %d / %d" % (r["location"], r["quote"], r["empty"])


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    s = p.add_subparsers(dest="cmd", required=True)
    for name in ("embed", "sweep", "phone"):
        c = s.add_parser(name)
        c.add_argument("run")
        c.add_argument("--doc", default="plain", choices=["plain", "titled"])
        c.add_argument("--query", default="search", choices=sorted(QUERY))
    a = p.parse_args(argv)
    if a.cmd == "embed":
        embed(a.run, a.doc, a.query)
    elif a.cmd == "phone":
        phone_check(a.run)
    else:
        sweep(a.run, a.doc, a.query)
    return 0


if __name__ == "__main__":
    sys.exit(main())
