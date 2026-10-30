"""Verse that reads as verse on a phone: every line its own block, hanging when it wraps.

Gutenberg sets verse as a paragraph with a `<br>` after each line, as a `<pre>`, or as one element per line.
On a narrow screen a long line wraps flush to the margin and reads as a new line of verse. `mark` rewrites
the first two kinds into `.gv` blocks of `span.gv-l` lines, which `CSS` hangs; one-element-per-line verse
inside a `div.stanza`, `div.poem` and the like gets the same hang from `WRAPPER_CSS`.

- A `p`, `div`, `td` or `blockquote` is a verse block when it holds at least two `<br>` of its own, no block
  elements, and its lines average at most 90 characters (so a prose paragraph with a few breaks stays prose).
- An empty line (`<br><br>`) becomes a `span.gv-gap`, a stanza break.
- Leading blanks beyond the block's own source indentation become a left margin: no-break spaces always
  count, ordinary spaces count beyond the least any source line carries.
- A number set off by three or more blanks at a line's end (`…hand     20`) and a gloss set off by six or
  more spaces (`swoot*,      *sweet`) become `span.gv-n`, a small note floated to the right margin.
- A drop cap inside verse is unwrapped: floated beside the first line, it broke the line apart.
- A first line that is a speaker's name in capitals (`POLONIUS.`) becomes `span.gv-sp`, in small capitals.

Blocks already marked `gv` (the eBible converter writes them) are left as they are.
"""
import re

from bs4 import BeautifulSoup, NavigableString, PageElement, Tag

CSS = """
.gv { text-align: start; text-indent: 0; hyphens: manual; -webkit-hyphens: manual; }
p.gv, div.gv, td.gv { margin-left: 0; margin-right: 0; }
.gv-pre { margin-top: 1em; margin-bottom: 1em; }
.gv-l { display: block; padding-left: 1.5em; text-indent: -1.5em; }
.gv-gap { display: block; height: .75em; }
.gv-n { float: right; font-size: .75em; opacity: .65; margin-left: .75em; text-indent: 0; font-style: normal; }
.gv-sp { font-variant-caps: all-small-caps; letter-spacing: .05em; }
.gv [class*=dropcap] { float: none; font-size: inherit; line-height: inherit; margin: 0; }
""".strip()

# Only in a work read as verse: one-line-per-element verse hangs too, and a poem's wrapper keeps no side
# margins, so its lines get the width a phone has (Faerie Queene: 62% of lines wrapped at 390 px, 38% without).
WRAPPER_CSS = """
div.stanza > p, div.stanza > div, div.poem > p, div.poetry > p, div.verse > p, div.tei-l, div.line {
  padding-left: 1.5em; text-indent: -1.5em; }
div.poem, div.stanza, div.poetry, div.verse { margin-left: 0; margin-right: 0; }
""".strip()

_BLOCKS = ["p", "div", "table", "ul", "ol", "dl", "blockquote", "pre", "h1", "h2", "h3", "h4", "h5", "h6", "hr"]
_MAX_LINE = 90
_TRAILING_NUMBER = re.compile(r"[ \t\xa0]{3,}(\[?\d{1,4}\]?)\s*$")
_GLOSS = re.compile(r"(\S)[ \t]{6,}(\S.*)$", re.S)
_SPEAKER = re.compile(r"^[A-Z][A-Z .,'’-]{0,38}\.$")


def mark(soup: BeautifulSoup) -> None:
    for block in soup.find_all(["p", "div", "td", "blockquote"]):
        if is_verse_block(block):
            _lines(soup, block)
    for pre in soup.find_all("pre"):
        _pre(soup, pre)
    for cap in soup.select(".gv span[class*=dropcap]"):
        cap.unwrap()


def is_verse_block(block: Tag) -> bool:
    if "gv" in (block.get("class") or []) or block.find_parent(class_="gv") is not None:
        return False
    if len(block.find_all("br", recursive=False)) < 2 or block.find(_BLOCKS) is not None:
        return False
    lines = [" ".join(_text(g).split()) for g in _groups(block)]
    lines = [line for line in lines if line]
    return bool(lines) and sum(map(len, lines)) / len(lines) <= _MAX_LINE


def _text(nodes) -> str:
    return "".join(n.get_text() if isinstance(n, Tag) else str(n) for n in nodes)


def _groups(block: Tag) -> list[list[PageElement]]:
    """The block's children cut at each `<br>` of its own (the `<br>`s themselves left out)."""
    groups: list[list[PageElement]] = [[]]
    for child in block.children:
        if isinstance(child, Tag) and child.name == "br":
            groups.append([])
        else:
            groups[-1].append(child)
    return groups


