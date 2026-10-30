"""Turns an eBible.org USFM Bible into chapter pages: one page per biblical book, in the source's order.

Each book's page opens with its `\\h` name as an `h2`, and each `\\c` becomes an `h3` ("Chapter N", or
"Psalm N" where the book sets a `\\cl` label). Verse numbers are small superscripts. Poetry (`\\q1`-`\\q4`)
becomes verse lines, `<div class="gv">` holding `<span class="gv-l">` lines indented by margin-left, the same
markup the Gutenberg verse pass writes, and `\\b` becomes a stanza gap. Section headings (`\\s`, `\\s1`,
`\\s2`) become `h4`.

Footnotes, cross-references, parallel-passage lines (`\\r`), figures and word-level data (Strong's numbers)
are dropped; `\\nd` keeps its text in small capitals, and `\\wj` and `\\add` keep their text plainly. Front
matter books (the preface, introductions, indexes) are skipped. A book too big for one page is split at
chapter boundaries into pages named for their chapters, e.g. "Psalms 1–41".
"""
import math
import re
from collections import Counter
from dataclasses import dataclass, field
from html import escape

from chapters import Chapter

# Books that aren't scripture text: front and back matter, introductions, concordance and indexes.
SKIPPED_BOOKS = {"FRT", "INT", "BAK", "OTH", "CNC", "TDX", "NDX"}
# Chapter labels for books whose source gives none with `\cl`.
LABELS = {"PSA": "Psalm"}

CSS = """
sup.v { font-size: .6em; line-height: 0; vertical-align: super; opacity: .75; }
.nd { font-variant: small-caps; }
.gv { text-align: start; text-indent: 0; hyphens: manual; -webkit-hyphens: manual; margin: 0 0 .8em; }
.gv-l { display: block; padding-left: 1.5em; text-indent: -1.5em; }
.gv-gap { display: block; height: .75em; }
.gv-l.qr { text-align: right; }
.gv-l.qc { text-align: center; }
p.pi, p.pm, p.mi { margin-left: 1.5em; }
p.li { margin-left: 2.5em; text-indent: -1em; }
p.li2 { margin-left: 3.5em; text-indent: -1em; }
p.pc, h3.ms, p.mr { text-align: center; }
p.mt, p.d, p.sp, p.qa, p.mr { font-style: italic; }
""".strip()

# Paragraph-level markers. Everything up to the next one is that block's content; `\v` stays inline.
_BLOCK = re.compile(
    r"\\(id|ide|h\d?|toc\d|toca\d|mte?\d?|imte?\d?|is\d?|ip|ipi|im|imi|ipq|imq|ipr|iq\d?|ib|ili\d?|io\d?|iot|ie"
    r"|rem|sts|usfm|c|cl|cp|cd|p|m|po|pr|cls|pmo|pm|pmc|pmr|pi\d?|mi|nb|pc|ph\d?|q\d?|qr|qc|qa|qm\d?|qd|b"
    r"|s\d?|ms\d?|mr|sr|r|d|sp|sd\d?|li\d?|lh|lf|lim\d?|tr)(?=\s|$)")
# Notes and other character spans dropped with their content: footnotes, endnotes, cross-references,
# figures, inline cross-reference quotes, alternate and published chapter/verse numbers.
_DROPPED_SPANS = re.compile(r"\\(f|fe|ef|x|ex|fig|rq|ca|va|vp)\s.*?\\\1\*", re.S)
# Word-level attributes, e.g. the `|strong="H1870"` in `\w is|strong="H1870"\w*`.
_ATTRIBUTES = re.compile(r"\|[^\\|]*(?=\\\+?[a-z][a-z0-9]*\*)")
# A verse number with no text after it, once its footnote is gone: a verse most manuscripts lack.
_EMPTY_VERSE = re.compile(r'<sup class="v">[^<]*</sup>\u00a0\s*(?=<sup class="v">|$)')
_INLINE = re.compile(r"\\v\s+(\S+)\s?|\\\+?([a-z][a-z0-9]*)(\*|\s|$)")
# Character markers kept as markup; any other character marker keeps its text and loses the marker.
_CHARACTER = {"nd": ('<span class="nd">', "</span>"), "sc": ('<span class="nd">', "</span>"),
              "bk": ("<i>", "</i>"), "it": ("<i>", "</i>"), "em": ("<i>", "</i>"), "tl": ("<i>", "</i>"),
              "qs": ("<i>", "</i>"), "bd": ("<b>", "</b>"), "k": ("<b>", "</b>"), "sup": ("<sup>", "</sup>")}
