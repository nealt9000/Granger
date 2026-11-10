"""Plan SQ Task 1.1: the known-item set for Search, drawn from the eval emulator's own ZIMs.

Each item is a query someone types to reach one thing they know exists, and the article that is that thing:

    exact     the title as written                      "Photosynthesis"
    lower     lower case, accents dropped               "rene descartes"
    typo      one slip (two letters swapped or dropped) "Photosytnhesis"
    prefix    the start, as typed before results show   "photosyn"
    alias     a redirect's title, to its article        "heart attack" -> Myocardial infarction

Sampling is seeded, so a rebuild gives the same set. Titles with digits, parentheses, commas, "List of" and the like are
left out: their queries are ambiguous and a person rarely types them.

    ~/.venvs/granger-vectors/bin/python -I tools/eval/known_items.py build [--seed 11] [--out tools/eval/sets/known-items.json]

The packs come from ~/.cache/granger/emu-stage/pack-sources.txt (name, path on the Mac), the same files the emulator holds.
"""

import argparse
import json
import random
import re
import sys
import unicodedata
from pathlib import Path

PACK_SOURCES = Path.home() / ".cache/granger/emu-stage/pack-sources.txt"

# zim-name prefix -> (language, {kind: count}). Wikipedia top carries most: it is what people look up. The dictionaries
# are left out: a uniform sample of Wiktionary is inflections and other languages' words, not what anyone looks up
# (dictionary lookups get their own check with the Define card, Plan SQ Task 5.4).
PLAN = {
    "wikipedia_en_top": ("en", {"exact": 15, "lower": 10, "typo": 12, "prefix": 10, "alias": 15}),
    "wikipedia_en_medicine": ("en", {"exact": 3, "lower": 3, "typo": 2, "alias": 4}),
    "wikipedia_en_history": ("en", {"exact": 3, "lower": 2, "typo": 2, "alias": 3}),
    "wikipedia_en_geography": ("en", {"exact": 3, "lower": 2, "typo": 2, "alias": 3}),
    "wikipedia_en_chemistry": ("en", {"exact": 2, "lower": 2, "typo": 2}),
    "wikem_en_all": ("en", {"exact": 3, "lower": 3, "typo": 2}),
    "wikivoyage_en_all": ("en", {"exact": 4, "lower": 3, "typo": 2}),
    "ifixit_en_all": ("en", {"exact": 3, "lower": 2}),
    "appropedia_en_all": ("en", {"exact": 3, "lower": 2}),
    "internet-encyclopedia-philosophy_en": ("en", {"exact": 3, "lower": 2, "typo": 1}),
    "granger_classics_en": ("en", {"exact": 4, "lower": 3, "prefix": 2}),
    "wikipedia_es_top": ("es", {"exact": 12, "lower": 10, "typo": 8, "prefix": 6, "alias": 10}),
    "wikivoyage_es_all": ("es", {"exact": 6, "lower": 4, "typo": 3}),
}

SKIP = re.compile(r"[\d(),:/;\[\]{}<>|#_]|^(list|lists|index|outline|glossary|timeline|category|template|help|portal|special|lista|anexo|categoría|plantilla) ", re.I)


def fold(text: str) -> str:
    """Lower case, accents dropped: as Search folds (SearchText.fold)."""
    return "".join(c for c in unicodedata.normalize("NFD", text) if unicodedata.category(c) != "Mn").lower()


def usable(title: str, pack: str = "", path: str = "") -> bool:
    """A title a person would type to reach it; [pack] and [path] rule out a pack's own junk (user pages, chapters)."""
    words = title.split()
    if "/User/" in "/" + path or path.startswith("User"):
        return False
    if pack.startswith("granger_classics") and ("/" in path or " — " in title):
        return False  # a book's own page, not its chapters
    if pack.startswith("wiktionary_en") and not title.isascii():
        return False  # English Wiktionary's other-language words
    return (
        3 <= len(title) <= 40
        and 1 <= len(words) <= 4
        and not SKIP.search(title)
        and any(c.isalpha() for c in title)
    )


