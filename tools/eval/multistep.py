#!/usr/bin/env python3
"""Plan R1 follow-up: answer a multi-step question as simple ones (Neal, 2026-10-23).

The model splits the question into simple questions; each is searched by meaning (on the Mac, as the phone would) and
answered with the app's own answer prompt; a later one may use an earlier one's answer (#1); a last answer prompt
combines them. The model steps replay on the phone through `answerers.py`'s replay (EvalActivity --es replay), so
each round is: write prompts here, replay them there, read the replies back.

  multistep.py split    BASE NEW                # round 1: NEW/split-prompts.jsonl, one per question of BASE
  multistep.py hop      BASE NEW --round N      # rounds 2, 3: the sub-questions whose #refs are answered
  multistep.py combine  BASE NEW                # round 4: the combined answer prompts
  multistep.py replay   NEW STAGE --model FILE  # pushes NEW/STAGE-prompts.jsonl, replays it, pulls NEW/STAGE-replies.jsonl
  multistep.py build    BASE NEW                # NEW/results.jsonl: BASE's records, multi-step ones answered this way

A question the model doesn't split keeps BASE's record, so NEW measures the change as the app would see it.
"""
import argparse
import json
import re
import shutil
import sys
from pathlib import Path

import answerers
import merge

HERE = Path(__file__).resolve().parent
RUNS = HERE / "runs"
VECTORS = HERE.parent / "vectors"
RELEASES = VECTORS / "releases"
WORK = Path.home() / "Developer/Granger/vectors-work/build"

DECLINE = "NOT IN LIBRARY"
MAX_PARTS = 3
SUB_PASSAGES = 4
COMBINED_PASSAGES = 8
GATE = 0.78  # Ranking's denseGate: a sub-question with nothing this close is not in the library
TEXT_CHARS, MAX_HEADER = 1200, 240

SPLIT = """Break the question into simple questions that can each be answered from one encyclopedia article.
Write one per line, numbered. A later question may use #1 or #2 for the answer to an earlier one.
If the question is already simple, write it alone as 1.
Example:
Question: Who was older, Mozart or Haydn?
1. When was Mozart born?
2. When was Haydn born?
Example:
Question: What river runs through the capital of France?
1. What is the capital of France?
2. What river runs through #1?
Example:
Question: What is a bowline knot?
1. What is a bowline knot?
"""

RULES = """You are Granger's research librarian. Answer the question using only the numbered passages from the user's library below.
Rules:
- Answer the question directly in your first sentence, then give the supporting details.
- Put the passage number in square brackets after every claim it supports, like [2]. Cite only the numbers listed.
- You may combine facts from different passages and work out simple things from them (dates, ages, which came first, how long between), citing every passage you used.
- Use the source details (title, author, date, collection) when they matter to the question.
- When passages from different works bear on each other, say how, citing each.
- Don't add facts that aren't in the passages.
- Passages about a different person, year, place or event than the question asks about don't answer it.
- If the passages don't answer the question, reply exactly: NOT IN LIBRARY
"""


# ---- prompts ----

def split_prompt(question, prior=None):
    head = SPLIT + ("Previous question: %s\n" % prior if prior else "")
    return head + "Question: %s" % question


NUMBERED = re.compile(r"^\s*(\d+)[.)]\s*(.+?)\s*$")


def parse_split(reply):
    """The numbered questions of a split reply, at most MAX_PARTS; [] when it isn't numbered."""
    out = []
    for line in (reply or "").splitlines():
        m = NUMBERED.match(line)
        if m and int(m.group(1)) == len(out) + 1:
            out.append(m.group(2))
    return out[:MAX_PARTS]


REF = re.compile(r"#(\d+)")
CITE = re.compile(r"\s*\[\d+(?:\s*,\s*\d+)*\]")


def refs(question):
    return {int(n) for n in REF.findall(question)}


def first_sentence(answer):
    text = CITE.sub("", answer or "").strip()
    m = re.search(r"(.+?[.!?])(\s|$)", text)
    return (m.group(1) if m else text).strip()


