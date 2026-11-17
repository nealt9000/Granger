#!/usr/bin/env python3
"""Plan EX T1: the parts of a broad topic's page (Neal's pick 2026-11-17: layout A, with C's ring as a map), by rule.

  parts.py CLASSES OUT.sqlite --lang en --items ITEMS --zim TOP_MAXI.zim --gvec TOP.gvec --gqid TOP.gqid

Broad: not a person, not a place with coordinates, not a dated event or work (a class, with P279, may carry dates:
Dog's domestication), and either BROAD_UNDER+ of the pack's
articles under its class (P31/P279, up to UNDER_DEPTH steps) or BROAD_INLINKS+ of the pack's articles linking to it.
For each broad topic, from its own pack (one language):
  kinds    the articles under it, best known first (KINDS_MAX), in up to GROUPS_MAX groups: greedily the class under the
           topic that covers most of the kinds not yet in a group (GROUP_MIN+ each; not the topic's own ancestors, not one
           covering GROUP_MAX_SHARE of the kinds); a group is named by the pack's article for its class (English packs fall
           back to the Wikidata label)
  sections its article's h2 sections (not References, See also…) and the pack's articles each links, in order
  figures  its article's pictures with their captions and sections, in order (FIGURES_MAX; maxi packs have them)
  people   people near it in meaning whose occupations are characteristic of its neighbourhood: occupations held by
           PEOPLE_OCC_MIN+ of its NEAR_PEOPLE nearest people, LIFT+ times more common there than among all people, and
           within LIFT_SPREAD of the most characteristic one's lift (pilots for Airplane, not inventors)
Close kin (Related) and dates (the time index) are read on the phone. Nothing is set per article.
Writes a small SQLite file that tools/qidindex attaches to the language's .gqid files: topics(qid, under, inlinks) and
topic_parts(topic, part, ord, item, grp, text, note).
Run with ~/.venvs/granger-vectors/bin/python (numpy, libzim).
"""
import argparse
import collections
import datetime
import html as H
import json
import math
import os
import re
import sqlite3
import sys
import time
from urllib.parse import unquote

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "subjects"))
sys.path.insert(0, os.path.join(HERE, "..", "vectors"))
from assign import HUMAN, load_classes, read_items  # noqa: E402

VERSION = 1
UNDER_DEPTH = 4
BROAD_UNDER = 10
BROAD_INLINKS = 300
KINDS_MAX = 60
GROUPS_MAX = 4
GROUP_MIN = 3
GROUP_MAX_SHARE = 0.6
LINKS_PER_SECTION = 24   # the story section needs its later dated links (Airplane: WWI, the Comet, the 747)
FIGURES_MAX = 16
FIGURE_MIN_W = 200
NEAR_PEOPLE = 60
PEOPLE_OCC_MIN = 3
LIFT = 5.0
LIFT_SPREAD = 2.5
PEOPLE_MAX = 12
DATED = ("P585", "P580", "P577", "P571")   # a dated event or work is not broad (a class has none of these)
SKIP_SECTIONS = {
    "en": {"see_also", "references", "notes", "external_links", "further_reading", "bibliography", "sources", "citations",
           "gallery", "footnotes", "works_cited", "notes_and_references"},
    "es": {"véase_también", "referencias", "notas", "enlaces_externos", "bibliografía", "fuentes", "galería",
           "lecturas_adicionales", "notas_y_referencias"},
}
# Part codes in topic_parts.
GROUP, KIND, SECTION, LINK, FIGURE, PERSON = 1, 2, 3, 4, 5, 6

TOK = re.compile(r'<h2 id="([^"]+)"[^>]*>(.*?)</h2>|href="([^"#:]+)"|<figure[^>]*>(.*?)</figure>', re.S)
IMG = re.compile(r'<img src="\./([^"]+)"[^>]*data-file-width="(\d+)" data-file-height="(\d+)"')
CAP = re.compile(r'<figcaption[^>]*>(.*?)</figcaption>', re.S)
TAG = re.compile(r'<[^>]+>')
HATNOTE = re.compile(r'<div[^>]*class="[^"]*hatnote[^"]*"[^>]*>.*?</div>', re.S)


