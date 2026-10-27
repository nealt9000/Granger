#!/usr/bin/env python3
"""Answer models compared on the same prompts (Plan R0, Task 5).

  answerers.py prompts BASE                          # runs/BASE/prompts.jsonl: each item's recorded answer prompt
  answerers.py run BASE NEW --model FILE [--push LOCAL] [--backend gpu|cpu] [--sample N | --set NAME]
                 [--speculative on|off] [--thinking off|config|template] [--max-tokens N]
                                                     # replays them on the phone, then builds runs/NEW
  answerers.py build BASE NEW                        # runs/NEW/results.jsonl from BASE's records and NEW/replay.jsonl
  answerers.py summary NEW [NEW ...] [--only SAMPLED]  # grounded, declined, timings and memory per model

The prompts are the ones BASE's ranking built, so only the model changes. NEW's records are BASE's with the reply,
outcome and citations replaced (Think deeper and best guesses dropped), so `eval.py worksheet/report` work on them
and an unchanged reply keeps its grade. Their timings estimate send to first words as BASE's rewrite, search and rank
plus the replay's own time to first words; the model swap that a different answer model would add is reported apart.

`--set NAME` replays the ids in `sets/NAME.json` (Plan R4: speed-10, sample-40, full; `speed-rewrite` replays rewrite
prompts, which only time the model and leave no results to build).
"""
import argparse
import json
import random
import shutil
import sys
from pathlib import Path

import adb
import grading
import metrics

HERE = Path(__file__).resolve().parent
RUNS = HERE / "runs"
SETS = HERE / "sets"
GRADES = HERE / "grades" / "grades.jsonl"


def read_jsonl(path):
    return [json.loads(l) for l in Path(path).read_text(encoding="utf-8").splitlines() if l.strip()]


def write_jsonl(path, rows):
    Path(path).write_text("".join(json.dumps(r, ensure_ascii=False) + "\n" for r in rows), encoding="utf-8")


def answer_prompt(rec, stage="answer"):
    """The last `answer` (or `rewrite`) stage prompt: a follow-up's prior question has its own, earlier one."""
    ps = [p for p in rec.get("prompts") or [] if p["stage"] == stage]
    return ps[-1]["prompt"] if ps else None


SAMPLE_SEED = 5


def prompts(base, sample=None, ids=None, stage="answer"):
    """Every recorded answer prompt, a fixed random `sample` of them (the same ids for every model), or those of `ids`.
    A rewrite prompt has no passages."""
    rows, known = [], set()
    for rec in read_jsonl(RUNS / base / "results.jsonl"):
        known.add(rec["id"])
        p = answer_prompt(rec, stage)
        if p is not None:
            rows.append({"id": rec["id"], "prompt": p, "passages": len(rec.get("chosen") or []) if stage == "answer" else 0})
    if sample:
        keep = {r["id"] for r in random.Random(SAMPLE_SEED).sample(rows, sample)}
        rows = [r for r in rows if r["id"] in keep]
    if ids is not None:
        # In a passages.py variant, an id with a record but no prompt (the gate left it nothing) is skipped.
        missing = set(ids) - (known if (RUNS / base / "variant.json").exists() else {r["id"] for r in rows})
        if missing:
            raise SystemExit("no %s prompt in runs/%s for %s" % (stage, base, ", ".join(sorted(missing))))
        rows = [r for r in rows if r["id"] in set(ids)]
    return rows


def load_set(name, base):
    """sets/NAME.json: `{base, stage, ids}`; its base must be the run's, or the run a passages.py variant of it."""
    s = json.loads((SETS / (name + ".json")).read_text(encoding="utf-8"))
    variant = RUNS / base / "variant.json"
    origin = json.loads(variant.read_text(encoding="utf-8"))["from"] if variant.exists() else base
    if s["base"] not in (base, origin):
        raise SystemExit("set %s was cut from runs/%s, not %s" % (name, s["base"], base))
    return s


def cmd_prompts(a):
    rows = prompts(a.base)
    write_jsonl(RUNS / a.base / "prompts.jsonl", rows)
    print("%d prompts -> runs/%s/prompts.jsonl" % (len(rows), a.base))
    return 0


REWRITE_TOKENS = 32  # ResearchPipeline.REWRITE_TOKENS (alternates off)

# What a replay record keeps in the built run. Plan R4 added the token counts and rates and the memory around the load;
# older replays lack them.
REPLAY_KEYS = ("firstWordsMs", "totalMs", "peakRssMb", "loadMs", "raw", "prefillTokens", "prefillTokensPerSecond",
               "outputTokens", "decodeTokensPerSecond", "memBeforeLoad", "memAfterLoad")


