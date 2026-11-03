#!/usr/bin/env python3
"""Plan R2 Task 7: Classics book cards, checked against each book's text and written as one EPUB of notes.

  cards.py check CARDS_DIR TEXT_DIR             # every card against text/<slug>.txt; exits 1 on a problem
  cards.py build CARDS_DIR OUT.epub [--date D]  # three files per card, in CARDS_DIR/order.json's order

A card (`<slug>.json`) says how a work begins (its first line, verbatim), what happens and who's who, in under 1,600
characters, written as three passages that each name the book. Questions about a whole book ("how does Pride and
Prejudice start", "who is Darcy") have no single passage in the novel that answers them; the card is that passage.
`verify/<slug>.json` logs each claim's check against the text (2026-11-02: 819 claims, 46 wrong and 20 unfound, all
corrected).
"""
import argparse
import json
import re
import sys
import unicodedata
import zipfile
from html import escape
from pathlib import Path

LIMIT = 1600
TITLE = "Granger Classics: Book Notes"
FIELDS = ("slug", "title", "author", "published", "kind", "firstLine", "begins", "happens", "characters")


def fold(s):
    """Letters and digits only, lowercased and unaccented, so a first line matches across quotes, dashes and line breaks."""
    s = unicodedata.normalize("NFD", s)
    return re.sub(r"[^a-z0-9]+", " ", "".join(c for c in s if not unicodedata.combining(c)).lower()).strip()


def card_length(c):
    return len(c["firstLine"]) + len(c["begins"]) + len(c["happens"]) + sum(len(x["name"]) + len(x["who"]) for x in c["characters"])


def problems(card, text):
    out = [f"missing {f}" for f in FIELDS if not card.get(f)]
    if out:
        return out
    ft = " " + fold(text) + " "
    if " " + fold(card["firstLine"]) + " " not in ft:
        out.append("first line not in the text: " + card["firstLine"][:80])
    n = card_length(card)
    if n > LIMIT:
        out.append(f"{n} characters, over {LIMIT}")
    for ch in card["characters"]:
        # A name is found when its last word is in the text (Mr. Darcy, Fitzwilliam Darcy and Darcy all count).
        # A description standing in for an unnamed character ("the narrator") isn't searched for.
        words = fold(ch["name"]).split()
        if ch["name"][:1].islower():
            continue
        if words and " " + words[-1] + " " not in ft:
            out.append("name not in the text: " + ch["name"])
    return out


MANIFEST = Path(__file__).resolve().parent / "classics/manifest.json"


def ordered(cards_dir):
    """The cards, with the year and translator the Classics manifest gives (a card's writer may have guessed)."""
    works = {w["slug"]: w for w in json.loads(MANIFEST.read_text(encoding="utf-8"))["works"]}
    cards = {}
    for p in sorted(Path(cards_dir).glob("*.json")):
        if p.name == "order.json":
            continue
        c = json.loads(p.read_text(encoding="utf-8"))
        if c["slug"] in works:
            c["published"], c["translator"] = works[c["slug"]]["published"], works[c["slug"]].get("translator")
        cards[p.stem] = c
    order = Path(cards_dir) / "order.json"
    if order.exists():
        slugs = json.loads(order.read_text(encoding="utf-8"))
        return [cards[s] for s in slugs if s in cards] + [c for s, c in cards.items() if s not in slugs]
    return list(cards.values())


def cmd_check(a):
    bad = 0
    for c in ordered(a.cards):
        t = Path(a.text) / (c["slug"] + ".txt")
        ps = problems(c, t.read_text(encoding="utf-8")) if t.exists() else ["no text file"]
        print("%-45s %5d  %s" % (c["slug"], card_length(c) if not ps or "missing" not in ps[0] else 0, "; ".join(ps) or "ok"))
        bad += bool(ps)
    print(f"{bad} with problems")
    return 1 if bad else 0


# When a translated work first appeared; the manifest's year is the translation's.
FIRST = {
    "odyssey": "ancient Greek",
    "crime-and-punishment": "1866",
    "war-and-peace": "1865–1869",
    "grimms-fairy-tales": "1812–1815",
    "brothers-karamazov": "1879–1880",
    "don-quixote": "1605 and 1615",
    "around-the-world-in-eighty-days": "1872",
    "count-of-monte-cristo": "1844–1846",
}


