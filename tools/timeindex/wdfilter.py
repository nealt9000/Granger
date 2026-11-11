#!/usr/bin/env python3
"""Plan H5b: cut the Wikidata dump down to the items whose article is in our packs (and, optionally, every class).

  gzcat wikidata-all.json.gz | wdfilter.py TITLES OUT_DIR [--site enwiki] [--no-classes] [--limit N]

TITLES holds one article path per line, as the ZIMs spell it (spaces as underscores). For each Wikidata item whose
--site sitelink is in TITLES, OUT_DIR/items-<site>.jsonl gets one line: qid, the path ("t"), sitelink count ("sl"),
English label, instance-of / occupation / genre / field / subclass-of, the dated properties, and coordinates. The
other language's title rides along ("es" on enwiki, "en" on eswiki) for a later cross-language join.

Unless --no-classes, OUT_DIR/classes.jsonl gets every class (any item with P279): qid, English label, P279 parents,
for mapping items to Timeline lanes (build.py). Classes don't depend on the language, so one run makes them for all.
The English run of 2026-11-07/08 used the first version of this script (items.jsonl, classes.jsonl on the X10).

Plan LL0 (Task 3): TITLES may be "-" for every item with a sitelink on the site (the QID index and LL1's link candidates
need all of Wikipedia, not only our packs), --site may name several sites ("enwiki,eswiki") so one pass over the dump
writes each site's file, and records carry the Linked Library's join properties (LINK_ITEM_PROPS, LINK_STRING_PROPS).
"""
import argparse
import json
import sys
import time

TIME_PROPS = ("P585", "P580", "P582", "P571", "P576", "P569", "P570", "P577")
ITEM_PROPS = ("P31", "P106", "P136", "P101", "P279")
OTHER = {"enwiki": ("eswiki", "es"), "eswiki": ("enwiki", "en")}
# The Linked Library's joins (spec §2, Plan LL0 Decision 3): author, notable work, edition of, main subject, composer, performer.
LINK_ITEM_PROPS = ("P50", "P800", "P629", "P921", "P86", "P175")
# Gutenberg ebook, LibriVox, MusicBrainz artist and work, archive.org identifiers.
LINK_STRING_PROPS = ("P2034", "P1899", "P434", "P435", "P724")


def best(claims, prop):
    """Values of a property's statements: preferred rank if any, else normal; deprecated dropped."""
    st = [c for c in claims.get(prop, []) if c.get("rank") != "deprecated" and c["mainsnak"].get("snaktype") == "value"]
    pref = [c for c in st if c.get("rank") == "preferred"]
    return [c["mainsnak"]["datavalue"]["value"] for c in (pref or st)]


def label(e):
    lab = e.get("labels", {})
    return (lab.get("en") or lab.get("mul") or {}).get("value")


def sitelink_title(line: str, key: str):
    """The sitelink title straight from the raw line (no JSON parse), spaces as underscores; None when absent."""
    i = line.find(key)
    if i < 0:
        return None
    j = k = i + len(key)
    while True:
        k = line.find('"', k)
        if line[k - 1] != "\\" or line[k - 2] == "\\":
            break
        k += 1
    return json.loads('"' + line[j:k] + '"').replace(" ", "_")


def record(e: dict, path: str, site: str) -> dict:
    claims = e.get("claims", {})
    rec = {"q": e["id"], "t": path, "sl": len(e.get("sitelinks", {})), "l": label(e)}
    other_site, other_key = OTHER.get(site, (None, None))
    other = e.get("sitelinks", {}).get(other_site) if other_site else None
    if other:
        rec[other_key] = other["title"]
    for p in ITEM_PROPS + LINK_ITEM_PROPS:
        v = [x["id"] for x in best(claims, p) if isinstance(x, dict) and "id" in x]
        if v:
            rec[p] = v
    for p in TIME_PROPS:
        v = [[x["time"], x["precision"], x["calendarmodel"].rsplit("/", 1)[-1]] for x in best(claims, p)
             if isinstance(x, dict) and "time" in x]
        if v:
            rec[p] = v
    for p in LINK_STRING_PROPS:
        v = [x for x in best(claims, p) if isinstance(x, str)]
        if v:
            rec[p] = v
    c = [x for x in best(claims, "P625") if isinstance(x, dict) and "latitude" in x and x.get("globe", "").endswith("Q2")]
    if c:
        rec["P625"] = [round(c[0]["latitude"], 5), round(c[0]["longitude"], 5)]
    return rec


def run(lines, titles, items, classes=None, site="enwiki", limit=None, log=sys.stderr):
    """One site. [titles] None keeps every item with a sitelink there."""
    return run_sites(lines, {site: (titles, items)}, classes, limit, log)


def run_sites(lines, targets: dict, classes=None, limit=None, log=sys.stderr):
    """[targets]: site -> (titles or None for all, the items file). One pass over the dump writes every site's file."""
    keys = {site: f'"{site}":{{"site":"{site}","title":"' for site in targets}
    n = hit = cls = 0
    t0 = time.time()
    for line in lines:
        n += 1
        if line.startswith("]"):
            break
        if limit and n > limit:
            break
        if n % 500000 == 0:
            for _, items in targets.values():
                items.flush()
            if classes is not None:
                classes.flush()
            print(f"{n:,} lines {hit:,} articles {cls:,} classes {time.time() - t0:.0f}s", file=log, flush=True)
        matches = {}
        for site, (titles, _) in targets.items():
            path = sitelink_title(line, keys[site])
            if path is not None and (titles is None or path in titles):
                matches[site] = path
        is_class = classes is not None and '"P279":[{"mainsnak"' in line
        if not matches and not is_class:
            continue
        e = json.loads(line.rstrip().rstrip(","))
        if is_class:
            cls += 1
            classes.write(json.dumps({"q": e["id"], "l": label(e), "p279": [v["id"] for v in best(e.get("claims", {}), "P279")]},
                                     ensure_ascii=False) + "\n")
        for site, path in matches.items():
            hit += 1
            targets[site][1].write(json.dumps(record(e, path, site), ensure_ascii=False) + "\n")
    print(f"DONE {n:,} lines {hit:,} articles {cls:,} classes {time.time() - t0:.0f}s", file=log, flush=True)
    return hit


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("titles"); p.add_argument("out")
    p.add_argument("--site", default="enwiki")
    p.add_argument("--no-classes", action="store_true")
    p.add_argument("--limit", type=int)
    a = p.parse_args(argv)
    titles = None if a.titles == "-" else set(open(a.titles, encoding="utf8").read().split("\n")) - {""}
    sites = a.site.split(",")
    if titles is not None and len(sites) > 1:
        p.error("one TITLES file is one site's paths; use - (every item) with several sites")
    files = {site: open(f"{a.out}/items-{site}.jsonl", "w", encoding="utf8") for site in sites}
    classes = None if a.no_classes else open(f"{a.out}/classes.jsonl", "w", encoding="utf8")
    try:
        run_sites(sys.stdin, {site: (titles, f) for site, f in files.items()}, classes, a.limit)
    finally:
        for f in files.values():
            f.close()
        if classes is not None:
            classes.close()


if __name__ == "__main__":
    main()
