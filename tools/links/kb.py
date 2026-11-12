#!/usr/bin/env python3
"""Plan LL1 Task 6: the knowledge base the linker looks names up in (Mac only, not shipped).

  kb.py ITEMS.jsonl ZIM GQID OUT.sqlite [--min-sl 5]

item(qid, path, sl, label, cls, year): every Wikidata item with an article in the pack and at least --min-sl sitelinks, with a
coarse class from P31 (human, fictional, name, disambig, place, work, event, org, other) and a year (birth P569 for a
person, inception P571 otherwise), so a book never links someone born after it was written.
alias(norm, qid, kind, raw): every article title ('title') and every redirect ('redirect') in the ZIM, normalised by
[norm], with the title as printed (Spanish matches keep their accents).
"""
import argparse
import json
import re
import sqlite3
import sys
import unicodedata
from pathlib import Path

HUMAN = {"Q5"}
FICTIONAL = {"Q95074", "Q15632617", "Q3658341", "Q15773347", "Q15773317", "Q4271324", "Q1114461", "Q21070568",
             "Q115537581", "Q20085850", "Q17624054", "Q14897293", "Q2775969", "Q108065012"}
NAME = {"Q202444", "Q12308941", "Q11879590", "Q101352", "Q3409032", "Q29042997", "Q66480858", "Q4116295"}
DISAMBIG = {"Q4167410", "Q22808320", "Q15407973", "Q4167836"}
WORK = {"Q7725634", "Q571", "Q47461344", "Q8261", "Q25379", "Q5185279", "Q482994", "Q11424", "Q3305213",
        "Q2188189", "Q7366", "Q1004", "Q16970", "Q41298", "Q1344", "Q49084", "Q35760"}
EVENT = {"Q178561", "Q198", "Q645883", "Q1190554", "Q13418847", "Q180684", "Q1261499", "Q188055", "Q831663",
         "Q124734", "Q467011", "Q2001676"}
ORG = {"Q43229", "Q4830453", "Q783794", "Q891723", "Q7278", "Q2659904", "Q327333", "Q31855", "Q3918", "Q9174",
       "Q13414953", "Q1530022", "Q879146", "Q163740", "Q48204", "Q20746389"}


def norm(s: str) -> str:
    """Lowercase, accents and punctuation off, spaces collapsed, a leading article dropped."""
    s = unicodedata.normalize("NFKD", s.replace("_", " "))
    s = "".join(c for c in s if not unicodedata.combining(c)).lower()
    s = re.sub(r"[^\w\s]", " ", s)
    s = re.sub(r"\s+", " ", s).strip()
    return re.sub(r"^(the|el|la|los|las) ", "", s)


def cls_of(p31) -> str:
    p = set(p31 or ())
    for name, ids in (("disambig", DISAMBIG), ("fictional", FICTIONAL), ("name", NAME), ("human", HUMAN),
                      ("event", EVENT), ("work", WORK), ("org", ORG)):
        if p & ids:
            return name
    return "other"


def year_of(r):
    """The first year of P569 (born) or else P571 (founded); None without one."""
    for prop in ("P569", "P571"):
        for v in r.get(prop) or ():
            m = re.match(r"([+-]\d+)-", v[0] if isinstance(v, list) else str(v))
            if m:
                return int(m.group(1))
    return None


def main(argv=None):
    a = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    a.add_argument("items"); a.add_argument("zim"); a.add_argument("gqid"); a.add_argument("out")
    a.add_argument("--min-sl", type=int, default=5)
    args = a.parse_args(argv)
    from libzim.reader import Archive

    paths = dict(sqlite3.connect(f"file:{args.gqid}?mode=ro", uri=True).execute("SELECT path, qid FROM articles"))
    by_qid = {}
    with open(args.items, encoding="utf8") as f:
        for line in f:
            r = json.loads(line)
            q = int(r["q"][1:])
            if r.get("sl", 0) < args.min_sl or paths.get(r.get("t")) != q:
                continue
            c = "place" if "P625" in r and cls_of(r.get("P31")) == "other" else cls_of(r.get("P31"))
            by_qid[q] = (r["t"], r.get("sl", 0), r.get("l") or r["t"].replace("_", " "), c, year_of(r))
    print(f"{len(by_qid):,} items with >= {args.min_sl} sitelinks", file=sys.stderr)
    path_qid = {v[0]: q for q, v in by_qid.items()}

    out = Path(args.out); out.unlink(missing_ok=True)
    db = sqlite3.connect(out)
    db.execute("CREATE TABLE item(qid INTEGER PRIMARY KEY, path TEXT, sl INTEGER, label TEXT, cls TEXT, year INTEGER)")
    db.execute("CREATE TABLE alias(norm TEXT NOT NULL, qid INTEGER NOT NULL, kind TEXT NOT NULL, raw TEXT NOT NULL)")
    db.executemany("INSERT INTO item VALUES (?,?,?,?,?,?)", ((q, *v) for q, v in by_qid.items()))
    z = Archive(args.zim)
    entry = getattr(z, "_get_entry_by_id", None) or z.get_entry_by_id
    rows, n = [], 0
    for i in range(z.all_entry_count):
        e = entry(i)
        if e.path.startswith(("-/", "_", "I/", "M/", "X/")):
            continue
        if e.is_redirect:
            try:
                target = e.get_redirect_entry().path
            except Exception:
                continue
            kind = "redirect"
        else:
            target, kind = e.path, "title"
        q = path_qid.get(target)
        if q is not None:
            raw = (e.title or e.path).replace("_", " ")
            rows.append((norm(raw), q, kind, raw))
        if len(rows) >= 200_000:
            db.executemany("INSERT INTO alias VALUES (?,?,?,?)", rows); n += len(rows); rows = []
    db.executemany("INSERT INTO alias VALUES (?,?,?,?)", rows); n += len(rows)
    db.execute("CREATE INDEX alias_norm ON alias(norm)")
    db.commit(); db.close()
    print(f"{n:,} aliases", file=sys.stderr)


if __name__ == "__main__":
    sys.exit(main())
