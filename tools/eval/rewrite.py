#!/usr/bin/env python3
"""The rewrite without the answer model, offline (Plan R4, Task 5).

  rewrite.py medical RUN [--labels sets/medical-labels.jsonl]   # the medical flag by the embedder, against E2B's
  rewrite.py keywords RUN                                       # plain keywords against E2B's, first questions

Today E2B's rewrite gives keywords and a medical flag before every search. The pipeline's flag is the model's OR the
`Medical` lexicon OR `PersonalMedical.strong` (core/research Keywords.kt); without the model it would be the lexicon,
the strong phrasings and whatever replaces the model. Here that is EmbeddingGemma's query vector (the phone already
computes it for dense retrieval: "task: search result | query: <question>", 256 dims) against two centroids built from
written labels, never from the tuning questions. The lexicon and phrasings are read from the Kotlin source, so they are
the app's own. `keywords` compares `Keywords.extract` (the pipeline's fallback) with E2B's keywords; recall needs the
phone (`--es rewrite plain`). Needs numpy; `medical` also sentence-transformers and torch.
"""
import argparse
import json
import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
RUNS = HERE / "runs"
KEYWORDS_KT = HERE.parents[1] / "core/research/src/main/kotlin/app/granger/core/research/Keywords.kt"
LABELS = HERE / "sets" / "medical-labels.jsonl"
MODEL = "google/embeddinggemma-2"
QUERY = "task: search result | query: %s"
DIMS = 256


def read_jsonl(path):
    return [json.loads(l) for l in Path(path).read_text(encoding="utf-8").splitlines() if l.strip()]


# --- the app's lexicon, read from Keywords.kt -------------------------------------------------------------------------

def _block(src, name):
    """The body of `name = listOf(...)` (or `setOf`), up to its closing bracket; brackets inside strings don't count."""
    at = src.index("(", src.index(name)) + 1
    depth, i, quoted = 1, at, False
    while depth:
        ch = src[i]
        if quoted:
            if ch == "\\":
                i += 1
            elif ch == '"':
                quoted = False
        elif ch == '"':
            quoted = True
        else:
            depth += {"(": 1, ")": -1}.get(ch, 0)
        i += 1
    return src[at:i - 1]


def _strings(body, interpolate=None):
    out = []
    for s in re.findall(r'"((?:[^"\\]|\\.)*)"', body):
        s = s.replace("\\\\", "\\")
        for k, v in (interpolate or {}).items():
            s = s.replace("$" + k, v)
        out.append(s)
    return out


def lexicon(src=None):
    """(looks_medical, strong, stopwords) as the app has them."""
    src = src or KEYWORDS_KT.read_text(encoding="utf-8")
    medical = src[src.index("object Medical"):src.index("object PersonalMedical")]
    personal = src[src.index("object PersonalMedical"):]
    terms = [r"\b" + re.escape(t) for t in _strings(_block(medical, "TERMS"))]
    whole = [r"\b%s\b" % t for t in _strings(_block(medical, "WHOLE_WORDS"))]
    looks = re.compile("|".join(terms + whole), re.I)
    consts = {k: v for k, v in re.findall(r'private val (NUMBER|RELATIVE) = "([^"]*)"', personal)}
    strong = [re.compile(p, re.I) for p in _strings(_block(personal, "STRONG"), consts)]
    stop = set(_strings(_block(src, "STOPWORDS")))
    return (lambda t: bool(looks.search(t)),
            lambda q: any(p.search(q.replace("’", "'")) for p in strong),
            stop)


def extract(question, stop, max_n=6):
    """Keywords.extract."""
    out = []
    for w in re.split(r"[^\w']+", question.lower().replace("\u2019", "'")):
        w = w.strip("'")
        w = w[:-2] if w.endswith("'s") else w
        if len(w) >= 3 and w.replace("'", "") not in stop and w not in out:
            out.append(w)
    return out[:max_n]


# --- the model's own flag, from the recorded rewrite reply ------------------------------------------------------------

MEDICAL_LINE = re.compile(r"^\s*medical\s*:\s*(yes|no)", re.I | re.M)


def model_flag(rec):
    """E2B's `medical:` line for the question itself (not a follow-up's prior question); None when it had none."""
    ps = [p for p in rec.get("prompts") or [] if p["stage"] == "rewrite"]
    m = MEDICAL_LINE.search(ps[-1].get("output") or "") if ps else None
    return None if m is None else m.group(1).lower() == "yes"


def text_of(item):
    """The pipeline's text for the lexicon: the question and its prior question."""
    return " ".join(x for x in (item["question"], item.get("priorQuestion")) if x)


# --- the embedder ---------------------------------------------------------------------------------------------------

def embed(texts):
    import numpy as np
    from sentence_transformers import SentenceTransformer
    model = SentenceTransformer(MODEL, device="mps")
    v = model.encode([QUERY % t for t in texts], batch_size=32, normalize_embeddings=True)[:, :DIMS]
    return v / np.linalg.norm(v, axis=1, keepdims=True)


