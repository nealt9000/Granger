#!/usr/bin/env python3
"""Fewer, tighter passages, tried offline and replayed on the phone (Plan R5, Task 3).

  passages.py score BASE                                   # runs/BASE/passage-scores.json: question and passages embedded
  passages.py threshold BASE                               # the gate's threshold, and what it would drop
  passages.py variant BASE NEW --mode gate|trim|both [--threshold T] [--chars 500]
                                                           # runs/NEW: BASE with each answer prompt rebuilt
  answerers.py run NEW NEW-e2b --model gemma-4-E2B-it-gpu.litertlm --set sample-40   # replays it
  passages.py check BASE NEW                                # Task 5: runs/NEW asks which passages answer, for replay
  passages.py filter BASE CHECKED-e2b NEW                   # runs/NEW: only the passages the check named

The answer step is all that changes, so instead of a debug flag through the whole pipeline the prompts BASE's ranking
built are rebuilt here and replayed through `answerers.py` (as Plan R4 compared models):
- **gate:** a chosen passage whose cosine to the question is under the threshold is dropped and the rest renumbered. An
  item left with none declines without the model, as the app does when nothing is found.
- **trim:** each passage keeps its best sentences by cosine to the question, each with one sentence either side, up to
  about `--chars` characters, from the passage's whole text (the prompt cuts it at about 1,250).

The scores are EmbeddingGemma's on the Mac (float), as `rewrite.py` does; the phone's int4 embedder will differ a little,
so a threshold carried into the app needs checking against the phone's own vectors. Questions are embedded as dense
retrieval embeds them ("task: search result | query: ..."; a follow-up with its prior question), passages and sentences
as the vector build titles chunks ("title: <title — heading> | text: ...").
"""
import argparse
import json
import re
import shutil
import sys
from pathlib import Path

import answerers
import grading

HERE = Path(__file__).resolve().parent
RUNS = HERE / "runs"
MODEL = "google/embeddinggemma-2"
QUERY = "task: search result | query: %s"
DOC = "title: %s | text: %s"
DIMS = 256
PASSAGE = re.compile(r"^\[(\d+)\] (.*)$")
SENTENCE = re.compile(r"(?<=[.!?…])[\"')\]]*\s+(?=[\"'(\[]?[A-Z0-9])")


def read_jsonl(path):
    return answerers.read_jsonl(path)


# --- the prompt's parts -----------------------------------------------------------------------------------------------

def split_prompt(prompt):
    """(rules, [(header, body)], tail): the text before "Passages:", each numbered passage, and from "Question:" on."""
    head, _, rest = prompt.partition("\nPassages:\n")
    body, sep, tail = rest.rpartition("\n\nQuestion:")
    passages = []
    for line in body.split("\n"):
        m = PASSAGE.match(line)
        if m and int(m.group(1)) == len(passages) + 1:
            passages.append([m.group(2), []])
        elif passages:
            passages[-1][1].append(line)
    return head, [(h, "\n".join(b).strip()) for h, b in passages], sep.lstrip("\n") + tail


def join_prompt(head, passages, tail):
    blocks = ["[%d] %s\n%s" % (n, h, b) for n, (h, b) in enumerate(passages, 1)]
    return "%s\nPassages:\n%s\n\n%s" % (head, "\n\n".join(blocks), tail)


def sentences(text):
    return [s.strip() for s in SENTENCE.split(re.sub(r"\s*\n\s*", " ", text or "")) if s.strip()]


def title_of(p):
    heading = p.get("heading") or ""
    return p.get("title") or "none" if not heading or heading == p.get("title") else "%s — %s" % (p.get("title"), heading)


def query_of(item):
    return " ".join(x for x in (item.get("priorQuestion"), item["question"]) if x)


# --- scores -----------------------------------------------------------------------------------------------------------

def embed(texts):
    import numpy as np
    from sentence_transformers import SentenceTransformer
    model = SentenceTransformer(MODEL, device="mps")
    v = model.encode(texts, batch_size=32, normalize_embeddings=True)[:, :DIMS]
    return v / np.linalg.norm(v, axis=1, keepdims=True)


