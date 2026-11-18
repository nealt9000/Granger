#!/usr/bin/env python3
"""Adult-content flags (roadmap, "Carried forward from EX"): one SQLite file of flags per language and item, for
tools/qidindex to copy into each .gqid.

  flags_build.py CLASSES OUT.sqlite --items en=items-enwiki.jsonl --items es=items-eswiki.jsonl \
      --top en=TOP.gqid --top es=TOP.gqid --review review-2026-11-18.json [--candidates OUT.jsonl]

The Top packs' candidates were reviewed by Neal one by one (review.json lists the unticked "lang:Q.." ids): a reviewed
item keeps its flag unless unticked; a TOPIC untick in either language clears the item in both. Everything outside
Top takes the rules: every TOPIC rule, and PICTURE only for sexual anatomy (rules.picture_outside_review).
Run with -I; reads the X10 work files (granger-ll/af0).
"""
import argparse
import datetime
import json
import sqlite3
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import rules  # noqa: E402

VERSION = 1


def decide(rows, unticked):
    """rows: candidate dicts (lang, q, t, kind, seed, top). Returns {(lang, qid int): kind}."""
    topic_keys = {f"{r['lang']}:{r['q']}" for r in rows if r["kind"] == rules.TOPIC and r["top"]}
    cleared = {u.split(":", 1)[1] for u in unticked if u in topic_keys}  # a TOPIC untick in one language clears both
    topic_qids = {r["q"] for r in rows if r["kind"] == rules.TOPIC and r["q"] not in cleared}
    langs = {r["lang"] for r in rows}
    out = {}
    for r in rows:
        key = (r["lang"], int(r["q"][1:]))
        if r["kind"] == rules.PICTURE and r["q"] not in topic_qids:
            if r["top"]:
                keep = f"{r['lang']}:{r['q']}" not in unticked
            else:
                keep = rules.picture_outside_review(r["seed"], r["t"])
            if keep:
                out[key] = rules.PICTURE
    for q in topic_qids:
        for lang in langs:
            out[(lang, int(q[1:]))] = rules.TOPIC
    return out


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("classes"); p.add_argument("out")
    p.add_argument("--items", action="append", required=True); p.add_argument("--top", action="append", required=True)
    p.add_argument("--review", required=True); p.add_argument("--candidates")
    a = p.parse_args(argv)
    items, tops = dict(x.split("=", 1) for x in a.items), dict(x.split("=", 1) for x in a.top)
    r = rules.Rules(rules.load_children(a.classes))
    rows = []
    for lang, path in items.items():
        top = {f"Q{q}" for (q,) in sqlite3.connect(f"file:{tops[lang]}?mode=ro", uri=True).execute("SELECT qid FROM articles")}
        with open(path) as f:
            for line in f:
                it = json.loads(line)
                c = r.classify(it)
                if c:
                    rows.append({"lang": lang, "q": it["q"], "t": it["t"], "sl": it.get("sl", 0), "kind": c[0],
                                 "seed": c[1], "why": c[2], "top": it["q"] in top})
    unticked = set(json.load(open(a.review))["unticked"])
    flags = decide(rows, unticked)
    if a.candidates:
        Path(a.candidates).write_text("\n".join(json.dumps(x, ensure_ascii=False) for x in rows) + "\n")
    out = Path(a.out)
    out.unlink(missing_ok=True)
    db = sqlite3.connect(out)
    db.executescript("""
        CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT NOT NULL);
        CREATE TABLE flags(lang TEXT NOT NULL, qid INTEGER NOT NULL, kind INTEGER NOT NULL, PRIMARY KEY(lang, qid)) WITHOUT ROWID;
    """)
    db.executemany("INSERT INTO flags VALUES (?, ?, ?)", ((l, q, k) for (l, q), k in sorted(flags.items())))
    db.executemany("INSERT INTO meta VALUES (?, ?)", [
        ("flagsVersion", str(VERSION)),
        ("builtAt", datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")),
        ("review", Path(a.review).name)])
    db.commit()
    for lang in sorted(items):
        n = dict(db.execute("SELECT kind, COUNT(*) FROM flags WHERE lang = ? GROUP BY kind", (lang,)).fetchall())
        print(f"{lang}: {n.get(1, 0):,} topics, {n.get(2, 0):,} pictures")
    db.close()


if __name__ == "__main__":
    main()