def centroid_scores(train, labels, test):
    """cos(medical centroid) - cos(other centroid) per test vector."""
    import numpy as np
    labels = np.asarray(labels, bool)
    c = [train[labels].mean(0), train[~labels].mean(0)]
    c = [x / np.linalg.norm(x) for x in c]
    return test @ c[0] - test @ c[1]


def knn_scores(train, labels, test, k=5):
    """The share of the k nearest labelled questions that are medical, minus a half."""
    import numpy as np
    labels = np.asarray(labels, float)
    near = np.argsort(-(test @ train.T), axis=1)[:, :k]
    return labels[near].mean(1) - 0.5


def held_out(vecs, labels, scorer, folds=5):
    """Scores of each labelled question from a scorer built without its fold."""
    import numpy as np
    out = np.empty(len(labels))
    idx = np.arange(len(labels))
    for f in range(folds):
        test = idx % folds == f
        out[test] = scorer(vecs[~test], [l for l, t in zip(labels, test) if not t], vecs[test])
    return out


def cmd_medical(a):
    import numpy as np
    looks, strong, _ = lexicon()
    items = json.loads((RUNS / a.run / "dataset.json").read_text(encoding="utf-8"))
    recs = {r["id"]: r for r in read_jsonl(RUNS / a.run / "results.jsonl")}
    labels = read_jsonl(a.labels)
    y = [l["medical"] for l in labels]
    vecs = embed([l["q"] for l in labels] + [i["question"] for i in items])
    lv, tv = vecs[:len(labels)], vecs[len(labels):]
    rows = []
    for name, scorer in (("centroid", centroid_scores), ("knn5", knn_scores)):
        # The threshold is set on the labels alone (held out by fold): the lowest score of a medical label, so none is missed.
        ho = held_out(lv, y, scorer)
        for rule, th in (("0", 0.0), ("no label missed", float(min(s for s, m in zip(ho, y) if m)) - 1e-9)):
            ho_fp = sum(1 for s, m in zip(ho, y) if s > th and not m)
            ho_fn = sum(1 for s, m in zip(ho, y) if s <= th and m)
            emb = [bool(x) for x in scorer(lv, y, tv) > th]
            rows.append((name, rule, th, int(ho_fn), int(ho_fp), emb))
    gold = [bool(i.get("medical")) for i in items]
    e2b = [bool(recs[i["id"]].get("medical")) for i in items]
    plain = [looks(text_of(i)) or strong(i["question"]) for i in items]
    model = [model_flag(recs[i["id"]]) for i in items]

    def report(name, flag):
        caught = [i["id"] for i, g, e, f in zip(items, gold, e2b, flag) if g and e and not f]
        return {"flag": name, "medical caught": "%d/%d" % (sum(f and g for f, g in zip(flag, gold)), sum(gold)),
                "flagged, not medical": sum(f and not g for f, g in zip(flag, gold)),
                "E2B caught, this missed": caught}

    out = [report("E2B pipeline (model | lexicon | strong)", e2b),
           report("model's own line", [bool(m) for m in model]),
           report("lexicon | strong (no model)", plain)]
    for name, rule, th, fn, fp, emb in rows:
        r = report("lexicon | strong | %s (threshold %s = %.3f)" % (name, rule, th), [p or e for p, e in zip(plain, emb)])
        r["labels held out: missed, false"] = [fn, fp]
        r["embedder alone: medical caught"] = "%d/%d" % (sum(e and g for e, g in zip(emb, gold)), sum(gold))
        r["embedder alone: flagged, not medical"] = [i["id"] for i, e, g in zip(items, emb, gold) if e and not g]
        out.append(r)
    for r in out:
        print(json.dumps(r))
    return 0


def cmd_keywords(a):
    _, _, stop = lexicon()
    items = {i["id"]: i for i in json.loads((RUNS / a.run / "dataset.json").read_text(encoding="utf-8"))}
    shared = total = same = adds = 0
    for rec in read_jsonl(RUNS / a.run / "results.jsonl"):
        item = items[rec["id"]]
        if item.get("priorQuestion") or not rec.get("keywords"):
            continue
        plain = extract(item["question"], stop)
        e2b_words = {w for k in rec["keywords"] for w in re.findall(r"\w+", k.lower())}
        in_question = {w for w in e2b_words if w in item["question"].lower()}
        total += 1
        shared += len(in_question & set(plain)) / max(1, len(in_question))
        same += in_question <= set(plain)
        adds += bool(e2b_words - set(re.findall(r"\w+", item["question"].lower())))
        if a.verbose:
            print("%-20s %-50s | %s" % (rec["id"], plain, rec["keywords"]))
    print(json.dumps({"first questions": total, "E2B's question words plain keeps (mean share)": round(shared / total, 2),
                      "plain keeps all of them": same, "E2B adds words not in the question": adds}))
    return 0


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    s = p.add_subparsers(dest="cmd", required=True)
    m = s.add_parser("medical"); m.add_argument("run"); m.add_argument("--labels", default=LABELS); m.set_defaults(f=cmd_medical)
    k = s.add_parser("keywords"); k.add_argument("run"); k.add_argument("-v", "--verbose", action="store_true")
    k.set_defaults(f=cmd_keywords)
    a = p.parse_args(argv)
    return a.f(a)


if __name__ == "__main__":
    sys.exit(main())