def cmd_score(a):
    items = {i["id"]: i for i in json.loads((RUNS / a.base / "dataset.json").read_text(encoding="utf-8"))}
    recs = [r for r in read_jsonl(RUNS / a.base / "results.jsonl") if r.get("chosen")]
    texts, where = [], []
    for r in recs:
        texts.append(QUERY % query_of(items[r["id"]])); where.append((r["id"], "q", None, None))
        for n, p in enumerate(r["chosen"]):
            texts.append(DOC % (title_of(p), p["text"])); where.append((r["id"], "p", n, None))
            for k, s in enumerate(sentences(p["text"])):
                texts.append(DOC % (title_of(p), s)); where.append((r["id"], "s", n, k))
    v = embed(texts)
    out, q = {}, {}
    for (id, kind, n, k), vec in zip(where, v):
        if kind == "q":
            q[id] = vec
            out[id] = {"passages": [], "sentences": []}
        elif kind == "p":
            out[id]["passages"].append(round(float(vec @ q[id]), 4))
            out[id]["sentences"].append([])
        else:
            out[id]["sentences"][n].append(round(float(vec @ q[id]), 4))
    (RUNS / a.base / "passage-scores.json").write_text(json.dumps(out), encoding="utf-8")
    print("%d items, %d texts -> runs/%s/passage-scores.json" % (len(out), len(texts), a.base))
    return 0


def load_scores(base):
    return json.loads((RUNS / base / "passage-scores.json").read_text(encoding="utf-8"))


def cited(rec):
    return {int(n) for n in re.findall(r"\d+", str(rec.get("cited") or ""))}


def cmd_threshold(a):
    """The highest threshold that keeps every passage a grounded reply cites, and what it and lower ones drop."""
    items = {i["id"]: i for i in json.loads((RUNS / a.base / "dataset.json").read_text(encoding="utf-8"))}
    recs = {r["id"]: r for r in read_jsonl(RUNS / a.base / "results.jsonl")}
    scores = load_scores(a.base)
    grades = grading.load_grades(HERE / "grades" / "grades.jsonl")
    kept = []
    for id, s in scores.items():
        r = recs[id]
        g = grading.current_grade(grades, id, "answer", r.get("reply", "")) if r.get("outcome") == "answered" else None
        if g and g["grade"] == "grounded":
            kept += [(s["passages"][n - 1], id) for n in cited(r) if 0 < n <= len(s["passages"])]
    floor, at = min(kept)
    print("lowest cited passage of a grounded reply: %.4f (%s)" % (floor, at))
    for t in sorted({round(floor - 0.0001, 4)} | {float(x) for x in a.try_ or []}):
        rows = {"threshold": t, "passages dropped": 0, "items with none left": {"answerable": 0, "unanswerable": 0},
                "grounded replies losing a cited passage": 0}
        for id, s in scores.items():
            drop = [n + 1 for n, c in enumerate(s["passages"]) if c < t]
            rows["passages dropped"] += len(drop)
            if len(drop) == len(s["passages"]):
                rows["items with none left"]["answerable" if items[id]["answerable"] else "unanswerable"] += 1
            r = recs[id]
            g = grading.current_grade(grades, id, "answer", r.get("reply", "")) if r.get("outcome") == "answered" else None
            if g and g["grade"] == "grounded" and cited(r) & set(drop):
                rows["grounded replies losing a cited passage"] += 1
        print(json.dumps(rows))
    return 0


# --- the variant ------------------------------------------------------------------------------------------------------

def trim(text, scores, chars):
    """The best sentences by score, each with one either side, in their own order, up to about `chars`."""
    ss = sentences(text)
    if len(ss) != len(scores) or sum(len(s) + 1 for s in ss) <= chars:
        return text
    keep = set()
    for best in sorted(range(len(ss)), key=lambda k: -scores[k]):
        window = {k for k in (best - 1, best, best + 1) if 0 <= k < len(ss)} - keep
        if keep and sum(len(ss[k]) + 1 for k in keep | window) > chars:
            break
        keep |= window
        if sum(len(ss[k]) + 1 for k in keep) >= chars:
            break
    out, last = [], None
    for k in sorted(keep):
        if last is not None and k != last + 1:
            out.append("…")
        out.append(ss[k])
        last = k
    return " ".join(out)


