#!/usr/bin/env python3
"""What does a reader do when the passage holding the answer is certainly in its prompt? (Ask R&D, 2026-11-06.)

  ceiling.py evidence BASE                  # runs/BASE/ceiling-evidence.json: the text holding each answerable item's quote(s)
  ceiling.py variant BASE NEW --size N [--place first|last]
                                            # runs/NEW: BASE's prompts with that evidence forced in, filled to N passages
  answerers.py run NEW NEW-e2b --model … --backend cpu   # replays it

This is a ceiling, not a retrieval method: it uses the dataset's answers, which nothing on a phone can know. It says how
far perfect targeting could take a reader, before anything is built to approach it.

- **Evidence:** a single-fact item needs one of its alternative quotes, a multi-step item all of them. Each is looked for in
  BASE's recorded hits (the chunk the phone could really find), then in the answer's own ZIM entry or EPUB chapter.
- **Text:** the app's cut (1,250 characters at a word) unless that loses the quote; then a window of that size around it.
- **Fill:** the rest of the N places come from BASE's chosen passages in order, skipping any that hold the answer. A
  multi-step item with more facts than N gets a place for each fact.
- **Unanswerable items:** BASE's prompts cut to their first N passages; the gate's declines stay declines.
- **Dropped:** the "Dates in the passages" block some prompts carry (4 on dev): it cites passage numbers that change here.
"""
import argparse
import html
import json
import posixpath
import re
import shutil
import sys
import xml.etree.ElementTree as ET
import zipfile
from dataclasses import replace
from pathlib import Path

import answerers
import coverage
import merge
import passages
import replay

HERE = Path(__file__).resolve().parent
RUNS = HERE / "runs"
PACKS = Path.home() / ".cache/granger/eval-packs"
BOOKS = Path.home() / ".cache/granger/eval-books/by-ref"
TEXT_CHARS = 1250
EVIDENCE = "ceiling-evidence.json"
FACTS = "\n\nDates in the passages:"


def fold_with_map(text):
    """coverage.fold's result, and for each of its characters the index in `text` it came from."""
    out, where = [], []
    table = {"’": "'", "‘": "'", "“": '"', "”": '"', "−": "-"}
    for i, ch in enumerate(text):
        c = table.get(ch, ch)
        if "‐" <= c <= "―":
            c = "-"
        c = c.lower()
        if c.isspace():
            if out and out[-1] != " ":
                out.append(" ")
                where.append(i)
            continue
        for x in c:
            out.append(x)
            where.append(i)
    while out and out[-1] == " ":
        out.pop()
        where.pop()
    return "".join(out), where


def holds(text, quote):
    q = coverage.fold(quote)
    return bool(q) and q in coverage.fold(text)


def cut(text, max_chars=TEXT_CHARS):
    if len(text) <= max_chars:
        return text
    head = text[:max_chars]
    space = max((i for i, c in enumerate(head) if c.isspace()), default=0)
    return (head[:space] if space > 0 else head).rstrip() + "…"


