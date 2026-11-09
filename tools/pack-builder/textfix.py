"""Structure a plain-text source only showed by spacing, put back: paragraphs, chapters and verse numbers.

Gutenberg's text-to-HTML converter (the GutenMark family: `id="id00009"` ids, no classes) kept the text file's
layout inside each `<p>`: a real paragraph or verse starts on a new line indented 4+ spaces (or with a verse
number and a tab), its continuation lines sit at column 0, and a `<p>` ends where the printed page did. HTML
collapses all of that, so the Analects read as run-on blocks with "CHAP. III." in mid-paragraph (formatting
audit 2026-11-09, §1: 648 hidden starts and 51 page-split paragraphs; Sale's Koran 3,546 and 123).

`restore` (on by default, `textfix: false` turns it off) fires only on a book with at least `MIN_HIDDEN`
hidden starts, which in the Classics is the Analects and Sale's Koran:
- a `<p>` (or a heading holding a whole passage, as the Analects' first chapter in an `h5`) is cut at each
  hidden start;
- a `<p>` that starts at column 0 after another `<p>` is the rest of a paragraph a page break cut, and joins it.

`promote` (manifest `labels: "chapter-verse"`, the Analects and the Tao Te Ching) turns labels at a
paragraph's start into structure: "CHAP. III." / "Ch. 3." (or the Tao's "2. 1.", chapter 2 verse 1) becomes a
chapter heading, a verse number "2." a small `span.gx-n`. "BOOK IV. LE JIN." headings, set at three levels in
the Analects' source, come to one, without the work's title repeated in front.

`verse_refs` (manifest `verseRefs: "split"`, the KJV, Apocrypha and Douay) starts each "36:16" verse on a
paragraph of its own with its reference as a `span.gx-n`. `note_markers` (manifest `noteMarkers`) sets apart
note marks glued to the words: letters after punctuation in Sale's Koran ("thee,g"), digits after a word in
Rodwell's ("orphan2").
"""
import re

from bs4 import BeautifulSoup, NavigableString, PageElement, Tag

CSS = """
:root .gx-v { text-indent: 0; }
:root .gx-n { font-weight: 700; font-size: .8em; margin-right: .4em; opacity: .75; }
:root .gx-chap { font-size: 1rem; margin: 1.4em 0 .4em; }
:root .gx-ref { font-size: .7em; vertical-align: super; line-height: 0; opacity: .7; }
""".strip()

MIN_HIDDEN = 20
_HIDDEN = re.compile(r"\n(?=[ \t]{4,}\S|\d{1,3}\t)")
_BLOCKS = ["p", "div", "table", "ul", "ol", "dl", "blockquote", "pre", "h1", "h2", "h3", "h4", "h5", "h6"]
_HEADINGS = ["h1", "h2", "h3", "h4", "h5", "h6"]
_LABEL_START = re.compile(r"^\s*(?:CHAP(?:TER)?\.|Ch\.|\d{1,3}\.\s|\d{1,3}\t)")
_SENTENCE_END = re.compile(r"[.?!:'’\"”)\]]\s*$")
_HOLDER = BeautifulSoup("", "lxml")


_COLUMN_0 = re.compile(r"\n(?=[^\s\d])")


def _hidden_starts(tag: Tag) -> int:
    """Indented line starts inside the block's own text, counted only when other lines sit at column 0: HTML
    that is merely pretty-printed indents every line."""
    strings = [str(s) for s in tag.find_all(string=True, recursive=False)]
    if not any(_COLUMN_0.search(s) for s in strings):
        return 0
    return sum(len(_HIDDEN.findall(s)) for s in strings)


def _leaf(tag: Tag) -> bool:
    return tag.find(_BLOCKS) is None


def _starts_at_column_0(p: Tag) -> bool:
    first = next((c for c in p.children if not (isinstance(c, Tag) and not c.get_text().strip())), None)
    return isinstance(first, NavigableString) and str(first)[:1].strip() != "" or isinstance(first, Tag)


def restore(body: Tag, min_hidden: int = MIN_HIDDEN) -> dict:
    """Cuts paragraphs at their hidden starts and joins those a page break split; returns counts."""
    blocks = [b for b in body.find_all(["p", *_HEADINGS]) if _leaf(b)]
    hidden = sum(_hidden_starts(b) for b in blocks)
    if hidden < min_hidden:
        return {"hidden": hidden, "split": 0, "joined": 0}
    joined = 0
    for p in body.find_all("p"):
        if p.parent is None or not _leaf(p):
            continue
        before = p.find_previous_sibling()
        text = p.get_text()
        if (before is not None and before.name == "p" and _leaf(before) and _starts_at_column_0(p)
                and text.strip() and not (len(text.strip()) < 80 and text.strip().upper() == text.strip())
                and not _LABEL_START.match(text)):  # a page break that fell at "CHAP. X." starts a paragraph
            before.append("\n")
            for child in list(p.contents):
                before.append(child.extract())
            p.decompose()
            joined += 1
    split = 0
    for block in [b for b in body.find_all(["p", *_HEADINGS]) if _leaf(b)]:
        if block.name != "p":
            # a heading holding a whole passage (Analects id00009: Chapter I's three verses in an h5)
            if len(block.get_text()) > 150 and _hidden_starts(block):
                block.name = "p"
                block.attrs = {k: v for k, v in block.attrs.items() if k == "id"}
            else:
                continue
        split += _split(block)
    return {"hidden": hidden, "split": split, "joined": joined}


