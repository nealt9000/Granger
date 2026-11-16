"""Plan EX Task 0: one main subject for every article of a Wikipedia pack, by rule, with ties and gaps settled by meaning.

Rules: an article's P31/P279/P136 classes walk P279 up to `depth` steps to a subject root (subjects.json). A person's
subjects come from all their occupations (P106) walked the same way; a fictional character's from its classes only.
When one subject's root is 2+ steps closer than the others, it wins.
Meaning: each article's shipped .gvec rows are averaged into one vector; a vote of its K nearest single-subject articles
(people against people, things against things), each vote weighted against its subject's size, settles ties and gaps.
People: the lead's first role words ("was a Roman general and statesman") add to the vote. Nothing is fixed per article.

    python spike.py ITEMS.jsonl CLASSES.jsonl PACK.gvec OUT_DIR
"""
import json, os, sys, collections, random
from collections import deque
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "vectors"))
from related_spike import G  # noqa: E402

HUMAN = "Q5"
K = 15
PRIOR = float(os.environ.get("PRIOR", "0.5"))


def load_spec(labels):
    spec = json.load(open(os.path.join(HERE, "subjects.json")))
    bad = []
    for s in spec["subjects"]:
        for table in ("roots", "occupations"):
            for q, want in s.get(table, {}).items():
                if labels.get(q) != want:
                    bad.append(f"{s['id']}.{table} {q} wants {want!r}, has {labels.get(q)!r}")
    for table in ("fictional", "drop"):
        for q, want in spec[table].items():
            if labels.get(q) != want:
                bad.append(f"{table} {q} wants {want!r}, has {labels.get(q)!r}")
    return spec, bad


class Walker:
    def __init__(self, parents, roots, depth):
        self.parents, self.roots, self.depth, self.memo = parents, roots, depth, {}

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
            if d < self.depth:
                for p in self.parents.get(c, ()):
                    if p not in seen:
                        seen.add(p)
                        todo.append((p, d + 1))
        self.memo[q] = found
        return found


def article_vectors(g, keys):
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
    import html as H, re
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


def lead_roles(sentence, role_of):
    """{subject: weight} from the role words after the sentence's verb ("was a Roman general and statesman"): 1 for the first
    subject named, 1/3 for the second, 1/9 for the third."""
    import re
    if not sentence:
        return {}
    m = re.search(r"\b(is|was|are|were)\b", sentence)
    words = re.findall(r"[a-z]+", sentence[m.end():].lower() if m else sentence.lower())
    out = {}
    for w in words:
        w1 = w[:-1] if w.endswith("s") and w[:-1] in role_of else w
        if w1 in role_of and role_of[w1] not in out:
            out[role_of[w1]] = 3.0 ** -len(out)
            if len(out) == 3:
                break
    return out


