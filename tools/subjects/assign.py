"""Plan EX: subjects for Wikipedia articles, by rule, with ties and gaps settled by meaning (Task 0 method, run 15).

Rules: an article's P31/P279/P136 classes walk P279 up to `depth` steps to a subject root (subjects.json). A person's
subjects come from all their occupations (P106) walked the same way. Stop classes ("person", "social scientist") are too
general to walk through. When one subject's root is 2+ steps closer than the others, it wins.
Meaning (packs with vectors): each article's .gvec rows are averaged into one vector; a vote of its K nearest
single-subject articles (people against people, things against things), each vote weighted against its subject's size,
settles ties and gaps and adds second subjects. People: the lead's role words ("was a Roman general and statesman") count.
History: added to a dated past event and to a dead person in public life. Nothing is fixed per article.

`full()` is the whole method for one pack with vectors; `Rules.rules_only()` is what an article without vectors gets
(Top en, round 2: 0 wrong marks, ~85% of articles get a subject).
"""
import collections
import json
import os
import re
import sys
from collections import deque

HERE = os.path.dirname(os.path.abspath(__file__))
HUMAN = "Q5"
K = 15
EXTRA_RULE = float(os.environ.get("EXTRA_RULE", "0.15"))
EXTRA_VOTE = float(os.environ.get("EXTRA_VOTE", "0.35"))
PRIOR = float(os.environ.get("PRIOR", "0.5"))
LEAD_VERB = {"en": r"\b(is|was|are|were)\b", "es": r"\b(es|fue|era|son|fueron)\b"}
PLURAL = {"en": "s?", "es": "(?:s|es)?"}


def load_spec(labels=None):
    """subjects.json, and the label mismatches against Wikidata's labels (a wrong id fails loudly)."""
    spec = json.load(open(os.path.join(HERE, "subjects.json")))
    bad = []
    if labels is not None:
        for s in spec["subjects"]:
            for table in ("roots", "occupations"):
                for q, want in s.get(table, {}).items():
                    if labels.get(q) != want:
                        bad.append(f"{s['id']}.{table} {q} wants {want!r}, has {labels.get(q)!r}")
            # A namesake is an item, often not a class: checked only when the classes know it.
            for q, want in s.get("namesakes", {}).items():
                if labels.get(q) is not None and labels.get(q) != want:
                    bad.append(f"{s['id']}.namesakes {q} wants {want!r}, has {labels.get(q)!r}")
        for table in ("fictional", "drop", "stop"):
            for q, want in spec.get(table, {}).items():
                if labels.get(q) != want:
                    bad.append(f"{table} {q} wants {want!r}, has {labels.get(q)!r}")
    return spec, bad


def load_classes(path):
    parents, labels = {}, {}
    for line in open(path, encoding="utf8"):
        r = json.loads(line)
        parents[r["q"]] = r.get("p279", [])
        labels[r["q"]] = r.get("l")
    return parents, labels


class Walker:
    def __init__(self, parents, roots, depth, stop=()):
        self.parents, self.roots, self.depth, self.stop, self.memo = parents, roots, depth, set(stop), {}

    def subjects(self, q):
        """{subject: steps} for the nearest root of each subject reachable from class q within depth."""
        if q in self.memo:
            return self.memo[q]
        found = {}
        seen, todo = {q}, deque([(q, 0)])
        while todo:
            c, d = todo.popleft()
            for s in self.roots.get(c, ()):
                found.setdefault(s, d)
            if d < self.depth and c not in self.stop:
                for p in self.parents.get(c, ()):
                    if p not in seen:
                        seen.add(p)
                        todo.append((p, d + 1))
        self.memo[q] = found
        return found


def year(values):
    """The year of the first Wikidata time value ([["+1830-10-04T00:00:00Z", 11, ...]]); None if there is none."""
    for v in values or ():
        m = re.match(r"([+-]\d+)-", v[0] if isinstance(v, list) else v)
        if m:
            return int(m.group(1))
    return None


