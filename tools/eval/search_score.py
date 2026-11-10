"""Plan SQ Tasks 1.2–1.3: Search's eval. Runs a query set through the real Search on a device, then scores it.

    run    pushes a set, runs it in the eval screen (EvalActivity's `search` mode), pulls runs/<runId>/search.jsonl
    score  scores a run against its set

Known-item sets (tools/eval/known_items.py): is the target article among the Titles, and where. Reported as @1, @3 and
MRR, by language and by kind (exact, lower, typo, prefix, alias), plus Articles (full text) @3 as a fallback view.

Question sets (Ask's datasets, answerable items): is an answer's article (or book) at the top of a group. Search shows
articles, not passages, so this is article level: Titles @5, Books @5, Articles @5 and @10, and "page @5" (any of the three
groups' first five, as the screen shows them). Ask's passage numbers stay in step-back-2026-11-05.md §9.

Latency: p50 / p95 milliseconds per group, as measured on the device.

    ANDROID_SERIAL=emulator-5580 python3 tools/eval/search_score.py run tools/eval/sets/known-items.json sq-base-ki
    python3 tools/eval/search_score.py score tools/eval/sets/known-items.json tools/eval/runs/sq-base-ki
"""

import argparse
import json
import statistics
import sys
import unicodedata
from collections import defaultdict
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))


def fold(text):
    return "".join(c for c in unicodedata.normalize("NFD", text or "") if unicodedata.category(c) != "Mn").lower().strip()


def family(file_or_name):
    """wikipedia_en_top_maxi_2026-09-14.zim -> wikipedia_en: the same article in another slice of a set counts."""
    parts = (file_or_name or "").split("_")
    return "_".join(parts[:2])


def load_items(path):
    data = json.loads(Path(path).read_text(encoding="utf-8"))
    return data if isinstance(data, list) else data["items"]


def load_run(run_dir):
    rows = {}
    for line in (Path(run_dir) / "search.jsonl").read_text(encoding="utf-8").splitlines():
        if line.strip():
            r = json.loads(line)
            rows[r["id"]] = r
    return rows


def known_rank(item, hits):
    """1-based rank of the target in [hits], or None: same file and path, or the same title in the same set."""
    for n, h in enumerate(hits, 1):
        if h.get("file") == item["pack"] and h.get("path") == item["path"]:
            return n
        if fold(h.get("title")) == fold(item["title"]) and family(h.get("file")) == family(item["pack"]):
            return n
    return None


def strip_book(source):
    return source[len("book:"):] if source.startswith("book:") else source


def answer_rank(item, hits, books=False):
    """1-based rank of the first hit on one of the item's answers (article: source + path; book: the book), or None."""
    for n, h in enumerate(hits, 1):
        for a in item["answers"]:
            src = a["source"]
            if src.startswith("book:") or books:
                if strip_book(h.get("source", "")) == strip_book(src):
                    return n
            elif h.get("source") == src and h.get("path") == a.get("location"):
                return n
    return None


def pct(n, d):
    return "%d/%d (%d%%)" % (n, d, round(100 * n / d)) if d else "0/0"


def latency(rows):
    out = {}
    for group in ("titles", "books", "fullText"):
        ms = sorted(r["ms"][group] for r in rows if group in r.get("ms", {}))
        if ms:
            out[group] = (statistics.median(ms), ms[min(len(ms) - 1, int(round(0.95 * (len(ms) - 1))))])
    return out


def score_known(items, run):
    by = defaultdict(lambda: {"n": 0, "at1": 0, "at3": 0, "rr": 0.0, "any": 0, "ft3": 0})
    misses = []
    for it in items:
        r = run.get(it["id"])
        if r is None:
            continue
        rank = known_rank(it, r["titles"])
        ft = known_rank(it, r["fullText"])
        for key in ("all", it["language"], "%s/%s" % (it["language"], it["kind"])):
            b = by[key]
            b["n"] += 1
            b["at1"] += rank == 1
            b["at3"] += bool(rank and rank <= 3)
            b["rr"] += 1 / rank if rank else 0
            b["any"] += rank is not None
            b["ft3"] += bool(ft and ft <= 3)
        if not rank or rank > 3:
            misses.append((it, rank, [h["title"] for h in r["titles"][:3]]))
    lines = ["| Slice | n | Titles @1 | Titles @3 | MRR | In Titles | Articles @3 |", "|---|---|---|---|---|---|---|"]
    for key in sorted(by, key=lambda k: (k != "all", "/" in k, k)):
        b = by[key]
        lines.append("| %s | %d | %s | %s | %.2f | %s | %s |" % (key, b["n"], pct(b["at1"], b["n"]), pct(b["at3"], b["n"]),
                                                         b["rr"] / b["n"], pct(b["any"], b["n"]), pct(b["ft3"], b["n"])))
    return lines, misses


