"""Grades and the grading worksheet.

Grades live in grades/grades.jsonl, one line per graded reply:
  {"id", "kind": answer|deeper|guess-small|guess-large, "replyHash": sha1(reply), "grade", "reason"}
Grades are keyed by (id, kind, replyHash), so a changed reply needs a new grade and the old reply keeps its grade
(runs of different replies for one question each stay graded). If the same key appears twice, the later line wins.
Malformed lines or grade values stop with a SystemExit naming the file and line.
"""
import hashlib
import json
from pathlib import Path

ANSWER_GRADES = ("grounded", "not-grounded")
GUESS_GRADES = ("right", "partly", "wrong", "confidently-wrong")


def reply_hash(text):
    return hashlib.sha1((text or "").encode("utf-8")).hexdigest()


KINDS = {"answer": ANSWER_GRADES, "deeper": ANSWER_GRADES, "guess-small": GUESS_GRADES, "guess-large": GUESS_GRADES}


def load_grades(path):
    out = {}
    p = Path(path)
    if p.exists():
        for n, line in enumerate(p.read_text(encoding="utf-8").splitlines(), 1):
            if not line.strip():
                continue
            where = "%s:%d" % (p, n)
            try:
                g = json.loads(line)
                id, kind, h, grade = g["id"], g["kind"], g["replyHash"], g["grade"]
            except (ValueError, KeyError, TypeError) as ex:
                raise SystemExit("%s: bad grade line (%s: %s)" % (where, type(ex).__name__, ex))
            if kind not in KINDS:
                raise SystemExit("%s: unknown kind %r (want one of %s)" % (where, kind, ", ".join(KINDS)))
            if grade not in KINDS[kind]:
                raise SystemExit("%s: grade %r is not valid for %s (want one of %s)" % (where, grade, kind, ", ".join(KINDS[kind])))
            out[(id, kind, h)] = g
    return out


def replies(item, rec):
    """The (kind, reply, passages) triples of this record that need a grade."""
    out = []
    if item.get("answerable") and rec.get("outcome") == "answered":
        out.append(("answer", rec.get("reply", ""), rec.get("chosen", [])))
    d = rec.get("deeper")
    if d and d.get("outcome") == "answered":
        out.append(("deeper", d.get("reply", ""), d.get("chosen", [])))
    for model, g in (rec.get("guesses") or {}).items():
        out.append(("guess-" + model, g.get("text", ""), []))
    return out


def current_grade(grades, id, kind, reply):
    return grades.get((id, kind, reply_hash(reply)))


def worksheet(items, records, grades):
    by_id = {i["id"]: i for i in items}
    parts = ["# Grading worksheet\n",
             "Grade each reply below per RUBRIC.md and append one line per reply to grades/grades.jsonl:\n",
             '`{"id": "...", "kind": "...", "replyHash": "...", "grade": "...", "reason": "..."}`\n'
             "(answer/deeper: grounded | not-grounded; guess-*: right | partly | wrong | confidently-wrong)\n"]
    n = 0
    for rec in records:
        item = by_id.get(rec["id"])
        if not item:
            continue
        for kind, reply, passages in replies(item, rec):
            if current_grade(grades, rec["id"], kind, reply):
                continue
            n += 1
            parts.append("\n## %s  (%s)%s\n" % (rec["id"], kind, "  MEDICAL: grounded only if nothing beyond the passages" if item.get("medical") else ""))
            parts.append("Question: %s\n" % item["question"])
            parts.append("Reference: %s\n" % item.get("reference", ""))
            if kind.startswith("guess") and item.get("goodGuess"):
                parts.append("Good guess: %s\n" % item["goodGuess"])
            parts.append("replyHash: %s\n" % reply_hash(reply))
            for i, p in enumerate(passages, 1):
                parts.append("\nPassage [%d] %s / %s / %s\n> %s\n" % (i, p.get("source", ""), p.get("title", ""), p.get("heading") or p.get("location", ""), (p.get("text") or "").replace("\n", "\n> ")))
            parts.append("\nReply:\n> %s\n" % (reply or "").replace("\n", "\n> "))
    parts.insert(1, "%d replies to grade.\n" % n)
    return "\n".join(parts)
