#!/usr/bin/env python3
"""Plan S1, Task 1: the Point step, offline. E2B reads one passage at a time and names the sentence that answers.

  point.py build BASE NEW [--pool 4|8] [--shape header|bare]  # runs/NEW: one Point prompt per (item, passage)
  point.py run NEW --model FILE [--backend cpu|gpu]             # replays runs/NEW/prompts.jsonl, pulls replay.jsonl
  point.py score NEW [NEW ...]                                  # no graders: hit / wrong / none, declines, timings
  point.py choose NEW CHOOSE --upto 4|8                         # runs/CHOOSE: one prompt per item listing the pointed
                                                                #   sentences; E2B names one or 0
  point.py score-choose CHOOSE [...]                            # no graders, as score
  point.py replies NEW OUT [--choose CHOOSE]                    # runs/OUT: S-quote replies (code only), for graders
  point.py say NEW SAY                                          # runs/SAY: a Say prompt per item that pointed
  point.py run SAY --model FILE --max-tokens 64                 #   replays them
  point.py replies NEW OUT --say SAY                            # runs/OUT: S-say replies, quoted where the check fails

Point follows Neal's rules for small models (2026-11-07). The input is the question and **one** passage, split into
numbered sentences. The operation is to name the sentence that answers. The output is `0` or one or two sentence numbers
(`3`, `3,4`), and code checks it: anything else counts as `0`.

The pool:
- **4:** the passages of BASE's recorded answer prompt, cut exactly as the phone saw them.
- **8:** those 4, then the ranker's next 4 (`merge.PICK` at small = large = 24, from BASE's recorded hits), cut the same.

Gold (no graders): a sentence is gold when it overlaps one of the item's answer quotes, matched on folded words
(`metrics.fold_words`). A quote can span sentences. The dataset's quotes see fewer answers than graders do, so the score
is a lower-bound proxy for comparing variants, not a verdict.

Sentences split per line (a list or infobox line stays its own sentence, as `CitedQuote.sentences` breaks paragraphs),
then on `passages.SENTENCE` within a line. The phone breaks with Java's BreakIterator, so its numbering can differ a little.
"""
import argparse
import json
import re
import sys
from dataclasses import replace
from pathlib import Path

import adb
import answerers
import merge
import metrics
import passages
import replay

HERE = Path(__file__).resolve().parent
RUNS = HERE / "runs"
POINT_TOKENS = 8
TEXT_CHARS = 1200  # AnswerPrompt.TEXT_CHARS
MAX_HEADER = 240  # AnswerPrompt.MAX_HEADER
REPLY = re.compile(r"^\s*(\d+)(?:\s*,\s*(\d+))?\s*\.?\s*$")


# --- sentences, prompt, reply ------------------------------------------------------------------------------------------

def split_sentences(text):
    """The passage's sentences, line by line; blank and punctuation-only pieces dropped."""
    out = []
    for line in (text or "").split("\n"):
        for s in passages.SENTENCE.split(line):
            s = s.strip()
            if re.search(r"\w", s):
                out.append(s)
    return out


INSTRUCTION = (
    "Find the sentence in the passage that answers the question.\n"
    "Reply with only that sentence's number. If the answer needs two sentences, reply with both numbers, like 3,4.\n"
    "If no sentence answers the question, reply 0."
)


def point_prompt(question, header, sentences, prior_question=None, shape="header"):
    lines = [INSTRUCTION, ""]
    lines.append("Passage: %s" % header if shape == "header" else "Passage:")
    lines += ["%d. %s" % (n, s) for n, s in enumerate(sentences, 1)]
    lines.append("")
    if prior_question:
        lines.append("Earlier question: %s" % prior_question)
    lines.append("Question: %s" % question)
    lines.append("Sentence number:")
    return "\n".join(lines)


