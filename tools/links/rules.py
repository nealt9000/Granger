#!/usr/bin/env python3
"""Plan LL1 Task 6, step 5: the Light and More rules (Decision 5) over link.py's candidates.

  rules.py CAND.jsonl… --tier light|more [--min-sl 40] [--min-sim 0.3] [--per-words 150] [--out SELECTED.jsonl]

Light: a type-fitting, unambiguous candidate with at least --min-sl sitelinks whose article lead matches the paragraph
(--min-sim), the first mention per chapter only, at most one link per --per-words words (the best-known kept).
More: no density cap and a quarter of the sitelink floor.
"""
import argparse
import collections
import json
import sys


def eligible(r, min_sl, min_sim):
    if not r.get("qid") or r["sl"] < min_sl or (r.get("sim") or 0) < min_sim:
        return False
    alts = r.get("alts") or []
    return not (alts and alts[0][2] * 2 > r["sl"])   # a close second candidate: ambiguous


def select(rows, tier="light", min_sl=40, min_sim=0.3, per_words=150):
    if tier == "more":
        min_sl, per_words = max(1, min_sl // 4), None
    by_ch = collections.defaultdict(list)
    for r in rows:
        by_ch[(r["book"], r["href"])].append(r)
    out = []
    for (_, _), rs in by_ch.items():
        words = max((r["word"] for r in rs), default=0) + 1
        seen, keep = set(), []
        for r in sorted(rs, key=lambda r: r["start"]):
            if eligible(r, min_sl, min_sim) and r["qid"] not in seen:
                seen.add(r["qid"]); keep.append(r)
        if per_words:
            cap = max(1, words // per_words)
            if len(keep) > cap:
                best = {id(r) for r in sorted(keep, key=lambda r: -r["sl"])[:cap]}
                keep = [r for r in keep if id(r) in best]
        out += keep
    return out


def main(argv=None):
    a = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    a.add_argument("cands", nargs="+"); a.add_argument("--tier", default="light", choices=("light", "more"))
    a.add_argument("--min-sl", type=int, default=40); a.add_argument("--min-sim", type=float, default=0.3)
    a.add_argument("--per-words", type=int, default=150); a.add_argument("--out")
    args = a.parse_args(argv)
    if args.out:
        open(args.out, "w").close()   # one run's books, appended below
    for path in args.cands:
        rows = [json.loads(l) for l in open(path, encoding="utf8")]
        words = sum(max((r["word"] for r in rows if r["href"] == h), default=0) for h in {r["href"] for r in rows})
        sel = select(rows, args.tier, args.min_sl, args.min_sim, args.per_words)
        print(f"{rows[0]['book']}: {len(sel):,} links ({len(sel) / max(1, words) * 1000:.1f} per 1000 words), "
              f"{len({r['qid'] for r in sel}):,} items; top: "
              + ", ".join(f"{l} {n}" for l, n in collections.Counter(r['label_wd'] for r in sel).most_common(8)))
        if args.out:
            with open(args.out, "a", encoding="utf8") as f:
                for r in sel:
                    f.write(json.dumps(r, ensure_ascii=False) + "\n")


if __name__ == "__main__":
    sys.exit(main())
