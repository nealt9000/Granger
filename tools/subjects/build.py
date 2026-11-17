#!/usr/bin/env python3
"""Plan EX Task 1: every Wikipedia article's subjects, keyed by QID, for the QID indexes (`.gqid`) to carry.

  build.py CLASSES OUT.sqlite --gqid-dir DIR --items en=ITEMS_ENWIKI --items es=ITEMS_ESWIKI \
           --full en=GVEC,ZIM --full es=GVEC,ZIM

Every article of every `.gqid` in DIR gets subjects (assign.py): the whole method (rules, the neighbours' vote, the lead's
role words) for the packs named by --full (Top en and es), rules alone for the rest (no vectors there). One item has one
set of subjects whatever the pack or language: English Top first, then Spanish Top, then rules. Writes a small SQLite
file (subjects(qid, ord, subject), ord 0 the main subject, and a meta table) that tools/qidindex attaches to each .gqid.
Also each item's sub-category in its main subject (facets.py, facets.json): the rule, and for the packs with vectors the
neighbours' vote where the rule leaves none; a facets(qid, facet) table, facet as `subject.facet`, and the facet list
with its names in meta `facets`.
Run with ~/.venvs/granger-links/bin/python (numpy, libzim).
"""
import argparse
import collections
import datetime
import glob
import json
import os
import sqlite3
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from assign import HUMAN, Rules, article_vectors, full, load_classes, load_spec, path_of, read_items  # noqa: E402
from facets import Facets, load_facets  # noqa: E402

VERSION = 2


def gqid_paths(gqid_dir):
    """{language: set of article paths} over every .gqid in the folder (the language from wikipedia_<lang>_…)."""
    out = collections.defaultdict(set)
    for f in sorted(glob.glob(os.path.join(gqid_dir, "wikipedia_*.gqid"))):
        lang = os.path.basename(f).split("_")[1]
        db = sqlite3.connect(f"file:{f}?mode=ro", uri=True)
        out[lang].update(p for (p,) in db.execute("SELECT path FROM articles"))
        db.close()
    return out


def facets_meta(facet_spec):
    """The facet list the app shows, in order, with its names: {subject: [{id, en, es}]} as JSON."""
    return json.dumps({s: [{"id": f["id"], "en": f["en"], "es": f["es"]} for f in fs] for s, fs in facet_spec.items()},
                      ensure_ascii=False, separators=(",", ":"))


