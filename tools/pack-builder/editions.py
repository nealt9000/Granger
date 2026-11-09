"""Granger's own editions of a public-domain text: a name map applied to its words, and the editor's note that says so.

A manifest row's `rename` names a map in `classics/names/` and where it applies:

  "rename": {"names": "homer-greek", "from": "Book I", "to": "Footnotes"}

Each name in the map is replaced as a whole capitalised word ("Jove", "Jove’s", "Jove-begotten", "Ulysses’") and in
capitals ("MINERVA’S" in a heading); "jovial" and "Marsh" are left alone. Only the text from the first heading that
reads `from` (inclusive) up to the first heading after it that reads `to` (exclusive) changes, so a translator's
preface and notes keep their own words. Headings match ignoring case, spacing and a closing "." or ":"; leave either
out to start at the top or run to the end. A heading named but not found is an error, not a silent no-op.

`editorNote` is a sentence for the front of the book (under the title block on its first page, put in by epubpack
after the split) and the colophon.
"""
import json
import re
from collections import Counter
from pathlib import Path

from bs4 import BeautifulSoup, NavigableString, Tag

NAMES = Path(__file__).parent / "classics" / "names"
HEADINGS = ("h1", "h2", "h3", "h4", "h5", "h6")
TITLE_BLOCK_TEXT = 200  # a block under the title longer than this is the book, not its title page


def load_names(name: str) -> dict[str, str]:
    path = NAMES / f"{name}.json"
    if not re.fullmatch(r"[a-z0-9-]+", name) or not path.exists():
        raise ValueError(f"no name map {name!r} in {NAMES}")
    names = json.loads(path.read_text(encoding="utf-8"))
    return {k: v for k, v in names.items() if not k.startswith("_")}  # "_note" keys explain the map


def _pattern(names: dict[str, str]) -> tuple[re.Pattern, dict[str, str]]:
    forms = {**names, **{k.upper(): v.upper() for k, v in names.items()}}
    words = "|".join(map(re.escape, sorted(forms, key=len, reverse=True)))
    return re.compile(rf"(?<!\w)({words})(?!\w)"), forms


def _heading_text(text: str) -> str:
    return " ".join(text.split()).rstrip(".:").lower()


def _in_scope(body: Tag, start: str | None, stop: str | None) -> list[NavigableString]:
    """The text nodes from the `start` heading up to the `stop` heading, in document order."""
    inside, started, stopped = start is None, start is None, False
    nodes = []
    for node in body.descendants:
        if isinstance(node, Tag) and node.name in HEADINGS:
            text = _heading_text(node.get_text())
            if not started and text == _heading_text(start):
                inside = started = True
            elif started and stop is not None and text == _heading_text(stop):
                inside, stopped = False, True
                break
        elif inside and type(node) is NavigableString:
            nodes.append(node)
    if not started:
        raise ValueError(f"rename: no heading reads {start!r}")
    if stop is not None and not stopped:
        raise ValueError(f"rename: no heading reads {stop!r} after {start!r}")
    return nodes


def rename(body: Tag, names: dict[str, str], start: str | None = None, stop: str | None = None) -> Counter:
    """Replaces the names in place between the headings; returns how many of each name were replaced."""
    pattern, forms = _pattern(names)
    counts: Counter = Counter()

    def swap(match: re.Match) -> str:
        counts[match.group(1)] += 1
        return forms[match.group(1)]

    for node in _in_scope(body, start, stop):
        text = pattern.sub(swap, str(node))
        if text != str(node):
            node.replace_with(NavigableString(text))
    return counts


def remaining(body: Tag, names: dict[str, str], start: str | None = None, stop: str | None = None) -> Counter:
    """The names still in the text between the headings (none, after `rename`)."""
    pattern, _ = _pattern(names)
    return Counter(m.group(1) for node in _in_scope(body, start, stop) for m in pattern.finditer(str(node)))


def front_note(body: Tag, text: str) -> None:
    """The editor's note under the book's title block: after its first h1 and the short headings and lines under it
    ("by Homer", "rendered into English prose..."), before whatever follows (a rule, the contents)."""
    soup = BeautifulSoup("", "lxml")
    note = soup.new_tag("div", attrs={"class": "editor-note"})
    paragraph = soup.new_tag("p")
    paragraph.string = text
    note.append(paragraph)
    title = body.find("h1")
    if title is None:
        body.insert(0, note)
        return
    last = title
    for sibling in title.next_siblings:
        if isinstance(sibling, NavigableString):
            continue
        short = sibling.name in ("p", "div") and len(sibling.get_text(" ", strip=True)) <= TITLE_BLOCK_TEXT
        if sibling.name in HEADINGS[1:] or short and not sibling.find(["table", "img", "hr"]):
            last = sibling
        else:
            break
    last.insert_after(note)