def fill(question, answers):
    """[question] with each #n replaced by answer n's first sentence; None while one is missing or declined."""
    need = refs(question)
    if any(n not in answers or answers[n] is None for n in need):
        return None
    return REF.sub(lambda m: "(%s)" % first_sentence(answers[int(m.group(1))]).rstrip("."), question)


def _cut(text, limit):
    if len(text) <= limit:
        return text
    head = text[:limit]
    space = max(head.rfind(" "), head.rfind("\n"))
    return (head[:space] if space > 0 else head).rstrip() + "…"


def answer_prompt(question, passages, notes=None):
    """AnswerPrompt.build without medical, prior or quote; [notes] are the parts' answers, for the combined prompt."""
    lines = [RULES, "Passages:"]
    for i, p in enumerate(passages):
        if i:
            lines.append("")
        head = "[%d] %s" % (i + 1, p.get("title", ""))
        if p.get("heading"):
            head += " · " + p["heading"]
        lines.append(_cut(head, MAX_HEADER))
        lines.append(_cut(p.get("text", ""), TEXT_CHARS))
    lines.append("")
    if notes:
        lines.append("Answers to the parts of the question, from the same passages:")
        lines += ["- %s %s" % (q, a) for q, a in notes]
        lines.append("")
    lines.append("Question: %s" % question)
    return "\n".join(lines) + "\nAnswer:"


def renumber(answer, mapping):
    """[answer]'s citations [n] as [mapping[n]], for a part's answer moved into the combined prompt."""
    def one(m):
        ns = [int(x) for x in re.findall(r"\d+", m.group(2))]
        kept = [str(mapping[n]) for n in ns if n in mapping]
        return m.group(1) + "[" + ", ".join(kept) + "]" if kept else ""
    return re.sub(r"(\s*)(\[\d+(?:\s*,\s*\d+)*\])", one, answer)


# ---- state ----

def read(path):
    return answerers.read_jsonl(path) if Path(path).exists() else []


def state_path(new):
    return RUNS / new / "multistep.json"


def load_state(new):
    p = state_path(new)
    return json.loads(p.read_text(encoding="utf-8")) if p.exists() else {}


def save_state(new, state):
    state_path(new).write_text(json.dumps(state, indent=1, ensure_ascii=False), encoding="utf-8")


def replies(new, stage):
    return {r["id"]: r for r in read(RUNS / new / ("%s-replies.jsonl" % stage))}


# ---- search, on the Mac as the phone would ----

def dense_search(texts, k=24):
    """Top [k] dense hits per text over the released vector files, scanned as hits.py scans the phone's."""
    sys.path.insert(0, str(VECTORS))
    import gvec
    import hits
    import numpy as np
    from sentence_transformers import SentenceTransformer
    released = {json.loads(p.read_text())["fileName"] for p in RELEASES.glob("*.json")}
    srcs = [s for s in hits.sources(WORK) if s[0].name in released]
    model = SentenceTransformer("google/embeddinggemma-2", device="cpu")
    vecs = np.array(model.encode_query(list(texts), batch_size=32), np.float32)
    files = []
    for f, _, _, _ in srcs:
        _, q, scales, _, _ = gvec.read(f)
        files.append((q, scales))
    best = hits.scan(gvec.truncate(vecs, 256), files, k)
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
    out = []
    for b in best:
        ps = []
        for score, fi, row in b:
            c, (_, _, kind, label) = chunks[(fi, row)], srcs[fi]
            ps.append({"kind": kind, "source": label, "title": c["title"], "location": c["key"],
                       "heading": c.get("heading") or "", "text": c["text"], "cosine": round(score, 4)})
        out.append(ps)
    return out


def pick(hits):
    """The passages for one part: dense hits from the floor up, unique by text, at most 2 per document; [] under the gate."""
    if not hits or max(p["cosine"] for p in hits) < GATE:
        return []
    seen, per_doc, out = set(), {}, []
    for p in sorted(hits, key=lambda p: -p["cosine"]):
        k, d = merge.text_key(p), (p["source"], p["location"])
        if p["cosine"] < 0.74 or k in seen or per_doc.get(d, 0) >= 2:
            continue
        seen.add(k)
        per_doc[d] = per_doc.get(d, 0) + 1
        out.append(p)
        if len(out) == SUB_PASSAGES:
            break
    return out


