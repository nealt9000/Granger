"""Static measures of how the Classics' plays and text-file books are structured, read from the built EPUBs.

  python drama_check/static.py BOOKS_DIR [slug ...] [--out FILE] [--all]

With no slugs it measures every drama and libretti row (and the text-converter and scripture books the
formatting audit names); `--all` measures every book in BOOKS_DIR for the measures that apply to any book
(gloss misfires, stranded link targets, hidden paragraph starts). Exits 1 when a play fails a bar.

Per play (formatting audit 2026-11-09, §5):
- **coverage**: of the speeches that open with a speaker's name, the share whose name is marked `gd-who`
  (bar 98%). The cast is the marked names seen at least twice; a block or verse line that starts with one of
  them, then `.` or `:`, outside a `gd-who`, is a stray label.
- **suspicious**: marked names seen once, listed for review. Not a bar: a messenger or a Second Lord with one
  speech is common (Hamlet has six), so the list is read by eye, not counted against the play.
- **gloss misfires**: a `.gv-n` margin note holding three or more letters and no `*` (Canterbury's glosses carry
  the star of the word they gloss; bar 0).
- **directions missed**: blocks or lines wholly in brackets, or opening with Enter/Exit/Exeunt, not marked
  `gd-sd`/`gd-sdi`, as a share of all directions found (bar 2%).
- **act/scene outside headings**: a block or line that is only "ACT II" or "SCENE I…" but no heading (bar 0),
  and whether the contents has an act entry when the play has acts.
- **hard wraps**: a line from a source `<pre>` (`.gv-pre`) of 45+ characters with no closing punctuation followed by a line in lower case
  (bar 0 in plays; reported elsewhere).
- **stranded targets**: a link target after the last words of its page (a Contents tap lands at the foot of
  the previous chapter; bar 0). A printed page's anchor (`page_36`, `Pg026`) doesn't count: a page can break
  where a chapter ends.
- **hidden starts**: a paragraph holding a line indented 4+ spaces after a line at column 0, the text-file
  converter's hidden paragraph start (bar 0).
"""
import argparse
import json
import re
import sys
import warnings
import zipfile
from collections import Counter
from pathlib import Path

from bs4 import BeautifulSoup, NavigableString, Tag, XMLParsedAsHTMLWarning

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import drama  # noqa: E402
import manifest  # noqa: E402

warnings.filterwarnings("ignore", category=XMLParsedAsHTMLWarning)
MANIFEST = Path(__file__).resolve().parent.parent / "classics" / "manifest.json"
TEXT_BOOKS = {"analects", "tao-te-ching", "koran-sale", "koran-rodwell", "king-james-bible",
              "apocrypha-deuterocanonical-books", "douay-rheims-bible", "pilgrim-s-progress"}
LEAVES = ["p", "div", "td", "li", "dd", "blockquote", "h1", "h2", "h3", "h4", "h5", "h6"]
INNER = ["p", "div", "table", "ul", "ol", "dl", "blockquote", "pre", "h1", "h2", "h3", "h4", "h5", "h6"]
DEBUG = False
BARS = {"coverage": 0.98, "gloss": 0, "sd_missed": 0.02, "act_text": 0, "hard_wraps": 0,
        "stranded": 0, "hidden": 0}
_DIRECTION = re.compile(r"^(\[[^\]]+\]?|\([^)]+\))\s*\.?$|^(Enter|Exit|Exeunt|Re-enter|Manet|Flourish)\b")


def flat(text: str) -> str:
    return " ".join(text.split())


def units(soup: BeautifulSoup):
    """Every text unit a speech could start, in reading order: a verse line, or a leaf block outside verse."""
    for tag in soup.body.find_all(True):
        if tag.name == "span" and "gv-l" in (tag.get("class") or []):
            yield tag
        elif tag.name in LEAVES and tag.find(INNER) is None and "gv" not in (tag.get("class") or []):
            yield tag


def pages(epub: Path) -> list[tuple[str, BeautifulSoup]]:
    with zipfile.ZipFile(epub) as z:
        names = sorted(n for n in z.namelist() if re.match(r"OEBPS/c\d{3}\.xhtml$", n))
        out = [(n, BeautifulSoup(z.read(n), "lxml")) for n in names]
        out.append(("nav", BeautifulSoup(z.read("OEBPS/nav.xhtml"), "lxml")))
    return out


