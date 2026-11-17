"""Plan EX lenses (Neal 2026-11-17): curated ways into a subject, not partitions (lenses.json).

An article joins every lens whose criteria it meets, from any subject in the lens's `in` (default its own subject): a
lens is a list of clauses, one holding is enough, and a clause holds when all its keys do (see lenses.json `note`). A
main-subject article in none of its subject's lenses takes the lens its K nearest same-subject, same-kind neighbours
share most, when that lens holds VOTE_MIN of their vote (packs with vectors). Nothing is set per article.
"""
import collections
import json
import os
from collections import deque

from assign import HUMAN, year

HERE = os.path.dirname(os.path.abspath(__file__))
K = 15
VOTE_MIN = float(os.environ.get("LENS_VOTE_MIN", "0.5"))
OCC_DEPTH = 0
PERSON_AGE = 40   # a person's year for after/before: birth + this
KEYS = {"seeds", "classes", "occupations", "genres", "not_classes", "not_occupations", "after", "before", "things",
        "main", "not_main"}


def load_lenses(spec, labels=None, path=None, facet_spec=None):
    """{subject: [lens]} from lenses.json, and its problems: unknown subjects or keys, ids repeating, names missing,
    a seed that names no sub-category, a label that doesn't match the class file."""
    data = json.load(open(path or os.path.join(HERE, "lenses.json")))
    lenses, bad = data["subjects"], []
    subjects = {s["id"] for s in spec["subjects"]}
    seeds = {f"{s}.{f['id']}" for s, fs in (facet_spec or {}).items() for f in fs}
    for s in subjects - set(lenses):
        bad.append(f"{s}: no lenses")
    for s, ls in lenses.items():
        if s not in subjects:
            bad.append(f"{s}: not a subject")
            continue
        ids = [l["id"] for l in ls]
        if len(set(ids)) != len(ids):
            bad.append(f"{s}: lens ids repeat")
        for l in ls:
            k = f"{s}.{l['id']}"
            if not (l.get("en") and l.get("es")):
                bad.append(f"{k}: needs en and es names")
            for x in l.get("in", []):
                if x not in subjects:
                    bad.append(f"{k}: in {x}, not a subject")
            if not l.get("any"):
                bad.append(f"{k}: no clauses")
            for c in l.get("any", []):
                for key in set(c) - KEYS:
                    bad.append(f"{k}: unknown key {key}")
                for x in c.get("seeds", []):
                    if facet_spec is not None and x not in seeds:
                        bad.append(f"{k}: seed {x} is no sub-category")
                if labels is not None:
                    for key in ("classes", "occupations", "genres", "not_classes", "not_occupations"):
                        for q, lab in c.get(key, {}).items():
                            if labels.get(q) != lab:
                                bad.append(f"{k}: {q} is {labels.get(q)!r}, not {lab!r}")
    return lenses, bad


def lens_meta(lenses):
    """The lens list the app shows (meta `facets`): {subject: [{id, en, es}]}."""
    return {s: [{"id": l["id"], "en": l["en"], "es": l["es"]} for l in ls] for s, ls in lenses.items()}


def item_year(r):
    """A thing's start or date; a person's birth + PERSON_AGE; None when undated."""
    if HUMAN in r.get("P31", []):
        y = year(r.get("P569"))
        return None if y is None else y + PERSON_AGE
    for p in ("P571", "P580", "P585", "P577"):
        y = year(r.get(p))
        if y is not None:
            return y
    return None