def parse(reply, n):
    """(numbers, valid): the sentence numbers named, in range, or ([], False) when the reply isn't the format.
    `0` is valid and names none."""
    text = (reply or "").strip().splitlines()[0] if (reply or "").strip() else ""
    m = REPLY.match(text)
    if not m:
        return [], False
    nums = [int(g) for g in m.groups() if g is not None]
    if nums == [0]:
        return [], True
    if any(k < 1 or k > n for k in nums):
        return [], False
    return sorted(set(nums)), True


def gold(sentences, answers):
    """1-based indices of the sentences an answer quote overlaps."""
    folded = [metrics.fold_words(s).strip() for s in sentences]
    starts, at = [], 0
    for f in folded:
        starts.append(at)
        at += len(f) + 1
    joined = " ".join(folded)
    out = set()
    for a in answers or []:
        q = metrics.fold_words(a.get("quote") or "").strip()
        if not q:
            continue
        i = joined.find(q)
        while i >= 0:
            end = i + len(q)
            for k, (s, f) in enumerate(zip(starts, folded), 1):
                if s < end and i < s + len(f):
                    out.add(k)
            i = joined.find(q, i + 1)
    return out


# --- the pool ------------------------------------------------------------------------------------------------------

def cut(text, max_chars):
    """AnswerPrompt.cut: at most max_chars at a word, with an ellipsis."""
    if len(text) <= max_chars:
        return text
    head = text[:max_chars]
    space = max((i for i, c in enumerate(head) if c.isspace()), default=0)
    return (head[:space] if space > 0 else head).rstrip() + "…"


def header_of(p):
    """AnswerPrompt.header for a recorded hit (found.jsonl keeps no author, date or collection)."""
    h = p.get("title") or ""
    if p.get("heading") and p["heading"] != p.get("title"):
        h += " · " + p["heading"]
    return cut(h, MAX_HEADER)


RANKER = merge.gated(merge.hybrid(0.1, 0.74), 0.78, 1.0)


def extras(item, corpus, hits, chosen, k):
    """The ranker's passages after the app's chosen ones, up to k of them."""
    for p in hits:
        p["cosine"] = float(p["cosine"])
    cfg = replace(replay.Config(), small=24, large=24)
    ranked = RANKER(item["question"], corpus, cfg, hits)
    have = {merge.text_key(p) for p in chosen}
    out = [p for p in ranked if merge.text_key(p) not in have]
    return out[:k]


def pool(rec, item, corpus, hits, size):
    """[(header, body, passage)] for an item: the answer prompt's passages, then the ranker's next ones up to size.
    `passage` is the record a worksheet shows (source, title, location, heading, text)."""
    prompt = answerers.answer_prompt(rec)
    _, ps, _ = passages.split_prompt(prompt)
    chosen = rec.get("chosen") or []
    keys = ("source", "title", "location", "heading")
    out = []
    for i, (h, b) in enumerate(ps[:size]):
        c = chosen[i] if i < len(chosen) else {"title": h}
        out.append((h, b, {**{k: c.get(k) for k in keys}, "text": b}))
    if size > len(out) and corpus is not None:
        for p in extras(item, corpus, hits, chosen, size - len(out)):
            body = cut(p.get("text") or "", TEXT_CHARS)
            out.append((header_of(p), body, {**{k: p.get(k) for k in keys}, "text": body}))
    return out


# --- commands ------------------------------------------------------------------------------------------------------

