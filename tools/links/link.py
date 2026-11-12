#!/usr/bin/env python3
"""Plan LL1 Task 6, steps 2–4: resolve a book's entity spans to Wikidata items (Mac only).

  link.py EPUB NER.jsonl KB.sqlite ZIM OUT.jsonl --lang en|es

For every span (ner.py) it writes the candidate it would link, with the features rules.py thresholds on:
  qid, path, label, sl (sitelinks), cls, alts (other type-fitting candidates), how (title / redirect / stripped),
  sim (EmbeddingGemma: the paragraph against the article's lead), word (the span's word index in its chapter),
  quote, before (the placing text, Decision 4), passage (for the grading page).
Spans that can't link are written with qid null and a reason, so the numbers add up.
"""
import argparse
import collections
import json
import re
import sqlite3
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from kb import norm  # noqa: E402
from text import chapters, title_of  # noqa: E402

# A title in front of a name: a rank title is dropped before the lookup ("General Kutuzov" -> "kutuzov"); a social one
# ("Mr. Bingley", "Doña Rodríguez") marks the book's own people, which Light never links (Decision 8).
RANK = {"en": {"emperor", "empress", "king", "queen", "tsar", "czar", "general", "field marshal", "marshal", "prince",
               "princess", "count", "countess", "baron", "baroness", "colonel", "captain", "major", "admiral", "pope",
               "saint", "st", "lord", "duke", "duchess", "archduke", "grand duke", "the emperor", "the king", "the tsar"},
        "es": {"rey", "reina", "emperador", "duque", "duquesa", "conde", "condesa", "marqués", "marquesa", "príncipe",
               "princesa", "san", "santo", "santa", "papa", "el rey", "el emperador", "el gran"}}
SOCIAL = {"en": {"mr", "mrs", "miss", "ms", "master", "madame", "mademoiselle", "monsieur", "sir", "lady", "dr",
                 "uncle", "aunt", "cousin", "old", "young", "little"},
          "es": {"don", "doña", "señor", "señora", "señorita", "maese", "fray", "padre", "micer", "mosén", "tío", "tía"}}
FITS = {"PERSON": {"human"}, "PER": {"human"},
        "GPE": {"place"}, "LOC": {"place", "other"}, "FAC": {"place", "other"},
        "ORG": {"org"}, "NORP": set(),  # nationalities and peoples: too noisy for Light
        "EVENT": {"event"}, "WORK_OF_ART": {"work"}, "MISC": {"work", "event"}}
POSSESSIVE = re.compile(r"[’']s$")
SAINT = {"saint", "st", "san", "santo", "santa", "são"}
# Words in SOCIAL that sit before places too: "old Moscow", "Little Mytíshchi", "el padre Tajo" (the river).
NOT_TITLES = {"old", "young", "little", "padre"}
# A mononym person ("Teodora", "Jerome", "Titus") whose name dozens of other people's articles also start with is a
# given name the book's own people carry, unless the one it links to is very well known.
GIVEN_NAME_BEARERS, GIVEN_NAME_SL = 30, 120


def proper(t):
    """A name as printed: starts with a capital, has three letters or more, no dash run ("the ——shire")."""
    return bool(t) and t[0].isupper() and sum(c.isalpha() for c in t) >= 3 and "——" not in t and "--" not in t


def agrees(n, c):
    """For people and works, the printed name must be the item's own name, not just any redirect to it: the whole
    label or title ("napoleon"), its surname ("kutuzov" for Mikhail Kutuzov), or a longer form of it
    ("miguel de cervantes saavedra"). "Maria" -> a skier and "Prince Andrew" -> the Duke of York fail here."""
    names = {norm(c["label"]), norm(re.sub(r"_\(.*\)$", "", c["path"]))}
    for full in names:
        if not full:
            continue
        if n == full or n.startswith(full + " "):
            return True
        if " " not in n and full.split()[-1] == n and len(full.split()) > 1:
            return True
    return False


def lookups(text, label, lang):
    """(normalised surface, how) to try, best first; empty for a span Light never links."""
    t = POSSESSIVE.sub("", text.strip()).rstrip(".,;:!?")
    words = t.split()
    first = words[0].lower().rstrip(".") if words else ""
    if label in ("PERSON", "PER") and first in SOCIAL[lang]:
        return []
    if not proper(t) or t.lower() in RANK[lang]:
        return [("", "not-a-name")]
    out = [(norm(t), "surface")]
    low = t.lower()
    for r in sorted(RANK[lang], key=len, reverse=True):
        if low.startswith(r + " ") and len(t) > len(r) + 1:
            out.append((norm(t[len(r) + 1:]), "stripped"))
            break
    return [(n, how) for n, how in out if n] or [("", "not-a-name")]