class Lenses:
    def __init__(self, lenses, parents, spec):
        self.parents, self.depth, self.stop = parents, spec["depth"], set(spec.get("stop", {}))
        self.memo = {}
        # [(key, home subject, in-set, [clause with sets])]
        self.rows, self.no_vote = [], set()
        for s, ls in lenses.items():
            for l in ls:
                if l.get("vote") is False:
                    self.no_vote.add(f"{s}.{l['id']}")
                cl = []
                for c in l["any"]:
                    d = dict(c)
                    for key in ("classes", "occupations", "genres", "not_classes", "not_occupations"):
                        if key in d:
                            d[key] = set(d[key])
                    for key in ("seeds", "main", "not_main"):
                        if key in d:
                            d[key] = set(d[key])
                    cl.append(d)
                self.rows.append((f"{s}.{l['id']}", s, set(l.get("in", [s])), cl))

    def _up(self, q, depth):
        """Every class within depth steps up from q (itself included), not walking through stop classes."""
        k = (q, depth)
        if k in self.memo:
            return self.memo[k]
        seen, todo = {q}, deque([(q, 0)])
        while todo:
            c, d = todo.popleft()
            if d < depth and c not in self.stop:
                for p in self.parents.get(c, ()):
                    if p not in seen:
                        seen.add(p)
                        todo.append((p, d + 1))
        self.memo[k] = frozenset(seen)
        return self.memo[k]

    def _reach(self, starts, depth):
        out = set()
        for q in starts:
            out |= self._up(q, depth)
        return out

    def match(self, r, subjects, seed=None):
        """The lens keys item r joins. subjects: its subjects, main first; seed: its sub-category (`subject.facet`)."""
        if not subjects:
            return []
        subs, main = set(subjects), subjects[0]
        person = HUMAN in r.get("P31", [])
        cls = occ = gen = None
        y, y_done = None, False
        out = []
        for key, home, ins, clauses in self.rows:
            if not subs & ins:
                continue
            for c in clauses:
                if "main" in c and main not in c["main"]:
                    continue
                if "not_main" in c and main in c["not_main"]:
                    continue
                if "seeds" in c and seed not in c["seeds"]:
                    continue
                if "things" in c and person:
                    continue
                if "classes" in c or "not_classes" in c:
                    if person:
                        continue
                    if cls is None:
                        cls = self._reach([r["q"]] + r.get("P31", []) + r.get("P279", []) + r.get("P136", []), self.depth)
                    if "classes" in c and not cls & c["classes"]:
                        continue
                    if "not_classes" in c and cls & c["not_classes"]:
                        continue
                if "occupations" in c or "not_occupations" in c:
                    if not person:
                        continue
                    if "main" not in c and main not in ins:
                        continue
                    if occ is None:
                        occ = self._reach(r.get("P106", []), OCC_DEPTH)
                    if "occupations" in c and not occ & c["occupations"]:
                        continue
                    if "not_occupations" in c and occ & c["not_occupations"]:
                        continue
                if "genres" in c:
                    if gen is None:
                        gen = self._reach(r.get("P136", []), OCC_DEPTH)
                    if not gen & c["genres"]:
                        continue
                if "after" in c or "before" in c:
                    if not y_done:
                        y, y_done = item_year(r), True
                    if y is None or y < c.get("after", -10 ** 9) or y >= c.get("before", 10 ** 9):
                        continue
                out.append(key)
                break
        return out

    def homes(self):
        """{lens key: home subject}."""
        return {key: home for key, home, _, _ in self.rows}

    def votable(self):
        """The lens keys the neighbours' vote may choose (not `vote: false`)."""
        return {key for key, _, _, _ in self.rows if key not in self.no_vote}


def vote(joined, main, person, vecs, homes, votable=None, k=K, vote_min=VOTE_MIN):
    """{path: lens key} for the main-subject paths in none of their subject's lenses: the K nearest same-subject,
    same-kind paths that are in one vote for each of those lenses by similarity; the winner needs vote_min of the vote.
    joined {path: [lens key]}, main {path: subject}, person {path: bool}, vecs {path: unit vector}, homes {key: subject},
    votable: the keys the vote may choose (default all)."""
    import numpy as np
    own = {t: [x for x in joined.get(t, ()) if homes[x] == main[t]] for t in main}
    choice = {t: [x for x in xs if votable is None or x in votable] for t, xs in own.items()}
    out = {}
    groups = collections.defaultdict(list)
    for t, s in main.items():
        if t in vecs:
            groups[(s, person[t])].append(t)
    for _, ts in groups.items():
        lab = [t for t in ts if choice[t]]
        todo = [t for t in ts if not own[t]]
        if len(lab) < 2 or not todo:
            continue
        M = np.stack([vecs[t] for t in lab])
        k1 = min(k, len(lab) - 1)
        for i in range(0, len(todo), 2048):
            chunk = todo[i:i + 2048]
            S = np.stack([vecs[t] for t in chunk]) @ M.T
            for j, t in enumerate(chunk):
                row = S[j]
                v = collections.Counter()
                for x in np.argpartition(-row, k1)[:k1]:
                    w = max(float(row[x]), 0.0)
                    for key in choice[lab[x]]:
                        v[key] += w
                tot = sum(max(float(row[x]), 0.0) for x in np.argpartition(-row, k1)[:k1])
                if tot > 0 and v:
                    key, w = v.most_common(1)[0]
                    if w / tot >= vote_min:
                        out[t] = key
    return out