def rebuild(rec, item, score, mode, threshold, chars):
    """(prompt or None, chosen): the record's answer prompt and passages after the gate and/or trim."""
    prompt = answerers.answer_prompt(rec)
    chosen = rec.get("chosen") or []
    if prompt is None or not chosen:
        return prompt, chosen
    head, passages, tail = split_prompt(prompt)
    if len(passages) != len(chosen):
        raise SystemExit("%s: the prompt has %d passages, the record %d" % (rec["id"], len(passages), len(chosen)))
    keep = list(range(len(chosen)))
    if mode in ("gate", "both"):
        keep = [n for n in keep if score["passages"][n] >= threshold]
    if not keep:
        return None, []
    out_p, out_c = [], []
    for n in keep:
        header, body = passages[n]
        p = dict(chosen[n])
        if mode in ("trim", "both"):
            p["text"] = trim(p["text"], score["sentences"][n], chars)
            body = p["text"]
        out_p.append((header, body))
        out_c.append(p)
    return join_prompt(head, out_p, tail), out_c


def set_prompt(rec, prompt):
    ps = [p for p in rec.get("prompts") or [] if p["stage"] == "answer"]
    ps[-1]["prompt"] = prompt


def cmd_variant(a):
    if a.mode in ("gate", "both") and a.threshold is None:
        raise SystemExit("--threshold is needed for %s" % a.mode)
    items = {i["id"]: i for i in json.loads((RUNS / a.base / "dataset.json").read_text(encoding="utf-8"))}
    scores = load_scores(a.base)
    out_dir = RUNS / a.new
    out_dir.mkdir(parents=True, exist_ok=True)
    out, emptied, chars_before, chars_after = [], 0, 0, 0
    for rec in read_jsonl(RUNS / a.base / "results.jsonl"):
        rec = {k: v for k, v in rec.items() if k not in ("deeper", "guesses")}
        before = answerers.answer_prompt(rec)
        if before is not None and rec.get("chosen"):
            prompt, chosen = rebuild(rec, items[rec["id"]], scores[rec["id"]], a.mode, a.threshold, a.chars)
            chars_before += len(before)
            if prompt is None:
                emptied += 1
                rec["prompts"] = [p for p in rec["prompts"] if p["stage"] != "answer"]
                rec.update(outcome="declined", reply="", cited="", chosen=[])
            else:
                chars_after += len(prompt)
                set_prompt(rec, prompt)
                rec["chosen"] = chosen
        out.append(rec)
    shutil.copy(RUNS / a.base / "dataset.json", out_dir / "dataset.json")
    answerers.write_jsonl(out_dir / "results.jsonl", out)
    (out_dir / "variant.json").write_text(json.dumps({"from": a.base, "mode": a.mode, "threshold": a.threshold,
                                                      "chars": a.chars}), encoding="utf-8")
    print(json.dumps({"variant": a.new, "items left with no passages": emptied,
                      "prompt characters (sum, before -> after)": [chars_before, chars_after]}))
    return 0


# --- the check pass (Task 5) -----------------------------------------------------------------------------------------

CHECK = ("Below are numbered passages from a library, and a question.\n\nPassages:\n%s\n\nQuestion: %s\n\n"
         "Which passages contain the answer to the question, or facts needed to work it out? "
         "Reply with only their numbers, comma-separated, or the word none.\nPassages:")


def check_prompt(prompt):
    """The answer prompt's passages and question, asked which passages answer it."""
    head, passages, tail = split_prompt(prompt)
    question = tail.partition("Question:")[2].partition("\nAnswer:")[0].strip()
    blocks = "\n\n".join("[%d] %s\n%s" % (n, h, b) for n, (h, b) in enumerate(passages, 1))
    prior = re.search(r"^Earlier in this conversation:\nQ: (.*)$", head, re.M)
    if prior:  # a follow-up: "how long did it take him" needs who
        question = "%s (follows: %s)" % (question, prior.group(1).strip())
    return CHECK % (blocks, question)