def link_targets(soups) -> set[str]:
    targets = set()
    for name, soup in soups:
        for a in soup.find_all("a", href=True):
            file, _, fragment = a["href"].partition("#")
            if fragment:
                targets.add(f"OEBPS/{file}#{fragment}" if file else f"{name}#{fragment}")
    return targets


def stranded(name: str, soup: BeautifulSoup, targets: set[str]) -> int:
    """Link targets that sit after the page's last words."""
    last = None
    for string in soup.body.find_all(string=True):
        if string.strip():
            last = string
    if last is None:
        return 0
    count = 0
    for tag in last.find_all_next(id=True):
        if f"{name}#{tag['id']}" in targets and not _PAGE_ANCHOR.match(tag["id"]):
            count += 1
    return count


# a printed page's own anchor ("page_36", "Pg026") may fall at a chapter's end: that's where the page broke
_PAGE_ANCHOR = re.compile(r"^(page|pg|p)[_-]?[0-9ivxlc]+$", re.I)


def hidden_starts(soup: BeautifulSoup) -> int:
    """Paragraphs (no `<br>`, not verse) whose source lines sit at column 0 but for some indented 4+ spaces."""
    count = 0
    for p in soup.body.find_all("p"):
        if p.find("br") is not None or "gv" in (p.get("class") or []):
            continue
        leads = [len(m.group(1)) for s in p.find_all(string=True)
                 if s.find_parent(class_=["gd-head", "gd-who"]) is None
                 for m in re.finditer(r"\n( *)\S", str(s))]  # a line start inside the text, not after a tag
        # pretty-printed HTML indents its lines; the converter's lines sit at column 0 but for the hidden starts
        count += bool(leads) and leads.count(0) >= 0.5 * len(leads) and any(n >= 4 for n in leads)
    return count


def gloss_misfires(soup: BeautifulSoup) -> int:
    return sum(1 for n in soup.select(".gv-n")
               if len(re.findall(r"[^\W\d_]", n.get_text())) >= 3 and "*" not in n.get_text())


def hard_wraps(soup: BeautifulSoup) -> int:
    count = 0
    for block in soup.select(".gv-pre"):
        lines = [flat(line.get_text()) for line in block.find_all("span", class_="gv-l", recursive=False)]
        for a, b in zip(lines, lines[1:]):
            if len(a) >= 45 and not re.search(r"[.,;:!?'’\"”)\]—-]$", a) and b[:1].islower():
                count += 1
                if DEBUG:
                    print("  wrap?", a[-50:], "|", b[:40])
    return count


def measure(epub: Path, play: bool) -> dict:
    soups = pages(epub)
    targets = link_targets(soups)
    out = Counter()
    chapters = [(n, s) for n, s in soups if n != "nav"]
    for name, soup in chapters:
        out["gloss"] += gloss_misfires(soup)
        out["stranded"] += stranded(name, soup, targets)
        out["hidden"] += hidden_starts(soup)
        if play:
            out["hard_wraps"] += hard_wraps(soup)
    result = dict(out)
    if not play:
        return result
    names = Counter(drama.key(w.get_text()) for _, s in chapters for w in s.select(".gd-who"))
    cast = {k for k, n in names.items() if n >= 2 and k}
    stray, strays, sd_found, sd_missed, act_text = 0, Counter(), 0, 0, 0
    begun = False  # labels in a cast list before the play aren't speeches
    for _, soup in chapters:
        for unit in units(soup):
            if not begun and (unit.find(class_="gd-who") is not None or unit.find_previous(class_="gd-who")):
                begun = True
            if unit.find_parent(class_=["gd-head", "gd-who"]) is not None:
                continue
            text = flat("".join(s for s in unit.find_all(string=True)
                                if s.find_parent(class_=["gd-who", "gd-head", "gv-n"]) is None))
            if not text:
                continue
            m = re.match(r"^([^.:(\[]{1,40}?)\s*(?:[\[(][^\])]*[\])])?\s*[.:]", text)
            if begun and m and drama.key(m.group(1)) in cast and unit.find(class_="gd-who") is None \
                    and not unit.find_parent(class_="gd-sd") and "gd-sd" not in (unit.get("class") or []):
                stray += 1
                strays[m.group(1)] += 1
                if DEBUG:
                    print("  stray?", text[:100])
            in_heading = unit.name in ("h1", "h2", "h3", "h4", "h5", "h6") or unit.find_parent(
                ["h1", "h2", "h3", "h4", "h5", "h6"]) is not None
            listed = unit.parent is not None and sum(
                bool(drama.act_scene(flat(u.get_text()))) for u in unit.parent.find_all(
                    "span", class_="gv-l", recursive=False)) >= 2  # a list of the acts, not a label
            if drama.act_scene(text) and not in_heading and not listed and not unit.find_parent(["table", "ul", "ol"]) \
                    and not unit.find_parent(class_="toc") \
                    and not unit.find("a", href=True):
                act_text += 1
                if DEBUG:
                    print("  act?", text[:100])
            if _DIRECTION.match(text) and not in_heading:
                sd_found += 1
                sdi = "".join(t.get_text() for t in unit.find_all(class_="gd-sdi"))
                marked = ("gd-sd" in (unit.get("class") or []) or unit.find_parent(class_=["gd-sd", "gd-sdi"])
                          or len(flat(sdi)) >= 0.8 * len(text))
                sd_missed += not marked
                if not marked and DEBUG:
                    print("  sd?", text[:100])
    speeches = sum(names.values())
    nav = soups[-1][1]
    has_acts = any(drama.act_scene(h.get_text()) == "act" for _, s in chapters for h in s.find_all(re.compile("^h[1-6]$")))
    result.update(speeches=speeches, stray=stray, strays=strays.most_common(5),
                  coverage=round(speeches / (speeches + stray), 4) if speeches + stray else None,
                  suspicious=sorted(k for k, n in names.items() if n == 1), sd_found=sd_found,
                  sd_missed=round(sd_missed / sd_found, 4) if sd_found else 0.0, act_text=act_text,
                  nav_act=(not has_acts) or any(re.search(r"\bAct\b", a.get_text()) for a in nav.select("nav a")))
    return result


