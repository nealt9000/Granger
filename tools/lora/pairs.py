#!/usr/bin/env python3
"""Training pairs for the Ask behaviour LoRA (Plan R2, 2026-11-04): (answer prompt -> cited answer | NOT IN LIBRARY).

Sources, tuning items only (dev and held-out stay out; checked by id prefix and by question text):
  recorded   the tuning runs' answer prompts with E2B's own reply where a grader called it grounded, and the prompts of
             unanswerable questions with the decline token as the target
  swapped    an answerable prompt with another question's passages (a different group, none of its answer quotes present)
             -> the decline token: declining only when the passages truly lack it
  worksheet  prompts that need an authored answer: the answer's quote is in the prompt and E2B misread or declined, and
             synthetic prompts where the work's own chunk (bookchunks.py) sits among encyclopedia passages

  pairs.py build      -> data/recorded.jsonl, data/swapped.jsonl, data/worksheet.jsonl (to author into data/authored.jsonl)
  pairs.py train      -> data/train.jsonl from the three plus authored answers; prints the mix
"""
import collections
import hashlib
import json
import random
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import bookchunks  # noqa: E402

HERE = Path(__file__).resolve().parent
EVAL = HERE.parent / "eval"
DATA = HERE / "data"
RUNS = ("emu-t10-cut-1", "r2-t10-cut", "emu-t10-p6", "r2-t10-all", "r1-t10-check", "article-cut-r2-t10-all")
BASE_RUN = "emu-t10-cut-1"
TUNE = ("tune.json", "tune-dates.json")
BLIND = ("dev.json", "dev-own.json", "heldout.json", "heldout2.json", "heldout2-own.json", "heldout-es.json")
DECLINE = "NOT IN LIBRARY"
SEED = 11
# Swapped-passage declines in the training set: enough to teach the condition, not enough to teach declining.
SWAPPED_CAP = 100


def read_jsonl(p):
    return [json.loads(l) for l in Path(p).read_text(encoding="utf-8").splitlines() if l.strip()]


def write_jsonl(p, rows):
    Path(p).write_text("".join(json.dumps(r, ensure_ascii=False) + "\n" for r in rows), encoding="utf-8")


def norm_q(q):
    return re.sub(r"[^a-z0-9áéíóúñü]+", " ", q.lower()).strip()


def tuning_items():
    """Tuning items by id, minus anything that is also a blind question."""
    blind = {norm_q(d["question"]) for f in BLIND for d in json.load(open(EVAL / "dataset" / f))}
    items = {}
    for f in TUNE:
        for d in json.load(open(EVAL / "dataset" / f)):
            assert not d["id"].startswith(("d-", "h-", "h2-")), d["id"]
            if norm_q(d["question"]) in blind:
                continue
            items.setdefault(d["id"], d)
    return items


def grades():
    out = {}
    for g in read_jsonl(EVAL / "grades" / "grades.jsonl"):
        if g["kind"] == "answer":
            out[(g["id"], g["replyHash"])] = g["grade"]
    return out


def sha(s):
    return hashlib.sha1((s or "").encode("utf-8")).hexdigest()


def records(items):
    """(run, record) for tuning items, in RUNS order (the base run first)."""
    out = []
    for run in RUNS:
        p = EVAL / "runs" / run / "results.jsonl"
        if p.exists():
            out += [(run, r) for r in read_jsonl(p) if r["id"] in items]
    return out


def answer_prompt(rec):
    ps = [p for p in rec.get("prompts") or [] if p["stage"] == "answer"]
    return ps[-1]["prompt"] if ps else None


PROMPT = re.compile(r"^(?P<head>.*?\nPassages:\n)(?P<middle>.*)\n\nQuestion: (?P<question>.*)\nAnswer:$", re.S)


