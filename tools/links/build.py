#!/usr/bin/env python3
"""Plan LL1 Task 7: a `.glink` per Classic (the Mac only; spaCy + the language's KB from kb.py).

  build.py OUT_DIR EPUB… [--books MANIFEST…] [--kb-dir DIR] [--qids tools/links/classics-qid.json]
           [--ner-dir DIR] [--releases tools/sidecar/releases]

For each EPUB that a Classics release manifest lists (by file name; --books, default both of the pack builder's
`*_books.json`) with that sha256, in the book's language (kb-<lang>.sqlite in --kb-dir): entity spans (ner.py; cached per EPUB in --ner-dir, the slow step), candidates (link.py's rules),
then Light and More (rules.py). Writes OUT_DIR/<epub stem>.glink, a sidecar (tools/sidecar/meta.py) per book, as the
books' vectors are, so it comes and goes with its book:

  meta      the sidecar contract (kind glink, schemaVersion 1, sourceZim = the EPUB's name, sourceSha256 = its sha256)
  book      id, qid (the work, from classics_qid.py; NULL if none), year (the cut-off for "born after the book")
  mentions  book, href, start, len, quote, before, qid, tier (1 Light, 2 More only), conf (1 - second candidate's
            sitelinks / the pick's), indexed on qid for "In your books" (plan Decision 4)

With --releases, writes each release descriptor there (<book id>.glink.json, for catalog_gen.py sidecars).
"""
import argparse
import collections
import json
import sqlite3
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE)); sys.path.insert(0, str(HERE.parent / "sidecar"))
import meta  # noqa: E402
from link import KB, candidate_rows  # noqa: E402
from rules import select  # noqa: E402

KIND, SCHEMA, BUILDER, BUILDER_VERSION = "glink", 1, "tools/links/build.py", 1
LIGHT_MIN_SL = 40   # the spike's floor (plan Task 6)


def tiers(rows):
    """Light and More picks, one row per mention, More-only ones at tier 2."""
    light = {(r["href"], r["start"]) for r in select(rows, "light", min_sl=LIGHT_MIN_SL, min_sim=0)}
    out = []
    for r in select(rows, "more", min_sl=LIGHT_MIN_SL, min_sim=0):
        alts = r.get("alts") or []
        conf = round(1 - (alts[0][2] / r["sl"] if alts and r["sl"] else 0), 3)
        out.append(dict(r, tier=1 if (r["href"], r["start"]) in light else 2, conf=conf))
    return out


def write_glink(path, epub, sha, book_id, qid, year, mentions):
    path = Path(path)
    tmp = path.with_suffix(".glink.tmp")
    tmp.unlink(missing_ok=True)
    db = sqlite3.connect(tmp)
    meta.write_meta(db, KIND, SCHEMA, epub, BUILDER, BUILDER_VERSION, source_sha256=sha)
    db.execute("CREATE TABLE book(id TEXT NOT NULL, qid INTEGER, year INTEGER)")
    db.execute("INSERT INTO book VALUES (?, ?, ?)", (book_id, qid, year))
    db.execute("CREATE TABLE mentions(book TEXT NOT NULL, href TEXT NOT NULL, start INTEGER NOT NULL, len INTEGER NOT NULL, "
               "quote TEXT NOT NULL, before TEXT NOT NULL, qid INTEGER NOT NULL, tier INTEGER NOT NULL, conf REAL NOT NULL)")
    db.executemany("INSERT INTO mentions VALUES (?,?,?,?,?,?,?,?,?)",
                   [(book_id, m["href"], m["start"], m["len"], m["quote"], m["before"], m["qid"], m["tier"], m["conf"])
                    for m in sorted(mentions, key=lambda m: (m["href"], m["start"]))])
    db.execute("CREATE INDEX mentions_qid ON mentions(qid)")
    db.execute("CREATE INDEX mentions_href ON mentions(href)")
    db.commit()
    db.execute("VACUUM")
    db.close()
    tmp.replace(path)
    return path