def _split(p: Tag) -> int:
    """`p` cut at each hidden start into paragraphs of its own (the first keeps the id)."""
    pieces: list[list[PageElement]] = [[]]
    for child in list(p.contents):
        if isinstance(child, NavigableString) and _HIDDEN.search(str(child)):
            parts = _HIDDEN.split(str(child))
            for i, part in enumerate(parts):
                if i:
                    pieces.append([])
                if part:
                    pieces[-1].append(NavigableString(part))
        else:
            pieces[-1].append(child.extract() if isinstance(child, Tag) else NavigableString(str(child)))
    pieces = [piece for piece in pieces if "".join(_text(n) for n in piece).strip()]
    if len(pieces) <= 1:
        return 0
    attrs = {k: v for k, v in p.attrs.items() if k != "id"}
    new = []
    for i, piece in enumerate(pieces):
        para = p if i == 0 else _HOLDER.new_tag("p", attrs=dict(attrs))
        if i == 0:
            p.clear()
        for node in piece:
            para.append(node)
        new.append(para)
    p.insert_after(*new[1:])
    return len(pieces) - 1


def _text(node: PageElement) -> str:
    return node.get_text() if isinstance(node, Tag) else str(node)


# --- labels -------------------------------------------------------------------------------------------------------

_CHAPTER = re.compile(r"^\s*(?:CHAP(?:TER)?\.?|Chap\.|Ch\.)\s*(?P<n>[IVXLC]+|\d+)\.\s*")
_CHAPTER_VERSE = re.compile(r"^\s*(?P<n>\d{1,3})\.\s+1\.\s")  # the Tao's "2. 1.": chapter 2, verse 1
_VERSE = re.compile(r"^\s*(?P<n>\d{1,3})\.(?:\s+|$)")
_BOOK = re.compile(r"^\s*(?:(?P<work>[A-Z][A-Z' ]+)\.\s+)?(?P<book>BOOK\s+[IVXLC]+\..*)$", re.S)


def _roman(text: str) -> int:
    values = {"I": 1, "V": 5, "X": 10, "L": 50, "C": 100}
    total = 0
    for a, b in zip(text, text[1:] + " "):
        total += -values[a] if values.get(b, 0) > values[a] else values[a]
    return total


def _strip_start(p: Tag, length: int) -> None:
    """Removes the first `length` characters of the paragraph's text (they sit in its first strings)."""
    for string in list(p.find_all(string=True)):
        if length <= 0:
            break
        text = str(string)
        cut = min(length, len(text))
        string.replace_with(NavigableString(text[cut:]))
        length -= cut


def promote(body: Tag) -> dict:
    """Chapter and verse labels at paragraph starts become a heading and a small number (see the docstring)."""
    books = _books(body)
    level = books + 1 if books else 3
    chapters = verses = 0
    current = verse = 0
    for p in list(body.find_all("p")):
        if p.parent is None or not _leaf(p):
            continue
        text = p.get_text()
        m = _CHAPTER.match(text)
        strip = m.end() if m else 0
        number = None
        if m:
            number = _roman(m.group("n")) if m.group("n")[0] in "IVXLC" else int(m.group("n"))
        elif current and (m := _CHAPTER_VERSE.match(text)) and int(m.group("n")) == current + 1:
            number = int(m.group("n"))
            strip = re.match(r"\s*\d{1,3}\.\s+", text).end()  # "1." stays, as the verse
        elif current and (m := _VERSE.match(text)) and int(m.group("n")) == current + 1 != verse + 1:
            number = int(m.group("n"))  # the Tao's one-verse chapter, "11. The thirty spokes…", or "6." over a pre
            strip = m.end()
        if number is not None:
            heading = _HOLDER.new_tag(f"h{level}", attrs={"class": "gx-chap"})
            heading.string = f"Chapter {number}"
            p.insert_before(heading)
            _strip_start(p, strip)
            current, verse = number, 0
            chapters += 1
            text = p.get_text()
            if not text.strip():
                p.decompose()
                continue
        if current and (v := _VERSE.match(text)):
            verse = int(v.group("n"))
            _strip_start(p, v.end())
            span = _HOLDER.new_tag("span", attrs={"class": "gx-n"})
            span.string = v.group("n")
            p.insert(0, span)
            p["class"] = (p.get("class") or []) + ["gx-v"]
            if p.get_text().strip() != v.group("n"):
                span.insert_after(" ")
            verses += 1
    return {"chapters": chapters, "verses": verses}


