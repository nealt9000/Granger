#!/usr/bin/env python3
"""Plan LL1 Task 7: each Classic's Wikidata work and the year it was written, decided on the Mac (LL0 finding: P2034 is
rare on work items, so the phone can't look it up).

  classics_qid.py BOOKS.json ITEMS.jsonl OUT.json      (wdfilter.py's items-enwiki / items-eswiki for the books' language)

A book matches a work by title (the manifest's, with its "(tr. …)" note and then its ": part" dropped, against the item's
article title, label or other-language title) and by author (the manifest author's surname in one of the item's P50
labels; a book with no author takes a work-like item, P50 or P577, of that title). The best-known match wins.

year: the work's earliest P577/P571 year. With no work, the author's death year when that is before the manifest's
`published` (which is the edition's or translation's year), else `published`. `how` says which.
The output is committed and reviewable (tools/links/classics-qid.json); build.py reads it.
"""
import argparse
import collections
import json
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from kb import norm  # noqa: E402

ARTICLES = ("the ", "a ", "an ", "el ", "la ", "los ", "las ", "lo ", "un ", "una ")


def strip_article(n):
    for a in ARTICLES:
        if n.startswith(a):
            return n[len(a):]
    return n


def title_keys(title):
    """Normalised titles to try, best first: the whole title, then without its note, then without its part."""
    t = re.sub(r"\s*\([^)]*\)\s*$", "", title).strip()
    keys = [title, t]
    if ":" in t:
        keys += [t.split(":")[0], t.split(":", 1)[1]]
    out = []
    for k in keys:
        n = strip_article(norm(k))
        if n and n not in out:
            out.append(n)
    return out


def surname(author):
    """The first named author's name words after the first, normalised: any one of them in a P50 label will do
    ("Miguel de Cervantes Saavedra" -> {"cervantes", "saavedra"}; "Erwin W. Roessler y Alfred Remy" -> {"roessler"})."""
    a = re.split(r"\s+(?:and|y|&)\s+|,", author or "")[0].strip()
    a = re.sub(r"\s*\(.*\)", "", a)
    words = [w for w in norm(a).split() if w not in ("de", "del", "la", "y", "von", "van", "da") and len(w) > 2]
    return set(words[1:] or words)


def year_of(item, props=("P577", "P571")):
    ys = []
    for p in props:
        for v in item.get(p, []):
            m = re.match(r"([+-])(\d+)-", v[0])
            if m:
                ys.append(int(m.group(2)) * (-1 if m.group(1) == "-" else 1))
    return min(ys) if ys else None


def item_keys(it):
    keys = {strip_article(norm(re.sub(r"_\(.*\)$", "", (it.get("t") or "")).replace("_", " "))),
            strip_article(norm((it.get("l") or "")))}
    for other in ("en", "es"):
        if it.get(other):
            keys.add(strip_article(norm(re.sub(r"\s*\(.*\)$", "", it[other]))))
    return keys - {""}


def match(books, lines):
    wanted = collections.defaultdict(set)     # title key -> book ids
    surnames = {b["id"]: surname(b.get("author")) for b in books}
    titles = {b["id"]: title_keys(b["title"]) for b in books}
    for b in books:
        for k in title_keys(b["title"]):
            wanted[k].add(b["id"])
    works = collections.defaultdict(list)     # book id -> [(rank of title key, item)]
    humans = {}                                # qid -> (label, death year)
    authors_named = {norm(b.get("author") or ""): [] for b in books} ; authors_named.pop("", None)
    for line in lines:
        it = json.loads(line)
        if "Q5" in it.get("P31", ()):
            humans[it["q"]] = ((it.get("l") or ""), year_of(it, ("P570",)))
            if norm((it.get("l") or "")) in authors_named and humans[it["q"]][1] is not None:
                authors_named[norm(it["l"])].append(humans[it["q"]][1])
            continue
        if not (it.get("P50") or it.get("P577")):
            continue
        for ik in item_keys(it):
            words = ik.split()
            for n in range(len(words), 0, -1):          # the whole title, then its word prefixes ("don quijote" in
                k = " ".join(words[:n])                 # "don quijote de la mancha"), ranked after every exact key
                for bid in wanted.get(k, ()):
                    works[bid].append((titles[bid].index(k) + (0 if n == len(words) else 10), it))
    out = {}
    for b in books:
        sn = surnames[b["id"]]
        cands = []
        for rank, it in works.get(b["id"], []):
            if rank >= 10 and not sn:
                continue   # a prefix match needs the author to agree
            authors = [humans.get(q, ("", None))[0] for q in it.get("P50", [])]
            if sn and not any(sn & set(norm(a).split()) for a in authors):
                continue
            y = year_of(it)
            if y is not None and b.get("published") is not None and y > b["published"] + 2:
                continue   # dated after this very edition: an adaptation or a later edition, not the work
            cands.append((rank, -it.get("sl", 0), it))
        if cands:
            _, _, it = min(cands, key=lambda c: c[:2])
            y = year_of(it)
            out[b["id"]] = {"qid": int(it["q"][1:]), "label": (it.get("l") or ""), "year": y if y is not None else b.get("published"),
                            "how": "work" if y is not None else "work-no-date"}
            continue
        died = authors_named.get(norm(b.get("author") or ""), [])
        pub = b.get("published")
        if died and pub is not None and min(died) < pub:
            out[b["id"]] = {"qid": None, "label": "", "year": min(died), "how": "author-death"}
        else:
            out[b["id"]] = {"qid": None, "label": "", "year": pub, "how": "published"}
    return out


def main(argv=None):
    a = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    a.add_argument("books"); a.add_argument("items"); a.add_argument("out")
    args = a.parse_args(argv)
    books = json.load(open(args.books, encoding="utf8"))["books"]
    with open(args.items, encoding="utf8") as f:
        found = match(books, f)
    old = json.load(open(args.out, encoding="utf8")) if Path(args.out).exists() else {}
    old.update(found)
    with open(args.out, "w", encoding="utf8") as f:
        json.dump(dict(sorted(old.items())), f, ensure_ascii=False, indent=1)
        f.write("\n")
    print(f"{len(found)} books: " + ", ".join(f"{k} {v}" for k, v in collections.Counter(x["how"] for x in found.values()).most_common()),
          file=sys.stderr)


if __name__ == "__main__":
    sys.exit(main())
