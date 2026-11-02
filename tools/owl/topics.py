#!/usr/bin/env python3
"""Topic centroids for Ask's instant owl reactions.

  topics.py texts OUT                 # anchors + eval questions, one per line, for EvalActivity's `embed` mode
  topics.py build VECTORS [--floor F] [--margin M] [--asset OUT]
                                      # centroids from the anchors' phone vectors; calibration on the eval questions

The phone embeds (the app's int4 EmbeddingGemma, query side), so the centroids match what the app computes. A question
gets a topic when its best cosine is at least the floor and beats the runner-up by the margin; MEDICAL nearest means
the plain lines whatever the margin. Eval questions are never anchors, so they calibrate honestly.
"""
import argparse
import json
import math
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
ANCHORS = HERE / "anchors.json"
EVAL = HERE.parent / "eval" / "dataset"
ASSET = HERE.parents[1] / "app" / "src" / "main" / "assets" / "owl" / "topics.txt"

# Eval groups whose topic is plain from the group; the rest (unanswerable, multi-step, vocab-gap, follow-up) are mixed.
GROUPS = {
    "bible": "SCRIPTURE", "literature": "LITERATURE", "who-wrote": "LITERATURE", "history": "HISTORY",
    "science": "SCIENCE", "physics": "SCIENCE", "chemistry": "SCIENCE", "geography": "PLACES",
    "geography-general": "PLACES", "words": "WORDS", "mathematics": "MATHS", "knots": "HOW_TO", "medical": "MEDICAL",
}


def anchors():
    return json.loads(ANCHORS.read_text(encoding="utf-8"))["topics"]


def eval_items():
    items = []
    for name in ("tune.json", "heldout.json"):
        d = json.loads((EVAL / name).read_text(encoding="utf-8"))
        items += d["items"] if isinstance(d, dict) else d
    return [i for i in items if not i.get("priorQuestion")]


def group_of(item):
    g = item["id"].rsplit("-", 1)[0]
    return g[2:] if g.startswith("h-") else g


def norm(v):
    n = math.sqrt(sum(x * x for x in v)) or 1.0
    return [x / n for x in v]


def dot(a, b):
    return sum(x * y for x, y in zip(a, b))


def classify(v, centroids, floor, margin, medical_floor=0.0):
    scored = sorted(((dot(v, c), t) for t, c in centroids.items()), reverse=True)
    (best, topic), (second, _) = scored[0], scored[1]
    if topic == "MEDICAL":
        return ("MEDICAL" if best >= medical_floor else "DEFAULT"), best, best - second
    if best >= floor and best - second >= margin:
        return topic, best, best - second
    return "DEFAULT", best, best - second


def cmd_texts(a):
    lines = [q for qs in anchors().values() for q in qs] + [i["question"] for i in eval_items()]
    assert all("\n" not in l for l in lines)
    Path(a.out).write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"{len(lines)} texts -> {a.out}")


def cmd_build(a):
    vectors = {}
    for line in Path(a.vectors).read_text(encoding="utf-8").splitlines():
        if line.strip():
            r = json.loads(line)
            vectors[r["text"]] = norm(r["v"])
    centroids = {}
    for topic, qs in anchors().items():
        missing = [q for q in qs if q not in vectors]
        if missing:
            sys.exit(f"no vector for {missing[:3]}")
        dims = len(vectors[qs[0]])
        centroids[topic] = norm([sum(vectors[q][d] for q in qs) / len(qs) for d in range(dims)])

    labelled = right = wrong = default = 0
    medical_miss = []
    confusions = []
    unlabelled = []
    for item in eval_items():
        v = vectors.get(item["question"])
        if v is None:
            continue
        got, best, gap = classify(v, centroids, a.floor, a.margin, a.medical_floor)
        want = GROUPS.get(group_of(item))
        if item.get("medical"):
            want = "MEDICAL"
        if want is None:
            unlabelled.append((got, round(best, 3), round(gap, 3), item["question"]))
            continue
        labelled += 1
        if got == want:
            right += 1
        elif got == "DEFAULT":
            default += 1
            if want == "MEDICAL":
                medical_miss.append(item["question"])
        else:
            wrong += 1
            confusions.append((want, got, round(best, 3), round(gap, 3), item["question"]))
    print(f"floor {a.floor} margin {a.margin} medical floor {a.medical_floor}: labelled {labelled}: right {right}, default {default}, wrong {wrong}")
    print(f"medical to DEFAULT (the lexicon still catches most): {len(medical_miss)}")
    for c in confusions:
        print("  WRONG", *c)
    if a.verbose:
        for m in medical_miss:
            print("  MED->DEFAULT", m)
        for u in sorted(unlabelled):
            print("  UNLABELLED", *u)
    else:
        print(f"unlabelled {len(unlabelled)}: " + ", ".join(f"{t}={sum(1 for u in unlabelled if u[0] == t)}"
                                                          for t in sorted({u[0] for u in unlabelled})))
    if a.asset:
        if not a.embedder:
            sys.exit("--asset needs --embedder SHA (the catalog's EmbeddingGemma sha256)")
        out = Path(a.asset)
        out.parent.mkdir(parents=True, exist_ok=True)
        lines = ["# Ask's owl reaction topics: centroids of tools/owl/anchors.json, embedded by the phone's EmbeddingGemma",
                 "# (query side). Rebuild with tools/owl/topics.py when the embedder changes.",
                 f"embedder {a.embedder}", f"floor {a.floor}", f"margin {a.margin}", f"medicalFloor {a.medical_floor}"]
        lines += [t + " " + " ".join(f"{x:.5f}" for x in c) for t, c in centroids.items()]
        out.write_text("\n".join(lines) + "\n", encoding="utf-8")
        print(f"asset -> {out}")


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    s = p.add_subparsers(required=True)
    t = s.add_parser("texts"); t.add_argument("out"); t.set_defaults(f=cmd_texts)
    b = s.add_parser("build"); b.add_argument("vectors")
    b.add_argument("--floor", type=float, default=0.0); b.add_argument("--margin", type=float, default=0.03)
    b.add_argument("--medical-floor", type=float, default=0.0)
    b.add_argument("--asset", nargs="?", const=str(ASSET)); b.add_argument("--embedder")
    b.add_argument("--verbose", action="store_true")
    b.set_defaults(f=cmd_build)
    a = p.parse_args()
    a.f(a)


if __name__ == "__main__":
    main()
