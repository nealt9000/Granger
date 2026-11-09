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
_WORD = re.compile(r"^xhtml_")
_WORD_FONT = re.compile(r"^xhtml_font")
_SPEAKER = re.compile(r"^[A-Z][A-Z .,'’-]{0,38}\.$")


def mark(soup: BeautifulSoup, gloss: bool = True) -> None:
    """`gloss=False` in a play: there a name and its speech set apart by spaces (`CARIOLA.     Never, my lord.`)
    look exactly like a line and its gloss, and the speech went to the margin (2,991 lines, audit 2026-11-09)."""
    for block in soup.find_all(["p", "div", "td", "blockquote"]):
        if is_verse_block(block):
            _lines(soup, block, gloss)
    for pre in soup.find_all("pre"):
        _pre(soup, pre, gloss)
    for cap in soup.select(".gv span[class*=dropcap]"):
        cap.unwrap()


def mark_pre(soup: BeautifulSoup) -> None:
    """Only the `<pre>` rule, for works not read as verse: a `<pre>` is never right on a phone (monospace, and a
    hard-wrapped line runs off the screen or breaks short). Prose in it becomes paragraphs, verse lines; a table
    drawn with spaces stays a `<pre>`."""
    for pre in soup.find_all("pre"):
        if not _drawn_with_spaces(pre.get_text()):
            _pre(soup, pre, gloss=False)


def _drawn_with_spaces(text: str) -> bool:
    lines = [line for line in text.split("\n") if line.strip()]
    columns = sum(bool(re.search(r"\S[ \t]{4,}\S", line)) for line in lines)
    return bool(lines) and columns >= max(2, 0.3 * len(lines))


def is_verse_block(block: Tag) -> bool:
    if "gv" in (block.get("class") or []) or block.find_parent(class_="gv") is not None:
        return False
    # a speech's name line (drama.py) is taken out first, so its first <br> is gone: two lines are verse already
    needed = 1 if _head(block) is not None else 2
    if len(block.find_all("br", recursive=False)) < needed or block.find(_BLOCKS) is not None:
        return False
    lines = [" ".join(_text(g).split()) for g in _groups(block)]
    lines = [line for line in lines if line]
    return bool(lines) and sum(map(len, lines)) / len(lines) <= _MAX_LINE


def _head(block: Tag) -> Tag | None:
    """A speech's `span.gd-head` (drama.py) when it opens the block: it stays a line of its own, not a verse line."""
    for child in block.children:
        if isinstance(child, NavigableString) and not child.strip():
            continue
        return child if isinstance(child, Tag) and "gd-head" in (child.get("class") or []) else None
    return None


def flatten_word_export(soup: BeautifulSoup) -> None:
    """Undoes a word processor's layout in a page that has its `xhtml_` classes: `span.xhtml_font*` wrappers
    (which hold the `<br>` lines, so the verse rule can't see them), single-column tables laid out as pages
    (each cell becomes a `div`) and the `blockquote > div` pairs it nests for each level of indent."""
    if soup.find(class_=_WORD) is None:
        return
    for span in soup.find_all("span", class_=_WORD_FONT):
        if all(_WORD_FONT.match(c) for c in span["class"]):
            span.unwrap()
    for table in reversed(soup.find_all("table")):
        rows = table.find_all("tr")
        cells = [row.find_all(["td", "th"], recursive=False) for row in rows]
        if (not any(c.startswith("xhtml_") for c in table.get("class") or ()) or not rows
                or any(len(c) > 1 or (c and c[0].name == "th") for c in cells)):
            continue
        for cell in (c[0] for c in cells if c):
            if not cell.get_text().strip() and cell.find(True) is None:
                continue
            div = soup.new_tag("div", attrs={k: v for k, v in cell.attrs.items() if k in ("id", "class", "style")})
            for child in list(cell.contents):
                div.append(child.extract())
            table.insert_before(div)
        table.decompose()
    for quote in soup.find_all("blockquote"):
        inner = _only_child(quote, "div")
        if inner is not None and not inner.attrs:
            inner.unwrap()
            quote.unwrap()


def is_gloss(text: str) -> bool:
    """A margin gloss is a word or two (`*sweet`, `crowd`), starred, or short with no sentence in it; a
    longer run after wide spacing is the line's own words set apart (a half-line, a stage direction)."""
    text = text.strip()
    if "*" in text:  # Canterbury's glosses carry the star of the word they gloss: "*sweet", "with the best*"
        return True
    return len(text) <= 25 and not re.search(r"[.!?;:]\s+\S|[A-Z]{3,}", text)


def _only_child(tag: Tag, name: str) -> Tag | None:
    children = [c for c in tag.contents if not (isinstance(c, NavigableString) and not c.strip())]
    return children[0] if len(children) == 1 and isinstance(children[0], Tag) and children[0].name == name else None


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


def _lines(soup: BeautifulSoup, block: Tag, gloss: bool = True) -> None:
    head = _head(block)
    if head is not None:
        head.extract()
    groups = _groups(block)
    leads = [_lead(_text(g)) for g in groups if _text(g).strip()]
    indented = [spaces for _, spaces, newline in leads if newline] or [spaces for _, spaces, _ in leads]
    base = min(indented, default=0)
    for child in list(block.contents):
        child.extract()
    _fill(soup, block, groups, base, 0.3, gloss)
    lines = block.find_all("span", class_="gv-l", recursive=False)
    if head is not None:
        block.insert(0, head)
    elif len(lines) > 1 and _SPEAKER.match(" ".join(lines[0].get_text().split())):
        lines[0]["class"] = ["gv-l", "gv-sp"]
    block["class"] = (block.get("class") or []) + ["gv"]