class KB:
    def __init__(self, path):
        self.db = sqlite3.connect(f"file:{path}?mode=ro", uri=True)
        self.cache = {}

    def candidates(self, n):
        if n not in self.cache:
            rows = self.db.execute(
                "SELECT i.qid, i.path, i.sl, i.label, i.cls, MAX(a.kind), i.year, GROUP_CONCAT(a.raw, '\x1f') "
                "FROM alias a JOIN item i ON i.qid = a.qid WHERE a.norm = ? GROUP BY i.qid ORDER BY i.sl DESC",
                (n,)).fetchall()
            self.cache[n] = [dict(qid=r[0], path=r[1], sl=r[2], label=r[3], cls=r[4], kind=r[5], year=r[6],
                                  raw=set((r[7] or "").split("\x1f"))) for r in rows]
        return self.cache[n]

    def bearers(self, path):
        """How many people's articles start with this one-word title as their first name ("Teodora_Comnena")."""
        if "_" in re.sub(r"_\(.*\)$", "", path):
            return 0
        lo = re.sub(r"_\(.*\)$", "", path) + "_"
        return self.db.execute("SELECT COUNT(*) FROM item WHERE path >= ? AND path < ? AND cls = 'human' "
                               "AND path NOT LIKE ?", (lo, lo + "\uffff", lo + "(%")).fetchone()[0]


class BookFacts:
    """What the book itself says about a name (plan Decision 8): the words it prints in lowercase (so "Tornó" or
    "Mass" at a sentence start is a common word), how often each name is tagged a person (the Rostóvs aren't the
    city), the names it prints after a title ("reina Ginebra", "don Lanzarote": people, not Geneva or the island),
    and the year it was written (nobody born after it is in it)."""

    def __init__(self, texts, spans, year=None, title="", lang="en"):
        self.lower, self.titled = set(), set()
        titles = sorted((RANK.get(lang, set()) | SOCIAL.get(lang, set())) - SAINT - NOT_TITLES, key=len, reverse=True)
        # Same line, a space or an abbreviation's dot between: "master.\nEurope" is two sentences.
        after = re.compile(r"\b(?:%s)\.? ([A-ZÁÉÍÓÚÑÜ][\w’'-]+)" % "|".join(map(re.escape, titles)), re.I)
        for t in texts:
            self.lower.update(w for w in re.findall(r"\b\w+\b", t) if w.islower())
            self.titled.update(norm(POSSESSIVE.sub("", m.group(1))) for m in after.finditer(t))
        tags = collections.defaultdict(collections.Counter)
        for s in spans:
            tags[norm(POSSESSIVE.sub("", s["text"]))][s["label"] in ("PERSON", "PER")] += 1
        self.person_share = {n: c[True] / sum(c.values()) for n, c in tags.items()}
        self.year, self.title = year, title


def resolve(kb, text, label, lang, book_title="", facts=None):
    """(candidate or None, alternatives, how, reason)."""
    facts = facts or BookFacts([], [], title=book_title)
    book_title = book_title or facts.title
    tries = lookups(text, label, lang)
    bare = POSSESSIVE.sub("", text.strip()).rstrip(".,;:!?")
    words = bare.split()
    if tries and tries != [("", "not-a-name")] and " " not in bare and bare.lower() in facts.lower:
        return None, [], None, "common-word"
    if not tries:
        return None, [], None, "social-title"
    if tries == [("", "not-a-name")]:
        return None, [], None, "not-a-name"
    fits = FITS.get(label, set())
    if not fits:
        return None, [], None, f"label-{label}"
    seen_any, disagreed = False, False
    for n, how in tries:
        cands = kb.candidates(n)
        seen_any |= bool(cands)
        if any(c["cls"] in ("fictional",) for c in cands):
            return None, [], how, "fictional"
        good = [c for c in cands if c["cls"] in fits and not c["label"][:1].islower()]  # "lake", "forest": common nouns
        bt = norm(book_title)
        if bt and any(bt in norm(c["label"]) or bt in norm(c["path"]) for c in good):
            return None, [], how, "the-book-itself"
        named = [c for c in good if c["cls"] not in ("human", "work") or agrees(n, c)]
        disagreed |= len(named) < len(good)
        good = named
        if lang == "es":   # Spanish accents are spelling: "Morón" isn't "Mörön"
            exact = [c for c in good if bare.casefold() in {r.casefold() for r in c["raw"]} or how == "stripped"]
            disagreed |= len(exact) < len(good)
            good = exact
        if not good:
            continue
        if facts.year is not None:
            late = [c for c in good if c["year"] is not None and c["cls"] != "place" and
                    c["year"] > facts.year - (10 if c["cls"] == "human" else 0)]
            if len(late) == len(good):
                return None, [], how, "after-the-book"
            good = [c for c in good if c not in late]
        if good[0]["cls"] == "place" and (facts.person_share.get(n, 0) >= 0.5 or n in facts.titled):
            return None, [], how, "a-person-here"
        if (good[0]["cls"] == "place" and words and words[0].lower().rstrip(".") in SAINT
                and any(c["cls"] == "human" for c in cands)):
            return None, [], how, "a-saint"      # "Santa Lucía" is the saint, not the island country
        # A title beats a redirect ("Moscow" the article over a redirect that happens to normalise the same).
        good.sort(key=lambda c: (c["kind"] != "title", -c["sl"]))
        best, alts = good[0], good[1:]
        if (best["cls"] == "human" and len(words) == 1 and best["sl"] < GIVEN_NAME_SL
                and kb.bearers(best["path"]) >= GIVEN_NAME_BEARERS):
            return None, [], how, "a-given-name"
        return best, alts, how, None
    if not seen_any:
        return None, [], None, "no-article"
    return None, [], None, "name-disagrees" if disagreed else "type-mismatch"