_PLAIN = {"w", "wj", "add", "wh", "wg", "wa", "pn", "png", "addpn", "qt", "sls", "ord", "dc", "lik", "liv",
          "litl", "ior", "iqt", "no", "bdit", "jmp", "fv"}
_METADATA = {"id", "ide", "h", "usfm", "rem", "sts", "cd"}
_PROSE = {"p": "<p>", "po": "<p>", "pr": "<p>", "cls": "<p>", "m": '<p class="m">', "nb": '<p class="m">',
          "mi": '<p class="mi">', "pc": '<p class="pc">', "pmo": '<p class="pm">', "pm": '<p class="pm">',
          "pmc": '<p class="pm">', "pmr": '<p class="pm">', "ip": '<p class="ip">', "ipi": '<p class="ip">',
          "im": '<p class="ip">', "imi": '<p class="ip">', "ipq": '<p class="ip">', "imq": '<p class="ip">',
          "ipr": '<p class="ip">', "ie": "<p>", "lh": "<p>", "lf": "<p>", "d": '<p class="d">',
          "sp": '<p class="sp">', "qa": '<p class="qa">', "mr": '<p class="mr">', "sr": '<p class="mr">',
          "qd": '<p class="d">'}


@dataclass
class Book:
    code: str
    title: str
    subtitle: str
    front: str
    chapters: list[tuple[str, str, str]]  # (number as published, heading, html)


@dataclass
class _Writer:
    """Collects one book's HTML, keeping each chapter's markup balanced so pages can split between chapters."""
    unknown: Counter
    out: list[str] = field(default_factory=list)
    open: str | None = None  # "p" or "gv"
    gap: bool = False
    heading: tuple[str, str] | None = None  # a chapter heading not yet written: (id, text)

    def close(self) -> None:
        if self.open:
            self.out.append("</p>" if self.open == "p" else "</div>")
        self.open, self.gap = None, False

    def flush_heading(self) -> None:
        if self.heading:
            ident, text = self.heading
            self.out.append(f'<h3 id="{ident}">{escape(text)}</h3>')
            self.heading = None

    def block(self, html: str) -> None:
        self.close()
        self.flush_heading()
        self.out.append(html)

    def paragraph(self, start: str, content: str) -> None:
        self.block(start + inline(content, self.unknown))
        self.open = "p"

    def text(self, content: str) -> None:
        """Content after a marker that doesn't open a paragraph: it goes into a paragraph of its own."""
        if not content.strip():
            return
        if self.open == "p":
            self.out.append(" " + inline(content, self.unknown))
        else:
            self.paragraph("<p>", content)

    def line(self, marker: str, content: str) -> None:
        html = inline(content, self.unknown)
        if not html:
            return
        if self.open != "gv":
            self.block('<div class="gv">')
            self.open = "gv"
        elif self.gap:
            self.out.append('<span class="gv-gap"></span>')
        self.gap = False
        level = int(marker[-1]) if marker[-1].isdigit() else 1
        kind = {"qr": " qr", "qc": " qc"}.get(marker, "")
        style = f' style="margin-left: {level - 1}em"' if level > 1 else ""
        self.out.append(f'<span class="gv-l{kind}"{style}>{html}</span>')

    def take(self) -> str:
        self.close()
        self.flush_heading()
        html, self.out = "".join(self.out), []
        return html


