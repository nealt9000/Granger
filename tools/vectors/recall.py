#!/usr/bin/env python3
"""Plan R1 Task 3: dims, quantization and query text, on R0's 35 book questions.

  recall.py prepare WORK_DIR       # app chunks of R0's books; R0's vectors reused where the text is the same
  recall.py queries RUN OUT.json   # query texts for ResearchBenchmarkActivity --es queries
  recall.py eval WORK_DIR RUN [--phone-queries OUT.json.out.json]
  recall.py dataset DATASET.json WORK_DIR [WORK_DIR ...]   # dense recall of a question set over built vector files (Plan L4)

WORK_DIR holds chunks.jsonl and f768.npy (float16, titled "title: <book> | text: ...", normalized).
"""
import argparse
import json
import pickle
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "eval"))
import gvec  # noqa: E402

R0 = Path("/Users/s.nealthompson/Developer/Granger/Granger/tools/eval/runs/r0-books")
MODEL = "google/embeddinggemma-2"
QUERY = "task: search result | query: %s"
DOC = "title: %s | text: %s"


def cmd_prepare(a):
    work = Path(a.work)
    work.mkdir(parents=True, exist_ok=True)
    new = pickle.load(open(a.chunks, "rb"))  # [(bookId, href, text)] from the app's chunker
    old = [json.loads(l) for l in (R0 / "chunks.jsonl").open(encoding="utf-8")]
    titles = {o["source"]: o["title"] for o in old}
    old_vecs = np.load(R0 / "chunks.npy")
    index = {(o["source"], o["text"]): i for i, o in enumerate(old)}
    vecs = np.zeros((len(new), old_vecs.shape[1]), dtype=np.float16)
    missing = []
    for i, (bid, href, text) in enumerate(new):
        j = index.get((bid, text))
        if j is None:
            missing.append(i)
        else:
            vecs[i] = old_vecs[j]
    print("reused %d, embedding %d" % (len(new) - len(missing), len(missing)))
    if missing:
        from sentence_transformers import SentenceTransformer
        model = SentenceTransformer(MODEL, device="mps")
        texts = [DOC % (titles.get(new[i][0], ""), new[i][2]) for i in missing]
        vecs[missing] = model.encode(texts, batch_size=32, normalize_embeddings=True).astype(np.float16)
    with (work / "chunks.jsonl").open("w", encoding="utf-8") as f:
        for bid, href, text in new:
            f.write(json.dumps({"source": bid, "location": href, "text": text}, ensure_ascii=False) + "\n")
    np.save(work / "f768.npy", vecs)
    return 0


def book_questions(run):
    import replay
    _, items, corpus, _ = replay.load(run)
    ids = [i for i in corpus if items[i]["answerable"] and any(x["source"].startswith("book:") for x in items[i]["answers"])]
    return ids, items


def query_texts(items, ids, mode):
    return [QUERY % query_text(items[i], mode) for i in ids]


def query_text(item, mode):
    """The question as the phone embeds it (the embedder adds the query prompt); "prior" puts the prior question first."""
    prior = item.get("priorQuestion")
    return prior + " " + item["question"] if mode == "prior" and prior else item["question"]


def cmd_queries(a):
    """[{id, mode, text}] for every question in RUN, for ResearchBenchmarkActivity's `queries` mode."""
    import replay
    _, items, corpus, _ = replay.load(a.run)
    out = [{"id": i, "mode": m, "text": query_text(items[i], m)} for i in corpus for m in ("raw", "prior")]
    Path(a.out).write_text(json.dumps(out, ensure_ascii=False))
    print("%d query texts" % len(out))
    return 0


def load_phone_queries(path):
    """{mode: {id: vector}} from the activity's `<file>.out.json`."""
    out = {"raw": {}, "prior": {}}
    for r in json.loads(Path(path).read_text()):
        out[r["mode"]][r["id"]] = r["vector"]
    return out


def first_hit(order, chunks, answers):
    import dense
    return next((r for r, i in enumerate(order) if dense.hit(chunks[i], answers)), None)


def cmd_eval(a):
    work = Path(a.work)
    chunks = [json.loads(l) for l in (work / "chunks.jsonl").open(encoding="utf-8")]
    full = np.load(work / "f768.npy").astype(np.float32)
    ids, items = book_questions(a.run)
    answers = {i: [x for x in items[i]["answers"] if x["source"].startswith("book:")] for i in ids}
    followups = [i for i in ids if items[i].get("priorQuestion")]
    queries = {}
    from sentence_transformers import SentenceTransformer
    model = SentenceTransformer(MODEL, device="mps")
    for mode in ("raw", "prior"):
        queries["mac-" + mode] = model.encode(query_texts(items, ids, mode), normalize_embeddings=True)
    if a.phone_queries:
        phone = load_phone_queries(a.phone_queries)
        for mode in ("raw", "prior"):
            queries["phone-" + mode] = np.array([phone[mode][i] for i in ids], dtype=np.float32)
    ks = (4, 12, 50)
    print("%d book questions (%d follow-ups); %d chunks" % (len(ids), len(followups), len(chunks)))
    print("%-12s %-14s %s" % ("query", "vectors", "  ".join("top%-3d" % k for k in ks) + "  follow-ups top4"))
    variants = [("768 float", 768, False), ("768 int8", 768, True), ("256 int8", 256, True), ("128 int8", 128, True)]
    for qname, qv in queries.items():
        for vname, dims, q8 in variants:
            d = gvec.truncate(full, dims)
            dq = gvec.quantize(d) if q8 else None
            counts, fu = {k: 0 for k in ks}, 0
            for i, qvec in zip(ids, gvec.truncate(qv, dims)):
                s = gvec.int8_scores(qvec, *dq) if q8 else d @ qvec
                order = np.argpartition(-s, max(ks))[:max(ks)]
                order = order[np.argsort(-s[order])]
                f = first_hit(order, chunks, answers[i])
                for k in ks:
                    counts[k] += f is not None and f < k
                fu += i in followups and f is not None and f < 4
            print("%-12s %-14s %s  %d/%d" % (qname, vname, "  ".join("%-6d" % counts[k] for k in ks), fu, len(followups)))
    return 0