def leads(zim_path, paths):
    """The first real paragraph of each article (the candidate's description for the context check)."""
    from libzim.reader import Archive
    from lxml import html as lhtml
    z = Archive(zim_path)
    out = {}
    for p in paths:
        try:
            e = z.get_entry_by_path(p)
            if e.is_redirect:
                e = e.get_redirect_entry()
            doc = lhtml.fromstring(bytes(e.get_item().content))
            for para in doc.iter("p"):
                t = re.sub(r"\s+", " ", para.text_content()).strip()
                t = re.sub(r"\[\d+\]", "", t)
                if len(t) > 60:
                    out[p] = t[:600]
                    break
        except Exception:
            pass
    return out


def main(argv=None):
    a = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    a.add_argument("epub"); a.add_argument("ner"); a.add_argument("kb"); a.add_argument("zim"); a.add_argument("out")
    a.add_argument("--lang", required=True, choices=("en", "es"))
    a.add_argument("--year", type=int, help="the year the book was written (nobody born after it is linked)")
    args = a.parse_args(argv)

    text = {href: "\n".join(t for _, t in bs) for href, bs in chapters(args.epub)}
    book_title = title_of(args.epub)
    spans = [json.loads(l) for l in open(args.ner, encoding="utf8")]
    facts = BookFacts(text.values(), spans, args.year, book_title, args.lang)
    kb = KB(args.kb)
    rows, reasons = [], collections.Counter()
    for s in spans:
        ch = text[s["href"]]
        best, alts, how, why = resolve(kb, s["text"], s["label"], args.lang, book_title, facts)
        if s["heading"]:
            best, why = None, "heading"
        r = dict(s, word=len(ch[:s["start"]].split()), quote=ch[s["start"]:s["start"] + s["len"]],
                 before=ch[max(0, s["start"] - 40):s["start"]])
        if best is None:
            reasons[why] += 1
            rows.append(dict(r, qid=None, reason=why))
            continue
        b = ch.rfind("\n", 0, s["start"]) + 1
        e = ch.find("\n", s["start"]); e = len(ch) if e < 0 else e
        para = ch[b:e]
        rows.append(dict(r, qid=best["qid"], path=best["path"], label_wd=best["label"], sl=best["sl"],
                         cls=best["cls"], kind=best["kind"], how=how,
                         alts=[[c["qid"], c["label"], c["sl"]] for c in alts[:3]], para=para))
        reasons["candidate"] += 1

    # The context check (step 3): the paragraph against the candidate article's lead, both with EmbeddingGemma.
    linked = [r for r in rows if r["qid"]]
    lead = leads(args.zim, sorted({r["path"] for r in linked}))
    from sentence_transformers import SentenceTransformer
    import numpy as np
    model = SentenceTransformer("google/embeddinggemma-2", device="mps")
    paths = sorted(lead)
    dv = model.encode(["title: %s | text: %s" % (p.replace("_", " "), lead[p]) for p in paths],
                      batch_size=32, normalize_embeddings=True)
    dvec = dict(zip(paths, dv))
    paras = sorted({r["para"][:700] for r in linked})
    qv = dict(zip(paras, model.encode(["task: search result | query: %s" % p for p in paras], batch_size=32,
                                      normalize_embeddings=True)))
    for r in linked:
        v = dvec.get(r["path"])
        r["sim"] = round(float(np.dot(qv[r["para"][:700]], v)), 4) if v is not None else None
        r["lead"] = lead.get(r["path"], "")[:300]
        r["passage"] = r.pop("para")[:700]

    with open(args.out, "w", encoding="utf8") as out:
        for r in rows:
            out.write(json.dumps(r, ensure_ascii=False) + "\n")
    print(f"{Path(args.epub).name}: {len(spans):,} spans; " + ", ".join(f"{k} {v:,}" for k, v in reasons.most_common()),
          file=sys.stderr)


if __name__ == "__main__":
    sys.exit(main())
