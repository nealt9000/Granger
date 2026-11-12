#!/usr/bin/env python3
"""A quote set whose lines use the other spelling (British for American, or the reverse), for Search's spelling match.

    python3 tools/eval/spelling_quotes.py BOOKS_DIR tools/eval/sets/spelling-quotes.json [--per-kind 25] [--seed 5]

Neal searched "the dapple-grey horse" and missed The Clouds, whose translation prints "dapple-gray". Each item is a line
from an English Classics book (as quotes.py reads them) holding a word from PAIRS, with that word swapped to its other
spelling, scored like quotes.json (search_score.py):

    spelling        8-12 words of a sentence, as printed but for the swapped word
    spelling-plain  the same, lower-cased with its punctuation dropped
    spelling-short  4-5 words around the swapped word

PAIRS is a subset of the app's table (core/text/.../Spellings.kt): common words in both directions.
"""
import argparse
import json
import random
import re
from pathlib import Path

from quotes import STOP, chapters, distinctive, plain, sentences

PAIRS = dict(p.split() for p in """
grey gray|colour color|colours colors|coloured colored|honour honor|honours honors|honourable honorable|favour favor
favourite favorite|neighbour neighbor|neighbours neighbors|neighbourhood neighborhood|labour labor|humour humor
harbour harbor|behaviour behavior|vapour vapor|splendour splendor|valour valor|rumour rumor|parlour parlor|armour armor
odour odor|ardour ardor|centre center|theatre theater|sombre somber|travelled traveled|travelling traveling
quarrelled quarreled|marvellous marvelous|realise realize|realised realized|recognised recognized|apologise apologize
civilised civilized|defence defense|offence offense|plough plow|mould mold|sceptical skeptical|jewellery jewelry
judgement judgment|waggon wagon|gaol jail|practised practiced|smouldering smoldering
""".replace("\n", "|").strip("|").split("|"))
PAIRS.update({us: uk for uk, us in list(PAIRS.items())})


def swap(word):
    """[word] with its spelling swapped, keeping a capital and trailing punctuation; None when it has no pair."""
    m = re.match(r"^([\"“‘'(]*)([A-Za-z]+)(.*)$", word)
    if not m or m.group(2).lower() not in PAIRS:
        return None
    other = PAIRS[m.group(2).lower()]
    if m.group(2)[0].isupper():
        other = other[0].upper() + other[1:]
    return m.group(1) + other + m.group(3)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("books")
    ap.add_argument("out")
    ap.add_argument("--per-kind", type=int, default=25)
    ap.add_argument("--seed", type=int, default=5)
    a = ap.parse_args()
    rng = random.Random(a.seed)
    epubs = {re.sub(r"_\d{4}-\d{2}-\d{2}$", "", p.stem): p for p in sorted(Path(a.books).glob("gc-*.epub"))
             if not p.stem.startswith("gc-es-")}
    # Every sentence with a word that has another spelling, by book.
    found = []
    for slug, path in epubs.items():
        chs = list(chapters(path))
        whole = plain(" ".join(t for _, t in chs))
        for href, text in chs:
            for s in sentences(text):
                words = s.split()
                if 10 <= len(words) <= 60 and any(swap(w) for w in words):
                    found.append((slug, href, words, whole))
    rng.shuffle(found)
    items, used = [], set()
    for kind in ("spelling", "spelling-plain", "spelling-short"):
        made = 0
        for slug, href, words, whole in found:
            if made >= a.per_kind:
                break
            at = [i for i, w in enumerate(words) if swap(w)]
            i = rng.choice(at)
            n = rng.randint(4, 5) if kind == "spelling-short" else rng.randint(8, 12)
            start = max(0, min(i - rng.randint(0, n - 1), len(words) - n))
            pick = words[start:start + n]
            if distinctive(pick, "en") < (2 if kind == "spelling-short" else 3):
                continue
            line = " ".join(pick).strip(" ,;:—-\"“”'‘’")
            if (slug, line) in used or whole.count(plain(line)) != 1:
                continue  # said twice in the book: no one chapter is the answer
            used.add((slug, line))
            k = i - start
            pick = pick[:k] + [swap(pick[k])] + pick[k + 1:]
            query = " ".join(pick).strip(" ,;:—-\"“”'‘’")
            if kind == "spelling-plain":
                query = plain(query)
            items.append({"query": query, "kind": kind, "language": "en", "book": "book:granger:" + slug, "href": href})
            made += 1
    for n, it in enumerate(items):
        it["id"] = "s-en-%03d" % n
    Path(a.out).write_text(json.dumps({"seed": a.seed, "items": items}, ensure_ascii=False, indent=1), encoding="utf-8")
    print("%d items, %d books with such sentences" % (len(items), len({f[0] for f in found})))


if __name__ == "__main__":
    main()