def inline(content: str, unknown: Counter) -> str:
    """A block's text as HTML: verse numbers as superscripts, character markers mapped or dropped."""
    content = re.sub(r"\s+", " ", content).strip()
    out, stack, pos = [], [], 0
    for match in _INLINE.finditer(content):
        out.append(escape(content[pos:match.start()], quote=False))
        pos = match.end()
        verse, name, end = match.groups()
        if verse:
            out.append(f'<sup class="v">{escape(verse)}</sup>\u00a0')
        elif end == "*":
            if name in stack:
                while stack:
                    top = stack.pop()
                    out.append(_CHARACTER.get(top, ("", ""))[1])
                    if top == name:
                        break
        else:
            if name not in _CHARACTER and name not in _PLAIN:
                unknown[name] += 1
            out.append(_CHARACTER.get(name, ("", ""))[0])
            stack.append(name)
    out.append(escape(content[pos:], quote=False))
    out.extend(_CHARACTER.get(name, ("", ""))[1] for name in reversed(stack))
    return _EMPTY_VERSE.sub("", "".join(out)).strip()


def parse(text: str, unknown: Counter | None = None) -> Book:
    """One USFM book."""
    unknown = Counter() if unknown is None else unknown
    text = _ATTRIBUTES.sub("", _DROPPED_SPANS.sub("", text))
    parts = _BLOCK.split(text)
    meta: dict[str, str] = {}
    titles: list[str] = []
    w = _Writer(unknown)
    front: str | None = None
    chapters: list[tuple[str, str, str]] = []
    number, label = "", ""
    w.text(parts[0])
    code = ""

    def end_chapter():
        nonlocal front
        html = w.take()
        if front is None:
            front = html
        else:
            chapters[-1] = (*chapters[-1][:2], html)

    for marker, content in zip(parts[1::2], parts[2::2]):
        if marker in _METADATA or marker.startswith("toc"):
            meta.setdefault(marker, " ".join(content.split()))
            if marker == "id" and not code:
                code = (content.split() or [""])[0]
                label = LABELS.get(code, "")
        elif re.fullmatch(r"mte?\d?|imte?\d?", marker):
            if content.strip():
                titles.append(inline(content, unknown))
        elif marker == "c":
            end_chapter()
            number, _, rest = content.strip().partition(" ")
            heading = f"{label} {number}" if label else f"Chapter {number}"
            chapters.append((number, heading, ""))
            w.heading = (f"c{number}", heading)
            w.text(rest)
        elif marker == "cl":
            if w.heading:  # a label for this chapter alone
                w.heading = (w.heading[0], " ".join(content.split()))
                chapters[-1] = (chapters[-1][0], w.heading[1], "")
            else:
                label = " ".join(content.split())
        elif marker == "cp":
            published = " ".join(content.split())
            if w.heading and chapters:
                heading = f"{label} {published}" if label else f"Chapter {published}"
                w.heading = (w.heading[0], heading)
                chapters[-1] = (published, heading, "")
        elif re.fullmatch(r"ms\d?", marker):
            w.close()
            w.out.append(f'<h3 class="ms">{inline(content, unknown)}</h3>')  # stays above the chapter heading
        elif marker == "mr" and w.heading:
            w.close()
            w.out.append(f'<p class="mr">{inline(content, unknown)}</p>')
        elif re.fullmatch(r"s\d?|is\d?|sd\d?", marker):
            if marker.startswith("sd"):
                w.close()
            elif content.strip():
                w.block(f"<h4>{inline(content, unknown)}</h4>")
        elif marker == "r":
            w.close()  # parallel-passage references, dropped like cross-references
        elif re.fullmatch(r"q\d?|qm\d?|qr|qc|iq\d?", marker):
            w.flush_heading()
            w.line(marker if marker in ("qr", "qc") else marker.replace("qm", "q").replace("iq", "q"), content)
        elif marker in ("b", "ib"):
            w.gap = w.open == "gv"
            if w.open != "gv":
                w.close()
            w.text(content)
        elif re.fullmatch(r"pi\d?", marker):
            w.paragraph('<p class="pi">', content)
        elif re.fullmatch(r"li\d?|ili\d?|lim\d?|ph\d?|io\d?", marker):
            level = int(marker[-1]) if marker[-1].isdigit() else 1
            w.paragraph('<p class="li">' if level == 1 else '<p class="li2">', content)
        elif marker in _PROSE:
            if marker in ("d", "qa", "sp", "mr", "sr", "qd") and not content.strip():
                continue
            w.paragraph(_PROSE[marker], content)
        else:  # iot, tr and other rare markers: keep the text as a paragraph
            unknown["\\" + marker] += 1
            w.paragraph("<p>", content)
    end_chapter()
    if len(chapters) == 1:  # Obadiah, Jude, Psalm 151: no "Chapter 1" under the book's own name
        number, heading, html = chapters[0]
        heading_html = rf'<h3 id="c[^"]*">{re.escape(escape(heading))}</h3>'
        chapters[0] = (number, heading, re.sub(heading_html, "", html, count=1))
    title = meta.get("h") or meta.get("toc2") or code
    subtitle = " ".join(titles)
    if _plain(subtitle) == _plain(title):
        subtitle = ""
    return Book(code=code, title=title, subtitle=subtitle, front=front or "", chapters=chapters)