def _fill(soup: BeautifulSoup, block: Tag, groups: list[list[PageElement]], base: int, em_per_space: float,
          gloss: bool = True) -> None:
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
        block.append(_line(soup, anchors + group, base, em_per_space, gloss))
        anchors = []
    if anchors:
        last = block.contents[-1] if block.contents else block
        for anchor in anchors:
            last.append(anchor)


def _pre(soup: BeautifulSoup, pre: Tag, gloss: bool = True) -> None:
    """A `<pre>` as a verse block: a line per source line, its indentation kept as a margin. Lines the source
    wrapped by hand are joined back (`join_wrapped`); a `<pre>` of hard-wrapped prose becomes paragraphs."""
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
    attrs = {k: v for k, v in pre.attrs.items() if k in ("id", "lang", "style")}
    classes = pre.get("class") or []
    if reads_as_prose(groups):
        runs = [(True, groups)]
    else:  # a cast list then a translator's note: verse and prose paragraphs each as they read
        runs = []
        for paragraph in _paragraph_groups(groups):
            prose = reads_as_prose(paragraph)
            if runs and runs[-1][0] == prose:
                runs[-1][1].extend([[]] + paragraph)
            else:
                runs.append((prose, list(paragraph)))
    base = min(_lead(_text(g))[1] for g in filled)
    out: list[Tag] = []
    for prose, run in runs:
        if prose:
            out += _paragraphs(soup, run, classes)
            continue
        div = soup.new_tag("div", attrs={k: v for k, v in attrs.items() if k != "id"})
        div["class"] = classes + ["gv", "gv-pre"]
        _fill(soup, div, join_wrapped(run), base, 0.5, gloss)
        out.append(div)
    if attrs.get("id"):
        out[0]["id"] = attrs["id"]
    pre.replace_with(*out)


def _paragraph_groups(groups: list[list[PageElement]]) -> list[list[list[PageElement]]]:
    """The pre's lines cut at blank lines."""
    paragraphs: list[list[list[PageElement]]] = [[]]
    for group in groups:
        if _text(group).strip():
            paragraphs[-1].append(group)
        elif paragraphs[-1]:
            paragraphs.append([])
    return [p for p in paragraphs if p]


_END_OF_SENTENCE = re.compile(r"[.!?]['’\"”)\]]*\s*$")


def _starts_lower(text: str) -> bool:
    stripped = text.lstrip(" \t\xa0'‘\"“(—-_")
    return bool(stripped) and stripped[0].islower()


def _pairs(groups: list[list[PageElement]]) -> list[tuple[str, str]]:
    """Each source line with the next one in the same paragraph (no blank line between)."""
    texts = [_text(g) for g in groups]
    return [(a, b) for a, b in zip(texts, texts[1:]) if a.strip() and b.strip()]


def reads_as_prose(groups: list[list[PageElement]]) -> bool:
    """Prose wrapped at a fixed column: most lines go on in lower case (verse starts its lines with capitals;
    Clouds is Hickie's prose, 947 such lines). Under three line pairs, every one must."""
    pairs = _pairs(groups)
    lower = sum(_starts_lower(b) for _, b in pairs)
    if len(pairs) < 3:  # a short pre: every line goes on in lower case after a long one
        return bool(pairs) and lower == len(pairs) and all(len(a.strip()) >= 45 for a, _ in pairs)
    return lower >= 0.5 * len(pairs)


def join_wrapped(groups: list[list[PageElement]]) -> list[list[PageElement]]:
    """Verse lines the source wrapped by hand, joined back: a line starting in lower case after a line that doesn't
    end a sentence and either runs near the source's wrapping column with this one set deeper (Leaves of Grass:
    `Born here of parents…, and their` / `    parents the same,`), or runs right up to a wide column (60+)."""
    width = max((len(_text(g).rstrip()) for g in groups), default=0)
    out: list[list[PageElement]] = []
    for group in groups:
        text = _text(group)
        before = _text(out[-1]).rstrip() if out else ""
        if (out and width >= 50 and text.strip() and _starts_lower(text) and not _END_OF_SENTENCE.search(before)
                and (len(before) >= 0.75 * width and _lead(text)[0] >= _lead(before)[0] + 2
                     or width >= 60 and len(before) >= 0.9 * width)):
            first, *rest = group
            if isinstance(first, NavigableString):
                first = NavigableString(" " + str(first).lstrip())
            else:
                rest, first = [first, *rest], NavigableString(" ")
            out[-1] = out[-1] + [first, *rest]
            continue
        out.append(list(group))
    return out


def _paragraphs(soup: BeautifulSoup, groups: list[list[PageElement]], classes: list[str]) -> list[Tag]:
    paragraphs: list[Tag] = []
    current: Tag | None = None
    for group in groups:
        if not _text(group).strip():
            current = None
            continue
        if current is None:
            current = soup.new_tag("p", attrs={"class": classes + ["gv-prose"]} if classes else {})
            paragraphs.append(current)
        elif current.contents:
            current.append(" ")
        for i, node in enumerate(group):
            if isinstance(node, NavigableString):
                text = " ".join(str(node).split())
                if str(node)[:1].isspace() and i and text:
                    text = " " + text
                if str(node)[-1:].isspace() and i < len(group) - 1 and text:
                    text += " "
                node = NavigableString(text)
            current.append(node)
    return paragraphs or [soup.new_tag("p")]


def _line(soup: BeautifulSoup, nodes: list[PageElement], base: int, em_per_space: float, gloss: bool = True) -> Tag:
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
        elif gloss and (m := _GLOSS.search(text.rstrip())) and is_gloss(m.group(2)):
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