def build(base, new, size=4, shape="header"):
    d, items, corpus, records = replay.load(base)
    dense = {id: c.get("dense", []) for id, c in corpus.items()}
    rows, meta = [], {}
    for id, rec in records.items():
        item = items[id]
        if answerers.answer_prompt(rec) is None:
            meta[id] = {"passages": [], "baseOutcome": rec.get("outcome")}
            continue
        entry = []
        for rank, (header, body, passage) in enumerate(pool(rec, item, corpus.get(id), dense.get(id, []), size), 1):
            sents = split_sentences(body)
            if not sents:
                continue
            rid = "%s#%d" % (id, rank)
            rows.append({"id": rid, "prompt": point_prompt(item["question"], header, sents, item.get("priorQuestion"), shape), "passages": 0})
            entry.append({"rank": rank, "rowId": rid, "header": header, "sentences": sents, "passage": passage,
                          "gold": sorted(gold(sents, item.get("answers") if item["answerable"] else []))})
        meta[id] = {"passages": entry, "baseOutcome": rec.get("outcome")}
    out = RUNS / new
    out.mkdir(parents=True, exist_ok=True)
    answerers.write_jsonl(out / "prompts.jsonl", rows)
    (out / "point.json").write_text(json.dumps({"from": base, "pool": size, "shape": shape, "items": meta}, ensure_ascii=False), encoding="utf-8")
    (out / "dataset.json").write_text((d / "dataset.json").read_text(encoding="utf-8"), encoding="utf-8")
    return rows, meta


def cmd_build(a):
    rows, meta = build(a.base, a.new, a.pool, a.shape)
    golds = sum(any(p["gold"] for p in m["passages"]) for m in meta.values())
    print("%d prompts for %d items (%d with a gold sentence in the pool) -> runs/%s" % (len(rows), len(meta), golds, a.new))
    return 0


def cmd_run(a, runner=adb.real_runner, sleep=adb.time.sleep):
    local = RUNS / a.new / "prompts.jsonl"
    adb.check_front(runner)
    remote = "%s/%s-prompts.jsonl" % (adb.REMOTE, a.new)
    runner(["shell", "rm", "-f", "%s/%s/done" % (adb.REMOTE, a.new), "%s/%s/failed" % (adb.REMOTE, a.new)])
    rc, o = runner(["push", str(local), remote])
    if rc:
        raise SystemExit("adb push failed: " + o)
    rc, o = runner(["shell", "am", "start", "-S", "-n", adb.PKG + "/.eval.EvalActivity", "--es", "runId", a.new,
                    "--es", "replay", "%s-prompts.jsonl" % a.new, "--es", "model", a.model, "--es", "backend", a.backend,
                    "--ei", "maxTokens", str(getattr(a, "max_tokens", POINT_TOKENS))])
    if rc:
        raise SystemExit("could not start the eval activity: " + o)
    adb.wait_done(runner, a.new, sleep)
    rc, o = runner(["pull", "%s/%s/replay.jsonl" % (adb.REMOTE, a.new), str(RUNS / a.new) + "/"])
    if rc:
        raise SystemExit("adb pull failed: " + o)
    print("replies -> runs/%s/replay.jsonl" % a.new)
    return 0


def picks(meta_item, replies, upto=None):
    """[(rank, [sentence numbers])] for the passages that pointed, in rank order; invalid replies counted apart."""
    out, invalid = [], 0
    for p in meta_item["passages"]:
        if upto is not None and p["rank"] > upto:
            break
        r = replies.get(p["rowId"])
        if r is None:
            continue
        nums, ok = parse(r.get("reply"), len(p["sentences"]))
        invalid += not ok
        if nums:
            out.append((p["rank"], nums))
    return out, invalid


def judge(item, meta_item, chosen):
    """One item's bucket from the picks kept: answerable → hit / wrong / none (and whether gold was in reach);
    unanswerable → declined / pointed."""
    by_rank = {p["rank"]: p for p in meta_item["passages"]}
    if not item["answerable"]:
        return "declined" if not chosen else "pointed"
    if not chosen:
        return "none"
    if any(set(nums) & set(by_rank[rank]["gold"]) for rank, nums in chosen):
        return "hit"
    return "wrong"