def _lead(text: str) -> tuple[int, int, bool]:
    """Leading blanks of a source line: (all of them, the ordinary spaces among them, whether a newline came
    first, i.e. whether the spaces are the source's own indentation)."""
    stripped = text.lstrip("\r\n")
    newline = len(stripped) < len(text)
    blanks = re.match(r"[ \t\xa0]*", stripped).group(0).replace("\t", "    ")
    return len(blanks), len(blanks) - blanks.count("\xa0"), newline


def _lines(soup: BeautifulSoup, block: Tag) -> None:
    groups = _groups(block)
    leads = [_lead(_text(g)) for g in groups if _text(g).strip()]
    indented = [spaces for _, spaces, newline in leads if newline] or [spaces for _, spaces, _ in leads]
    base = min(indented, default=0)
    for child in list(block.contents):
        child.extract()
    _fill(soup, block, groups, base, 0.3)
    lines = block.find_all("span", class_="gv-l", recursive=False)
    if len(lines) > 1 and _SPEAKER.match(" ".join(lines[0].get_text().split())):
        lines[0]["class"] = ["gv-l", "gv-sp"]
    block["class"] = (block.get("class") or []) + ["gv"]


def _fill(soup: BeautifulSoup, block: Tag, groups: list[list[PageElement]], base: int, em_per_space: float) -> None:
    """Appends a line per group, a gap per run of empty ones; an anchor on an empty line joins the next line.

    A newline goes between lines, so the block's plain text (search, read aloud) keeps its lines apart."""
    anchors: list[Tag] = []
    gap = False
    for group in groups:
        if not _text(group).strip():
            anchors += [n for n in group if isinstance(n, Tag) and (n.get("id") or n.find(id=True))]
            gap = gap or bool(block.contents)
            continue
        if gap:
            block.extend(["\n", soup.new_tag("span", attrs={"class": "gv-gap"})])
            gap = False
        if block.contents:
            block.append("\n")
        block.append(_line(soup, anchors + group, base, em_per_space))
        anchors = []
    if anchors:
        last = block.contents[-1] if block.contents else block
        for anchor in anchors:
            last.append(anchor)


def _pre(soup: BeautifulSoup, pre: Tag) -> None:
    """A `<pre>` as a verse block: a line per source line, its indentation kept as a margin."""
    groups: list[list[PageElement]] = [[]]
    for child in list(pre.children):
        child.extract()
        if isinstance(child, NavigableString):
            for i, part in enumerate(str(child).split("\n")):
                if i:
                    groups.append([])
                if part:
                    groups[-1].append(NavigableString(part))
        else:
            groups[-1].append(child)
    filled = [g for g in groups if _text(g).strip()]
    if not filled:
        pre.decompose()
        return
    base = min(_lead(_text(g))[1] for g in filled)
    div = soup.new_tag("div", attrs={k: v for k, v in pre.attrs.items() if k in ("id", "lang", "style")})
    div["class"] = (pre.get("class") or []) + ["gv", "gv-pre"]
    _fill(soup, div, groups, base, 0.5)
    pre.replace_with(div)


def _line(soup: BeautifulSoup, nodes: list[PageElement], base: int, em_per_space: float) -> Tag:
    """One verse line: leading blanks become its margin, a trailing number or gloss a right-margin note."""
    nodes = [n.extract() if n.parent is not None else n for n in nodes]
    lead = 0
    for i, node in enumerate(nodes):
        if isinstance(node, Tag):
            if node.get_text():
                break
            continue  # an empty anchor before the words
        m = re.match(r"[\r\n]*([ \t\xa0]*)", str(node))
        lead += len(m.group(1).replace("\t", "    "))
        nodes[i] = NavigableString(str(node)[m.end():])
        if nodes[i]:
            break
    note = None
    last = next((i for i in range(len(nodes) - 1, -1, -1)
                 if not (isinstance(nodes[i], NavigableString) and not nodes[i].strip())), None)
    if last is not None and isinstance(nodes[last], NavigableString):
        text = str(nodes[last])
        if (m := _TRAILING_NUMBER.search(text)) and (text[:m.start()].strip() or last > 0):
            note, nodes[last] = m.group(1), NavigableString(text[:m.start()])
        elif m := _GLOSS.search(text.rstrip()):
            note, nodes[last] = text[m.start() + 1:].strip(), NavigableString(text[:m.start() + 1])
    line = soup.new_tag("span", attrs={"class": "gv-l"})
    extra = lead - min(base, lead)
    if extra >= 2:
        line["style"] = f"margin-left: min({extra * em_per_space:.2f}em, 35%)"
    if note:
        number = soup.new_tag("span", attrs={"class": "gv-n"})
        number.string = note
        line.extend([number, " "])
    for node in nodes:
        if not (isinstance(node, NavigableString) and not str(node)):
            line.append(node)
    return line