def replay_extras(a):
    """The `am start` extras for the Plan R4 knobs that were given."""
    out = []
    for name, value in (("speculative", a.speculative), ("thinking", a.thinking)):
        if value:
            out += ["--es", name, value]
    if a.max_tokens:
        out += ["--ei", "maxTokens", str(a.max_tokens)]
    return out


def before_answer(rec):
    ms = rec.get("ms") or {}
    return sum(ms.get(k, 0) for k in ("rewrite", "search", "rank"))


def build(base, new):
    replies = {r["id"]: r for r in read_jsonl(RUNS / new / "replay.jsonl")}
    out = []
    for rec in read_jsonl(RUNS / base / "results.jsonl"):
        rec = {k: v for k, v in rec.items() if k not in ("deeper", "guesses")}
        r = replies.get(rec["id"])
        if answer_prompt(rec) is not None:
            if r is None:
                continue  # not replayed (yet)
            pre = before_answer(rec)
            rec.update(runId=new, outcome=r["outcome"], reply=r["reply"],
                       cited="".join("[%d]" % n for n in r["cited"]),
                       ms={"firstWords": pre + r["firstWordsMs"], "total": pre + r["totalMs"]},
                       replay={k: r[k] for k in REPLAY_KEYS if r.get(k) is not None})
        out.append(rec)
    shutil.copy(RUNS / base / "dataset.json", RUNS / new / "dataset.json")
    write_jsonl(RUNS / new / "results.jsonl", out)
    return out


def cmd_build(a):
    print("%d records -> runs/%s/results.jsonl" % (len(build(a.base, a.new)), a.new))
    return 0


def cmd_run(a, runner=adb.real_runner, sleep=adb.time.sleep):
    chosen = load_set(a.set, a.base) if getattr(a, "set", None) else {"stage": "answer", "ids": None}
    rewrite = chosen["stage"] == "rewrite"
    if rewrite and not a.max_tokens:
        a.max_tokens = REWRITE_TOKENS
    rows = prompts(a.base, a.sample, chosen["ids"], chosen["stage"])
    local = RUNS / a.base / "prompts.jsonl"
    write_jsonl(local, rows)
    adb.check_front(runner)
    remote_model = "%s/models/%s" % (adb.REMOTE, a.model)
    runner(["shell", "mkdir", "-p", adb.REMOTE + "/models"])
    if a.push:
        size = Path(a.push).stat().st_size
        rc, o = runner(["shell", "stat", "-c", "%s", remote_model])
        if rc or o.strip() != str(size):
            print("pushing %s (%.2f GB)…" % (a.model, size / 1e9))
            rc, o = runner(["push", a.push, remote_model])
            if rc:
                raise SystemExit("adb push failed: " + o)
    (RUNS / a.new).mkdir(parents=True, exist_ok=True)
    runner(["shell", "rm", "-f", "%s/%s/done" % (adb.REMOTE, a.new), "%s/%s/failed" % (adb.REMOTE, a.new)])
    rc, o = runner(["push", str(local), adb.REMOTE + "/r0-prompts.jsonl"])
    if rc:
        raise SystemExit("adb push failed: " + o)
    rc, o = runner(["shell", "am", "start", "-S", "-n", adb.PKG + "/.eval.EvalActivity", "--es", "runId", a.new,
                    "--es", "replay", "r0-prompts.jsonl", "--es", "model", a.model, "--es", "backend", a.backend]
                   + replay_extras(a))
    if rc:
        raise SystemExit("could not start the eval activity: " + o)
    adb.wait_done(runner, a.new, sleep)
    rc, o = runner(["pull", "%s/%s/replay.jsonl" % (adb.REMOTE, a.new), str(RUNS / a.new) + "/"])
    if rc:
        raise SystemExit("adb pull failed: " + o)
    if rewrite:
        print(json.dumps({"run": a.new, **model_timings(read_jsonl(RUNS / a.new / "replay.jsonl"))}))
        return 0
    print("%d records -> runs/%s/results.jsonl" % (len(build(a.base, a.new)), a.new))
    return 0