def score(new, upto=None):
    pt = json.loads((RUNS / new / "point.json").read_text(encoding="utf-8"))
    items = {i["id"]: i for i in json.loads((RUNS / new / "dataset.json").read_text(encoding="utf-8"))}
    replies = {r["id"]: r for r in answerers.read_jsonl(RUNS / new / "replay.jsonl")}
    tally = {"all": {}, "first": {}}
    invalid = calls = 0
    gold_reach = 0
    ms, ms_first, prefill = [], [], []
    for id, m in pt["items"].items():
        item = items[id]
        ps = [p for p in m["passages"] if upto is None or p["rank"] <= upto]
        if item["answerable"] and any(p["gold"] for p in ps):
            gold_reach += 1
        chosen, bad = picks(m, replies, upto)
        invalid += bad
        calls += sum(p["rowId"] in replies for p in ps)
        for mode, kept in (("all", chosen), ("first", chosen[:1])):
            b = judge(item, m, kept)
            tally[mode][b] = tally[mode].get(b, 0) + 1
        # time for an item: every call (all), or calls up to the first pointing passage (first)
        t = [(p["rank"], replies[p["rowId"]].get("totalMs", 0)) for p in ps if p["rowId"] in replies]
        if t:
            ms.append(sum(x for _, x in t))
            stop = chosen[0][0] if chosen else None
            ms_first.append(sum(x for r, x in t if stop is None or r <= stop))
        prefill += [replies[p["rowId"]].get("prefillTokens") for p in ps if replies.get(p["rowId"], {}).get("prefillTokens")]
    answerable = sum(1 for i in items.values() if i["answerable"])
    return {"run": new, "pool": upto or pt["pool"], "calls": calls, "invalid": invalid, "goldInReach": gold_reach,
            "answerable": answerable, "unanswerable": len(items) - answerable, "all": tally["all"], "first": tally["first"],
            "itemMsMedian": metrics.median(ms), "itemMsMedianFirst": metrics.median(ms_first), "prefillMedian": metrics.median(prefill)}


def cmd_score(a):
    for new in a.runs:
        pt = json.loads((RUNS / new / "point.json").read_text(encoding="utf-8"))
        for upto in sorted({4, pt["pool"]}):
            if upto <= pt["pool"]:
                print(json.dumps(score(new, upto)))
    return 0


# --- full replies (Task 3) -----------------------------------------------------------------------------------------

DECLINE = "NOT IN LIBRARY"
SAY_TOKENS = 64
SAY_INSTRUCTION = "Answer the question in one short sentence. Use only facts from the sentences below and add nothing else."
WORD = re.compile(r"\w+")


def title_of(p):
    return (p["passage"].get("title") or p["header"]).strip()


def quote_reply(p, nums):
    """S-quote: the pointed sentences, quoted under the passage's title, cited."""
    return '%s: "%s" [1]' % (title_of(p), " ".join(p["sentences"][n - 1] for n in nums))


def say_prompt(question, title, sentences, prior_question=None):
    lines = [SAY_INSTRUCTION, "", 'From "%s":' % title] + list(sentences) + [""]
    if prior_question:
        lines.append("Earlier question: %s" % prior_question)
    lines += ["Question: %s" % question, "Answer:"]
    return "\n".join(lines)


def content(text):
    return {w for w in WORD.findall(metrics.fold_words(text)) if len(w) >= 3 and w not in replay.STOPWORDS}


def say_check(reply, question, title, sentences):
    """(ok, new words): every content word of the reply is in the sentences, the question or the title. A word counts as
    there when it shares its first five letters with one that is (swallowed / swallow)."""
    text = (reply or "").strip()
    if not text or DECLINE.lower() in text.lower():
        return False, []
    allowed = content(" ".join(sentences)) | content(question) | content(title)
    stems = {w[:5] for w in allowed if len(w) >= 5}
    new = sorted(w for w in content(text) - allowed if not (len(w) >= 5 and w[:5] in stems))
    return not new, new


CHOOSE_INSTRUCTION = "Which sentence answers the question? Reply with only its number. If none of them answers it, reply 0."