def window(text, quote, max_chars=TEXT_CHARS):
    """The app's cut when the quote survives it; otherwise about max_chars around the quote, cut at words."""
    if holds(cut(text, max_chars), quote):
        return cut(text, max_chars)
    folded, where = fold_with_map(text)
    at = folded.find(coverage.fold(quote))
    start, end = where[at], where[at + len(coverage.fold(quote)) - 1] + 1
    spare = max(0, max_chars - (end - start))
    a = max(0, start - spare // 2)
    b = min(len(text), a + max(max_chars, end - start))
    a = max(0, b - max(max_chars, end - start))
    if a > 0:
        space = text.find(" ", a, start)
        a = space + 1 if space >= 0 else a
    if b < len(text):
        space = text.rfind(" ", end, b)
        b = space if space >= 0 else b
    return ("…" if a > 0 else "") + text[a:b].strip() + ("…" if b < len(text) else "")


def strip_markup(markup):
    markup = re.sub(r"<(script|style)\b.*?</\1>", " ", markup, flags=re.S | re.I)
    text = html.unescape(re.sub(r"<[^>]+>", " ", markup)).replace("\xa0", " ")
    text = re.sub(r"\[\s*(\d+|[a-z]|edit|citation needed|note \d+)\s*\]", "", text)  # footnote marks: the app's passages have none
    text = re.sub(r"[ \t]+", " ", text)
    return re.sub(r" ?\n\s*", "\n", text).strip()


class Library:
    """The answer's own text, read from the ZIMs and EPUBs the dataset is validated against."""

    def __init__(self):
        self.archives = {}

    def zim(self, source, location):
        from libzim.reader import Archive
        f = PACKS / (source + ".zim")
        if not f.exists():
            return None
        arc = self.archives.setdefault(f, Archive(str(f)))
        bare = location.split("#", 1)[0]
        for path in (bare, "A/" + bare):
            try:
                if arc.has_entry_by_path(path):
                    e = arc.get_entry_by_path(path)
                    item = e.get_item()
                    return e.title, strip_markup(bytes(item.content).decode("utf-8", "replace"))
            except (RuntimeError, KeyError):
                pass
        return None

    def epub(self, source, location, quote):
        stem = source.split(":", 2)[2] if source.count(":") == 2 else source
        f = BOOKS / (stem + ".epub")
        if not f.exists():
            return None
        with zipfile.ZipFile(f) as z:
            opf = ET.fromstring(z.read("META-INF/container.xml")).find(".//{*}rootfile").get("full-path")
            root = ET.fromstring(z.read(opf))
            title = next((e.text for e in root.iter() if e.tag.endswith("}title") and e.text), stem)
            manifest = {m.get("id"): m.get("href") for m in root.iter() if m.tag.endswith("}item") or m.tag == "item"}
            base = posixpath.dirname(opf)
            hrefs = [manifest[r.get("idref")] for r in root.iter() if r.tag.endswith("itemref") and r.get("idref") in manifest]
            for h in (h for h in hrefs if location.lower() in h.lower()):
                body = strip_markup(z.read(posixpath.normpath(posixpath.join(base, h))).decode("utf-8", "replace"))
                if holds(body, quote):
                    return title, body
        return None

    def find(self, answer):
        src, loc, quote = answer.get("source") or "", answer.get("location") or "", answer.get("quote") or ""
        got = self.epub(src, loc, quote) if src.startswith("book:") else self.zim(src, loc) if loc else None
        return got if got and holds(got[1], quote) else None


def suffixes(records):
    """Each source's header after the title, read off the prompts the app built: "(2026-09-14) · Wikipedia"."""
    out = {}
    for rec in records:
        p = answerers.answer_prompt(rec)
        if p is None:
            continue
        _, ps, _ = passages.split_prompt(p)
        for c, (h, _) in zip(rec.get("chosen") or [], ps):
            title, heading = c.get("title") or "", c.get("heading") or ""
            if h.startswith(title):
                s = h[len(title):]
                if heading and s.endswith(" · " + heading):
                    s = s[: -len(" · " + heading)]
                out.setdefault(c["source"], s)
    return out


def header(p, suffix):
    h = (p.get("title") or "none") + suffix.get(p["source"], "")
    if p.get("heading") and p["heading"] != p.get("title"):
        h += " · " + p["heading"]
    return h


def cmd_evidence(a):
    items, corpus, dense, records = merge.load(a.base, with_records=True)
    recs = answerers.read_jsonl(RUNS / a.base / "results.jsonl")
    suffix = suffixes(recs)
    lib = Library()
    ranker = merge.hybrid(0.1, 0.74)
    cfg = replace(replay.Config(), small=48, large=48, per_document=99)
    out, stats = {}, {"hits": 0, "library": 0, "missing": 0}
    for id, it in items.items():
        if not it["answerable"]:
            continue
        c = corpus.get(id) or {"passages": [], "keywords": [], "id": id}
        hits = dense.get(id, [])
        pool = ranker(it["question"], c, cfg, hits) + c["passages"] + hits  # the ranker's order, then all the rest
        found = []
        for ans in it["answers"]:
            hit = next((p for p in pool if holds(p.get("text"), ans.get("quote"))), None)
            if hit:
                found.append(dict(via="hits", answer=ans, source=hit["source"], title=hit.get("title"), location=hit.get("location"),
                                  heading=hit.get("heading") or "", header=header(hit, suffix), text=window(hit["text"], ans["quote"])))
                continue
            got = lib.find(ans)
            if got:
                p = dict(source=ans["source"], title=got[0], location=ans.get("location"), heading="")
                found.append(dict(via="library", answer=ans, header=header(p, suffix), text=window(got[1], ans["quote"]), **p))
        multi = bool(it.get("multiStep"))
        if multi:
            ok = len(found) == len(it["answers"])
            chosen = found if ok else []
        else:
            ok = bool(found)
            hit = [f for f in found if f["via"] == "hits"]
            chosen = (hit or found)[:1]
        out[id] = dict(evidence=chosen, complete=ok, parts=len(it["answers"]) if multi else 1)
        stats["missing" if not ok else ("hits" if all(e["via"] == "hits" for e in chosen) else "library")] += 1
    (RUNS / a.base / EVIDENCE).write_text(json.dumps(out, ensure_ascii=False, indent=1), encoding="utf-8")
    print(json.dumps(stats))
    missing = [i for i, e in out.items() if not e["complete"]]
    print("missing:", " ".join(missing))
    return 0


def template(recs, medical):
    """A recorded head for an item the gate left without a prompt, medical line and all."""
    for rec in recs:
        p = answerers.answer_prompt(rec)
        if p is None:
            continue
        head, _, tail = passages.split_prompt(p)
        if ("medical question" in head) == medical and "Earlier in this" not in head:
            return head
    raise SystemExit("no template head")


def cmd_variant(a):
    base = answerers.RUNS / a.base
    items = {i["id"]: i for i in json.loads((base / "dataset.json").read_text(encoding="utf-8"))}
    evidence = json.loads((base / EVIDENCE).read_text(encoding="utf-8"))
    recs = answerers.read_jsonl(base / "results.jsonl")
    out_dir = RUNS / a.new
    out_dir.mkdir(parents=True, exist_ok=True)
    _, corpus, dense = merge.load(a.base)
    ranker, cfg, suffix = merge.hybrid(0.1, 0.74), replay.Config(), suffixes(recs)
    out, forced = [], 0
    for rec in recs:
        rec = {k: v for k, v in rec.items() if k not in ("deeper", "guesses")}
        id, it = rec["id"], items[rec["id"]]
        before = answerers.answer_prompt(rec)
        if not it["answerable"]:
            if before is not None:
                head, ps, tail = passages.split_prompt(before)
                ps = [(h, b.split(FACTS)[0]) for h, b in ps[: a.size]]
                passages.set_prompt(rec, passages.join_prompt(head, ps, tail))
                rec["chosen"] = (rec.get("chosen") or [])[: a.size]
            out.append(rec)
            continue
        e = evidence[id]
        if not e["complete"]:
            continue  # not in the ceiling's denominator; reported apart
        if before is not None:
            head, ps, tail = passages.split_prompt(before)
            ps = [(h, b.split(FACTS)[0]) for h, b in ps]
        else:
            # The gate left this item no prompt: the fill is what the ranker would have chosen without the gate.
            head = template(recs, bool(it.get("medical")))
            top = ranker(it["question"], corpus[id], cfg, dense.get(id, [])) if id in corpus else []
            ps = [(header(p, suffix), cut(p.get("text") or "")) for p in top]
            rec["chosen"] = top
            tail = "Question: %s\nAnswer:" % it["question"]
            rec["prompts"] = (rec.get("prompts") or []) + [{"stage": "answer", "prompt": ""}]
        chosen_in = list(zip(ps, (rec.get("chosen") or []) + [None] * len(ps)))
        quotes = [x.get("quote") for x in it["answers"]]
        fills = [(pb, c) for pb, c in chosen_in if not any(holds(pb[1], q) for q in quotes)]
        gold = [((x["header"], x["text"]), {k: x[k] for k in ("source", "title", "location", "heading", "text")}) for x in e["evidence"]]
        room = max(a.size - len(gold), 0)
        fill = fills[:room]
        final = gold + fill if a.place == "first" else fill + gold
        passages.set_prompt(rec, passages.join_prompt(head, [pb for pb, _ in final], tail))
        rec["chosen"] = [dict(c) if c else {"source": "?", "text": pb[1]} for pb, c in final]
        forced += 1
        out.append(rec)
    shutil.copy(base / "dataset.json", out_dir / "dataset.json")
    answerers.write_jsonl(out_dir / "results.jsonl", out)
    (out_dir / "variant.json").write_text(json.dumps({"from": a.base, "ceiling": a.size, "place": a.place}), encoding="utf-8")
    print(json.dumps({"variant": a.new, "forced": forced, "records": len(out)}))
    return 0


def main(argv=None):
    ap = argparse.ArgumentParser()
    s = ap.add_subparsers(dest="cmd", required=True)
    e = s.add_parser("evidence"); e.add_argument("base"); e.set_defaults(f=cmd_evidence)
    v = s.add_parser("variant"); v.add_argument("base"); v.add_argument("new"); v.add_argument("--size", type=int, required=True)
    v.add_argument("--place", default="first", choices=["first", "last"]); v.set_defaults(f=cmd_variant)
    a = ap.parse_args(argv)
    return a.f(a)


if __name__ == "__main__":
    sys.exit(main())