def split_prompt(prompt):
    m = PROMPT.match(prompt)
    if not m:
        return None
    head, middle, q = m.group("head"), m.group("middle"), m.group("question")
    paras = middle.split("\n\n")
    passages = [p for p in paras if re.match(r"^\[\d+\] ", p)]
    extra = [p for p in paras if not re.match(r"^\[\d+\] ", p)]
    return {"head": head, "passages": passages, "extra": extra, "question": q}


def render(head, passages, question, extra=()):
    body = "\n\n".join(re.sub(r"^\[\d+\] ", "[%d] " % (i + 1), p) for i, p in enumerate(passages))
    tail = "".join(e + "\n\n" for e in extra)
    return f"{head}{body}\n\n{tail}Question: {question}\nAnswer:"


def quote_in(d, text):
    t = norm_q(text)
    return any(norm_q(a["quote"]) in t for a in d["answers"] if a.get("quote"))


def book_headers(all_runs_dir):
    """Book source -> the header line the app renders for it (the most common across every recorded prompt)."""
    heads = collections.defaultdict(collections.Counter)
    for p in all_runs_dir.glob("*/results.jsonl"):
        for r in read_jsonl(p):
            chosen = r.get("chosen") or []
            prompt = answer_prompt(r)
            if not prompt:
                continue
            sp = split_prompt(prompt)
            if not sp:
                continue
            for i, para in enumerate(sp["passages"]):
                if i < len(chosen) and chosen[i].get("kind") == "BOOK":
                    heads[chosen[i]["source"]][re.sub(r"^\[\d+\] ", "", para.split("\n", 1)[0])] += 1
    return {s: c.most_common(1)[0][0] for s, c in heads.items()}