def build(classes, out, gqid_dir, items, fulls, log=print):
    t0 = time.time()
    parents, labels = load_classes(classes)
    spec, bad = load_spec(labels)
    if bad:
        raise SystemExit("LABEL MISMATCHES (fix subjects.json):\n  " + "\n  ".join(bad))
    rules = Rules(spec, parents, labels)
    facet_spec, fbad, weak = load_facets(spec)
    if fbad:
        raise SystemExit("FACET PROBLEMS (fix facets.json):\n  " + "\n  ".join(fbad))
    fx = Facets(facet_spec, parents, spec, weak)
    facet_of = {}   # qid -> "subject.facet"
    wanted = gqid_paths(gqid_dir)
    log(f"classes loaded, {sum(map(len, wanted.values())):,} pack articles ({', '.join(f'{k} {len(v):,}' for k, v in wanted.items())}) "
        f"[{time.time() - t0:.0f}s]")
    chosen = {}   # qid -> (subjects, how)
    counts = collections.Counter()
    for lang in ("en", "es"):
        if lang not in fulls:
            continue
        gvec, zim = fulls[lang]
        sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "vectors"))
        from related_spike import G
        pack = read_items(items[lang], set(G(gvec).keymap()))
        res = full(rules, pack, gvec, zim, lang)
        main = {t: r["main"] for t, r in res.items() if r["main"] and r["q"] not in chosen}
        for r in res.values():
            if r["main"] and r["q"] not in chosen:
                chosen[r["q"]] = (r["subjects"], f"full-{lang}")
                counts[f"full-{lang}"] += 1
        # Sub-categories: the rule, then the neighbours' vote where it leaves none (or only a catch-all).
        ruled = {t: fx.rule(pack[t], s) for t, s in main.items()}
        person = {t: HUMAN in pack[t].get("P31", []) for t in main}
        final = fx.final(ruled, main, person, article_vectors(G(gvec), main.keys()))
        for t, f in final.items():
            if f:
                facet_of[pack[t]["q"]] = f"{main[t]}.{f}"
        counts[f"facets-full-{lang}"] = sum(1 for f in final.values() if f)
        log(f"full {lang}: {len(res):,} articles, {counts[f'full-{lang}']:,} items [{time.time() - t0:.0f}s]")
    for lang in ("en", "es"):
        if lang not in items:
            continue
        want = wanted.get(lang, set())
        n = 0
        with open(items[lang], encoding="utf8") as f:
            for line in f:
                if path_of(line) not in want:
                    continue
                r = json.loads(line)
                if r["q"] in chosen:
                    continue
                n += 1
                main, subjects, mode = rules.rules_only(r)
                if main:
                    chosen[r["q"]] = (subjects, f"rules-{lang}")
                    counts[f"rules-{lang}"] += 1
                    f = fx.rule(r, main)
                    if f:
                        facet_of[r["q"]] = f"{main}.{f}"
                        counts[f"facets-rules-{lang}"] += 1
                else:
                    counts[f"none-{lang}" if mode != "dropped" else "dropped"] += 1
        log(f"rules {lang}: {n:,} items read [{time.time() - t0:.0f}s]")
    if os.path.exists(out):
        os.unlink(out)
    db = sqlite3.connect(out)
    db.execute("CREATE TABLE subjects(qid INTEGER NOT NULL, ord INTEGER NOT NULL, subject TEXT NOT NULL, PRIMARY KEY(qid, ord)) WITHOUT ROWID")
    db.execute("CREATE TABLE how(qid INTEGER PRIMARY KEY, how TEXT NOT NULL)")
    db.executemany("INSERT INTO subjects VALUES (?,?,?)",
                   ((int(q[1:]), i, s) for q, (subs, _) in chosen.items() for i, s in enumerate(subs)))
    db.executemany("INSERT INTO how VALUES (?,?)", ((int(q[1:]), h) for q, (_, h) in chosen.items()))
    db.execute("CREATE TABLE facets(qid INTEGER PRIMARY KEY, facet TEXT NOT NULL)")
    db.executemany("INSERT INTO facets VALUES (?,?)", ((int(q[1:]), f) for q, f in facet_of.items()))
    db.execute("CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT NOT NULL)")
    db.executemany("INSERT INTO meta VALUES (?,?)", [
        ("subjectsVersion", str(spec["version"])), ("builder", "tools/subjects/build.py"), ("builderVersion", str(VERSION)),
        ("builtAt", datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")),
        ("counts", json.dumps(counts, sort_keys=True)),
        ("facets", facets_meta(facet_spec))])
    db.commit()
    db.execute("VACUUM")
    db.close()
    log(f"wrote {out}: {len(chosen):,} items {dict(counts)} [{time.time() - t0:.0f}s]")
    return counts


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("classes"); p.add_argument("out"); p.add_argument("--gqid-dir", required=True)
    p.add_argument("--items", action="append", default=[], help="LANG=wdfilter items file")
    p.add_argument("--full", action="append", default=[], help="LANG=GVEC,ZIM: the pack with vectors for the whole method")
    a = p.parse_args(argv)
    items = dict(x.split("=", 1) for x in a.items)
    fulls = {k: tuple(v.split(",", 1)) for k, v in (x.split("=", 1) for x in a.full)}
    build(a.classes, a.out, a.gqid_dir, items, fulls, log=lambda m: print(m, file=sys.stderr, flush=True))


if __name__ == "__main__":
    main()