class Rules:
    def __init__(self, spec, parents, labels, this_year=2026):
        self.spec, self.labels, self.this_year = spec, labels, this_year
        self.ids = [s["id"] for s in spec["subjects"]]
        roots, occ = collections.defaultdict(set), collections.defaultdict(set)
        for s in spec["subjects"]:
            for q in s["roots"]:
                roots[q].add(s["id"])
            for q in s.get("occupations", {}):
                occ[q].add(s["id"])
        stop = spec.get("stop", {})
        depth = spec["depth"]
        self.cls_walk = Walker(parents, roots, depth, stop)
        # A root listed under two subjects (hurricane: Science and History) gives an article that reaches it both.
        self.dual_walk = Walker(parents, {q: v for q, v in roots.items() if len(v) > 1}, depth, stop)
        self.occ_walk = Walker(parents, occ, depth, stop)
        self.neutral_walk = Walker(parents, {q: {"neutral"} for q in spec.get("neutral", {})}, 2)
        self.hist = spec.get("history", {})
        self.event_walk = Walker(parents, {q: {"event"} for q in self.hist.get("event_class", {})},
                                 self.hist.get("event_depth", 4), stop)
        self.drop = set(spec["drop"])
        # The article about a subject itself (Art, Health, Politics) belongs to that subject alone.
        self.namesake = {q: s["id"] for s in spec["subjects"] for q in s.get("namesakes", {})}
        self.max = spec.get("max_subjects", 3)

    def dropped(self, r):
        return any(q in self.drop for q in r.get("P31", []))

    def candidates(self, r):
        """{subject: steps to its nearest root} from the item's classes, or a person's occupations."""
        p31 = r.get("P31", [])
        cand = {}
        if HUMAN in p31:
            for q in r.get("P106", []):
                for s, d in self.occ_walk.subjects(q).items():
                    cand[s] = min(cand.get(s, 99), d)
            return cand
        for q in [r["q"]] + p31 + r.get("P279", []) + r.get("P136", []):  # "Mountain" is itself the mountain root
            if self.neutral_walk.subjects(q):
                continue  # a glossary or an outline says nothing about its subject; meaning decides
            for s, d in self.cls_walk.subjects(q).items():
                cand[s] = min(cand.get(s, 99), d)
        # An article about an occupation or office ("songwriter", "President of Mexico") takes that occupation's subject.
        for q in [r["q"]] + r.get("P279", []):
            for s, d in self.occ_walk.subjects(q).items():
                cand[s] = min(cand.get(s, 99), d)
        return cand

    def duals(self, r):
        if HUMAN in r.get("P31", []):
            return set()
        d = set()
        for q in r.get("P31", []) + r.get("P279", []) + r.get("P136", []):
            d |= set(self.dual_walk.subjects(q))
        return d

    def past(self, r, main):
        """History: a dated past event (classes reach 'occurrence'; not a founded thing; not a work or a sports event),
        or a dead person in public life."""
        if HUMAN in r.get("P31", []):
            return "P570" in r and main in self.hist.get("person_main", ())
        if "P571" in r or main in self.hist.get("event_skip_main", ()):
            return False
        end = year(r.get("P582")) or year(r.get("P585")) or year(r.get("P580"))
        if end is None or end > self.this_year - self.hist.get("recent_years", 5):
            return False
        # Recorded history only: the Big Bang and the Cambrian explosion are dated past events, but not History.
        if self.before_history(r):
            return False
        return any(self.event_walk.subjects(q) for q in r.get("P31", []) + r.get("P279", []))

    def before_history(self, r):
        """Dated before recorded history (history.from_year): the Hadean, the Cambrian explosion. Never History, as a
        main subject or a second one, however its classes reach it."""
        if HUMAN in r.get("P31", []):
            return False
        ys = [y for y in (year(r.get("P580")), year(r.get("P585")), year(r.get("P582"))) if y is not None]
        return bool(ys) and min(ys) < self.hist.get("from_year", -10**12)

    def decide(self, r, cand, sims, lead_w):
        """(main, subjects, mode, share) from the rule candidates, the neighbours' vote and the lead's role words."""
        if r["q"] in self.namesake:
            s = self.namesake[r["q"]]
            return s, [s], "namesake", {s: 1.0}
        if self.before_history(r):
            cand = {k: v for k, v in cand.items() if k != "history"}
            sims = {k: v for k, v in sims.items() if k != "history"}
            lead_w = {k: v for k, v in lead_w.items() if k != "history"}
        person = HUMAN in r.get("P31", [])
        near = sorted(cand.values())
        if len(cand) == 1 and not lead_w:
            main_s, mode = next(iter(cand)), "rule"
        elif not person and len(near) > 1 and near[1] - near[0] >= 2:
            main_s, mode = min(cand, key=cand.get), "rule"  # one subject's root is much closer: that kind of thing first
        elif lead_w:
            # A person: the vote share, plus the lead's role words, plus 0.5 for a subject the occupations give.
            tot = sum(sims.values()) or 1.0
            main_s = max(set(cand) | set(lead_w), key=lambda x: sims.get(x, 0) / tot + lead_w.get(x, 0) + (0.5 if x in cand else 0))
            mode = "rule+lead"
        elif len(cand) > 1:
            main_s = max(cand, key=lambda s: (sims.get(s, -1), -cand[s])) if sims else min(cand, key=lambda s: (cand[s], self.ids.index(s)))
            mode = "rule+meaning" if sims else "rule"
        elif sims:
            main_s, mode = max(sims, key=sims.get), "meaning"
        else:
            return None, [], "none", {}
        # More subjects: a rule subject the neighbours back at least a little, any subject the neighbours back strongly,
        # or a role the lead names that the rules or neighbours back too. At most max_subjects, best first.
        tot = sum(sims.values()) or 1.0
        share = {x: sims.get(x, 0) / tot for x in set(cand) | set(sims) | set(lead_w)}
        extra = [x for x in share if x != main_s and (
            (x in cand and share[x] >= EXTRA_RULE) or share[x] >= EXTRA_VOTE or
            (lead_w.get(x, 0) >= 1 / 3 and (x in cand or share[x] >= EXTRA_RULE)))]
        # Places as a second subject: only for something with coordinates (P625), or a type of place the rules name.
        if "P625" not in r and not ("places" in cand and r.get("P279")):
            extra = [x for x in extra if x != "places"]
        extra.sort(key=lambda x: share[x] + lead_w.get(x, 0) + (0.4 if x in cand else 0), reverse=True)
        d = self.duals(r)
        if main_s in d:
            for x in d:
                if x != main_s and x not in extra:
                    extra.insert(0, x)
        subjects = [main_s] + extra[:self.max - 1]
        if "history" not in subjects and len(subjects) < self.max and self.past(r, main_s):
            subjects.append("history")
        return main_s, subjects, mode, share

    def rules_only(self, r):
        """(main, subjects, mode) for an article with no vectors and no lead: rules, dual roots and History only."""
        if self.dropped(r):
            return None, [], "dropped"
        main_s, subjects, mode, _ = self.decide(r, self.candidates(r), {}, {})
        return main_s, subjects, mode


