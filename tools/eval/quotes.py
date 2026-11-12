#!/usr/bin/env python3
"""Plan SQ Step 4: a quote set from the Granger Classics books, for Search's Books group and Best matches.

    python3 tools/eval/quotes.py BOOKS_DIR tools/eval/sets/quotes.json [--per-kind 25] [--seed 7]

BOOKS_DIR holds the classics EPUBs as the app has them (gc-<slug>_<date>.epub; the eval emulator's are in
~/.cache/granger/emu-stage/files/books/granger). Each item is a line someone might remember from a book, with the book
(its shelf id, book:granger:<slug>) and the chapter (the EPUB href) it comes from:

    exact     8-12 words of a sentence, as printed
    fragment  4-5 distinctive words from inside a sentence
    loose     an exact line with one inner word left out (remembered almost right)
    plain     an exact line lower-cased with its punctuation dropped (typed quickly)
    famous    well-known lines (FAMOUS), kept only where the text has them

Spanish books (gc-es-*) give items with language "es". Lines are drawn from the books' chapters (no colophon, cover or
navigation), skipping verse-like short lines and text that occurs more than once in the book.
"""
import argparse
import html
import json
import random
import re
import sys
import zipfile
from pathlib import Path

FAMOUS = [
    ("It is a truth universally acknowledged", "gc-pride-and-prejudice"),
    ("Call me Ishmael", "gc-moby-dick"),
    ("It was the best of times, it was the worst of times", "gc-tale-of-two-cities"),
    ("Happy families are all alike", "gc-anna-karenina"),
    ("To be, or not to be, that is the question", "gc-hamlet"),
    ("Something is rotten in the state of Denmark", "gc-hamlet"),
    ("Alas, poor Yorick", "gc-hamlet"),
    ("In a village of La Mancha", "gc-don-quixote"),
    ("all that glisters is not gold", "gc-merchant-of-venice"),
    ("The quality of mercy is not strained", "gc-merchant-of-venice"),
    ("Now is the winter of our discontent", "gc-king-richard-iii"),
    ("A horse! a horse! my kingdom for a horse", "gc-king-richard-iii"),
    ("All the world's a stage", "gc-as-you-like-it"),
    ("If music be the food of love, play on", "gc-twelfth-night"),
    ("Double, double toil and trouble", "gc-macbeth"),
    ("Out, damned spot", "gc-macbeth"),
    ("Friends, Romans, countrymen, lend me your ears", "gc-julius-caesar"),
    ("Wherefore art thou Romeo", "gc-romeo-and-juliet"),
    ("Reader, I married him", "gc-jane-eyre"),
    ("Whatever our souls are made of, his and mine are the same", "gc-wuthering-heights"),
    ("Of all the gin joints", None),  # not in the collection: a control that should find nothing in Books
    ("Hope is the thing with feathers", "gc-poems-three-series"),
    ("Because I could not stop for Death", "gc-poems-three-series"),
    ("Water, water, every where", "gc-rime-of-the-ancient-mariner"),
    ("The time has come, the Walrus said", "gc-through-the-looking-glass"),
    ("Curiouser and curiouser", "gc-alice-s-adventures-in-wonderland"),
    ("Elementary, my dear Watson", None),  # never in the stories: a control
    ("You have been in Afghanistan, I perceive", "gc-study-in-scarlet"),
    ("When you have eliminated the impossible", "gc-adventures-of-sherlock-holmes"),
    ("To a great mind, nothing is little", "gc-study-in-scarlet"),
]

SKIP = re.compile(r"(cover|colophon|nav|toc|title|imprint|uncopyright)", re.I)
TAG = re.compile(r"<[^>]+>")
BLOCK = re.compile(r"</?(?:p|h[1-6]|li|div|br|blockquote|pre|td|tr|dd|dt|table|section)\b[^>]*>", re.I)
SPACE = re.compile(r"\s+")
WORD = re.compile(r"[\w'’-]+", re.U)
STOP = set("a an and the of to in on at for with by from as is was be it its that this or but not no he she they we you i his her their our your my me him them us so if then than there here who whom which what when where why how all any".split())
STOP_ES = set("el la los las un una unos unas y o de del al a en con por para que se su sus lo le les es era fue no ni mas más como pero si ya muy me mi te tu".split())


