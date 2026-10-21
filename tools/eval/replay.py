#!/usr/bin/env python3
"""Replays Research ranking offline over a run's recorded corpus (Plan R0, Task 1).

  replay.py check RUN                  # does the port choose what the phone chose? (the Task 1 gate)
  replay.py run RUN [--config NAME]    # recall@4 and empty rate; writes runs/RUN/replay-NAME.jsonl

A port of `Ranking` and `LexicalScore` (core/research) as the app runs them with the embedder off: preselect,
`byLexical`, `liftMedia`, `capMedia`, then the small model's 4. `found.jsonl` holds every passage the search found,
so a ranking idea can be scored here in seconds instead of in a phone run.
"""
import argparse
import json
import re
import sys
import unicodedata
from dataclasses import dataclass, replace
from pathlib import Path

from dataset import _norm
from metrics import matches

HERE = Path(__file__).resolve().parent
RUNS = HERE / "runs"


@dataclass(frozen=True)
class Config:
    preselect: int = 12
    per_document: int = 3
    lexical_threshold: float = 0.6
    small: int = 4
    large: int = 8
    media_cap: int = 1
    media_cap_asked: int = 2


CONFIGS = {"baseline": Config()}

# Keywords.kt
STOPWORDS = {
    "the", "and", "for", "are", "but", "not", "you", "all", "any", "can", "had", "her", "was", "one", "our",
    "out", "has", "have", "his", "how", "its", "who", "what", "when", "where", "which", "why", "whom", "whose",
    "this", "that", "these", "those", "with", "from", "into", "than", "then", "them", "they", "their", "there",
    "about", "does", "did", "doing", "done", "been", "being", "were", "will", "would", "could", "should",
    "may", "might", "must", "shall", "also", "just", "very", "some", "such", "own", "same", "too", "she", "him",
    "your", "yours", "mine", "ours", "theirs", "tell", "explain", "give", "show", "say", "said", "know",
    "get", "got", "make", "made", "much", "many", "more", "most", "other", "over", "under", "again", "once",
    "here", "each", "few", "both", "between", "through", "during", "before", "after", "above", "below", "off",
    "cant", "dont", "isnt", "doesnt", "didnt", "whats", "hows",
}
ALIASES = {"quran": "koran", "koran": "quran", "gita": "bhagavad"}
WORD = re.compile(r"[^\W_]+")  # Kotlin's [\p{L}\p{N}]+
TITLE_BONUS = 0.1
LEAD_BONUS = 0.3

# MediaCues.kt
CUES = {
    "music", "album", "albums", "song", "songs", "track", "tracks", "listen", "listening", "recording", "recordings",
    "record", "records", "soundtrack", "symphony", "composer", "composed", "band", "singer",
    "audiobook", "audiobooks", "narrator", "narrated",
}
PHRASES = [["read", "by"], ["do", "i", "have"], ["have", "i", "got"]]


def words(keyword):
    return WORD.findall(keyword)


def alternatives(word):
    w = word.lower()
    return {w, ALIASES[w]} if w in ALIASES else {w}


def _ascii_lower(s):
    # Kotlin's IGNORE_CASE without UNICODE_CASE folds ASCII only, so "É" doesn't match "é" on the phone either.
    return s.translate(_ASCII_LOWER)


_ASCII_LOWER = {c: c + 32 for c in range(ord("A"), ord("Z") + 1)}


def _whole_word(word):
    return re.compile(r"(?<![^\W_])" + re.escape(_ascii_lower(word)) + r"(?![^\W_])")


def is_lead(p):
    heading = (p.get("heading") or "").strip()
    return p["kind"] == "ARTICLE" and (not heading or heading.lower() == (p.get("title") or "").strip().lower())


