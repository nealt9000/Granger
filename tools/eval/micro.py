#!/usr/bin/env python3
"""Micro ceiling (2026-11-09): BASE's ceiling prompts (dev-c1-first-2: answer passage only) with each passage cut to
the answer sentence(s) plus one neighbour each side.

  micro.py BASE NEW [--pad 1]   # then answerers.py run NEW NEW-e2b --model … --backend cpu

- Answerable: sentences overlapping a dataset quote (point.gold), then +-pad sentences, in passage order.
  No overlap found -> passage left as is (counted).
- Unanswerable: the passage's best sentence by EmbeddingGemma cosine to the question (what a micro retriever would
  pick), +-pad. Gate declines stay declines.
"""
import argparse, json, shutil, sys
from pathlib import Path

EVAL = Path(__file__).resolve().parent
sys.path.insert(0, str(EVAL))
import answerers, passages, point  # noqa: E402

FACTS = "\n\nDates in the passages:"


def span(sents, idx, pad):
    lo, hi = max(0, min(idx) - pad), min(len(sents) - 1, max(idx) + pad)
    return " ".join(sents[lo:hi + 1])


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("base"); ap.add_argument("new"); ap.add_argument("--pad", type=int, default=1)
    a = ap.parse_args()
    base = EVAL / "runs" / a.base
    items = {i["id"]: i for i in json.loads((base / "dataset.json").read_text(encoding="utf-8"))}
    recs = answerers.read_jsonl(base / "results.jsonl")
    model = None
    out, stats = [], {"answerable": 0, "fallback": 0, "unanswerable": 0, "charsBefore": 0, "charsAfter": 0}
    for rec in recs:
        rec = dict(rec)
        it = items[rec["id"]]
        p = answerers.answer_prompt(rec)
        if p is None:
            out.append(rec); continue
        head, ps, tail = passages.split_prompt(p)
        new_ps = []
        for h, b in ps:
            b = b.split(FACTS)[0]
            sents = point.split_sentences(b)
            stats["charsBefore"] += len(b)
            if it["answerable"]:
                idx = [i - 1 for i in point.gold(sents, it["answers"])]
                if idx:
                    b2 = span(sents, idx, a.pad)
                else:
                    b2 = b; stats["fallback"] += 1
            else:
                if model is None:
                    from sentence_transformers import SentenceTransformer
                    model = SentenceTransformer("google/embeddinggemma-2", device="mps")
                q = model.encode([it["question"]], prompt_name="query", normalize_embeddings=True)
                d = model.encode(["title: %s | text: %s" % (h, s) for s in sents] or [""], normalize_embeddings=True)
                best = int((d @ q[0]).argmax())
                b2 = span(sents, [best], a.pad) if sents else b
            stats["charsAfter"] += len(b2)
            new_ps.append((h, b2))
        stats["answerable" if it["answerable"] else "unanswerable"] += 1
        passages.set_prompt(rec, passages.join_prompt(head, new_ps, tail))
        if rec.get("chosen"):
            rec["chosen"] = [dict(c, text=pb[1]) for c, pb in zip(rec["chosen"], new_ps)]
        out.append(rec)
    d = EVAL / "runs" / a.new
    d.mkdir(parents=True, exist_ok=True)
    shutil.copy(base / "dataset.json", d / "dataset.json")
    answerers.write_jsonl(d / "results.jsonl", out)
    (d / "variant.json").write_text(json.dumps({"from": a.base, "micro": True, "pad": a.pad}), encoding="utf-8")
    print(json.dumps(stats))


if __name__ == "__main__":
    main()