def main(items_path, classes_path, gvec_path, out_dir, zim_path=None):
    os.makedirs(out_dir, exist_ok=True)
    g = G(gvec_path)
    keys = set(g.keymap())
    items = {}
    for line in open(items_path):
        r = json.loads(line)
        if r["t"] in keys:
            items[r["t"]] = r
    parents, labels = {}, {}
    for line in open(classes_path):
        r = json.loads(line)
        parents[r["q"]] = r.get("p279", [])
        labels[r["q"]] = r.get("l")
    spec, bad = load_spec(labels)
    if bad:
        print("LABEL MISMATCHES (fix subjects.json):\n  " + "\n  ".join(bad))
    ids = [s["id"] for s in spec["subjects"]]
    roots, occ = collections.defaultdict(set), collections.defaultdict(set)
    for s in spec["subjects"]:
        for q in s["roots"]:
            roots[q].add(s["id"])
        for q in s.get("occupations", {}):
            occ[q].add(s["id"])
    cls_walk = Walker(parents, roots, spec["depth"])
    occ_walk = Walker(parents, occ, spec["depth"])
    drop, fictional = set(spec["drop"]), set(spec["fictional"])

    rules, why = {}, {}
    dropped = []
    for t, r in items.items():
        p31 = r.get("P31", [])
        if any(q in drop for q in p31):
            dropped.append(t)
            continue
        cand = {}
        if HUMAN in p31:
            for q in r.get("P106", []):
                for s, d in occ_walk.subjects(q).items():
                    cand[s] = min(cand.get(s, 99), d)
        else:
            classes = p31 + r.get("P279", []) + r.get("P136", [])
            for q in classes:
                for s, d in cls_walk.subjects(q).items():
                    cand[s] = min(cand.get(s, 99), d)
            # An article about an occupation or office ("songwriter", "President of Mexico") takes that occupation's subject.
            for q in [r["q"]] + r.get("P279", []):
                for s, d in occ_walk.subjects(q).items():
                    cand[s] = min(cand.get(s, 99), d)
        rules[t] = cand

    vecs = article_vectors(g, rules.keys())
    # A biography is compared with biographies, a thing with things. Meaning is a vote of the K nearest articles the
    # rules tag with exactly one subject (a neighbour vote keeps mixed subjects, like companies plus economics, intact).
    is_person = {t: HUMAN in items[t].get("P31", []) for t in rules}
    pool = {}
    for person in (True, False):
        ts = [t for t, cand in rules.items() if len(cand) == 1 and t in vecs and is_person[t] == person]
        lab = [next(iter(rules[t])) for t in ts]
        n = collections.Counter(lab)
        # A big subject mustn't win close calls by headcount: each vote is weighted by (mean size / its subject's size)^PRIOR.
        w = {s: (len(ts) / len(n) / c) ** PRIOR for s, c in n.items()}
        pool[person] = (ts, np.stack([vecs[t] for t in ts]), lab, w)
    votes = {}
    for person in (True, False):
        ts_pool, M, lab, w = pool[person]
        todo = [t for t in rules if t in vecs and is_person[t] == person]
        for i in range(0, len(todo), 2048):
            chunk = todo[i:i + 2048]
            S = np.stack([vecs[t] for t in chunk]) @ M.T
            for j, t in enumerate(chunk):
                row = S[j]
                top = np.argpartition(-row, K + 1)[:K + 1]
                v = collections.Counter()
                for k in top:
                    if ts_pool[k] != t:
                        v[lab[k]] += float(row[k]) * w[lab[k]]
                votes[t] = v
    leads = person_leads(zim_path, [t for t in rules if is_person[t]]) if zim_path else {}
    role_of = {}
    for sj in spec["subjects"]:
        for w in sj.get("roles_en", []):
            role_of[w] = sj["id"]
    result = {}
    how = collections.Counter()
    for t, cand in rules.items():
        sims = dict(votes.get(t, {}))
        lead_w = lead_roles(leads.get(t), role_of) if t in leads else {}
        near = sorted(cand.values())
        if len(cand) == 1 and not lead_w:
            main_s, mode = next(iter(cand)), "rule"
        elif not is_person[t] and len(near) > 1 and near[1] - near[0] >= 2:
            # One subject's root is much closer than the rest: the article is that kind of thing first.
            main_s, mode = min(cand, key=cand.get), "rule"
        elif lead_w:
            # A person: the neighbours' vote share, plus the lead's role words (1, 1/3, 1/9 by order), plus 0.5 for a subject
            # the occupations give. The lead can name a subject Wikidata's occupations miss ("Sheshi was a ruler…").
            tot = sum(sims.values()) or 1.0
            pool_s = set(cand) | set(lead_w)
            main_s = max(pool_s, key=lambda x: sims.get(x, 0) / tot + lead_w.get(x, 0) + (0.5 if x in cand else 0))
            mode = "rule+lead"
        elif len(cand) > 1:
            main_s = max(cand, key=lambda s: sims.get(s, -1)) if sims else min(cand, key=cand.get)
            mode = "rule+meaning"
        elif sims:
            main_s, mode = max(sims, key=sims.get), "meaning"
        else:
            main_s, mode = None, "none"
        how[mode] += 1
        best = max(sims, key=sims.get) if sims else None
        disagree = bool(sims) and mode != "meaning" and best != main_s and sims.get(main_s, 0) < 0.25 * sum(sims.values())
        result[t] = {"q": items[t]["q"], "label": items[t]["l"], "main": main_s, "mode": mode,
                     "rules": sorted(cand), "nearest": best, "flag": disagree}

    counts = collections.Counter(r["main"] for r in result.values())
    report = {"articles": len(items), "dropped_lists": len(dropped), "assigned": sum(1 for r in result.values() if r["main"]),
              "how": dict(how), "flagged": sum(r["flag"] for r in result.values()),
              "by_subject": {s: counts.get(s, 0) for s in ids}, "pool_people": len(pool[True][0]), "pool_things": len(pool[False][0]), "label_mismatches": bad}
    json.dump(result, open(os.path.join(out_dir, "subjects.json"), "w"), ensure_ascii=False)
    json.dump(report, open(os.path.join(out_dir, "report.json"), "w"), indent=1, ensure_ascii=False)
    print(json.dumps(report, indent=1, ensure_ascii=False))
    return result


if __name__ == "__main__":
    main(*sys.argv[1:6])