def failures(slug: str, r: dict, play: bool) -> list[str]:
    # glosses are a bar in plays, where a name and its speech looked like one; Canterbury's real glosses
    # run to several words with no star ("the day during which"), so elsewhere the count is only reported
    bad = [f"{k} {r.get(k, 0)}" for k in (("gloss",) if play else ()) + ("stranded", "hidden") if r.get(k, 0) > BARS[k]]
    if play:
        if r.get("speeches", 0) and (r["coverage"] or 0) < BARS["coverage"]:
            bad.append(f"coverage {r['coverage']}")
        if r.get("sd_missed", 0) > BARS["sd_missed"]:
            bad.append(f"sd_missed {r['sd_missed']}")
        for k in ("act_text", "hard_wraps"):
            if r.get(k, 0) > BARS[k]:
                bad.append(f"{k} {r[k]}")
        if not r.get("nav_act", True):
            bad.append("no act in contents")
    return bad


def main(argv):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("directory")
    parser.add_argument("slugs", nargs="*")
    parser.add_argument("--out")
    parser.add_argument("--all", action="store_true")
    parser.add_argument("--debug", action="store_true", help="print each stray label, missed direction and act line")
    parser.add_argument("--manifest", default=str(MANIFEST))
    args = parser.parse_args(argv)
    global DEBUG
    DEBUG = args.debug
    m = manifest.load(Path(args.manifest))
    plays = {w.slug for w in m.works if w.is_drama}
    wanted = args.slugs or (None if args.all else sorted(plays | TEXT_BOOKS))
    results, failed = {}, 0
    for epub in sorted(Path(args.directory).expanduser().glob("*.epub")):
        slug = epub.stem.rsplit("_", 1)[0].removeprefix(m.book_id_prefix)
        if wanted is not None and slug not in wanted:
            continue
        play = slug in plays
        r = measure(epub, play)
        bad = failures(slug, r, play)
        failed += bool(bad)
        results[slug] = {**r, "fail": bad}
        if play:
            print(f"{slug[:34]:34} speeches {r['speeches']:5} cover {r['coverage'] or 0:.3f} stray {r['stray']:4} "
                  f"sus {len(r['suspicious']):3} gloss {r.get('gloss', 0):3} sdmiss {r['sd_missed']:.3f} "
                  f"act {r['act_text']:3} wrap {r.get('hard_wraps', 0):3} strand {r.get('stranded', 0):3} "
                  f"{'FAIL ' + '; '.join(bad) if bad else 'ok'}")
        elif bad or args.slugs:
            print(f"{slug[:34]:34} gloss {r.get('gloss', 0):3} strand {r.get('stranded', 0):4} "
                  f"hidden {r.get('hidden', 0):4} {'FAIL ' + '; '.join(bad) if bad else 'ok'}")
    if args.out:
        Path(args.out).write_text(json.dumps(results, indent=1, ensure_ascii=False))
    print(f"{len(results)} books, {failed} failing")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