def clean(s):
    return re.sub(r"\s+", " ", H.unescape(TAG.sub("", s))).strip()


def read_article(html, lang="en"):
    """[(section id, section title, [link paths], [(img, w, h, caption)])] in order; the lead is ('', '')."""
    out = [["", "", [], []]]
    for m in TOK.finditer(HATNOTE.sub("", html)):
        if m.group(1):
            out.append([m.group(1), clean(m.group(2)), [], []])
        elif m.group(3):
            u = m.group(3)
            if u.startswith(("./", "http", "//")) or u.endswith((".css", ".js", ".png", ".svg", ".jpg")):
                continue
            out[-1][2].append(unquote(u))
        else:
            f = m.group(4)
            im, c = IMG.search(f), CAP.search(f)
            if im:
                out[-1][3].append((im.group(1), int(im.group(2)), int(im.group(3)), clean(c.group(1)) if c else ""))
    skip = SKIP_SECTIONS.get(lang, set())
    return [tuple(s) for s in out if s[0].lower() not in skip]


def under_index(items, parents, depth=UNDER_DEPTH):
    """{class qid: {item qid: depth}} for every class an item's P31/P279 reach within depth steps (P279 up)."""
    under = collections.defaultdict(dict)
    for r in items.values():
        seen, front, d = set(), set(r.get("P31", [])) | set(r.get("P279", [])), 1
        while front and d <= depth:
            nxt = set()
            for c in front:
                if c in seen:
                    continue
                seen.add(c)
                under[c].setdefault(r["q"], d)
                nxt.update(parents.get(c, ()))
            front, d = nxt - seen, d + 1
    return under


def is_candidate(r):
    """Could be broad: not a person, not a place with coordinates, not a dated event or work (a class may have dates)."""
    if HUMAN in r.get("P31", []) or r.get("P625"):
        return False
    return bool(r.get("P279")) or not any(r.get(p) for p in DATED)


def kinds_and_groups(topic, kinds, reach, labels, rank):
    """(kinds best known first, [(class, label, [kinds])]): groups greedily by the class under the topic that covers
    most kinds not yet grouped. kinds: item qids under the topic; reach {item: set of classes it reaches}; labels
    {class: name}, a class without a name never groups."""
    kinds = sorted(kinds, key=lambda q: (-rank.get(q, 0), q))[:KINDS_MAX]
    # A class groups only when it is itself under the topic (it reaches the topic), so "aircraft model" and other
    # siblings don't group airplanes.
    above = reach.get(topic, set())
    cover = collections.defaultdict(set)
    for q in kinds:
        for c in reach.get(q, ()):
            if c != topic and c not in above and labels.get(c) and topic in reach.get(c, ()):
                cover[c].add(q)
    cover = {c: s for c, s in cover.items() if len(s) < GROUP_MAX_SHARE * len(kinds)}
    groups, left = [], set(kinds)
    while len(groups) < GROUPS_MAX and cover:
        c = max(cover, key=lambda c: (len(cover[c] & left), len(cover[c]), c))
        if len(cover[c] & left) < GROUP_MIN:
            break
        members = [q for q in kinds if q in cover[c]]
        groups.append((c, labels.get(c) or c, members))
        left -= cover.pop(c)
    return kinds, groups


def characteristic_people(near, occupations, all_counts, n_people):
    """The near people (best first) who hold an occupation characteristic of the neighbourhood.
    near: [person qid] by similarity; occupations {qid: [occupation]}; all_counts {occupation: people holding it}."""
    near = near[:NEAR_PEOPLE]
    c = collections.Counter(o for q in near for o in set(occupations.get(q, ())))
    lift = {o: (n / len(near)) / (all_counts[o] / n_people) for o, n in c.items() if n >= PEOPLE_OCC_MIN}
    best = max(lift.values(), default=0)
    keep = {o for o, x in lift.items() if x >= LIFT and x >= best / LIFT_SPREAD}
    return [q for q in near if keep & set(occupations.get(q, ()))][:PEOPLE_MAX], keep


