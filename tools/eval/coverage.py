#!/usr/bin/env python3
"""Do the chosen passages hold the answer? (Plan R5, Task 2; offline.)

  coverage.py RUN [--hand sets/coverage-hand.jsonl] [-v]

For each answerable item of RUN, each of the dataset's answers (source, location, quote) is looked for in the passages
the answer model was given (`chosen`):
- `passage`: the quote, folded as the validator folds it, is in a chosen passage's text;
- `article`: a chosen passage is from the answer's article or chapter, but not the part holding the quote;
- `no`: neither.
A single-fact item's answers are alternatives (any one will do); a multi-step item lists one answer per fact.

Matching is literal, so a passage can hold the answer in other words. A grounded single-fact reply settles it (the rubric:
every claim is supported by a passage it cites), so those count as holding the answer. The hand file corrects the rest:
lines of
`{"id", "run", "facts": ["passage"|"article"|"no", ...], "why"}`, one mark per answer, read after the automatic ones.
The grade comes from grades/grades.jsonl (the in-session grading of RUN's replies).
"""
import argparse
import json
import re
import sys
from collections import Counter
from pathlib import Path

import grading

HERE = Path(__file__).resolve().parent
RUNS = HERE / "runs"
HAND = HERE / "sets" / "coverage-hand.jsonl"
GRADES = HERE / "grades" / "grades.jsonl"


def fold(text):
    """Case, whitespace, curly quotes and dashes folded, as the dataset validator's quote check does."""
    text = (text or "").lower().replace("’", "'").replace("‘", "'").replace("“", '"').replace("”", '"')
    text = re.sub("[‐-―−]", "-", text)
    return re.sub(r"\s+", " ", text).strip()


def location_of(p):
    return (p.get("source") or "", (p.get("location") or "").split("#")[0])


def mark(answer, chosen):
    quote = fold(answer.get("quote"))
    if quote and any(quote in fold(p.get("text")) for p in chosen):
        return "passage"
    where = (answer.get("source") or "", (answer.get("location") or "").split("#")[0])
    return "article" if any(location_of(p) == where for p in chosen) else "no"


def outcome(item, rec, grades):
    """grounded / not-grounded / declined / unverified / ungraded."""
    if rec.get("outcome") != "answered":
        return rec.get("outcome") or "none"
    g = grading.current_grade(grades, item["id"], "answer", rec.get("reply", ""))
    return g["grade"] if g else "ungraded"


def load_hand(path, run):
    p = Path(path)
    if not p.exists():
        return {}
    rows = [json.loads(l) for l in p.read_text(encoding="utf-8").splitlines() if l.strip()]
    return {r["id"]: r["facts"] for r in rows if r.get("run") == run}


def rows(run, hand_path=HAND):
    items = {i["id"]: i for i in json.loads((RUNS / run / "dataset.json").read_text(encoding="utf-8"))}
    grades = grading.load_grades(GRADES)
    hand = load_hand(hand_path, run)
    out = []
    for line in (RUNS / run / "results.jsonl").read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        rec = json.loads(line)
        item = items[rec["id"]]
        if not item.get("answerable"):
            continue
        chosen = rec.get("chosen") or []
        facts = [mark(a, chosen) for a in item.get("answers") or []]
        auto = list(facts)
        if rec["id"] in hand:
            facts = hand[rec["id"]]
            if len(facts) != len(auto):
                raise SystemExit("%s: hand marks %d answers, the dataset has %d" % (rec["id"], len(facts), len(auto)))
        out.append({"id": rec["id"], "multi": bool(item.get("multiStep")), "facts": facts, "auto": auto,
                    "hand": rec["id"] in hand, "outcome": outcome(item, rec, grades), "passages": len(chosen)})
    return out


def holds(row):
    """Single fact: any answer in a passage. Multi-step: how many facts are in a passage (both / one / none)."""
    n = sum(f == "passage" for f in row["facts"])
    if not row["multi"]:
        return "in passage" if n or row["outcome"] == "grounded" else ("article only" if "article" in row["facts"] else "not there")
    return "both" if n >= len(row["facts"]) else ("one" if n else "none")


def table(rs, multi):
    sel = [r for r in rs if r["multi"] == multi]
    cols = ["grounded", "not-grounded", "declined", "other"]
    keys = ["both", "one", "none"] if multi else ["in passage", "article only", "not there"]
    c = Counter((holds(r), r["outcome"] if r["outcome"] in cols else "other") for r in sel)
    lines = ["| %s | %s | total |" % ("facts in passages" if multi else "answer", " | ".join(cols)),
             "|---|" + "---|" * (len(cols) + 1)]
    for k in keys:
        lines.append("| %s | %s | %d |" % (k, " | ".join(str(c[(k, col)]) for col in cols), sum(c[(k, col)] for col in cols)))
    lines.append("| total | %s | %d |" % (" | ".join(str(sum(c[(k, col)] for k in keys)) for col in cols), len(sel)))
    return "\n".join(lines)


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("run")
    p.add_argument("--hand", default=HAND)
    p.add_argument("-v", "--verbose", action="store_true", help="list each item")
    a = p.parse_args(argv)
    rs = rows(a.run, a.hand)
    if a.verbose:
        for r in rs:
            print("%-22s %-5s %-13s %-30s%s" % (r["id"], "multi" if r["multi"] else "", r["outcome"], ",".join(r["facts"]),
                                                " (hand; auto %s)" % ",".join(r["auto"]) if r["hand"] else ""))
    print("Single fact (%d)\n\n%s\n\nMulti-step (%d)\n\n%s" % (
        sum(not r["multi"] for r in rs), table(rs, False), sum(r["multi"] for r in rs), table(rs, True)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