def _plain(html: str) -> str:
    words = re.sub(r"[^a-z0-9]+", " ", re.sub(r"<[^>]+>", "", html).lower()).split()
    return " ".join(words[1:] if words[:1] == ["the"] else words)


def book_pages(book: Book, max_bytes: int) -> list[Chapter]:
    """The book on one page, or on several split between chapters when it's over `max_bytes`."""
    def head(title: str, first: bool) -> str:
        html = f"<h2>{escape(title)}</h2>"
        if first:
            html += (f'<p class="mt">{book.subtitle}</p>' if book.subtitle else "") + book.front
        return html

    def size(html: str) -> int:
        return len(html.encode())

    total = size(head(book.title, True)) + sum(size(html) for _, _, html in book.chapters)
    if total <= max_bytes or len(book.chapters) < 2:
        return [Chapter(book.title, head(book.title, True) + "".join(html for _, _, html in book.chapters))]
    sizes = [size(html) for _, _, html in book.chapters]
    sizes[0] += size(head(book.title, True))
    for count in range(math.ceil(total / max_bytes), len(sizes) + 1):
        runs = _even_runs(list(zip(book.chapters, sizes)), sizes, count)
        groups = [[chapter for chapter, _ in run] for run in runs]
        heads = [size(head(f"{book.title} {g[0][0]}–{g[-1][0]}", False)) if i else 0 for i, g in enumerate(groups)]
        if all(sum(s for _, s in run) + extra <= max_bytes for run, extra in zip(runs, heads)):
            break
    pages = []
    for i, group in enumerate(groups):
        title = f"{book.title} {group[0][0]}–{group[-1][0]}" if len(group) > 1 else f"{book.title} {group[0][0]}"
        pages.append(Chapter(title, head(title, i == 0) + "".join(html for _, _, html in group)))
    return pages


def _even_runs(items: list, sizes: list[int], count: int) -> list[list]:
    """`items` cut into `count` consecutive runs whose sizes are as even as the boundaries allow."""
    total, runs, start, done = sum(sizes), [], 0, 0
    for k in range(1, count):
        goal, end, reach = total * k / count, start + 1, done + sizes[start]
        while end < len(items) - (count - k) and abs(reach + sizes[end] - goal) < abs(reach - goal):
            reach += sizes[end]
            end += 1
        runs.append(items[start:end])
        start, done = end, reach
    runs.append(items[start:])
    return runs


def convert(books: list[tuple[str, str]], max_bytes: int) -> tuple[list[Chapter], list[str], Counter]:
    """Chapter pages for a whole Bible, the book codes skipped as front matter, and unknown markers seen."""
    pages, skipped, unknown = [], [], Counter()
    for code, text in books:
        if code in SKIPPED_BOOKS:
            skipped.append(code)
            continue
        pages.extend(book_pages(parse(text, unknown), max_bytes))
    return pages, skipped, unknown