def ner_spans(nlp_holder, epub, sha, lang, ner_dir, book_id):
    """The book's spans, from the cache when the EPUB is unchanged."""
    import ner
    cache = Path(ner_dir) / f"{Path(epub).stem}.{sha[:12]}.ner.jsonl" if ner_dir else None
    if cache and cache.exists():
        return [json.loads(l) for l in open(cache, encoding="utf8")]
    if "nlp" not in nlp_holder:
        nlp_holder["nlp"] = ner.load(lang)
    spans = list(ner.spans(nlp_holder["nlp"], epub, book_id))
    if cache:
        cache.parent.mkdir(parents=True, exist_ok=True)
        part = cache.with_suffix(".part")
        with open(part, "w", encoding="utf8") as f:
            for s in spans:
                f.write(json.dumps(s, ensure_ascii=False) + "\n")
        part.replace(cache)
    return spans


MANIFESTS = [HERE.parent / "pack-builder" / "releases" / n
             for n in ("granger_classics_books.json", "clasicos_granger_es_books.json")]


def main(argv=None):
    a = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    a.add_argument("out"); a.add_argument("epubs", nargs="+")
    a.add_argument("--books", nargs="+", default=[str(m) for m in MANIFESTS])
    a.add_argument("--kb-dir", default="/Volumes/X10 Pro/granger-ll/ll1")
    a.add_argument("--qids", default=str(HERE / "classics-qid.json"))
    a.add_argument("--ner-dir", help="cache each book's entity spans here (NER is the slow step)")
    a.add_argument("--releases", help="write the release descriptors here")
    args = a.parse_args(argv)

    by_file = {b["urls"][0].rsplit("/", 1)[-1]: b for m in args.books for b in json.load(open(m, encoding="utf8"))["books"]}
    qids = json.load(open(args.qids, encoding="utf8")) if Path(args.qids).exists() else {}
    kbs, nlps, out = {}, {}, Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    totals = collections.Counter()
    epubs = sorted(Path(e) for e in args.epubs)
    for i, epub in enumerate(epubs, 1):
        b = by_file.get(epub.name)
        if b is None:
            print(f"[{i}/{len(epubs)}] {epub.name}: no Classics manifest lists it, skipped", file=sys.stderr)
            totals["unlisted"] += 1
            continue
        lang = b["language"]
        if lang not in kbs:
            kbs[lang], nlps[lang] = KB(Path(args.kb_dir) / f"kb-{lang}.sqlite"), {}
        kb, nlp = kbs[lang], nlps[lang]
        sha = meta.sha256(epub)
        if sha != b["sha256"]:
            print(f"[{i}/{len(epubs)}] {b['id']}: {epub.name} isn't the manifest's file, skipped", file=sys.stderr)
            totals["wrong-file"] += 1
            continue
        t0 = time.time()
        info = qids.get(b["id"]) or {}
        year = info.get("year", b.get("published"))
        spans = ner_spans(nlp, epub, sha, lang, args.ner_dir, b["id"])
        rows, _ = candidate_rows(kb, epub, spans, lang, year)
        mentions = tiers(rows)
        path = write_glink(out / f"{epub.stem}.{KIND}", epub, sha, b["id"], info.get("qid"), year, mentions)
        if args.releases:
            Path(args.releases).mkdir(parents=True, exist_ok=True)
            (Path(args.releases) / f"{b['id']}.{KIND}.json").write_text(
                json.dumps(meta.descriptor(path, b["id"], "book"), indent=1) + "\n")
        n1 = sum(m["tier"] == 1 for m in mentions)
        totals["books"] += 1; totals["light"] += n1; totals["more"] += len(mentions) - n1
        print(f"[{i}/{len(epubs)}] {b['id']}: {len(spans):,} spans, {n1:,} Light + {len(mentions) - n1:,} More, "
              f"{path.stat().st_size // 1024:,} KB, {time.time() - t0:.0f} s", file=sys.stderr, flush=True)
    print(", ".join(f"{k} {v:,}" for k, v in totals.items()), file=sys.stderr)
    return 1 if totals["wrong-file"] or totals["unlisted"] else 0


if __name__ == "__main__":
    sys.exit(main())