def typo(title: str, rng: random.Random) -> str | None:
    """One slip in the longest word of five letters or more: two neighbours swapped, or one letter dropped, never the first."""
    words = title.split()
    i = max(range(len(words)), key=lambda k: len(words[k]))
    w = words[i]
    if len(w) < 5:
        return None
    p = rng.randrange(1, len(w) - 1)
    if rng.random() < 0.5 and w[p] != w[p + 1]:
        w2 = w[:p] + w[p + 1] + w[p] + w[p + 2:]
    else:
        w2 = w[:p] + w[p + 1:]
    words[i] = w2
    return " ".join(words)


def prefix(title: str) -> str | None:
    """The first ~60% of the title, at least six letters, cut mid-word as typing would."""
    n = max(6, round(len(title) * 0.6))
    return fold(title[:n]).rstrip() if n < len(title) else None


def html_entry(archive, i):
    entry = archive._get_entry_by_id(i)
    if entry.is_redirect:
        return entry, None
    try:
        item = entry.get_item()
    except Exception:
        return entry, None
    return entry, item if item.mimetype.startswith("text/html") else None


def sample(archive, kinds: dict, rng: random.Random, pack: str, lang: str, taken: set):
    out = []
    want = dict(kinds)
    count = archive.all_entry_count
    tries = 0
    while any(v > 0 for v in want.values()) and tries < 400_000:
        tries += 1
        entry, item = html_entry(archive, rng.randrange(count))
        if want.get("alias", 0) > 0 and entry.is_redirect:
            try:
                target = entry.get_redirect_entry()
            except Exception:
                continue
            alias, title = entry.title, target.title
            if (usable(alias) and usable(title, pack, target.path) and fold(alias) != fold(title) and fold(alias) not in fold(title)
                    and fold(title) not in fold(alias) and not target.is_redirect and title not in taken):
                taken.add(title)
                out.append(item_of(pack, lang, "alias", alias, title, target.path))
                want["alias"] -= 1
            continue
        if item is None or not usable(entry.title, pack, entry.path) or entry.title in taken:
            continue
        title = entry.title
        for kind in ("exact", "lower", "typo", "prefix"):
            if want.get(kind, 0) <= 0:
                continue
            query = {"exact": title, "lower": fold(title), "typo": typo(title, rng), "prefix": prefix(title)}[kind]
            if not query or (kind == "lower" and query == title):
                continue
            taken.add(title)
            out.append(item_of(pack, lang, kind, query, title, entry.path))
            want[kind] -= 1
            break
    missing = {k: v for k, v in want.items() if v > 0}
    if missing:
        print(f"  {pack}: short {missing}", file=sys.stderr)
    return out


def item_of(pack, lang, kind, query, title, path):
    return {"query": query, "kind": kind, "language": lang, "pack": pack, "title": title, "path": path}


def build(args):
    from libzim.reader import Archive

    sources = {}
    for line in PACK_SOURCES.read_text().splitlines():
        if line.strip():
            name, src = line.split(" ", 1)
            sources[name] = src
    rng = random.Random(args.seed)
    items = []
    for prefix_name, (lang, kinds) in PLAN.items():
        name = next((n for n in sorted(sources) if n.startswith(prefix_name)), None)
        if name is None:
            print(f"  no pack for {prefix_name}", file=sys.stderr)
            continue
        print(f"{name}: {kinds}", file=sys.stderr)
        archive = Archive(sources[name])
        items += sample(archive, kinds, rng, name, lang, set())
    for n, item in enumerate(items, 1):
        item["id"] = f"ki-{item['language']}-{n:03d}"
    out = Path(args.out)
    out.write_text(json.dumps({"seed": args.seed, "items": items}, ensure_ascii=False, indent=1) + "\n")
    by = {}
    for it in items:
        by[(it["language"], it["kind"])] = by.get((it["language"], it["kind"]), 0) + 1
    print(f"{len(items)} items -> {out}: {dict(sorted(by.items()))}", file=sys.stderr)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    b = sub.add_parser("build")
    b.add_argument("--seed", type=int, default=11)
    b.add_argument("--out", default="tools/eval/sets/known-items.json")
    b.set_defaults(fn=build)
    args = ap.parse_args()
    args.fn(args)


if __name__ == "__main__":
    main()
