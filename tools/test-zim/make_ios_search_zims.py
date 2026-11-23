"""Builds the three tiny ZIMs the iPhone's Search tests read (Plan I7 Task 7b), into testdata/ios-search/:

    ~/.venvs/granger-pack/bin/python tools/test-zim/make_ios_search_zims.py testdata/ios-search

- `wiktionary-test.zim`: a Wiktionary in the English Wiktionary's shape (`Name` starts `wiktionary_`; headings in
  div.mw-heading, quotations nested in senses, a second language after the first): "betimes", "cat" and "cats" (an
  inflected form whose entry leads to its lemma's). The Define card reads it.
- `slice-small.zim` and `slice-big.zim`: two slices of one set (`Name` granger_testset_small / granger_testset_big, one
  family), sharing the article "Lighthouse" at the same path (the small one's says more of "lighthouse keeper", and its title sorts
  first, so it is the copy Search would pick); the big one has one more. With the catalog naming them a
  set, the small slice's hits for an article the big one has are left out (Plan WF Decision 6).

All three have a full-text index. Clusters are stored uncompressed.
"""
import sys
from pathlib import Path

from libzim.writer import Compression, Creator

from make_test_zim import Entry


def wikt(title, body):
    return (f'<!DOCTYPE html><html><head><meta charset="utf-8"><title>{title}</title></head>'
            f'<body><div class="mw-parser-output">{body}</div></body></html>')


def h(level, text):
    return f'<div class="mw-heading mw-heading{level}"><h{level} id="{text.replace(" ", "_")}">{text}</h{level}></div>'


QUOTES = "<ul><li>1611, King James Version: " + "and rose up betimes in the morning " * 20 + "</li></ul>"
BETIMES = wikt("betimes", h(2, "English") + h(3, "Etymology") + "<p>From Middle English betymes, from bi- and time.</p>"
               + h(3, "Adverb") + "<p>betimes (not comparable)</p>"
               + f"<ol><li>(dated) In good season or time; early, especially in the morning.{QUOTES}</li>"
               + "<li>(archaic) In a short time, soon.</li></ol>"
               + h(4, "Translations") + "<p>French: de bonne heure</p>"
               + h(2, "Middle English") + h(3, "Adverb") + "<ol><li>at an early hour</li></ol>")
CAT = wikt("cat", h(2, "English") + h(3, "Etymology") + "<p>From Middle English cat, from Old English catt.</p>"
           + h(3, "Noun") + "<p>cat (plural cats)</p>"
           + "<ol><li>A domesticated species of feline, kept as a house pet or for catching mice.</li></ol>"
           + h(2, "Dutch") + h(3, "Noun") + "<ol><li>a slap, a blow</li></ol>")
CATS = wikt("cats", h(2, "English") + h(3, "Noun") + "<ol><li>plural of cat</li></ol>"
            + h(2, "Dutch") + h(3, "Noun") + "<ol><li>plural of cat</li></ol>")


def page(title, body):
    return f'<!DOCTYPE html><html><head><meta charset="utf-8"><title>{title}</title></head><body><h1>{title}</h1>{body}</body></html>'


LIGHTHOUSE = page("Lighthouse", "<p>A lighthouse is a tower with a bright lamp that guides ships at night. "
                  "The lighthouse keeper trimmed the wicks and wound the clockwork every evening.</p>"
                  "<h2>Lenses</h2><p>The Fresnel lens let a small flame be seen from far out at sea.</p>")
# The small slice's copy says more about the keeper and has the title that sorts first, so without the set rule it is the
# one Search would list (one row per family keeps whichever comes first).
LIGHTHOUSE_SMALL = page("Lighthouse", "<p>A lighthouse is a tower with a bright lamp that guides ships at night. "
                        "The lighthouse keeper trimmed the wicks and wound the clockwork every evening. "
                        "A lighthouse keeper's log noted every lighthouse lamp lit and every ship the lighthouse keeper saw.</p>"
                        "<h2>Lenses</h2><p>The Fresnel lens let a small flame be seen from far out at sea.</p>")
FOGHORN = page("Foghorn", "<p>A foghorn sounds when fog hides the lighthouse lamp from passing ships.</p>")


def build(out: Path, name: str, title: str, main: str, items, language="eng") -> None:
    out.unlink(missing_ok=True)
    with Creator(out).config_compression(Compression.none).config_indexing(True, language) as c:
        c.set_mainpath(main)
        for path, item_title, body in items:
            c.add_item(Entry(path, item_title, "text/html", body, True))
        for key, value in [("Title", title), ("Language", language), ("Creator", "Granger tests"), ("Publisher", "Granger"),
                           ("Name", name), ("Date", "2026-11-23"), ("Description", f"{title}, for the iPhone's tests")]:
            c.add_metadata(key, value)


def main(folder: str) -> None:
    out = Path(folder)
    out.mkdir(parents=True, exist_ok=True)
    build(out / "wiktionary-test.zim", "wiktionary_en_test", "Wiktionary test", "betimes",
          [("betimes", "betimes", BETIMES), ("cat", "cat", CAT), ("cats", "cats", CATS)])
    build(out / "slice-small.zim", "granger_testset_small", "A test set (small)", "Lighthouse",
          [("Lighthouse", "Lighthouse", LIGHTHOUSE_SMALL)])
    build(out / "slice-big.zim", "granger_testset_big", "Test set (big)", "Lighthouse",
          [("Lighthouse", "Lighthouse", LIGHTHOUSE), ("Foghorn", "Foghorn", FOGHORN)])


if __name__ == "__main__":
    main(sys.argv[1])
