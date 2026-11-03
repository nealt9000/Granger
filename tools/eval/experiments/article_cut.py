"""Which 8 articles does the keyword search read? (Plan R2, 2026-11-03; offline, CPU)

  article_cut.py RUN --packs DIR [DIR ...] [--docs 8] [--per-pack 8]

LibraryPassageFinder reads `docs` articles per question: every pack's top `per-pack` hits for every keyword, deduplicated
by path, sorted by Kiwix's score, cut to `docs`. Kiwix's score is a percentage and the top hits all come back at 100
(r2-t10-all: 154 of 159 questions had all 8 at 100), so the cut is the packs' order, then the keywords' order. This
replays each tuning question's recorded keywords against the same packs through libzim and asks, per ordering, whether
an answer's article (an ARTICLE answer's source and location) is among the articles read:

  phone     what the phone read (RUN's recorded passages): the ground truth for the app's cut
  current   pack order, then keyword order, then rank, as if every score tied (the app ties only its 100s, which
            libzim's Python binding doesn't report, so this is a rough stand-in)
  ranked    most keywords hitting the article first, then a keyword in its title, then best rank, then pack order
  ceiling   among all hits (any cut)

Classics ZIM pages of works installed as EPUBs are dropped after the cut (withoutClassicsTwins); `ranked` drops them
before. The pack order is the order packs first appear in RUN's recorded passages. Tuning runs only.
"""
import argparse
import json
import re
import sys
from pathlib import Path

from libzim.reader import Archive
from libzim.search import Query, Searcher

HERE = Path(__file__).resolve().parents[1]
ap = argparse.ArgumentParser()
ap.add_argument("run"); ap.add_argument("--packs", nargs="+", required=True)
ap.add_argument("--docs", type=int, default=8); ap.add_argument("--per-pack", type=int, default=8)
a = ap.parse_args()
RUN = HERE / "runs" / a.run
items = json.loads((RUN / "dataset.json").read_text())
items = items["items"] if isinstance(items, dict) else items
assert not any(str(it.get("split", "")).startswith("heldout") for it in items), "tuning runs only"
recs = {r["id"]: r for r in map(json.loads, (RUN / "results.jsonl").open()) if r}
found = {r["id"]: r for r in map(json.loads, (RUN / "found.jsonl").open()) if r}

# Pack id as the app names it ("wikipedia_en_top"): the file name without its flavour and date.
FLAVOURS = ("_maxi", "_nopic", "_mini")
def pack_id(name):
    stem = re.sub(r"_\d{4}-\d{2}(-\d{2})?$", "", name.removesuffix(".zim"))
    for f in FLAVOURS:
        stem = stem.removesuffix(f)
    return stem

files = {}
for d in a.packs:
    for f in sorted(Path(d).glob("*.zim")):
        files.setdefault(pack_id(f.name), f)
# The app's pack order, as the recorded runs show it: the average place a pack first appears.
places = {}
for f in found.values():
    seen = []
    for p in f.get("passages", []):
        if p["kind"] == "ARTICLE" and p["source"] not in seen:
            seen.append(p["source"])
    for i, s in enumerate(seen):
        places.setdefault(s, []).append(i)
recorded_order = sorted(places, key=lambda s: sum(places[s]) / len(places[s]))
order = [p for p in recorded_order if p in files] + sorted(p for p in files if p not in recorded_order)
missing = [p for p in recorded_order if p not in files]
print("packs", len(order), "recorded but missing here:", missing, flush=True)

archives = {}
for p in order:
    try:
        ar = Archive(str(files[p]))
        if ar.has_fulltext_index:
            archives[p] = (ar, Searcher(ar))
    except Exception as e:  # noqa: BLE001
        print("skip", p, e)
order = [p for p in order if p in archives]

STOP = set("a an the of in on at to is are was were and or for with by from as be it its this that what which who how".split())
def words(s):
    return [w for w in re.findall(r"[\w']+", s.lower()) if w not in STOP]

def variants(k):
    return [v.strip() for v in k.split("|") if v.strip()]

def norm(path):
    return re.sub(r"^[A-Z]/", "", path)

# Works installed as EPUBs: any book:granger:gc-<slug> the run's searches returned.
installed = set()
for f in found.values():
    for p in f.get("passages", []) + f.get("dense", []):
        m = re.match(r"book:granger:gc-(.+)", p.get("source", ""))
        if m:
            installed.add(m.group(1))

def twin(pack, path):
    return pack == "granger_classics_en" and norm(path).split("/")[0] in installed