def build():
    DATA.mkdir(exist_ok=True)
    items = tuning_items()
    gr = grades()
    recs = records(items)
    rnd = random.Random(SEED)
    recorded, seen = [], set()
    by_item = collections.defaultdict(list)
    for run, r in recs:
        prompt = answer_prompt(r)
        if not prompt:
            continue
        by_item[r["id"]].append((run, r, prompt))
        d = items[r["id"]]
        reply = r.get("reply") or ""
        if d["answerable"] and r["outcome"] == "answered" and gr.get((r["id"], sha(reply))) == "grounded":
            key = sha(prompt + "\n" + reply)
            if key not in seen:
                seen.add(key)
                recorded.append({"id": r["id"], "run": run, "kind": "recorded-grounded", "prompt": prompt, "target": reply})
        elif not d["answerable"]:
            key = sha(prompt)
            if key not in seen:
                seen.add(key)
                recorded.append({"id": r["id"], "run": run, "kind": "recorded-decline", "prompt": prompt, "target": DECLINE})
    # Swapped passages -> decline. One per grounded prompt, passages from a question of another group.
    grounded = [p for p in recorded if p["kind"] == "recorded-grounded"]
    swapped = []
    for p in grounded:
        sp = split_prompt(p["prompt"])
        if not sp or sp["extra"]:
            continue
        d = items[p["id"]]
        others = [o for o in grounded if items[o["id"]]["group"] != d["group"]]
        rnd.shuffle(others)
        for o in others:
            so = split_prompt(o["prompt"])
            if not so or so["extra"]:
                continue
            block = "\n\n".join(so["passages"])
            if quote_in(d, block):
                continue
            swapped.append({"id": p["id"], "from": o["id"], "kind": "swapped-decline",
                            "prompt": render(sp["head"], so["passages"], sp["question"]), "target": DECLINE})
            break
    # Worksheet: the answer in the prompt, the reply not grounded or a decline (one prompt per item, the base run first).
    worksheet, done = [], set()
    for id_, lst in by_item.items():
        d = items[id_]
        if not d["answerable"]:
            continue
        for run, r, prompt in lst:
            reply = r.get("reply") or ""
            g = gr.get((id_, sha(reply)))
            if quote_in(d, prompt) and (r["outcome"] == "declined" or g == "not-grounded") and id_ not in done:
                done.add(id_)
                worksheet.append({"id": id_, "kind": "misread" if r["outcome"] == "answered" else "wrong-decline", "run": run,
                                  "question": d["question"], "reference": d.get("reference"),
                                  "quotes": [a["quote"] for a in d["answers"] if a.get("quote")], "multiStep": d.get("multiStep", False),
                                  "was": reply, "prompt": prompt})
    # Book prompts: the work's own chunk among the recorded passages of the same question.
    headers = book_headers(EVAL / "runs")
    generic_head = next(split_prompt(p["prompt"])["head"] for p in recorded if split_prompt(p["prompt"]))
    books, missing = [], collections.Counter()
    for id_, d in items.items():
        if not d["answerable"]:
            continue
        for a in d["answers"]:
            if not a["source"].startswith("book:") or not a.get("quote"):
                continue
            found = bookchunks.chunk_with_quote(a["source"], a["location"], a["quote"])
            if not found or not found[2]:
                missing["no chunk" if not found else "quote beyond the cut"] += 1
                continue
            header = headers.get(a["source"])
            if not header:
                missing["no header"] += 1
                continue
            book_para = f"[0] {header}\n{found[0]}"
            lst = by_item.get(id_) or []
            base = next((x for x in lst if x[0] == BASE_RUN), lst[0] if lst else None)
            if base:
                sp = split_prompt(base[2])
                head, passages = sp["head"], [p for p in sp["passages"] if not quote_in(d, p)][:3]
            else:
                head, passages = generic_head, []
            if len(passages) < 2:
                # Not enough recorded company: borrow encyclopedia passages from another question of the same group.
                pool = [p for o in recorded if items[o["id"]]["group"] == d["group"] and o["id"] != id_
                        for p in (split_prompt(o["prompt"]) or {}).get("passages", []) if not quote_in(d, p)]
                rnd.shuffle(pool)
                passages = (passages + pool)[:3]
            if len(passages) < 2:
                missing["no company"] += 1
                continue
            slot = rnd.randrange(len(passages) + 1)
            allp = passages[:slot] + [book_para] + passages[slot:]
            books.append({"id": id_, "kind": "book", "source": a["source"], "slot": slot + 1, "question": d["question"],
                          "reference": d.get("reference"), "quotes": [a["quote"]], "multiStep": d.get("multiStep", False),
                          "prompt": render(head, allp, d["question"])})
            break
    write_jsonl(DATA / "recorded.jsonl", recorded)
    write_jsonl(DATA / "swapped.jsonl", swapped)
    write_jsonl(DATA / "worksheet.jsonl", worksheet + books)
    kinds = collections.Counter(p["kind"] for p in recorded + swapped + worksheet + books)
    print("tuning items", len(items), "| pairs:", dict(kinds), "| book prompts skipped:", dict(missing))
    print("worksheet items to author:", len(worksheet) + len(books), "(misread %d, wrong-decline %d, book %d)" % (
        sum(w["kind"] == "misread" for w in worksheet), sum(w["kind"] == "wrong-decline" for w in worksheet), len(books)))


def train():
    swapped = read_jsonl(DATA / "swapped.jsonl")
    random.Random(SEED).shuffle(swapped)
    rows = read_jsonl(DATA / "recorded.jsonl") + swapped[:SWAPPED_CAP]
    authored = {a["id"] + "|" + a["kind"]: a for a in read_jsonl(DATA / "authored.jsonl")} if (DATA / "authored.jsonl").exists() else {}
    n = 0
    for w in read_jsonl(DATA / "worksheet.jsonl"):
        a = authored.get(w["id"] + "|" + w["kind"])
        if a:
            rows.append({"id": w["id"], "kind": "authored-" + w["kind"], "prompt": w["prompt"], "target": a["answer"]})
            n += 1
    random.Random(SEED).shuffle(rows)
    write_jsonl(DATA / "train.jsonl", rows)
    print(len(rows), "pairs,", n, "authored;", dict(collections.Counter(r["kind"] for r in rows)))


if __name__ == "__main__":
    {"build": build, "train": train}[sys.argv[1]]()
