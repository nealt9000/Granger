#!/usr/bin/env python3
"""Source-kind weighting, offline (Plan R0, Task 3).

  kinds.py sweep RUN [--base lexical|rerank]

Fiction (Classics novels, drama, poetry, libretti) ranks the same as reference works on factual questions ("how far
away is the moon" picks Dracula). Here a fiction passage's score is multiplied by a weight below 1 unless the question
is a literature question. `order` uses the weighted score only for ordering (preselect and ranking), and the threshold
still applies to the raw score; `filter` thresholds the weighted score too. `--base rerank` weights Task 2's best
config (titled passages, search query, preselect 24, alpha 0.1, threshold 0.59; needs numpy and its vectors).
"""
import argparse
import json
import sys
from dataclasses import replace
from pathlib import Path

import replay
from metrics import matches

MANIFEST = Path(__file__).resolve().parents[1] / "pack-builder" / "classics" / "manifest.json"
FICTION_SECTIONS = {"novels", "drama", "poetry", "libretti"}
# archive.org books in the recorded library, by hand.
ARCHIVE = {"book:archive-org:goodytwoshoes00newyiala": "fiction",
           "book:archive-org:TheCompleteBookOfEnochStandardEnglishVersionJayWinter": "scripture"}
# Words that make a question about literature, so fiction keeps its full weight.
LITERARY = {
    "novel", "novels", "story", "stories", "tale", "tales", "poem", "poems", "poetry", "poet", "play", "plays", "playwright",
    "character", "characters", "chapter", "scene", "act", "stanza", "line", "lines", "quote", "quotes", "say", "says",
    "said", "narrator", "hero", "heroine", "fiction", "author", "wrote", "written", "book", "books", "literature",
    "opera", "libretto", "verse", "verses",
}


def sections():
    m = json.loads(MANIFEST.read_text(encoding="utf-8"))
    return {"book:granger:gc-" + w["slug"]: w["section"] for w in m["works"]}


SECTIONS = sections()


def kind(p):
    """reference, scripture, philosophy, fiction, media or other."""
    if p["kind"] in ("ARTICLE", "CARD"):
        return "reference"
    if p["kind"] == "MEDIA":
        return "media"
    s = SECTIONS.get(p["source"]) or ARCHIVE.get(p["source"])
    if s in FICTION_SECTIONS or s == "fiction":
        return "fiction"
    return s or "other"


def literary(question, keywords, passages):
    """A literature cue word in the question, or a keyword naming a fiction work the search found."""
    if any(w.lower() in LITERARY for w in replay.words(question)):
        return True
    titles = [{w.lower() for w in replay.words(p.get("title") or "")} for p in passages if kind(p) == "fiction"]
    for k in keywords:
        ws = [w.lower() for w in replay.words(k) if w.lower() not in replay.STOPWORDS]
        if ws and any(all(w in t for w in ws) for t in titles):
            return True
    return False


def lexical_ranker(weight, mode):
    """The app's ranking with fiction weighted on non-literature questions."""
    def rank(question, corpus, cfg):
        kw, ps = corpus["keywords"], corpus["passages"]
        raw = replay.scorer(kw)
        w = 1.0 if literary(question, kw, ps) else weight

        def weighted(p):
            return raw(p) * (w if kind(p) == "fiction" else 1.0)
        asked = replay.media_asked(question)
        found = replay.preselect(ps, kw, cfg, score=weighted)
        gate = weighted if mode == "filter" else raw
        kept = [p for p in sorted(found, key=lambda p: -weighted(p)) if gate(p) >= cfg.lexical_threshold]
        return replay.cap_media(replay.lift_media(kept, found, asked), asked, cfg)[:cfg.large][:cfg.small]
    return rank


def rerank_ranker(vectors, weight, mode, n=24, alpha=0.1, threshold=0.59):
    """Task 2's best config with fiction weighted on non-literature questions (preselect stays lexical, as there)."""
    def rank(question, corpus, cfg):
        kw, ps = corpus["keywords"], corpus["passages"]
        lex = replay.scorer(kw)
        w = 1.0 if literary(question, kw, ps) else weight
        asked = replay.media_asked(question)
        found = replay.preselect(ps, kw, replace(cfg, preselect=n))
        scored = []
        for p in found:
            s = alpha * lex(p) + (1 - alpha) * vectors.cos(question, p)
            scored.append((p, s * (w if kind(p) == "fiction" else 1.0), s))
        scored.sort(key=lambda x: -x[1])
        kept = [p for p, ws, s in scored if (ws if mode == "filter" else s) >= threshold]
        return replay.cap_media(replay.lift_media(kept, found, asked), asked, cfg)[:cfg.large][:cfg.small]
    return rank


def fiction_intrusions(items, chosen):
    """Answerable non-literature questions with a fiction passage chosen and no answer passage among those chosen."""
    out = []
    for id, top in chosen.items():
        item = items[id]
        if item["answerable"] and item["group"] not in ("literature", "who-wrote", "words") and any(kind(p) == "fiction" for p in top):
            out.append(id)
    return out


def breakdown(items, r, groups=("literature", "who-wrote", "words", "science", "multi-step", "knots", "bible", "vocab-gap")):
    cells = []
    for g in groups:
        ids = [id for id in r["chosen"] if items[id]["group"] == g and items[id]["answerable"]]
        hit = sum(any(matches(p, items[id]["answers"]) for p in r["chosen"][id]) or replay.quote_hit(r["chosen"][id], items[id]["answers"]) for id in ids)
        cells.append("%s %d/%d" % (g, hit, len(ids)))
    return ", ".join(cells)


def sweep(run, base):
    _, items, corpus, _ = replay.load(run)
    cfg = replay.CONFIGS["baseline"]
    lit = sum(literary(items[id]["question"], c["keywords"], c["passages"]) for id, c in corpus.items())
    print("literature questions by the rule: %d of %d" % (lit, len(corpus)))
    if base == "rerank":
        import rerank
        v = rerank.Vectors(replay.RUNS / run, "titled", "search")
        make = lambda w, mode: rerank_ranker(v, w, mode)
    else:
        make = lexical_ranker
    for mode in ("order", "filter"):
        for w in (1.0, 0.9, 0.8, 0.7, 0.5, 0.3, 0.0):
            r = replay.evaluate(items, corpus, cfg, make(w, mode))
            print("%s %s w=%.1f: %s; fiction on factual questions %d" % (
                base, mode, w, replay.summary(r), len(fiction_intrusions(items, r["chosen"]))))
            print("    by group (answer found among the 4): %s" % breakdown(items, r))


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    s = p.add_subparsers(dest="cmd", required=True)
    c = s.add_parser("sweep"); c.add_argument("run"); c.add_argument("--base", default="lexical", choices=["lexical", "rerank"])
    a = p.parse_args(argv)
    sweep(a.run, a.base)
    return 0


if __name__ == "__main__":
    sys.exit(main())
