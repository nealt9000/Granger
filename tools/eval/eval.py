#!/usr/bin/env python3
"""Drives the Research eval (docs/superpowers/specs/2026-10-17-research-eval-design.md).

  eval.py validate dataset/tune.json [--packs DIR] [--books DIR] [--media EXPORT.json]   # EXPORT from media_library.py
  eval.py run dataset/tune.json --run-id ID [--ids a,b] [--groups g1,g2] [--guesses none|small|large|both] [--rewrite alternates]
  eval.py worksheet ID                       # runs/ID/worksheet.md: replies that still need a grade
  eval.py report --dataset-only dataset/tune.json dataset/heldout.json
  eval.py report ID [--totals-only] [--compare OTHER]
  eval.py sweep ID --lexical 0.20:0.50:0.02

Runs live in runs/<ID>/ (dataset.json copy, results.jsonl, worksheet.md, report.html); grades in grades/grades.jsonl.
"""
import argparse
import json
import sys
from pathlib import Path

import adb
import dataset
import grading
import metrics
import report
import results

HERE = Path(__file__).resolve().parent
RUNS = HERE / "runs"
GRADES = HERE / "grades" / "grades.jsonl"


def load_run(run_id):
    d = RUNS / run_id
    items = json.loads((d / "dataset.json").read_text(encoding="utf-8"))
    recs = results.load_results(d / "results.jsonl")
    return d, items, recs


def cmd_validate(a):
    items = dataset.load(a.dataset)
    media = json.loads(Path(a.media).read_text(encoding="utf-8")) if a.media else None
    problems = dataset.validate_answers(items, packs=a.packs, books=a.books, media=media)
    for p in problems:
        print(p)
    print("%d items, %d problems" % (len(items), len(problems)))
    return 1 if problems else 0


def cmd_run(a):
    items = dataset.load(a.dataset)
    out = adb.run_eval(adb.real_runner, a.dataset, a.run_id, RUNS, ids=a.ids, groups=a.groups, guesses=a.guesses, fresh=a.fresh, rewrite=a.rewrite)
    print("results in %s (%d items in the dataset)" % (out, len(items)))
    return 0


def cmd_worksheet(a):
    d, items, recs = load_run(a.run)
    text = grading.worksheet(items, recs, grading.load_grades(GRADES))
    (d / "worksheet.md").write_text(text, encoding="utf-8")
    print(text.splitlines()[1] + " -> " + str(d / "worksheet.md"))
    return 0


def cmd_report(a):
    if a.dataset_only:
        pages = [(Path(p).name, dataset.load(p)) for p in a.targets]
        out = Path("dataset-review.html")
        out.write_text(report.dataset_report(pages), encoding="utf-8")
        print("wrote", out)
        return 0
    d, items, recs = load_run(a.targets[0])
    grades = grading.load_grades(GRADES)
    if a.totals_only:
        print(report.totals_text(metrics.compute(items, recs, grades)))
        return 0
    cmp = None
    if a.compare:
        _, _, other = load_run(a.compare)
        cmp = (a.compare, other, grades)
    (d / "report.html").write_text(report.run_report(a.targets[0], items, recs, grades, cmp), encoding="utf-8")
    print("wrote", d / "report.html")
    return 0


def cmd_sweep(a):
    _, items, recs = load_run(a.run)
    lo, hi, step = (float(x) for x in a.lexical.split(":"))
    print("lexical\tunanswerable declined\tanswerable grounded")
    for r in metrics.sweep(items, recs, grading.load_grades(GRADES), lo, hi, step):
        print("%.2f\t%s\t%s" % (r["lexical"], report.pct(r["unanswerableRate"]), report.pct(r["answerableGrounded"])))
    return 0


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    s = p.add_subparsers(dest="cmd", required=True)
    v = s.add_parser("validate"); v.add_argument("dataset"); v.add_argument("--packs"); v.add_argument("--books"); v.add_argument("--media"); v.set_defaults(f=cmd_validate)
    r = s.add_parser("run"); r.add_argument("dataset"); r.add_argument("--run-id", required=True)
    r.add_argument("--fresh", action="store_true", help="wipe the run on the phone first instead of resuming it"); r.add_argument("--ids"); r.add_argument("--groups"); r.add_argument("--guesses", default="none", choices=["none", "small", "large", "both"])
    r.add_argument("--rewrite", choices=["alternates"], help="a debug rewrite variant (Plan R0)"); r.set_defaults(f=cmd_run)
    w = s.add_parser("worksheet"); w.add_argument("run"); w.set_defaults(f=cmd_worksheet)
    rp = s.add_parser("report"); rp.add_argument("targets", nargs="+"); rp.add_argument("--dataset-only", action="store_true")
    rp.add_argument("--totals-only", action="store_true"); rp.add_argument("--compare"); rp.set_defaults(f=cmd_report)
    sw = s.add_parser("sweep"); sw.add_argument("run"); sw.add_argument("--lexical", required=True); sw.set_defaults(f=cmd_sweep)
    a = p.parse_args(argv)
    return a.f(a)


if __name__ == "__main__":
    sys.exit(main())