def scorer(keywords):
    """LexicalScore.scorer: each keyword counts for the share of its words present; title and lead bonuses."""
    # Each keyword is a list of alternatives (`wine|strong drink`, or just itself), each a list of words.
    groups = []
    for k in keywords:
        alts = []
        for v in (x.strip() for x in k.split("|")):
            g = list(dict.fromkeys(w.lower() for w in words(v) if w.lower() not in STOPWORDS))
            if g:
                alts.append(g)
        if alts:
            groups.append(alts)
    if not groups:
        return lambda p: 0.0
    patterns = {w: _whole_word(w) for alts in groups for g in alts for w in g}

    def score(p):
        text = _ascii_lower("%s %s" % (p.get("heading") or "", p.get("text") or ""))
        present = {w for w, rx in patterns.items() if rx.search(text)}
        title_words = {w.lower() for w in words(p.get("title") or "")}
        in_title = any(all(alternatives(w) & title_words for w in g) for alts in groups for g in alts)
        # A keyword counts for its best alternative.
        found = sum(max(sum(1 for w in g if w in present) / len(g) for g in alts) for alts in groups)
        return found / len(groups) + (TITLE_BONUS if in_title else 0.0) + (LEAD_BONUS if found > 0 and is_lead(p) else 0.0)

    return score


def media_asked(question):
    folded = "".join(c for c in unicodedata.normalize("NFKD", question) if not unicodedata.category(c).startswith("M")).lower()
    ws = words(folded)
    return any(w in CUES for w in ws) or any(ws[i:i + len(ph)] == ph for ph in PHRASES for i in range(len(ws)))


def preselect(passages, keywords, cfg, score=None):
    score = score or scorer(keywords)
    scored = sorted(((p, score(p)) for p in passages), key=lambda x: (-x[1], -(x[0].get("searchScore") or 0.0)))
    seen, others, media, out = {}, 0, 0, []
    for p, _ in scored:
        is_media = p["kind"] == "MEDIA"
        if (media if is_media else others) >= cfg.preselect:
            continue
        key = (p["source"], p.get("location") or "")
        seen[key] = seen.get(key, 0) + 1
        if seen[key] <= cfg.per_document:
            out.append(p)
            if is_media:
                media += 1
            else:
                others += 1
    return out


def by_lexical(candidates, keywords, cfg):
    score = scorer(keywords)
    kept = [(p, s) for p, s in ((p, score(p)) for p in candidates) if s >= cfg.lexical_threshold]
    return [p for p, _ in sorted(kept, key=lambda x: -x[1])]


def lift_media(ranked, found, asked):
    if not asked:
        return ranked
    best = next((p for p in found if p["kind"] == "MEDIA"), None)
    if best is None:
        return ranked
    return [best] + [p for p in ranked if p is not best and key(p) != key(best)]


def cap_media(passages, asked, cfg):
    cap = cfg.media_cap_asked if asked else cfg.media_cap
    out, media = [], 0
    for p in passages:
        if p["kind"] == "MEDIA":
            media += 1
            if media > cap:
                continue
        out.append(p)
    return out


def rank(question, corpus, cfg):
    """The passages the small model's prompt would get, before AnswerPrompt.fit trims any for length."""
    asked = media_asked(question)
    found = preselect(corpus["passages"], corpus["keywords"], cfg)
    ranked = lift_media(by_lexical(found, corpus["keywords"], cfg), found, asked)
    return cap_media(ranked, asked, cfg)[:cfg.large][:cfg.small]


def key(p):
    return (p["kind"], p["source"], p.get("title") or "", p.get("location") or "", p.get("heading") or "", p.get("text") or "")


def agrees(chosen, top):
    """The phone's `chosen` is our top 4 with any trimmed from the end by AnswerPrompt.fit (it keeps at least one)."""
    if not chosen:
        return not top
    return [key(p) for p in chosen] == [key(p) for p in top[:len(chosen)]]


def quote_hit(passages, answers):
    texts = [_norm("%s %s" % (p.get("heading") or "", p.get("text") or "")) for p in passages]
    return any(_norm(a["quote"]) in t for a in answers if a.get("quote") for t in texts)