cache = {}
def hits(pack, term):
    key = (pack, term)
    if key not in cache:
        ar, se = archives[pack]
        try:
            paths = list(se.search(Query().set_query(term)).getResults(0, a.per_pack))
        except Exception:  # noqa: BLE001
            paths = []
        out = []
        for path in paths:
            try:
                title = ar.get_entry_by_path(path).title
            except Exception:  # noqa: BLE001
                title = path
            out.append((path, title))
        cache[key] = out
    return cache[key]

rows = []
stats = {"current": 0, "ranked": 0, "ceiling": 0, "twins_cut": 0, "overlap": 0.0, "compared": 0}
for n, it in enumerate(items):
    rec = recs.get(it["id"])
    if not rec:
        continue
    terms = list(dict.fromkeys(v for k in rec.get("keywords", []) for v in variants(k)))
    kwords = [set(words(v)) for k in rec.get("keywords", []) for v in variants(k)]
    allhits = []  # (pack index, pack, path, title, first keyword index, rank)
    per = {}
    for pi, pack in enumerate(order):
        seen = {}
        for ti, term in enumerate(terms):
            for rank, (path, title) in enumerate(hits(pack, term)):
                key = (pack, norm(path))
                if key not in seen:
                    seen[key] = (pi, pack, path, title, ti, rank)
                per.setdefault(key, set()).add(ti)
                best = seen[key]
                if rank < best[5]:
                    seen[key] = best[:5] + (rank,)
        allhits += list(seen.values())
    current = [h for h in allhits][: a.docs]
    current_read = [h for h in current if not twin(h[1], h[2])]
    stats["twins_cut"] += len(current) - len(current_read)

    def title_hit(title):
        tw = set(words(title))
        return any(ws and ws <= tw for ws in kwords)
    pool = [h for h in allhits if not twin(h[1], h[2])]
    cnt = lambda h: len(per[(h[1], norm(h[2]))])
    orders = {
        "count": lambda h: (-cnt(h), not title_hit(h[3]), h[5], h[0], h[4]),
        "title": lambda h: (not title_hit(h[3]), -cnt(h), h[5], h[0], h[4]),
        "rank": lambda h: (h[5], -cnt(h), not title_hit(h[3]), h[0], h[4]),
        "titlerank": lambda h: (not title_hit(h[3]), h[5], -cnt(h), h[0], h[4]),
    }
    sorted_by = {k: sorted(pool, key=f) for k, f in orders.items()}
    ranked = sorted_by["count"][: a.docs]

    # Fidelity: the simulated current cut against the articles the phone read.
    rec_arts = {(p["source"], norm(p["location"])) for p in found.get(it["id"], {}).get("passages", []) if p["kind"] == "ARTICLE"}
    if rec_arts:
        sim = {(h[1], norm(h[2])) for h in current_read}
        stats["overlap"] += len(sim & rec_arts) / max(1, len(rec_arts)); stats["compared"] += 1

    if rec_arts:
        every = {(h[1], norm(h[2])) for h in allhits}
        stats["recorded_found"] = stats.get("recorded_found", 0) + len(rec_arts & every)
        stats["recorded"] = stats.get("recorded", 0) + len(rec_arts)
    phone = bool(rec_arts & {(x.get("source"), norm(x.get("location", "")).split("#")[0]) for x in it.get("answers") or []})
    targets = {(x.get("source"), norm(x.get("location", "")).split("#")[0]) for x in it.get("answers") or []
               if x.get("source") and not str(x.get("source")).startswith("book:")}
    if not it.get("answerable") or not targets:
        continue
    def has(hs):
        return any((h[1], norm(h[2])) in targets for h in hs)
    r = {"id": it["id"], "current": has(current_read), "phone": phone, "ranked": has(ranked), "ceiling": has(allhits)}
    for k, lst in sorted_by.items():
        for d in (8, 12, 16, 24):
            r[f"{k}@{d}"] = has(lst[:d])
    for k in ("current", "phone", "ranked", "ceiling"):
        stats.setdefault(k, 0)
    for k in ("current", "phone", "ranked", "ceiling"):
        stats[k] += r[k]
    rows.append(r)
    if n % 20 == 0:
        print(n, flush=True)

print(json.dumps({k: (round(v / stats["compared"], 3) if k == "overlap" else v) for k, v in stats.items()}))
print("items with an article answer:", len(rows))
for k in sorted(k for k in rows[0] if "@" in k):
    print(k, sum(r[k] for r in rows), "gained", sum(1 for r in rows if r[k] and not r["phone"]), "lost", sum(1 for r in rows if r["phone"] and not r[k]))
print("gained over the phone:", [r["id"] for r in rows if r["ranked"] and not r["phone"]])
print("lost against the phone:", [r["id"] for r in rows if r["phone"] and not r["ranked"]])
(RUN.parent / f"article-cut-{a.run}.json").write_text(json.dumps({"stats": stats, "rows": rows}, indent=1))
