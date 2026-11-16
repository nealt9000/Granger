#!/usr/bin/env python3
"""Plan EX Task 1: check subjects before they ship, and on every upkeep rebuild (Linked Library spec §5.5).

  check.py GQID [--graded tools/subjects/graded-2026-11-16.json] [--min-score 0.95] [--min-share 0.98]

GQID is a built QID index with its subjects table (English Top for the graded sample). Fails (exit 1) when a subject id
isn't one of subjects.json's, when fewer than --min-share of a whole encyclopedia's or subject pack's items have subjects, or, with --graded, when
fewer than --min-score of the graded sample's judged marks the index still gives are right (right / (right + wrong));
it also prints the marks gained and lost against the graded set, so a rebuild's drift is visible.
"""
import argparse
import json
import sqlite3
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
ENCYCLOPEDIAS = {"top", "top1m", "all", "100"}


def subjects_of(db):
    out = {}
    for qid, subject in db.execute("SELECT qid, subject FROM subjects ORDER BY qid, ord"):
        out.setdefault(f"Q{qid}", []).append(subject)
    return out


def score(subs, graded):
    """Counts of the index's marks on the graded items: right, wrong, unsure, new (never graded), missed (right or missing,
    not given)."""
    c = dict(right=0, wrong=0, unsure=0, new=0, missed=0, items=0)
    for g in graded:
        if not g["q"]:
            continue
        c["items"] += 1
        pred = set(subs.get(g["q"], []))
        good = {s for s, v in g["marks"].items() if v == "right"} | set(g["missing"])
        bad = {s for s, v in g["marks"].items() if v == "wrong"}
        uns = {s for s, v in g["marks"].items() if v == "unsure"}
        c["right"] += len(pred & good); c["wrong"] += len(pred & bad); c["unsure"] += len(pred & uns)
        c["new"] += len(pred - good - bad - uns); c["missed"] += len(good - pred)
    return c


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("gqid"); p.add_argument("--graded"); p.add_argument("--min-score", type=float, default=0.95)
    p.add_argument("--min-share", type=float, default=0.98)
    a = p.parse_args(argv)
    ids = {s["id"] for s in json.loads((HERE / "subjects.json").read_text(encoding="utf8"))["subjects"]}
    db = sqlite3.connect(f"file:{a.gqid}?mode=ro", uri=True)
    problems = []
    try:
        subs = subjects_of(db)
        items = db.execute("SELECT COUNT(DISTINCT qid) FROM articles").fetchone()[0]
        by = dict(db.execute("SELECT subject, COUNT(*) FROM subjects WHERE ord = 0 GROUP BY subject"))
        m = dict(db.execute("SELECT key, value FROM meta"))
    except sqlite3.Error as e:
        print(f"FAIL {a.gqid}: {e}")
        return 1
    finally:
        db.close()
    share = len(subs) / max(1, items)
    print(f"{Path(a.gqid).name}: {len(subs):,}/{items:,} items have subjects ({share:.1%}); subjects v{m.get('subjectsVersion')}, "
          f"pack subject {m.get('packSubject') or '-'}")
    print("  main: " + ", ".join(f"{s} {n:,}" for s, n in sorted(by.items(), key=lambda kv: -kv[1])))
    unknown = {s for v in subs.values() for s in v} - ids
    if unknown:
        problems.append(f"unknown subject ids {sorted(unknown)}")
    # The floor holds for whole encyclopedias and subject packs (which give their subject to what the rules miss). A pack
    # with neither (knots, category pages) is reported only.
    topic = (m.get("sourceZim") or "").split("_")
    encyclopedia = len(topic) > 3 and topic[2] in ENCYCLOPEDIAS
    if share < a.min_share and (encyclopedia or m.get("packSubject")):
        problems.append(f"only {share:.1%} of items have subjects (floor {a.min_share:.0%})")
    if a.graded:
        c = score(subs, json.loads(Path(a.graded).read_text(encoding="utf8"))["items"])
        judged = c["right"] + c["wrong"]
        s = c["right"] / max(1, judged)
        print(f"  graded sample ({c['items']} items): {c['right']} right, {c['wrong']} wrong, {c['unsure']} unsure, "
              f"{c['new']} never graded, {c['missed']} missed -> {s:.1%} of judged")
        if s < a.min_score:
            problems.append(f"graded sample {s:.1%} < {a.min_score:.0%}")
    for line in problems:
        print("FAIL", line)
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