def load(run_id):
    d = RUNS / run_id
    items = {i["id"]: i for i in json.loads((d / "dataset.json").read_text(encoding="utf-8"))}
    corpus = {c["id"]: c for c in _jsonl(d / "found.jsonl")}
    records = {r["id"]: r for r in _jsonl(d / "results.jsonl")}
    # The finder is given every alternate of a `wine|strong drink` group on its own; ranking scores the groups, which
    # the result records keep. Without alternates the two lists are the same.
    for id, c in corpus.items():
        if records.get(id, {}).get("keywords"):
            c["keywords"] = records[id]["keywords"]
    return d, items, corpus, records


def _jsonl(path):
    return [json.loads(l) for l in path.read_text(encoding="utf-8").splitlines() if l.strip()]


def check(items, corpus, records, cfg):
    """Ids where the port and the phone disagree, with what each chose."""
    bad = []
    for id, rec in records.items():
        if rec.get("outcome") == "failed" or id not in corpus:
            continue
        top = rank(items[id]["question"], corpus[id], cfg)
        if not agrees(rec.get("chosen", []), top):
            bad.append((id, rec.get("chosen", []), top))
    return bad


def evaluate(items, corpus, cfg, ranker=rank):
    """recall@4 by location and by quote over answerable items, and the corpus ceiling; empty rate over unanswerable ones."""
    out = {"answerable": 0, "location": 0, "quote": 0, "inCorpus": 0, "unanswerable": 0, "empty": 0, "chosen": {}}
    for id, c in corpus.items():
        item = items[id]
        top = ranker(item["question"], c, cfg)
        out["chosen"][id] = top
        if item["answerable"]:
            out["answerable"] += 1
            out["location"] += any(matches(p, item["answers"]) for p in top)
            out["quote"] += quote_hit(top, item["answers"])
            # The most any reranking of this corpus could reach: an answer passage was found at all.
            out["inCorpus"] += any(matches(p, item["answers"]) for p in c["passages"]) or quote_hit(c["passages"], item["answers"])
        else:
            out["unanswerable"] += 1
            out["empty"] += not top
    return out


def summary(r):
    def pct(a, b):
        return "%d/%d (%.0f%%)" % (a, b, 100.0 * a / b) if b else "-"
    return "recall@4 location %s, quote %s (answer found at all %s); unanswerable empty %s" % (
        pct(r["location"], r["answerable"]), pct(r["quote"], r["answerable"]), pct(r["inCorpus"], r["answerable"]),
        pct(r["empty"], r["unanswerable"]))


def cmd_check(a):
    _, items, corpus, records = load(a.run)
    bad = check(items, corpus, records, CONFIGS[a.config])
    n = sum(1 for id, r in records.items() if r.get("outcome") != "failed" and id in corpus)
    for id, chosen, top in bad:
        print("%s\n  phone: %s\n  port:  %s" % (id, [(p["source"], p["location"]) for p in chosen], [(p["source"], p["location"]) for p in top]))
    print("%d of %d agree" % (n - len(bad), n))
    return 0


def cmd_run(a):
    d, items, corpus, _ = load(a.run)
    r = evaluate(items, corpus, CONFIGS[a.config])
    with (d / ("replay-%s.jsonl" % a.config)).open("w", encoding="utf-8") as f:
        for id, top in r["chosen"].items():
            f.write(json.dumps({"id": id, "chosen": top}, ensure_ascii=False) + "\n")
    print("%s: %s" % (a.config, summary(r)))
    return 0


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    s = p.add_subparsers(dest="cmd", required=True)
    for name, f in (("check", cmd_check), ("run", cmd_run)):
        c = s.add_parser(name); c.add_argument("run"); c.add_argument("--config", default="baseline", choices=sorted(CONFIGS)); c.set_defaults(f=f)
    a = p.parse_args(argv)
    return a.f(a)


if __name__ == "__main__":
    sys.exit(main())