def path_of(line):
    """The "t" field of a wdfilter line without parsing it all: wdfilter writes q, then t, first."""
    i = line.find('"t": "')
    if i < 0:
        return None
    j = i + 6
    while True:
        k = line.find('"', j)
        if k < 0:
            return None
        if line[k - 1] != "\\" or line[k - 2] == "\\":
            return json.loads(line[i + 5:k + 1])
        j = k + 1


def read_items(items_path, wanted):
    """{path: wdfilter record} for the wanted paths (only those lines are parsed); the first item per path wins."""
    out = {}
    for line in open(items_path, encoding="utf8"):
        if path_of(line) in wanted:
            r = json.loads(line)
            out.setdefault(r["t"], r)
    return out


def article_vectors(g, keys):
    import numpy as np
    km = g.keymap()
    out = {}
    for k in keys:
        rows = km.get(k)
        if not rows:
            continue
        v = (g.q[rows].astype(np.float32) * g.scales[rows][:, None]).mean(0)
        out[k] = v / max(np.linalg.norm(v), 1e-12)
    return out


def person_leads(zim_path, paths):
    """The lead's first sentence for each path, from the pack itself."""
    import html as H
    from libzim.reader import Archive
    a = Archive(zim_path)
    out = {}
    for p in paths:
        try:
            e = a.get_entry_by_path(p)
            while e.is_redirect:
                e = e.get_redirect_entry()
            h = bytes(e.get_item().content).decode("utf-8", "replace")
        except KeyError:
            continue
        for para in re.findall(r"<p[^>]*>(.*?)</p>", h, flags=re.S):
            t = re.sub(r"<(style|sup|script)[^>]*>.*?</\1>", "", para, flags=re.S)
            t = H.unescape(re.sub(r"<[^>]+>", "", t))
            for _ in range(3):
                t = re.sub(r"\s*\([^()]*\)", "", t)
            t = re.sub(r"\s+", " ", t).strip()
            if len(t) > 60:
                m = re.match(r"(.+?[.!?])(\s|$)", t)
                out[p] = (m.group(1) if m else t)[:400]
                break
    return out