def choose_prompt(question, candidates, prior_question=None):
    """candidates: [(title, text)] in rank order."""
    lines = [CHOOSE_INSTRUCTION, ""] + ["%d. (%s) %s" % (n, t, s) for n, (t, s) in enumerate(candidates, 1)] + [""]
    if prior_question:
        lines.append("Earlier question: %s" % prior_question)
    lines += ["Question: %s" % question, "Sentence number:"]
    return "\n".join(lines)


def candidates_of(m, replies, upto):
    """[(passage meta, sentence numbers)]: every passage that pointed, in rank order."""
    chosen, _ = picks(m, replies, upto)
    by_rank = {p["rank"]: p for p in m["passages"]}
    return [(by_rank[r], nums) for r, nums in chosen]


def cmd_choose(a):
    pt = json.loads((RUNS / a.new / "point.json").read_text(encoding="utf-8"))
    items = {i["id"]: i for i in json.loads((RUNS / a.new / "dataset.json").read_text(encoding="utf-8"))}
    replies = {r["id"]: r for r in answerers.read_jsonl(RUNS / a.new / "replay.jsonl")}
    rows, meta = [], {}
    for id, m in pt["items"].items():
        cands = candidates_of(m, replies, a.upto)
        meta[id] = [{"rank": p["rank"], "sentences": nums} for p, nums in cands]
        if cands:
            it = items[id]
            text = [(title_of(p), " ".join(p["sentences"][n - 1] for n in nums)) for p, nums in cands]
            rows.append({"id": id, "prompt": choose_prompt(it["question"], text, it.get("priorQuestion")), "passages": 0})
    out = RUNS / a.choose
    out.mkdir(parents=True, exist_ok=True)
    answerers.write_jsonl(out / "prompts.jsonl", rows)
    (out / "choose.json").write_text(json.dumps({"from": a.new, "upto": a.upto, "items": meta}), encoding="utf-8")
    print("%d Choose prompts -> runs/%s/prompts.jsonl" % (len(rows), a.choose))
    return 0


def chosen_picks(new, choose):
    """{item id: (passage meta, sentence numbers)} as Choose named them (none when it replied 0 or off format)."""
    pt = json.loads((RUNS / new / "point.json").read_text(encoding="utf-8"))
    ch = json.loads((RUNS / choose / "choose.json").read_text(encoding="utf-8"))
    replies = {r["id"]: r for r in answerers.read_jsonl(RUNS / choose / "replay.jsonl")}
    out = {}
    for id, cands in ch["items"].items():
        if not cands or id not in replies:
            continue
        nums, ok = parse(replies[id].get("reply"), len(cands))
        if ok and len(nums) == 1:
            c = cands[nums[0] - 1]
            out[id] = (next(p for p in pt["items"][id]["passages"] if p["rank"] == c["rank"]), c["sentences"])
    return pt, out


def cmd_score_choose(a):
    for choose in a.runs:
        ch = json.loads((RUNS / choose / "choose.json").read_text(encoding="utf-8"))
        pt, got = chosen_picks(ch["from"], choose)
        items = {i["id"]: i for i in json.loads((RUNS / ch["from"] / "dataset.json").read_text(encoding="utf-8"))}
        replies = {r["id"]: r for r in answerers.read_jsonl(RUNS / choose / "replay.jsonl")}
        t = {}
        for id, it in items.items():
            pick = got.get(id)
            if not it["answerable"]:
                b = "declined" if pick is None else "pointed"
            elif pick is None:
                b = "none"
            else:
                b = "hit" if set(pick[1]) & set(pick[0]["gold"]) else "wrong"
            t[b] = t.get(b, 0) + 1
        ms = [r.get("totalMs", 0) for r in replies.values()]
        print(json.dumps({"run": choose, "upto": ch["upto"], **t, "chooseMsMedian": metrics.median(ms),
                          "prefillMedian": metrics.median([r.get("prefillTokens") for r in replies.values() if r.get("prefillTokens")])}))
    return 0