def _books(body: Tag) -> int:
    """Book headings ("CONFUCIAN ANALECTS. BOOK I. HSIO R.", "BOOK II. WEI CHANG.") at one level, the highest
    they use, without the repeated work title; returns that level (0 when there are none)."""
    found = [h for h in body.find_all(_HEADINGS) if _BOOK.match(" ".join(h.get_text().split()))]
    if len(found) < 3:
        return 0
    level = min(int(h.name[1]) for h in found)
    for h in found:
        m = _BOOK.match(" ".join(h.get_text().split()))
        h.name = f"h{level}"
        if m.group("work"):
            ids = [t for t in [h, *h.find_all(id=True)] if t.get("id")]
            keep = {k: v for k, v in h.attrs.items() if k in ("id", "class")}
            h.clear()
            h.attrs = keep
            h.string = m.group("book")
            for t in ids[1:]:
                h.insert(0, _HOLDER.new_tag("span", attrs={"id": t["id"]}))
    return level


# --- scripture references -------------------------------------------------------------------------------------------

_REF = re.compile(r"(?:(?<=\s)|^)(\d{1,3}:\d{1,3})\.?\s(?=[A-Z‘“(\[¶])")  # Douay: "1:1. In the beginning"


def verse_refs(body: Tag) -> int:
    """Each "36:16 Duke Korah…" starts a paragraph of its own, its reference a `span.gx-n`."""
    count = 0
    for p in list(body.find_all("p")):
        if p.parent is None or not _leaf(p) or not _REF.search(p.get_text()):
            continue
        pieces: list[list[PageElement]] = [[]]
        refs: list[str | None] = [None]
        for child in list(p.contents):
            if isinstance(child, NavigableString) and _REF.search(str(child)):
                text, at = str(child), 0
                for m in _REF.finditer(text):
                    before = text[at:m.start()]
                    if before:
                        pieces[-1].append(NavigableString(before))
                    pieces.append([])
                    refs.append(m.group(1))
                    at = m.end()
                if text[at:]:
                    pieces[-1].append(NavigableString(text[at:]))
            else:
                pieces[-1].append(child.extract() if isinstance(child, Tag) else NavigableString(str(child)))
        keep = [(r, piece) for r, piece in zip(refs, pieces) if r or "".join(_text(n) for n in piece).strip()]
        attrs = {k: v for k, v in p.attrs.items() if k != "id"}
        out = []
        for i, (ref, piece) in enumerate(keep):
            para = p if i == 0 else _HOLDER.new_tag("p", attrs=dict(attrs))
            if i == 0:
                p.clear()
            if ref:
                para["class"] = [c for c in para.get("class") or [] if c != "gx-v"] + ["gx-v"]
                span = _HOLDER.new_tag("span", attrs={"class": "gx-n"})
                span.string = ref
                para.extend([span, " "])
                count += 1
            for node in piece:
                para.append(node)
            out.append(para)
        if len(out) > 1:
            p.insert_after(*out[1:])
    return count


# --- note markers ---------------------------------------------------------------------------------------------------

_LETTER_MARK = re.compile(r"(?<=[,;:.!?])([a-z])(?=\s|$|[)\]])")
_DIGIT_MARK = re.compile(r"(?<=[a-z,;:.!?])(\d{1,3})(?=[\s,;:.!?)\]]|$)")


def note_markers(body: Tag, kind: str) -> int:
    """Note marks glued to the words become `sup.gx-ref`: a letter after punctuation ("thee,g") or digits after
    a word ("orphan2")."""
    pattern = _LETTER_MARK if kind == "letters" else _DIGIT_MARK
    count = 0
    for string in list(body.find_all(string=pattern)):
        if string.parent is None or string.find_parent(["a", "sup", "h1", "h2", "h3", "h4", "h5", "h6"]) is not None:
            continue
        text, at, out = str(string), 0, []
        for m in pattern.finditer(text):
            out.append(NavigableString(text[at:m.start()]))
            sup = _HOLDER.new_tag("sup", attrs={"class": "gx-ref"})
            sup.string = m.group(1)
            out.append(sup)
            at = m.end()
            count += 1
        out.append(NavigableString(text[at:]))
        string.replace_with(*[n for n in out if not (isinstance(n, NavigableString) and not str(n))])
    return count