def lead_roles(sentence, role_of, role_re, lang="en"):
    """{subject: weight} from the role words after the sentence's verb ("was a Roman general and statesman"): each role
    mention adds 0.8^i to its subject (i = its order). Longest phrase first ("theoretical physicist" before "physicist")."""
    if not sentence:
        return {}
    m = re.search(LEAD_VERB[lang], sentence)
    text = (sentence[m.end():] if m else sentence).lower()
    out = {}
    for i, hit in enumerate(role_re.finditer(text)):
        s = role_of[hit.group(1)]
        out[s] = out.get(s, 0) + 0.8 ** i
    return out


def role_words(rules, items, lang):
    """{role word: subject}: the hand list for the language, plus (English) every occupation label the pack's people hold
    whose walk reaches exactly one subject ("astrophysicist", "organic chemist")."""
    role_of = {}
    for sj in rules.spec["subjects"]:
        for w in sj.get(f"roles_{lang}", []):
            role_of[w] = sj["id"]
    if lang == "en":
        for q in {q for r in items.values() for q in r.get("P106", [])}:
            subs = rules.occ_walk.subjects(q)
            lab = (rules.labels.get(q) or "").lower()
            if len(subs) == 1 and lab and lab not in role_of and len(lab) > 3:
                role_of[lab] = next(iter(subs))
    return role_of


def full(rules, items, gvec_path, zim_path=None, lang="en"):
    """{path: result} for one pack with vectors: items is {path: wdfilter record} for the pack's articles."""
    import numpy as np
    sys.path.insert(0, os.path.join(HERE, "..", "vectors"))
    from related_spike import G
    g = G(gvec_path)
    cands = {t: rules.candidates(r) for t, r in items.items() if not rules.dropped(r)}
    vecs = article_vectors(g, cands.keys())
    # A biography is compared with biographies, a thing with things: a vote of the K nearest articles the rules tag with
    # exactly one subject (a neighbour vote keeps mixed subjects, like companies plus economics, intact).
    is_person = {t: HUMAN in items[t].get("P31", []) for t in cands}
    votes = {}
    for person in (True, False):
        ts = [t for t, c in cands.items() if len(c) == 1 and t in vecs and is_person[t] == person]
        if not ts:
            continue
        lab = [next(iter(cands[t])) for t in ts]
        n = collections.Counter(lab)
        # A big subject mustn't win close calls by headcount: each vote is weighted by (mean size / its size)^PRIOR.
        w = {s: (len(ts) / len(n) / c) ** PRIOR for s, c in n.items()}
        M = np.stack([vecs[t] for t in ts])
        todo = [t for t in cands if t in vecs and is_person[t] == person]
        k1 = min(K + 1, len(ts) - 1)
        for i in range(0, len(todo), 2048):
            chunk = todo[i:i + 2048]
            S = np.stack([vecs[t] for t in chunk]) @ M.T
            for j, t in enumerate(chunk):
                row = S[j]
                v = collections.Counter()
                for k in np.argpartition(-row, k1)[:k1]:
                    if ts[k] != t:
                        v[lab[k]] += float(row[k]) * w[lab[k]]
                votes[t] = v
    leads = person_leads(zim_path, [t for t in cands if is_person[t]]) if zim_path else {}
    role_of = role_words(rules, items, lang)
    role_re = re.compile(r"\b(" + "|".join(sorted(map(re.escape, role_of), key=len, reverse=True)) + r")" + PLURAL[lang] + r"\b")
    result = {}
    for t, cand in cands.items():
        r = items[t]
        sims = dict(votes.get(t, {}))
        lead_w = lead_roles(leads.get(t), role_of, role_re, lang) if t in leads else {}
        main_s, subjects, mode, share = rules.decide(r, cand, sims, lead_w)
        best = max(sims, key=sims.get) if sims else None
        flag = bool(sims) and mode != "meaning" and best != main_s and sims.get(main_s, 0) < 0.25 * sum(sims.values())
        result[t] = {"q": r["q"], "label": r.get("l"), "main": main_s, "subjects": subjects, "mode": mode,
                     "rules": sorted(cand), "nearest": best, "flag": flag,
                     "share": {x: round(v, 2) for x, v in sorted(share.items(), key=lambda kv: -kv[1])[:4]}}
    return result