GATE = 0.78  # R1 Task 7's gate: the best dense hit must reach this, or the dense side returns nothing


def first_dense_hit(passages, answers, any_source=False):
    """The rank of the first passage that holds an answer (by location or quote, as `dense.hit`), or None. With
    [any_source], the same article from another pack counts too (a subject pack holds Top's articles)."""
    import dense
    def ok(p):
        return dense.hit(p, [dict(x, source=p["source"]) for x in answers] if any_source else answers)
    return next((r for r, p in enumerate(passages) if ok(p)), None)


def covered(item, labels):
    """Does any of the item's answers come from a source that has vectors in [labels]?"""
    return any(x["source"] in labels for x in item["answers"])


def recall_table(items, firsts, top, labels, ks):
    """Lines of the report: recall at each k per group and overall, on answerable questions with an answer in the vectors."""
    groups = {}
    for item in items:
        groups.setdefault(item["group"], []).append(item)
    lines = ["%-22s %8s %s %s" % ("group", "in set", "  ".join("top%-3d" % k for k in ks), "  gate %.2f" % GATE)]
    rows = sorted(groups.items()) + [("all", items)]
    for name, its in rows:
        ans = [i for i in its if i["answerable"]]
        cov = [i for i in ans if covered(i, labels)]
        hits = ["%2d/%-3d" % (sum(1 for i in cov if firsts[i["id"]] is not None and firsts[i["id"]] < k), len(cov)) for k in ks]
        un = [i for i in its if not i["answerable"]]
        gate = "%d/%d ans, %d/%d unans" % (sum(top[i["id"]] >= GATE for i in cov), len(cov),
                                           sum(top[i["id"]] >= GATE for i in un), len(un))
        lines.append("%-22s %3d/%-4d %s  %s" % (name, len(cov), len(ans), "  ".join(hits), gate))
    return lines


def cmd_dataset(a):
    """Every question embedded on the Mac (prior question first, as the phone does), scanned over the vector files in
    WORK_DIR/out as the phone scans them (`hits.scan`), with the first hit by location or quote."""
    import hits
    items = json.loads(Path(a.dataset).read_text(encoding="utf-8"))
    ks = tuple(int(k) for k in a.k.split(","))
    srcs = [s for w in a.work for s in hits.sources(Path(w))]
    files, dims = [], None
    for f, _, _, _ in srcs:
        head, q, scales, _, _ = gvec.read(f)
        dims = dims or head["dims"]
        assert head["dims"] == dims, f
        files.append((q, scales))
    if not files:
        raise SystemExit("no vector files in %s" % " ".join(a.work))
    labels = {label for _, _, _, label in srcs}
    from sentence_transformers import SentenceTransformer
    model = SentenceTransformer(MODEL, device="mps")
    queries = model.encode([QUERY % query_text(i, "prior") for i in items], normalize_embeddings=True)
    best = hits.scan(gvec.truncate(queries, dims), files, max(ks))
    wanted = {}
    for row in best:
        for _, fi, r in row:
            wanted.setdefault(fi, set()).add(r)
    chunks = {}
    for fi, rows in wanted.items():
        with srcs[fi][1].open(encoding="utf-8") as f:
            for n, line in enumerate(f):
                if n in rows:
                    chunks[(fi, n)] = json.loads(line)
    firsts, top = {}, {}
    for item, row in zip(items, best):
        passages = [dict(chunks[(fi, r)], source=srcs[fi][3], location=chunks[(fi, r)]["key"]) for _, fi, r in row]
        firsts[item["id"]] = first_dense_hit(passages, item["answers"], a.any_source) if item["answerable"] else None
        top[item["id"]] = row[0][0] if row else 0.0
    print("%d questions; %d files, %d rows, %d dims" % (len(items), len(files), sum(len(q) for q, _ in files), dims))
    for line in recall_table(items, firsts, top, labels, ks):
        print(line)
    missed = [i["id"] for i in items if i["answerable"] and covered(i, labels) and (firsts[i["id"]] is None or firsts[i["id"]] >= ks[0])]
    print("missed at top%d: %s" % (ks[0], " ".join(missed) or "none"))
    return 0


def main(argv=None):
    ap = argparse.ArgumentParser()
    s = ap.add_subparsers(dest="cmd", required=True)
    p = s.add_parser("prepare"); p.add_argument("work"); p.add_argument("--chunks", required=True); p.set_defaults(f=cmd_prepare)
    q = s.add_parser("queries"); q.add_argument("run"); q.add_argument("out"); q.set_defaults(f=cmd_queries)
    e = s.add_parser("eval"); e.add_argument("work"); e.add_argument("run"); e.add_argument("--phone-queries"); e.set_defaults(f=cmd_eval)
    d = s.add_parser("dataset"); d.add_argument("dataset"); d.add_argument("work", nargs="+")
    d.add_argument("--k", default="4,12,50"); d.add_argument("--any-source", action="store_true")
    d.set_defaults(f=cmd_dataset)
    a = ap.parse_args(argv)
    return a.f(a)


if __name__ == "__main__":
    sys.exit(main())
