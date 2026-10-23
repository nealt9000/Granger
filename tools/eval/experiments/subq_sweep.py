"""Run from tools/eval after experiments/subq.py: per-subject dense queries merged and gated three ways (r1-dense-1)."""
import sys, json
sys.path.insert(0, ".")
import merge, replay
from pathlib import Path
RUN = sys.argv[1] if len(sys.argv) > 1 else "r1-dense-1"
items, corpus, dense = merge.load(RUN)
subq = {v: {r["id"]: r["subs"] for r in map(json.loads, (Path("runs") / RUN / ("subq-%s.jsonl" % v)).open())} for v in "AB"}

def combined(main, subs, per_sub):
    best = {}
    for p in main + [p for s in subs for p in s["passages"][:per_sub]]:
        k = merge.text_key(p)
        if k not in best or p["cosine"] > best[k]["cosine"]:
            best[k] = p
    return sorted(best.values(), key=lambda p: -p["cosine"])

def ranker(variant, gate_mode, per_sub, bonus, gate=0.78, lexical=1.0):
    inner = merge.cover(0.1, 0.74, bonus)
    def rank(question, c, cfg, hits):
        subs = subq[variant].get(c["id"], [])
        allh = combined(hits, subs, per_sub)
        w = merge._weight(question, c, allh)
        _, kept, lex = merge._keyword(question, c, cfg, w)
        best_main = max((p["cosine"] * merge._fw(p, w) for p in hits), default=0.0)
        best_lex = max((lex(p) for p in kept), default=0.0)
        ok = best_main >= gate or best_lex >= lexical
        if gate_mode == "any":
            ok = ok or max((p["cosine"] for p in allh), default=0) >= gate
        elif gate_mode == "all" and subs:
            ok = ok or all(max((p["cosine"] for p in s["passages"]), default=0) >= gate for s in subs)
        if not ok:
            return []
        return inner(question, c, cfg, allh)
    return rank

base = merge.evaluate(items, corpus, dense, merge.configs()[merge.PICK])
cov = merge.evaluate(items, corpus, dense, merge.gated(merge.cover(0.1, 0.74, 0.1), 0.78, 1.0))
def line(name, r):
    print("%-34s loc %3d/%d quote %3d empty %2d/%d fiction %d parts %2d/%d" % (name, r["location"], r["answerable"], r["quote"], r["empty"], r["unanswerable"], r["fiction"], r["parts"], r["multi"]))
line("PICK (phone)", base); line("cover0.10", cov)
for v in "AB":
    for gm in ("main", "all", "any"):
        for ps in (4, 8):
            for b in (0.05, 0.1):
                line("sub%s gate=%s per%d cover%.2f" % (v, gm, ps, b), merge.evaluate(items, corpus, dense, ranker(v, gm, ps, b)))