def first_picks(new):
    """{item id: (passage meta, sentence numbers)} for every item where some passage pointed, the first in rank order."""
    pt = json.loads((RUNS / new / "point.json").read_text(encoding="utf-8"))
    replies = {r["id"]: r for r in answerers.read_jsonl(RUNS / new / "replay.jsonl")}
    out = {}
    for id, m in pt["items"].items():
        chosen, _ = picks(m, replies)
        if chosen:
            rank, nums = chosen[0]
            out[id] = (next(p for p in m["passages"] if p["rank"] == rank), nums)
    return pt, out


def picks_for(a):
    return chosen_picks(a.new, a.choose) if getattr(a, "choose", None) else first_picks(a.new)


def cmd_say(a):
    pt, first = picks_for(a)
    items = {i["id"]: i for i in json.loads((RUNS / a.new / "dataset.json").read_text(encoding="utf-8"))}
    rows = []
    for id, (p, nums) in first.items():
        it = items[id]
        rows.append({"id": id, "prompt": say_prompt(it["question"], title_of(p), [p["sentences"][n - 1] for n in nums], it.get("priorQuestion")), "passages": 0})
    out = RUNS / a.say
    out.mkdir(parents=True, exist_ok=True)
    answerers.write_jsonl(out / "prompts.jsonl", rows)
    print("%d Say prompts -> runs/%s/prompts.jsonl" % (len(rows), a.say))
    return 0


def cmd_replies(a):
    """runs/OUT/results.jsonl: BASE's records with S1's replies, for `eval.py worksheet` and the graders. The pick is
    Choose's (--choose) or else the first passage that pointed."""
    pt, first = picks_for(a)
    items = {i["id"]: i for i in json.loads((RUNS / a.new / "dataset.json").read_text(encoding="utf-8"))}
    said = {r["id"]: r for r in answerers.read_jsonl(RUNS / a.say / "replay.jsonl")} if a.say else {}
    rows, fallbacks = [], 0
    for rec in answerers.read_jsonl(RUNS / pt["from"] / "results.jsonl"):
        rec = {k: v for k, v in rec.items() if k not in ("deeper", "guesses")}
        rec["runId"] = a.out
        if pt["items"][rec["id"]]["passages"]:
            if rec["id"] not in first:
                rec.update(outcome="declined", reply=DECLINE, cited="")
            else:
                p, nums = first[rec["id"]]
                reply = quote_reply(p, nums)
                s = said.get(rec["id"])
                if a.say:
                    ok, _ = say_check(s and s.get("reply"), items[rec["id"]]["question"], title_of(p), [p["sentences"][n - 1] for n in nums])
                    if ok:
                        reply = s["reply"].strip()
                        reply = reply if "[1]" in reply else reply + " [1]"
                    else:
                        fallbacks += 1
                rec.update(outcome="answered", reply=reply, cited="[1]", chosen=[p["passage"]],
                           point={"rank": p["rank"], "sentences": nums})
        rows.append(rec)
    out = RUNS / a.out
    out.mkdir(parents=True, exist_ok=True)
    answerers.write_jsonl(out / "results.jsonl", rows)
    (out / "dataset.json").write_text((RUNS / a.new / "dataset.json").read_text(encoding="utf-8"), encoding="utf-8")
    print("%d records -> runs/%s/results.jsonl (%d Say replies failed the check, quoted instead)" % (len(rows), a.out, fallbacks))
    return 0


# --- one passage, today's answer prompt ---------------------------------------------------------------------------

