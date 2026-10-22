#!/usr/bin/env python3
"""Plan R1 Task 7: merging keyword and dense passages, offline.

  merge.py sweep RUN       # every ranker over a grid of its settings; recall@4, empty rate, fiction intrusions
  merge.py show RUN NAME   # one config's per-domain split (NAME as the sweep prints it)

RUN needs found.jsonl (the keyword search, as recorded) and dense.jsonl (`tools/vectors/hits.py`). The keyword side is
the app's ranking (`replay`) with fiction weighted on non-literature questions (`kinds`, 0.7, filter mode). A dense hit
whose text a keyword passage already has is the same passage: it lends that passage its cosine.

Rankers:
- **hybrid:** alpha·lexical + (1−alpha)·cosine where a cosine exists, lexical alone otherwise. Keyword passages keep the
  lexical threshold, dense hits the cosine floor.
- **rrf:** reciprocal rank fusion of the keyword list and the dense list above the floor.
- **slots:** 1 or 2 of the 4 for the best dense hits above the floor that keyword ranking didn't choose; keyword fills the rest
  (and dense fills any keyword leaves empty).
"""
import argparse
import json
import sys
from dataclasses import replace

import kinds
import replay
from metrics import matches

FICTION_WEIGHT = 0.7
RRF_K = 60


def text_key(p):
    return " ".join((p.get("text") or "").split())


def load(run):
    d, items, corpus, _ = replay.load(run)
    dense = {r["id"]: r["passages"] for r in replay._jsonl(d / "dense.jsonl")}
    return items, corpus, dense


def _weight(question, corpus, hits):
    return 1.0 if kinds.literary(question, corpus["keywords"], corpus["passages"] + hits) else FICTION_WEIGHT


def _fw(p, w):
    return w if kinds.kind(p) == "fiction" else 1.0


def _keyword(question, corpus, cfg, w):
    """The app's keyword ranking with fiction weighted (filter mode), before the media cap and the cut to 4."""
    kw, ps = corpus["keywords"], corpus["passages"]
    raw = replay.scorer(kw)

    def weighted(p):
        return raw(p) * _fw(p, w)
    found = replay.preselect(ps, kw, cfg, score=weighted)
    kept = [p for p in sorted(found, key=lambda p: -weighted(p)) if weighted(p) >= cfg.lexical_threshold]
    return found, kept, weighted


def _dense(hits, floor, w):
    return [p for p in hits if p["cosine"] * _fw(p, w) >= floor]


def _finish(question, ranked, found, cfg):
    """Per-document cap, media lift and cap, then the 4."""
    asked = replay.media_asked(question)
    seen, out = {}, []
    for p in ranked:
        k = (p["source"], p.get("location") or "")
        seen[k] = seen.get(k, 0) + 1
        if seen[k] <= cfg.per_document:
            out.append(p)
    return replay.cap_media(replay.lift_media(out, found, asked), asked, cfg)[:cfg.large][:cfg.small]


def _unique(ps):
    seen, out = set(), []
    for p in ps:
        k = text_key(p)
        if k not in seen:
            seen.add(k)
            out.append(p)
    return out


def hybrid(alpha, floor):
    def rank(question, corpus, cfg, hits):
        w = _weight(question, corpus, hits)
        found, kept, lex = _keyword(question, corpus, cfg, w)
        cos = {text_key(p): p["cosine"] for p in hits}
        scored = []
        for p in kept:
            c = cos.get(text_key(p))
            scored.append((p, (alpha * lex(p) + (1 - alpha) * c * _fw(p, w)) if c is not None else alpha * lex(p) + (1 - alpha) * floor))
        for p in _dense(hits, floor, w):
            scored.append((p, alpha * replay.scorer(corpus["keywords"])(p) * _fw(p, w) + (1 - alpha) * p["cosine"] * _fw(p, w)))
        ranked = _unique([p for p, _ in sorted(scored, key=lambda x: -x[1])])
        return _finish(question, ranked, found, cfg)
    return rank


def rrf(floor):
    def rank(question, corpus, cfg, hits):
        w = _weight(question, corpus, hits)
        found, kept, _ = _keyword(question, corpus, cfg, w)
        score, by_key = {}, {}
        for lst in (kept, _dense(hits, floor, w)):
            for r, p in enumerate(lst):
                k = text_key(p)
                by_key.setdefault(k, p)
                score[k] = score.get(k, 0.0) + 1.0 / (RRF_K + r + 1)
        ranked = [by_key[k] for k in sorted(score, key=lambda k: -score[k])]
        return _finish(question, ranked, found, cfg)
    return rank