def build(classes, out, lang, items_path, zim_path, gvec_path, gqid_path, log=print):
    import numpy as np
    from libzim.reader import Archive
    from assign import article_vectors
    from related_spike import G
    t0 = time.time()
    db = sqlite3.connect(f"file:{gqid_path}?mode=ro", uri=True)
    q_of = {p: f"Q{q}" for p, q in db.execute("SELECT path, qid FROM articles")}
    rank = {f"Q{q}": r for q, r in db.execute("SELECT qid, max(rank) FROM articles GROUP BY qid")}
    db.close()
    items = read_items(items_path, set(q_of))
    by_q = {r["q"]: r for r in items.values()}
    path_of = {}
    for p, q in q_of.items():
        path_of.setdefault(q, p)
    parents, labels = load_classes(classes)
    log(f"{len(items):,} items, classes loaded [{time.time() - t0:.0f}s]")

    # One pass over the pack: every article's sections, links and figures; in-links counted on the way.
    zim = Archive(zim_path)
    resolved = {}

    def resolve(p):
        if p in resolved:
            return resolved[p]
        q = q_of.get(p)
        if q is None:
            try:
                e = zim.get_entry_by_path(p)
                if e.is_redirect:
                    q = q_of.get(e.get_redirect_entry().path)
            except KeyError:
                pass
        resolved[p] = q
        return q

    inlinks, pages = collections.Counter(), {}
    for i, (p, q) in enumerate(q_of.items()):
        try:
            e = zim.get_entry_by_path(p)
        except KeyError:
            continue
        if e.is_redirect:
            continue
        secs = read_article(bytes(e.get_item().content).decode("utf8", "replace"), lang)
        linked = {resolve(x) for s in secs for x in s[2]} - {None, q}
        inlinks.update(linked)
        if q in by_q and is_candidate(by_q[q]):
            pages[q] = secs
        if i % 10000 == 0:
            log(f"  read {i:,} [{time.time() - t0:.0f}s]")

    under = under_index(items, parents)
    reach = collections.defaultdict(set)   # item or class -> classes it reaches (within depth)
    for c, qs in under.items():
        for q in qs:
            reach[q].add(c)
    for c in list(under):   # classes reach their own ancestors too, for "is this class under the topic"
        seen, front, d = set(), set(parents.get(c, ())), 1
        while front and d <= UNDER_DEPTH:
            seen |= front
            front = {p for x in front for p in parents.get(x, ())} - seen
            d += 1
        reach[c] |= seen
    broad = {}
    for q in pages:
        kinds = [k for k in under.get(q, {}) if k != q and HUMAN not in by_q[k].get("P31", [])]
        if len(kinds) >= BROAD_UNDER or inlinks[q] >= BROAD_INLINKS:
            broad[q] = kinds
    log(f"{len(broad):,} broad topics of {len(pages):,} candidates [{time.time() - t0:.0f}s]")

    # People: averaged article vectors; each topic's nearest people.
    g = G(gvec_path)
    people = [q for q, r in by_q.items() if HUMAN in r.get("P31", [])]
    occupations = {q: by_q[q].get("P106", []) for q in people}
    all_counts = collections.Counter(o for q in people for o in set(occupations[q]))
    pv = article_vectors(g, [path_of[q] for q in people])
    people = [q for q in people if path_of[q] in pv]
    P = np.stack([pv[path_of[q]] for q in people]) if people else np.zeros((0, 1), np.float32)
    tv = article_vectors(g, [path_of[q] for q in broad])

    # A group's name: the pack's own article for its class, else (English) the Wikidata label, first letter up.
    def name(c):
        p = path_of.get(c)
        if p:
            return p.replace("_", " ")
        lab = labels.get(c)
        return (lab[:1].upper() + lab[1:]) if lab and lang == "en" else None
    names = {c: name(c) for c in {c for q in broad for k in broad[q] for c in reach.get(k, ())}}

    rows, n_kept_occ = [], collections.Counter()
    for q, kinds in broad.items():
        t = int(q[1:])
        kinds, groups = kinds_and_groups(q, kinds, reach, names, rank)
        for i, (c, lab, members) in enumerate(groups):
            rows.append((t, GROUP, i, int(c[1:]), len(members), lab, None))
        gi = {k: i for i, (_, _, members) in enumerate(groups) for k in members}
        for i, k in enumerate(kinds):
            rows.append((t, KIND, i, int(k[1:]), gi.get(k, -1), None, None))
        ls, fs, si = 0, 0, 0
        for sid, title, links, figs in pages[q]:
            if sid:
                rows.append((t, SECTION, si, None, None, title, sid))
            seen = set()
            for x in links:
                k = resolve(x)
                if k and k != q and k not in seen and len(seen) < LINKS_PER_SECTION:
                    seen.add(k)
                    rows.append((t, LINK, ls, int(k[1:]), si if sid else -1, None, None))
                    ls += 1
            for img, w, h, cap in figs:
                if fs < FIGURES_MAX and w >= FIGURE_MIN_W and not img.lower().endswith(".svg"):
                    rows.append((t, FIGURE, fs, None, si if sid else -1, cap, img))
                    fs += 1
            if sid:
                si += 1
        if path_of[q] in tv and len(people):
            sims = P @ tv[path_of[q]]
            near = [people[i] for i in np.argsort(-sims)[:NEAR_PEOPLE]]
            chosen, kept = characteristic_people(near, occupations, all_counts, len(people))
            n_kept_occ.update(kept)
            for i, k in enumerate(chosen):
                rows.append((t, PERSON, i, int(k[1:]), None, None, None))

    if os.path.exists(out):
        os.unlink(out)
    o = sqlite3.connect(out)
    o.execute("CREATE TABLE topics(qid INTEGER PRIMARY KEY, under INTEGER NOT NULL, inlinks INTEGER NOT NULL)")
    o.executemany("INSERT INTO topics VALUES (?,?,?)", ((int(q[1:]), len(k), inlinks[q]) for q, k in broad.items()))
    o.execute("CREATE TABLE topic_parts(topic INTEGER NOT NULL, part INTEGER NOT NULL, ord INTEGER NOT NULL, item INTEGER, "
              "grp INTEGER, text TEXT, note TEXT, PRIMARY KEY(topic, part, ord)) WITHOUT ROWID")
    o.executemany("INSERT INTO topic_parts VALUES (?,?,?,?,?,?,?)", rows)
    counts = collections.Counter(r[1] for r in rows)
    o.execute("CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT NOT NULL)")
    o.executemany("INSERT INTO meta VALUES (?,?)", [
        ("builder", "tools/topics/parts.py"), ("builderVersion", str(VERSION)), ("lang", lang),
        ("builtAt", datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")),
        ("source", os.path.basename(zim_path)),
        ("counts", json.dumps({"topics": len(broad), **{str(k): v for k, v in counts.items()}}))])
    o.commit()
    o.execute("VACUUM")
    o.close()
    log(f"wrote {out}: {len(broad):,} topics, {len(rows):,} parts {dict(counts)} [{time.time() - t0:.0f}s]")
    log("most kept occupations: " + ", ".join(f"{labels.get(k, k)} {n}" for k, n in n_kept_occ.most_common(15)))
    return broad


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("classes"); p.add_argument("out")
    p.add_argument("--lang", required=True); p.add_argument("--items", required=True); p.add_argument("--zim", required=True)
    p.add_argument("--gvec", required=True); p.add_argument("--gqid", required=True)
    a = p.parse_args(argv)
    build(a.classes, a.out, a.lang, a.items, a.zim, a.gvec, a.gqid, log=lambda m: print(m, file=sys.stderr, flush=True))


if __name__ == "__main__":
    main()
