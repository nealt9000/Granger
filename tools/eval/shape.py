#!/usr/bin/env python3
"""Prompt-shape variants of a run's answer prompts, replayed through `answerers.py` (Ask step-back, 2026-11-05).

  shape.py variant BASE NEW --shape qfirst|quote|p3     # runs/NEW: BASE with each answer prompt reshaped
  shape.py verify NEW                                    # quote shape: marks replies whose quote isn't verbatim in the
                                                         #   cited passage as declined (runs/NEW/results.jsonl, in place;
                                                         #   the original outcome is kept in `quoteOutcome`)

Shapes (the passages and their order never change, so the reader is all that differs from BASE):
- **qfirst:** the question is also stated before the rules, so the model reads the passages knowing what it looks for.
- **quote:** the reply must start with `Quote:` and one sentence copied word for word from the passage that answers,
  with its number; then the answer. `verify` checks the copy against the passage text and declines the reply when it
  is not there (an answer the model couldn't back with a real sentence).
- **p3:** the fourth passage is dropped (fewer distractors, the same ranking).
"""
import argparse
import json
import re
import shutil
import sys
from pathlib import Path

import answerers
import passages

HERE = Path(__file__).resolve().parent
RUNS = HERE / "runs"
QUESTION = re.compile(r"^Question: (.*)$", re.M)
QUOTE_LINE = re.compile(r"^\s*Quote:\s*(.*?)\s*$", re.M)
CITE = re.compile(r"\[\d+(?:\s*,\s*\d+)*\]")
RULE_FIRST = "- Answer the question directly in your first sentence."
QUOTE_RULES = (
    "- Your first line must be: Quote: followed by one sentence copied word for word from the passage that answers "
    "the question, then its number in square brackets, like [2]. Copy it exactly; do not paraphrase.\n"
    "- Then, on a new line, answer the question directly in one or two sentences, based on that sentence."
)


def question_of(tail):
    m = QUESTION.search(tail)
    return m.group(1).strip() if m else None


def reshape(prompt, shape):
    head, ps, tail = passages.split_prompt(prompt)
    if shape == "qfirst":
        q = question_of(tail)
        if q is None:
            return prompt
        first, _, rest = head.partition("\n")
        head = "%s\nQuestion: %s\n%s" % (first, q, rest)
    elif shape == "quote":
        if RULE_FIRST not in head:
            return prompt
        head = head.replace(RULE_FIRST + " Keep the whole answer short:", QUOTE_RULES + "\n- Keep the whole answer short:", 1)
        head = head.replace("- If the passages don't answer the question, reply exactly:",
                            "- If no passage answers the question, reply with only:", 1)
    elif shape == "p3":
        ps = ps[:3]
    else:
        raise SystemExit("unknown shape " + shape)
    return passages.join_prompt(head, ps, tail)


def cmd_variant(a):
    out_dir = RUNS / a.new
    out_dir.mkdir(parents=True, exist_ok=True)
    out, changed = [], 0
    for rec in answerers.read_jsonl(RUNS / a.base / "results.jsonl"):
        rec = {k: v for k, v in rec.items() if k not in ("deeper", "guesses")}
        before = answerers.answer_prompt(rec)
        if before is not None:
            after = reshape(before, a.shape)
            if after != before:
                changed += 1
                passages.set_prompt(rec, after)
                if a.shape == "p3":
                    rec["chosen"] = (rec.get("chosen") or [])[:3]
        out.append(rec)
    shutil.copy(RUNS / a.base / "dataset.json", out_dir / "dataset.json")
    answerers.write_jsonl(out_dir / "results.jsonl", out)
    (out_dir / "variant.json").write_text(json.dumps({"from": a.base, "shape": a.shape}), encoding="utf-8")
    print(json.dumps({"variant": a.new, "shape": a.shape, "prompts changed": changed, "records": len(out)}))
    return 0


def norm(s):
    s = s.replace("’", "'").replace("‘", "'").replace("“", '"').replace("”", '"')
    s = re.sub(r"[—–]", "-", s)
    return re.sub(r"\s+", " ", s).strip().lower().strip(".\"' ")


def quote_in(quote, text):
    """Verbatim after normalising quotes, dashes and spaces; or near-verbatim: 90% of the quote's words in the passage."""
    q, t = norm(quote), norm(text)
    if not q:
        return False
    if q in t:
        return True
    qw = q.split()
    tw = set(t.split())
    return len(qw) >= 6 and sum(w in tw for w in qw) >= 0.9 * len(qw)


def cmd_verify(a):
    path = RUNS / a.new / "results.jsonl"
    recs = answerers.read_jsonl(path)
    counts = {"answered": 0, "verified": 0, "noQuoteLine": 0, "quoteNotInCited": 0, "quoteElsewhere": 0}
    for rec in recs:
        if rec.get("outcome") != "answered":
            continue
        counts["answered"] += 1
        reply = rec.get("reply") or ""
        m = QUOTE_LINE.search(reply)
        chosen = rec.get("chosen") or []
        cited = {int(n) for n in re.findall(r"\d+", str(rec.get("cited") or ""))}
        verdict = None
        if not m:
            verdict = "noQuoteLine"
        else:
            line = m.group(1)
            nums = {int(n) for n in re.findall(r"\d+", " ".join(CITE.findall(line)))} or cited
            quote = CITE.sub("", line)
            where = [n for n in range(1, len(chosen) + 1) if quote_in(quote, chosen[n - 1].get("text") or "")]
            if any(n in nums for n in where):
                verdict = "verified"
            elif where:
                verdict = "quoteElsewhere"
            else:
                verdict = "quoteNotInCited"
        counts[verdict] += 1
        rec["quoteCheck"] = verdict
        if verdict != "verified":
            rec["quoteOutcome"] = rec["outcome"]
            rec["outcome"] = "declined"
    answerers.write_jsonl(path, recs)
    print(json.dumps({"run": a.new, **counts}))
    return 0


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    s = p.add_subparsers(dest="cmd", required=True)
    v = s.add_parser("variant"); v.add_argument("base"); v.add_argument("new")
    v.add_argument("--shape", required=True, choices=["qfirst", "quote", "p3"]); v.set_defaults(f=cmd_variant)
    c = s.add_parser("verify"); c.add_argument("new"); c.set_defaults(f=cmd_verify)
    a = p.parse_args(argv)
    return a.f(a)


if __name__ == "__main__":
    sys.exit(main())