def slots(n, floor):
    def rank(question, corpus, cfg, hits):
        w = _weight(question, corpus, hits)
        found, kept, _ = _keyword(question, corpus, cfg, w)
        lexical = _finish(question, kept, found, cfg)
        taken = {text_key(p) for p in lexical}
        extra = [p for p in _dense(hits, floor, w) if text_key(p) not in taken][:n]
        keep = lexical[:max(cfg.small - len(extra), 0)]
        rest = [p for p in _dense(hits, floor, w) if text_key(p) not in taken and p not in extra]
        out = _unique(keep + extra + rest)
        return _finish(question, out, found, cfg)
    return rank


def gated(inner, gate, lexical):
    """[inner]'s passages only when the best dense hit reaches [gate] or the best keyword passage scores [lexical] or more."""
    def rank(question, corpus, cfg, hits):
        w = _weight(question, corpus, hits)
        found, kept, lex = _keyword(question, corpus, cfg, w)
        best_cos = max((p["cosine"] * _fw(p, w) for p in hits), default=0.0)
        best_lex = max((lex(p) for p in kept), default=0.0)
        if best_cos < gate and best_lex < lexical:
            return []
        return inner(question, corpus, cfg, hits)
    return rank


def keyword_only():
    def rank(question, corpus, cfg, hits):
        found, kept, _ = _keyword(question, corpus, cfg, _weight(question, corpus, []))
        return _finish(question, kept, found, cfg)
    return rank


def dense_only(floor):
    def rank(question, corpus, cfg, hits):
        return _finish(question, _unique(_dense(hits, floor, _weight(question, corpus, hits))), [], cfg)
    return rank


def configs():
    floors = [0.72, 0.74, 0.76, 0.78, 0.80]
    out = {"keyword": keyword_only()}
    out.update({"dense f%.2f" % f: dense_only(f) for f in floors})
    out.update({"hybrid a%.1f f%.2f" % (a, f): hybrid(a, f) for a in (0.0, 0.1, 0.2, 0.3, 0.4) for f in floors})
    out.update({"rrf f%.2f" % f: rrf(f) for f in floors})
    out.update({"slots%d f%.2f" % (n, f): slots(n, f) for n in (1, 2) for f in floors})
    for g in (0.74, 0.76, 0.78):
        for lx in (1.0, 1.1, 1.3, 9.0):
            out["gated g%.2f l%.1f hybrid a0.1 f0.74" % (g, lx)] = gated(hybrid(0.1, 0.74), g, lx)
            out["gated g%.2f l%.1f rrf f0.74" % (g, lx)] = gated(rrf(0.74), g, lx)
    return out


def evaluate(items, corpus, dense, ranker, cfg=replay.Config()):
    r = replay.evaluate(items, corpus, cfg, lambda q, c, cf: ranker(q, c, cf, dense.get(c["id"], [])))
    r["fiction"] = len(kinds.fiction_intrusions(items, r["chosen"]))
    return r


def domains(items, r):
    """recall@4 (location or quote) per dataset group, answerable only."""
    out = {}
    for id, top in r["chosen"].items():
        it = items[id]
        if not it["answerable"]:
            continue
        g = out.setdefault(it["group"], [0, 0])
        g[1] += 1
        g[0] += any(matches(p, it["answers"]) for p in top) or replay.quote_hit(top, it["answers"])
    return out


def cmd_sweep(a):
    items, corpus, dense = load(a.run)
    base = evaluate(items, corpus, dense, keyword_only())
    print("config\tlocation\tquote\tempty\tfiction\tkeeps empty")
    for name, ranker in configs().items():
        if a.only and not name.startswith(a.only):
            continue
        r = evaluate(items, corpus, dense, ranker)
        print("%s\t%d/%d\t%d\t%d/%d\t%d\t%s" % (name, r["location"], r["answerable"], r["quote"], r["empty"], r["unanswerable"],
                                              r["fiction"], "yes" if r["empty"] >= base["empty"] else "no"))
    return 0


def cmd_show(a):
    items, corpus, dense = load(a.run)
    r = evaluate(items, corpus, dense, configs()[a.name])
    print(replay.summary(r), "; fiction intrusions %d" % r["fiction"])
    for g, (hit, n) in sorted(domains(items, r).items()):
        print("  %s %d/%d" % (g, hit, n))
    return 0


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    s = p.add_subparsers(dest="cmd", required=True)
    c = s.add_parser("sweep"); c.add_argument("run"); c.add_argument("--only"); c.set_defaults(f=cmd_sweep)
    c = s.add_parser("show"); c.add_argument("run"); c.add_argument("name"); c.set_defaults(f=cmd_show)
    a = p.parse_args(argv)
    return a.f(a)


if __name__ == "__main__":
    sys.exit(main())
