"""Plan EX editorial pass: each article's sub-category (facet) within its main subject (facets.json).

Rule: a thing's nearest roots of its main subject decide (when they reach several facets, the one listed first); a
person's occupations decide (the facet most of them reach; a tie, the one listed first). A facet with `taxon` takes the
living things whose classes reach that taxon. Vote (packs with vectors): an article the rule leaves without a facet takes
the facet its K nearest same-subject articles with one share most (people against people, things against things), when
that facet holds at least VOTE_MIN of the vote. Nothing is fixed per article.
"""
import collections
import json
import os
from collections import deque

HERE = os.path.dirname(os.path.abspath(__file__))
HUMAN = "Q5"
K = 15
VOTE_MIN = float(os.environ.get("FACET_VOTE_MIN", "0.5"))
TAXON_DEPTH = 12


def load_facets(spec, path=None):
    """{subject: [facet]} from facets.json, and the problems against subjects.json (every root and occupation of a
    subject in exactly one of its facets; facet ids unique within a subject)."""
    data = json.load(open(path or os.path.join(HERE, "facets.json")))
    facets, bad = data["subjects"], []
    for s in spec["subjects"]:
        fs = facets.get(s["id"])
        if not fs:
            bad.append(f"{s['id']}: no facets")
            continue
        ids = [f["id"] for f in fs]
        if len(set(ids)) != len(ids):
            bad.append(f"{s['id']}: facet ids repeat")
        placed = collections.Counter(q for f in fs for q in f.get("roots", []) + f.get("occupations", []))
        want = set(s["roots"]) | set(s.get("occupations", {}))
        for q in sorted(want - set(placed)):
            bad.append(f"{s['id']}: {q} ({s['roots'].get(q) or s.get('occupations', {}).get(q)}) in no facet")
        for q in sorted(set(placed) - want):
            bad.append(f"{s['id']}: {q} is not one of its roots or occupations")
        for q, n in placed.items():
            if n > 1:
                bad.append(f"{s['id']}: {q} in {n} facets")
        for f in fs:
            if not (f.get("en") and f.get("es")):
                bad.append(f"{s['id']}.{f['id']}: needs en and es names")
    weak = {q for q in data.get("weak", {}) if q != "note"}
    return facets, bad, weak


class Facets:
    def __init__(self, facets, parents, spec, weak=()):
        self.facets, self.parents, self.weak = facets, parents, set(weak)
        self.fallback = {s: {f["id"] for f in fs if f.get("fallback")} for s, fs in facets.items()}
        self.stop, self.depth, self.memo = set(spec.get("stop", {})), spec["depth"], {}
        # {subject: {root or occupation: facet index}}
        self.root_of = {s: {q: i for i, f in enumerate(fs) for q in f.get("roots", [])} for s, fs in facets.items()}
        self.occ_of = {s: {q: i for i, f in enumerate(fs) for q in f.get("occupations", [])} for s, fs in facets.items()}
        self.taxa = {s: [(i, set([f["taxon"]] if isinstance(f["taxon"], str) else f["taxon"]))
                         for i, f in enumerate(fs) if f.get("taxon")] for s, fs in facets.items()}

    def _nearest(self, q, targets, key, depth):
        k = (q, key)
        if k in self.memo:
            return self.memo[k]
        seen, todo, best, bd = {q}, deque([(q, 0)]), set(), None
        while todo:
            c, d = todo.popleft()
            if bd is not None and d > bd:
                break
            if c in targets:
                best.add(c)
                bd = d
            if d < depth and c not in self.stop:
                for p in self.parents.get(c, ()):
                    if p not in seen:
                        seen.add(p)
                        todo.append((p, d + 1))
        self.memo[k] = (best, bd)
        return best, bd

    def rule(self, r, subject):
        """The facet id of item r in its main subject by rule, or None."""
        fs = self.facets.get(subject)
        if not fs:
            return None
        if HUMAN in r.get("P31", []):
            occ = self.occ_of[subject]
            n = collections.Counter()
            for o in r.get("P106", []):
                hits, _ = self._nearest(o, set(occ), subject + "/o", self.depth)
                # A broad occupation (musician, actor, lawyer) counts half.
                w = {}
                for h in hits:
                    w[occ[h]] = max(w.get(occ[h], 0), 0.5 if h in self.weak else 1.0)
                for i, x in w.items():
                    n[i] += x
            if not n:
                return None
            return fs[min(n, key=lambda i: (-n[i], i))]["id"]
        for i, taxa in self.taxa[subject]:
            if any(self._nearest(c, taxa, f"{subject}/t{i}", TAXON_DEPTH)[0] for c in [r["q"]] + r.get("P279", [])):
                return fs[i]["id"]
        roots = self.root_of[subject]
        # A broad root (tourist destination) decides only when no other root is reached.
        for targets, key in ((set(roots) - self.weak, "/r"), (set(roots) & self.weak, "/w")):
            hits, bestd = set(), 99
            for c in [r["q"]] + r.get("P31", []) + r.get("P279", []) + r.get("P136", []):
                b, d = self._nearest(c, targets, subject + key, self.depth)
                if not b:
                    continue
                if d < bestd:
                    bestd, hits = d, set(b)
                elif d == bestd:
                    hits |= b
            if hits:
                return fs[min(roots[h] for h in hits)]["id"]
        return None

    def final(self, ruled, main, person, vecs):
        """{path: facet or None}: the rule's facet, else (or for a catch-all facet) the neighbours' vote when it decides."""
        open_ = {t: (None if f in self.fallback.get(main.get(t), ()) else f) for t, f in ruled.items()}
        voted = vote(open_, main, person, vecs)
        return {t: (voted.get(t) or ruled[t]) if not open_[t] else open_[t] for t in ruled}


def vote(ruled, main, person, vecs, k=K, vote_min=VOTE_MIN):
    """{path: facet} for the paths with a main subject but no rule facet: the K nearest same-subject, same-kind paths
    with a rule facet vote, each by similarity; the winner needs vote_min of the vote. ruled {path: facet or None},
    main {path: subject}, person {path: bool}, vecs {path: unit vector}."""
    import numpy as np
    out = {}
    groups = collections.defaultdict(list)
    for t, s in main.items():
        if t in vecs:
            groups[(s, person[t])].append(t)
    for key, ts in groups.items():
        lab = [t for t in ts if ruled.get(t)]
        todo = [t for t in ts if not ruled.get(t)]
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
                    v[ruled[lab[x]]] += max(float(row[x]), 0.0)
                tot = sum(v.values())
                if tot > 0:
                    f, w = v.most_common(1)[0]
                    if w / tot >= vote_min:
                        out[t] = f
    return out