def score_questions(items, run):
    keys = ("titles5", "books5", "ft5", "ft10", "page5")
    by = defaultdict(lambda: dict.fromkeys(keys + ("n",), 0))
    for it in items:
        if not it.get("answerable", True) or not it.get("answers"):
            continue
        r = run.get(it["id"])
        if r is None:
            continue
        t = answer_rank(it, r["titles"])
        b = answer_rank(it, r["books"], books=True)
        f = answer_rank(it, r["fullText"])
        got = {
            "titles5": bool(t and t <= 5), "books5": bool(b and b <= 5), "ft5": bool(f and f <= 5),
            "ft10": bool(f and f <= 10),
        }
        got["page5"] = got["titles5"] or got["books5"] or got["ft5"]
        for key in ("all", it.get("domain", "?")):
            s = by[key]
            s["n"] += 1
            for k in keys:
                s[k] += got[k]
    lines = ["| Slice | n | Titles @5 | Books @5 | Articles @5 | Articles @10 | Page @5 |", "|---|---|---|---|---|---|---|"]
    for key in sorted(by, key=lambda k: (k != "all", k)):
        s = by[key]
        lines.append("| %s | %d | %s |" % (key, s["n"], " | ".join(pct(s[k], s["n"]) for k in keys)))
    return lines


def cmd_score(args):
    items = load_items(args.set)
    run = load_run(args.run)
    is_known = "query" in items[0] and "pack" in items[0]
    print("Run %s: %d of %d items answered\n" % (Path(args.run).name, len(run), len(items)))
    if is_known:
        lines, misses = score_known(items, run)
        print("\n".join(lines))
        if args.misses:
            print("\nMissed at 3:")
            for it, rank, top in misses:
                print("  %-8s %-6s %-32s want %-32s got %s%s" % (it["id"], it["kind"], it["query"][:32], it["title"][:32],
                                                             top, "" if rank is None else " (rank %d)" % rank))
    else:
        print("\n".join(score_questions(items, run)))
    print("\nLatency, ms (p50 / p95):")
    for group, (p50, p95) in latency(list(run.values())).items():
        print("  %-9s %6.0f / %6.0f" % (group, p50, p95))


def cmd_run(args):
    import adb

    runner = adb.real_runner
    adb.check_front(runner)
    out = Path(args.runs) / args.run_id
    out.mkdir(parents=True, exist_ok=True)
    name = "search-%s.json" % args.run_id
    runner(["shell", "mkdir", "-p", adb.REMOTE])
    if args.fresh:
        runner(["shell", "rm", "-rf", "%s/%s" % (adb.REMOTE, args.run_id)])
    else:
        runner(["shell", "rm", "-f", "%s/%s/done" % (adb.REMOTE, args.run_id), "%s/%s/failed" % (adb.REMOTE, args.run_id)])
    rc, o = runner(["push", args.set, "%s/%s" % (adb.REMOTE, name)])
    if rc:
        raise SystemExit("adb push failed: " + o)
    rc, o = runner(["shell", "am", "start", "-S", "-n", adb.PKG + "/.eval.EvalActivity", "--es", "runId", args.run_id,
                    "--es", "search", name])
    if rc:
        raise SystemExit("could not start the eval activity: " + o)
    adb.wait_done(runner, args.run_id)
    rc, o = runner(["pull", "%s/%s/search.jsonl" % (adb.REMOTE, args.run_id), str(out) + "/"])
    if rc:
        raise SystemExit("adb pull failed: " + o)
    print(out)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    r = sub.add_parser("run")
    r.add_argument("set")
    r.add_argument("run_id")
    r.add_argument("--runs", default=str(HERE / "runs"))
    r.add_argument("--fresh", action="store_true")
    r.set_defaults(fn=cmd_run)
    s = sub.add_parser("score")
    s.add_argument("set")
    s.add_argument("run")
    s.add_argument("--misses", action="store_true")
    s.set_defaults(fn=cmd_score)
    args = ap.parse_args()
    args.fn(args)


if __name__ == "__main__":
    main()