def chapters(epub):
    """(href, plain text) of each chapter of [epub] in spine order, skipping front and back matter."""
    with zipfile.ZipFile(epub) as z:
        opf_path = next(n for n in z.namelist() if n.endswith(".opf"))
        opf = z.read(opf_path).decode("utf-8", "replace")
        base = opf_path.rsplit("/", 1)[0] + "/" if "/" in opf_path else ""
        items = dict(re.findall(r'<item[^>]*id="([^"]+)"[^>]*href="([^"]+)"', opf))
        items.update({i: h for h, i in re.findall(r'<item[^>]*href="([^"]+)"[^>]*id="([^"]+)"', opf)})
        for idref in re.findall(r'<itemref[^>]*idref="([^"]+)"', opf):
            href = items.get(idref)
            if not href or SKIP.search(href):
                continue
            raw = z.read(base + href).decode("utf-8", "replace")
            body = raw.split("<body", 1)[-1]
            # Block tags part words; inline ones don't (a drop cap: <span>I</span>T is), as the app's Jsoup text() reads it.
            text = html.unescape(TAG.sub("", BLOCK.sub(" ", body)))
            yield base + href, SPACE.sub(" ", text).strip()


def sentences(text):
    return [s.strip() for s in re.split(r"(?<=[.!?;])\s+", text) if s.strip()]


def plain(text):
    return " ".join(WORD.findall(text.lower().replace("’", "'")))


def distinctive(words, lang):
    stop = STOP_ES if lang == "es" else STOP
    return sum(1 for w in words if w.lower() not in stop and len(w) > 3)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("books")
    ap.add_argument("out")
    ap.add_argument("--per-kind", type=int, default=25)
    ap.add_argument("--seed", type=int, default=7)
    a = ap.parse_args()
    rng = random.Random(a.seed)
    epubs = {re.sub(r"_\d{4}-\d{2}-\d{2}$", "", p.stem): p for p in sorted(Path(a.books).glob("gc-*.epub"))}
    texts = {}

    def book(slug):
        if slug not in texts:
            texts[slug] = list(chapters(epubs[slug]))
        return texts[slug]

    items = []
    # Famous lines, where the book is in the collection and holds the line (folded); the controls kept as they are.
    for line, slug in FAMOUS:
        lang = "es" if (slug or "").startswith("gc-es-") else "en"
        if slug is None:
            items.append({"query": line, "kind": "famous", "language": lang, "book": None, "href": None})
            continue
        if slug not in epubs:
            continue
        want = plain(line)
        href = next((h for h, t in book(slug) if want in plain(t)), None)
        if href:
            items.append({"query": line, "kind": "famous", "language": lang, "book": "book:granger:" + slug, "href": href})
    # Sampled lines: per kind, from books drawn at random (Spanish books in proportion, at least a few).
    slugs = list(epubs)
    es = [s for s in slugs if s.startswith("gc-es-")]
    for kind in ("exact", "fragment", "loose", "plain"):
        made = 0
        tries = 0
        while made < a.per_kind and tries < a.per_kind * 60:
            tries += 1
            slug = rng.choice(es) if es and made % 5 == 4 else rng.choice(slugs)
            lang = "es" if slug.startswith("gc-es-") else "en"
            chs = [c for c in book(slug) if len(c[1]) > 2000]
            if not chs:
                continue
            href, text = rng.choice(chs)
            ss = [s for s in sentences(text) if 14 <= len(s.split()) <= 60]
            if not ss:
                continue
            words = rng.choice(ss).split()
            if kind == "fragment":
                n = rng.randint(4, 5)
                start = rng.randint(1, max(1, len(words) - n - 1))
                pick = words[start:start + n]
                if distinctive(pick, lang) < 2:
                    continue
            else:
                n = rng.randint(8, 12)
                start = rng.randint(0, max(0, len(words) - n))
                pick = words[start:start + n]
                if distinctive(pick, lang) < 3:
                    continue
            line = " ".join(pick).strip(" ,;:—-\"“”'‘’")
            whole = plain(" ".join(t for _, t in book(slug)))
            if whole.count(plain(line)) != 1:
                continue  # said twice in the book: no one chapter is the answer
            if kind == "loose":
                inner = [i for i in range(1, len(pick) - 1) if pick[i].lower() not in (STOP_ES if lang == "es" else STOP)]
                if not inner:
                    continue
                drop = rng.choice(inner)
                line = " ".join(pick[:drop] + pick[drop + 1:]).strip(" ,;:—-\"“”'‘’")
            elif kind == "plain":
                line = plain(line)
            items.append({"query": line, "kind": kind, "language": lang, "book": "book:granger:" + slug, "href": href})
            made += 1
    for n, it in enumerate(items):
        it["id"] = "q-%s-%03d" % (it["language"], n)
    Path(a.out).write_text(json.dumps({"seed": a.seed, "items": items}, ensure_ascii=False, indent=1), encoding="utf-8")
    kinds = {}
    for it in items:
        kinds[it["kind"]] = kinds.get(it["kind"], 0) + 1
    print(len(items), "items", kinds, file=sys.stderr)


if __name__ == "__main__":
    main()