def summary(new, grades, only=None):
    ds = json.loads((RUNS / new / "dataset.json").read_text(encoding="utf-8"))
    items = ds["items"] if isinstance(ds, dict) else ds
    by_id = {i["id"]: i for i in items}
    recs = read_jsonl(RUNS / new / "results.jsonl")
    if only is not None:
        recs = [r for r in recs if r["id"] in only]
    m = metrics.compute(items, recs, grades)
    replayed = [r["replay"] for r in recs if r.get("replay")]
    an = [r for r in recs if by_id[r["id"]]["answerable"]]
    outcome = lambda rs, o: sum(r["outcome"] == o for r in rs)
    not_grounded = sum(1 for r in an if r["outcome"] == "answered" and
                       (grading.current_grade(grades, r["id"], "answer", r.get("reply", "")) or {}).get("grade") == "not-grounded")
    multi = [r for r in an if by_id[r["id"]].get("multiStep")]
    multi_g = sum(1 for r in multi if r["outcome"] == "answered" and
                  (grading.current_grade(grades, r["id"], "answer", r.get("reply", "")) or {}).get("grade") == "grounded")
    load = next((r for r in replayed if "loadMs" in r), {})
    return {
        "run": new,
        "grounded": "%d/%d" % (m["answerable"]["grounded"], m["answerable"]["n"]),
        "ungraded": m["answerable"]["ungraded"],
        "notGrounded": not_grounded,
        "answerableDeclined": outcome(an, "declined"),
        "unverified": outcome(recs, "unverified"),
        "unanswerableDeclined": "%d/%d" % (m["unanswerable"]["handled"], m["unanswerable"]["n"]),
        "multiStepGrounded": "%d/%d" % (multi_g, len(multi)),
        **model_timings(replayed),
        "modelTotalMedianMs": metrics.median([r["totalMs"] for r in replayed]),
        "sendToFirstWordsMedianMs": m["timings"]["firstWords"]["median"],
        "loadMs": load.get("loadMs"),
        "memAfterLoad": load.get("memAfterLoad"),
        "peakRssMb": max((r["peakRssMb"] for r in replayed), default=None),
    }


def model_timings(replayed):
    """The model's own first words and decode rate, median and p90 (Plan R4); the rates are None for older replays."""
    first = [r["firstWordsMs"] for r in replayed]
    rates = [r["decodeTokensPerSecond"] for r in replayed if r.get("decodeTokensPerSecond") is not None]
    tokens = [r["outputTokens"] for r in replayed if r.get("outputTokens") is not None]
    return {
        "modelFirstWordsMedianMs": metrics.median(first),
        "modelFirstWordsP90Ms": metrics.percentile(first, 0.9),
        # A slow reply is the low end of the rate, so its p90 is the 10th percentile.
        "decodeTokPerSecMedian": metrics.median(rates),
        "decodeTokPerSecP90": metrics.percentile(rates, 0.1),
        "outputTokensMedian": metrics.median(tokens),
    }


def cmd_summary(a):
    grades = grading.load_grades(GRADES)
    only = None
    if a.only:
        # The items a sampled run replayed, plus those that never reached a model (the same in every run).
        replayed = {r["id"] for r in read_jsonl(RUNS / a.only / "replay.jsonl")}
        reached = {r["id"] for r in prompts(a.base)}
        only = replayed | {r["id"] for r in read_jsonl(RUNS / a.base / "results.jsonl") if r["id"] not in reached}
    for new in a.runs:
        print(json.dumps(summary(new, grades, only)))
    return 0


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    s = p.add_subparsers(dest="cmd", required=True)
    c = s.add_parser("prompts"); c.add_argument("base"); c.set_defaults(f=cmd_prompts)
    r = s.add_parser("run"); r.add_argument("base"); r.add_argument("new"); r.add_argument("--model", required=True)
    r.add_argument("--push"); r.add_argument("--backend", default="gpu", choices=["gpu", "cpu"])
    pick = r.add_mutually_exclusive_group()
    pick.add_argument("--sample", type=int, help="replay a fixed random sample of this many prompts")
    pick.add_argument("--set", help="replay the prompts of sets/SET.json")
    r.add_argument("--speculative", choices=["on", "off"], help="speculative decoding (MTP); the runtime's default when not given")
    r.add_argument("--thinking", choices=["off", "config", "template"], help="switch a reasoning model's thinking off: both ways, or one")
    r.add_argument("--max-tokens", type=int, help="reply token limit (512 when not given, as the pipeline asks)")
    r.set_defaults(f=cmd_run)
    b = s.add_parser("build"); b.add_argument("base"); b.add_argument("new"); b.set_defaults(f=cmd_build)
    m = s.add_parser("summary"); m.add_argument("runs", nargs="+")
    m.add_argument("--only", help="restrict to the items this (sampled) run replayed"); m.add_argument("--base", default="r0-base")
    m.set_defaults(f=cmd_summary)
    a = p.parse_args(argv)
    return a.f(a)


if __name__ == "__main__":
    sys.exit(main())