def year(c):
    """The work's date, and its translation's when it is one: "1865–1869; tr. Aylmer Maude and Louise Maude, 1923"."""
    tr = c.get("translator")
    if not tr:
        return str(c["published"])
    first = FIRST.get(c["slug"])
    return f"{first}; tr. {tr}, {c['published']}" if first else f"tr. {tr}, {c['published']}"


def xhtml(head, para):
    return (
        '<?xml version="1.0" encoding="utf-8"?>\n<!DOCTYPE html>\n'
        '<html xmlns="http://www.w3.org/1999/xhtml" xml:lang="en" lang="en">\n'
        f"<head><title>{escape(head)}</title></head>\n<body>\n<h1>{escape(head)}</h1>\n"
        f"<p>{escape(para, quote=False)}</p>\n</body>\n</html>\n"
    )


def quoted(line):
    """A first line in quotation marks, unless it is itself speech in them (“TOM!”)."""
    return line if line[:1] in "“\"‘'" else f"“{line}”"


def sections(c):
    """The card as three files, each a passage that names its book: how it begins, what happens, who's who. The app
    closes a chunk at 400 characters and never joins two files, and the answer prompt cuts a passage near 1,000."""
    by = f" by {c['author']}" if c["author"] else ""
    who = "; ".join(f"{x['name']}, {x['who']}" for x in c["characters"])
    t = c["title"]
    kind = ("an " if c["kind"][:1] in "aeiou" else "a ") + c["kind"]
    return [
        (f"{t}: how it begins", f"How {t} ({kind}{by}, {year(c)}) begins: the first line is {quoted(c['firstLine'])} {c['begins']}"),
        (f"{t}: what happens", f"What happens in {t}, the {c['kind']}{by}: {c['happens']}"),
        (f"{t}: who's who", f"Who's who in {t}{by}: {who}."),
    ]


def build(cards, out, date):
    chapters = [(h, xhtml(h, para)) for c in cards for h, para in sections(c)]
    items = "\n".join(f'<item id="c{i:03d}" href="c{i:03d}.xhtml" media-type="application/xhtml+xml"/>' for i in range(len(chapters)))
    spine = "\n".join(f'<itemref idref="c{i:03d}"/>' for i in range(len(chapters)))
    opf = f"""<?xml version="1.0" encoding="utf-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id" xml:lang="en">
<metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
<dc:identifier id="id">granger-classics-book-notes-{date}</dc:identifier>
<dc:title>{TITLE}</dc:title>
<dc:creator>Granger</dc:creator>
<dc:language>en</dc:language>
<dc:description>How each of the most-read Granger Classics begins, what happens in it and who's who.</dc:description>
<meta property="dcterms:modified">{date}T00:00:00Z</meta>
</metadata>
<manifest>
<item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
{items}
</manifest>
<spine>
{spine}
</spine>
</package>
"""
    # The contents list each book once, at its first section.
    links = "\n".join(f'<li><a href="c{3 * i:03d}.xhtml">{escape(c["title"])}</a></li>' for i, c in enumerate(cards))
    nav = f"""<?xml version="1.0" encoding="utf-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" xml:lang="en" lang="en">
<head><title>Contents</title></head>
<body><nav epub:type="toc"><h1>Contents</h1><ol>
{links}
</ol></nav></body>
</html>
"""
    container = """<?xml version="1.0" encoding="utf-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
<rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
</container>
"""
    stamp = (2020, 1, 1, 0, 0, 0)
    with zipfile.ZipFile(out, "w") as z:
        def put(name, data, method=zipfile.ZIP_DEFLATED):
            z.writestr(zipfile.ZipInfo(name, stamp), data, compress_type=method)
        put("mimetype", "application/epub+zip", zipfile.ZIP_STORED)
        put("META-INF/container.xml", container)
        put("OEBPS/content.opf", opf)
        put("OEBPS/nav.xhtml", nav)
        for i, (_, x) in enumerate(chapters):
            put(f"OEBPS/c{i:03d}.xhtml", x)


def cmd_build(a):
    cards = ordered(a.cards)
    build(cards, a.out, a.date)
    print(f"{len(cards)} cards -> {a.out}")
    return 0


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    s = p.add_subparsers(dest="cmd", required=True)
    c = s.add_parser("check"); c.add_argument("cards"); c.add_argument("text"); c.set_defaults(f=cmd_check)
    b = s.add_parser("build"); b.add_argument("cards"); b.add_argument("out"); b.add_argument("--date", default="2026-11-02")
    b.set_defaults(f=cmd_build)
    a = p.parse_args(argv)
    return a.f(a)


if __name__ == "__main__":
    sys.exit(main())