# ---- commands ----

def base_items(base):
    ds = json.loads((RUNS / base / "dataset.json").read_text(encoding="utf-8"))
    items = ds["items"] if isinstance(ds, dict) else ds
    return {i["id"]: i for i in items}


def cmd_split(a):
    (RUNS / a.new).mkdir(parents=True, exist_ok=True)
    rows = [{"id": id, "prompt": split_prompt(it["question"], it.get("priorQuestion")), "passages": 0}
            for id, it in base_items(a.base).items()]
    answerers.write_jsonl(RUNS / a.new / "split-prompts.jsonl", rows)
    print("%d split prompts -> runs/%s/split-prompts.jsonl" % (len(rows), a.new))
    return 0


def parts_of(new):
    """{id: [sub-questions]} for every question the model split into two or more."""
    out = {}
    for id, r in replies(new, "split").items():
        qs = parse_split(r.get("reply"))
        if len(qs) >= 2:
            out[id] = qs
    return out


def cmd_hop(a):
    state = load_state(a.new)
    parts = parts_of(a.new)
    # Read back the previous hop's replies.
    for stage in ("hop%d" % n for n in range(2, a.round)):
        for key, r in replies(a.new, stage).items():
            s = state[key]
            reply = r.get("reply") or ""
            s["answer"] = None if r.get("outcome") == "declined" or DECLINE in reply else reply
    todo = []
    for id, qs in parts.items():
        answers = {}
        for n, q in enumerate(qs, 1):
            s = state.get("%s#%d" % (id, n))
            if s is not None and "answer" in s:
                answers[n] = s["answer"]
        for n, q in enumerate(qs, 1):
            key = "%s#%d" % (id, n)
            if key in state:
                continue
            filled = fill(q, answers)
            if filled is None and refs(q) and all(m in answers for m in refs(q)):
                state[key] = {"question": q, "filled": None, "passages": [], "answer": None}  # an earlier part was declined
            elif filled is not None:
                todo.append((key, filled))
    hits = dense_search([q for _, q in todo]) if todo else []
    rows = []
    for (key, q), h in zip(todo, hits):
        ps = pick(h)
        state[key] = {"question": q, "filled": q, "passages": ps}
        if ps:
            rows.append({"id": key, "prompt": answer_prompt(q, ps), "passages": len(ps)})
        else:
            state[key]["answer"] = None
    save_state(a.new, state)
    answerers.write_jsonl(RUNS / a.new / ("hop%d-prompts.jsonl" % a.round), rows)
    print("%d parts searched, %d to answer -> runs/%s/hop%d-prompts.jsonl" % (len(todo), len(rows), a.new, a.round))
    return 0


def combined(item, qs, state):
    """The combined prompt's passages and notes: every answered part's passages (renumbered) and its answer."""
    passages, notes, index = [], [], {}
    for n in range(1, len(qs) + 1):
        s = state.get("%s#%d" % (item["id"], n)) or {}
        if not s.get("answer"):
            continue
        mapping = {}
        for i, p in enumerate(s["passages"], 1):
            k = merge.text_key(p)
            if k not in index and len(passages) < COMBINED_PASSAGES:
                passages.append(p)
                index[k] = len(passages)
            if k in index:
                mapping[i] = index[k]
        notes.append((s["filled"], renumber(s["answer"], mapping)))
    return passages, notes