def cmd_one(a):
    """runs/ONE/prompts.jsonl: BASE's answer prompt for each item Choose picked, with only the picked passage."""
    pt, got = chosen_picks(a.new, a.choose)
    recs = {r["id"]: r for r in answerers.read_jsonl(RUNS / pt["from"] / "results.jsonl")}
    rows = []
    for id, (p, _) in got.items():
        head, ps, tail = passages.split_prompt(answerers.answer_prompt(recs[id]))
        rows.append({"id": id, "prompt": passages.join_prompt(head, [ps[p["rank"] - 1]], tail), "passages": 1})
    out = RUNS / a.one
    out.mkdir(parents=True, exist_ok=True)
    answerers.write_jsonl(out / "prompts.jsonl", rows)
    print("%d one-passage prompts -> runs/%s/prompts.jsonl" % (len(rows), a.one))
    return 0


def cmd_one_replies(a):
    """runs/OUT/results.jsonl from ONE's replay: E2B's reply over the one passage; no pick means a decline."""
    pt, got = chosen_picks(a.new, a.choose)
    said = {r["id"]: r for r in answerers.read_jsonl(RUNS / a.one / "replay.jsonl")}
    rows = []
    for rec in answerers.read_jsonl(RUNS / pt["from"] / "results.jsonl"):
        rec = {k: v for k, v in rec.items() if k not in ("deeper", "guesses")}
        rec["runId"] = a.out
        if pt["items"][rec["id"]]["passages"]:
            if rec["id"] not in got:
                rec.update(outcome="declined", reply=DECLINE, cited="")
            else:
                p, _ = got[rec["id"]]
                r = said[rec["id"]]
                rec.update(outcome=r["outcome"], reply=r["reply"], cited="".join("[%d]" % n for n in r["cited"]),
                           chosen=[p["passage"]], point={"rank": p["rank"]})
        rows.append(rec)
    out = RUNS / a.out
    out.mkdir(parents=True, exist_ok=True)
    answerers.write_jsonl(out / "results.jsonl", rows)
    (out / "dataset.json").write_text((RUNS / a.new / "dataset.json").read_text(encoding="utf-8"), encoding="utf-8")
    print("%d records -> runs/%s/results.jsonl" % (len(rows), a.out))
    return 0


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    s = p.add_subparsers(dest="cmd", required=True)
    b = s.add_parser("build"); b.add_argument("base"); b.add_argument("new")
    b.add_argument("--pool", type=int, default=4, choices=[4, 8]); b.add_argument("--shape", default="header", choices=["header", "bare"])
    b.set_defaults(f=cmd_build)
    r = s.add_parser("run"); r.add_argument("new"); r.add_argument("--model", required=True)
    r.add_argument("--backend", default="cpu", choices=["gpu", "cpu"])
    r.add_argument("--max-tokens", type=int, default=POINT_TOKENS, help="%d for Point, %d for Say" % (POINT_TOKENS, SAY_TOKENS))
    r.set_defaults(f=cmd_run)
    c = s.add_parser("score"); c.add_argument("runs", nargs="+"); c.set_defaults(f=cmd_score)
    h = s.add_parser("choose"); h.add_argument("new"); h.add_argument("choose"); h.add_argument("--upto", type=int, default=4)
    h.set_defaults(f=cmd_choose)
    sc = s.add_parser("score-choose"); sc.add_argument("runs", nargs="+"); sc.set_defaults(f=cmd_score_choose)
    y = s.add_parser("say"); y.add_argument("new"); y.add_argument("say"); y.add_argument("--choose"); y.set_defaults(f=cmd_say)
    e = s.add_parser("one"); e.add_argument("new"); e.add_argument("one"); e.add_argument("--choose", required=True)
    e.set_defaults(f=cmd_one)
    er = s.add_parser("one-replies"); er.add_argument("new"); er.add_argument("one"); er.add_argument("out")
    er.add_argument("--choose", required=True); er.set_defaults(f=cmd_one_replies)
    o = s.add_parser("replies"); o.add_argument("new"); o.add_argument("out"); o.add_argument("--say"); o.add_argument("--choose"); o.set_defaults(f=cmd_replies)
    a = p.parse_args(argv)
    return a.f(a)


if __name__ == "__main__":
    sys.exit(main())