def parse_check(reply, n):
    """The passage numbers the check kept (1-based, in range), [] for none, or None when the reply is neither."""
    text = (reply or "").strip().lower()
    nums = [int(x) for x in re.findall(r"\d+", text) if 0 < int(x) <= n]
    if nums:
        return sorted(set(nums))
    return [] if "none" in text else None


def cmd_check(a):
    """runs/NEW: BASE with each answer prompt replaced by its check prompt, to replay with --max-tokens 16."""
    out_dir = RUNS / a.new
    out_dir.mkdir(parents=True, exist_ok=True)
    out = []
    for rec in read_jsonl(RUNS / a.base / "results.jsonl"):
        rec = {k: v for k, v in rec.items() if k not in ("deeper", "guesses")}
        if answerers.answer_prompt(rec) is not None and rec.get("chosen"):
            set_prompt(rec, check_prompt(answerers.answer_prompt(rec)))
        out.append(rec)
    shutil.copy(RUNS / a.base / "dataset.json", out_dir / "dataset.json")
    answerers.write_jsonl(out_dir / "results.jsonl", out)
    (out_dir / "variant.json").write_text(json.dumps({"from": a.base, "mode": "check-prompt"}), encoding="utf-8")
    print("runs/%s: check prompts" % a.new)
    return 0


def cmd_filter(a):
    """runs/NEW: BASE's answer prompts keeping only the passages CHECKED's replay named; "none" declines without the
    model; a reply that is neither keeps every passage."""
    replies = {r["id"]: r for r in read_jsonl(RUNS / a.checked / "replay.jsonl")}
    out_dir = RUNS / a.new
    out_dir.mkdir(parents=True, exist_ok=True)
    out, counts = [], {"none": 0, "kept some": 0, "kept all": 0, "unparsed": 0, "not checked": 0}
    for rec in read_jsonl(RUNS / a.base / "results.jsonl"):
        rec = {k: v for k, v in rec.items() if k not in ("deeper", "guesses")}
        prompt, chosen = answerers.answer_prompt(rec), rec.get("chosen") or []
        if prompt is not None and chosen:
            r = replies.get(rec["id"])
            keep = parse_check(r["reply"], len(chosen)) if r else None
            if r is None:
                counts["not checked"] += 1
            elif keep is None:
                counts["unparsed"] += 1
            elif not keep:
                counts["none"] += 1
                rec["prompts"] = [p for p in rec["prompts"] if p["stage"] != "answer"]
                rec.update(outcome="declined", reply="", cited="", chosen=[])
            else:
                counts["kept all" if len(keep) == len(chosen) else "kept some"] += 1
                head, passages, tail = split_prompt(prompt)
                set_prompt(rec, join_prompt(head, [passages[k - 1] for k in keep], tail))
                rec["chosen"] = [chosen[k - 1] for k in keep]
        out.append(rec)
    shutil.copy(RUNS / a.base / "dataset.json", out_dir / "dataset.json")
    answerers.write_jsonl(out_dir / "results.jsonl", out)
    (out_dir / "variant.json").write_text(json.dumps({"from": a.base, "mode": "check", "checked": a.checked}), encoding="utf-8")
    print(json.dumps(counts))
    return 0


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    s = p.add_subparsers(dest="cmd", required=True)
    sc = s.add_parser("score"); sc.add_argument("base"); sc.set_defaults(f=cmd_score)
    th = s.add_parser("threshold"); th.add_argument("base"); th.add_argument("--try", dest="try_", nargs="*")
    th.set_defaults(f=cmd_threshold)
    v = s.add_parser("variant"); v.add_argument("base"); v.add_argument("new")
    v.add_argument("--mode", required=True, choices=["gate", "trim", "both"]); v.add_argument("--threshold", type=float)
    v.add_argument("--chars", type=int, default=500); v.set_defaults(f=cmd_variant)
    c = s.add_parser("check"); c.add_argument("base"); c.add_argument("new"); c.set_defaults(f=cmd_check)
    fl = s.add_parser("filter"); fl.add_argument("base"); fl.add_argument("checked"); fl.add_argument("new")
    fl.set_defaults(f=cmd_filter)
    a = p.parse_args(argv)
    return a.f(a)


if __name__ == "__main__":
    sys.exit(main())