def cmd_combine(a):
    state = load_state(a.new)
    for stage in ("hop2", "hop3", "hop4"):
        for key, r in replies(a.new, stage).items():
            reply = r.get("reply") or ""
            state[key]["answer"] = None if r.get("outcome") == "declined" or DECLINE in reply else reply
    save_state(a.new, state)
    items, rows = base_items(a.base), []
    for id, qs in parts_of(a.new).items():
        passages, notes = combined(items[id], qs, state)
        if passages:
            rows.append({"id": id, "prompt": answer_prompt(items[id]["question"], passages, notes), "passages": len(passages)})
    answerers.write_jsonl(RUNS / a.new / "combine-prompts.jsonl", rows)
    print("%d combined prompts -> runs/%s/combine-prompts.jsonl" % (len(rows), a.new))
    return 0


def cmd_build(a):
    state, items = load_state(a.new), base_items(a.base)
    parts, final = parts_of(a.new), replies(a.new, "combine")
    out = []
    for rec in answerers.read_jsonl(RUNS / a.base / "results.jsonl"):
        rec = {k: v for k, v in rec.items() if k not in ("deeper", "guesses", "prompts")}
        rec["runId"] = a.new
        if rec["id"] in parts:
            passages, _ = combined(items[rec["id"]], parts[rec["id"]], state)
            r = final.get(rec["id"])
            rec.update(outcome=r["outcome"] if r else "declined", reply=(r or {}).get("reply", ""),
                       cited="".join("[%d]" % n for n in (r or {}).get("cited", [])), chosen=passages,
                       multistep={"parts": parts[rec["id"]]})
        out.append(rec)
    shutil.copy(RUNS / a.base / "dataset.json", RUNS / a.new / "dataset.json")
    answerers.write_jsonl(RUNS / a.new / "results.jsonl", out)
    print("%d records (%d answered as parts) -> runs/%s/results.jsonl" % (len(out), len(parts), a.new))
    return 0


def cmd_replay(a, runner=None, sleep=None):
    """Replays NEW/STAGE-prompts.jsonl on the phone as answerers.py run does, into NEW/STAGE-replies.jsonl."""
    import adb
    runner = runner or adb.real_runner
    sleep = sleep or adb.time.sleep
    local = RUNS / a.new / ("%s-prompts.jsonl" % a.stage)
    run_id = "%s-%s" % (a.new, a.stage)
    adb.check_front(runner)
    runner(["shell", "rm", "-f", "%s/%s/done" % (adb.REMOTE, run_id), "%s/%s/failed" % (adb.REMOTE, run_id)])
    rc, o = runner(["push", str(local), adb.REMOTE + "/r0-prompts.jsonl"])
    if rc:
        raise SystemExit("adb push failed: " + o)
    rc, o = runner(["shell", "am", "start", "-S", "-n", adb.PKG + "/.eval.EvalActivity", "--es", "runId", run_id,
                    "--es", "replay", "r0-prompts.jsonl", "--es", "model", a.model, "--es", "backend", a.backend])
    if rc:
        raise SystemExit("could not start the eval activity: " + o)
    adb.wait_done(runner, run_id, sleep)
    rc, o = runner(["pull", "%s/%s/replay.jsonl" % (adb.REMOTE, run_id), str(RUNS / a.new / ("%s-replies.jsonl" % a.stage))])
    if rc:
        raise SystemExit("adb pull failed: " + o)
    print("%d replies -> runs/%s/%s-replies.jsonl" % (len(read(RUNS / a.new / ("%s-replies.jsonl" % a.stage))), a.new, a.stage))
    return 0


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    s = p.add_subparsers(dest="cmd", required=True)
    for name, f in (("split", cmd_split), ("combine", cmd_combine), ("build", cmd_build)):
        c = s.add_parser(name); c.add_argument("base"); c.add_argument("new"); c.set_defaults(f=f)
    c = s.add_parser("hop"); c.add_argument("base"); c.add_argument("new"); c.add_argument("--round", type=int, required=True)
    c.set_defaults(f=cmd_hop)
    c = s.add_parser("replay"); c.add_argument("new"); c.add_argument("stage"); c.add_argument("--model", required=True)
    c.add_argument("--backend", default="gpu"); c.set_defaults(f=cmd_replay)
    a = p.parse_args(argv)
    return a.f(a)


if __name__ == "__main__":
    sys.exit(main())
