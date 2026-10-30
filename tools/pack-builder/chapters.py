"""Splits a cleaned book body into reader-sized chapter pages.

A book splits at the first heading level (h1-h4) it uses at least three times. A page still over the size
limit splits again at the next heading level found inside it, then between block elements. Pages are
served as siblings (`<slug>/001`, `<slug>/002`, ...), so links to anchors are rewritten to point at the
page that now holds the anchor.

A wrapper element opened to split inside it (a `div.poem` holding many cantos, or one too big for a page)
isn't thrown away: each run of its children goes back into a copy of it, so the work's rules scoped to the
wrapper (`.poem p.i2`) still match on every page. Only the first copy keeps the wrapper's id.
"""
import re
from dataclasses import dataclass
from typing import Callable

from bs4 import BeautifulSoup, NavigableString, PageElement, Tag

from gutenberg import unwrap_link

MAX_TITLE = 120
FRONT_MATTER_CHARS = 200
HEADINGS = {"h1", "h2", "h3", "h4", "h5", "h6"}
PAGE_NUMBER = {"pagenum", "pageno", "page-num", "pagenumber"}
SCHEME = re.compile(r"^[a-z][a-z0-9+.-]*:", re.I)


@dataclass
class Chapter:
    title: str
    html: str


@dataclass
class _Part:
    title: str
    nodes: list[PageElement]


def page_name(index: int) -> str:
    return f"{index + 1:03d}"


def clean_title(title: str, work_title: str, author: str, index: int) -> str:
    """A chapter title for a reader's contents list: no byline, no "Contents", no repeat of the work's title,
    and no shouting ("BOOK I." reads "Book I")."""
    segments = (s.strip() for s in re.sub(r"<\d+>", "", title).split(" — "))
    kept = [s for s in segments
            if s and not re.match(r"by\s", s, re.I) and s.lower() not in ("contents", "table of contents")
            and s.lower() != work_title.strip().lower()]
    return " — ".join(map(_quieter, kept)) or f"Part {index + 1}"


def _quieter(segment: str) -> str:
    """One title segment, title-cased if it's in capitals; a size split's "(part 2)" doesn't count."""
    m = _PART_SUFFIX.search(segment)
    head, tail = (segment[:m.start()], m.group(0)) if m else (segment, "")
    if not shouting(head):
        return segment
    return drop_full_stop(title_case(head)[0]) + tail


_PART_SUFFIX = re.compile(r"\s*\(part \d+\)$")
_ROMAN = re.compile(r"M{0,4}(CM|CD|D?C{0,3})(XC|XL|L?X{0,3})(IX|IV|V?I{0,3})")
_SMALL_WORDS = {"a", "an", "and", "as", "at", "but", "by", "for", "from", "in", "into", "nor", "of", "on", "or",
                "the", "to", "upon", "with"}
_WORD = re.compile(r"[^\W\d_][^\s\-–—/]*")


def shouting(text: str) -> bool:
    """True when the text has capital letters and no small ones."""
    return any(c.isupper() for c in text) and not any(c.islower() for c in text)


def title_case(text: str, first: bool = True) -> tuple[str, bool]:
    """Capitals to title case: "CANTO THE SECOND" -> "Canto the Second", keeping Roman numerals ("BOOK IV")
    and initials ("U.S.A."). Also returns whether the next word starts a sentence, for text split across nodes."""
    def word(m: re.Match) -> str:
        nonlocal first
        token = m.group(0)
        bare = token.rstrip(".,;:!?’'\")»”")
        starts, first = first, token.endswith((".", ":", "!", "?"))
        if "." in bare or (bare and _ROMAN.fullmatch(bare)):  # initials ("U.S.A.") or a numeral
            return token
        if not starts and bare.lower() in _SMALL_WORDS:
            return token.lower()
        return token[0] + token[1:].lower()

    if not any(c.isalpha() for c in text):
        return text, first
    return _WORD.sub(word, text), first


def drop_full_stop(text: str) -> str:
    """"Book I." -> "Book I", but not an ellipsis or the last stop of initials ("U.S.A.")."""
    stripped = text.rstrip()
    if stripped.endswith(".") and "." not in stripped.split()[-1][:-1]:
        return stripped[:-1] + text[len(stripped):]
    return text


def split(body: Tag, split_at: str | None = None, max_bytes: int = 150_000,
          page_href: Callable[[int], str] = page_name) -> list[Chapter]:
    nodes = list(body.contents)
    level = int(split_at[1]) if split_at else _pick_level(body)
    parts = _split_level(nodes, level, None, max_bytes) if level else None
    if not parts:
        parts = _fit(_Part("", nodes), max_bytes, 1)
    return _finish(parts, page_href)


def _pick_level(body: Tag) -> int | None:
    return next((n for n in range(1, 5) if len(body.find_all(f"h{n}")) >= 3), None)


def _has(node: PageElement, level: int) -> int:
    """How many h{level} headings the node is or holds."""
    if not isinstance(node, Tag):
        return 0
    return (node.name == f"h{level}") + len(node.find_all(f"h{level}"))


def _flatten(nodes: list[PageElement], level: int) -> list[PageElement]:
    """Opens up wrapper elements that hold more than one split heading, so each heading can start a page."""
    out = []
    for node in nodes:
        if isinstance(node, Tag) and node.name != f"h{level}" and _has(node, level) >= 2:
            out.extend(_rewrap(node, _flatten(list(node.contents), level), level))
        else:
            out.append(node)
    return out


_HOLDER = BeautifulSoup("", "lxml")


def _shell(wrapper: Tag, keep_id: bool) -> Tag:
    """An empty copy of a wrapper, with all its attributes but the id unless asked to keep it."""
    attrs = {k: list(v) if isinstance(v, list) else v for k, v in wrapper.attrs.items() if keep_id or k != "id"}
    return _HOLDER.new_tag(wrapper.name, attrs=attrs)


def _rewrap(wrapper: Tag, children: list[PageElement], level: int) -> list[PageElement]:
    """The opened wrapper's children in runs, each new run starting at a split heading, each in a copy of it."""
    runs: list[list[PageElement]] = [[]]
    for child in children:
        if _has(child, level) and runs[-1]:
            runs.append([])
        runs[-1].append(child)
    out: list[PageElement] = []
    first = True
    for run in runs:
        if all(isinstance(n, NavigableString) and not n.strip() for n in run):
            out.extend(run)
            continue
        copy = _shell(wrapper, keep_id=first)
        first = False
        for node in run:
            copy.append(node.extract() if node.parent is not None else node)
        out.append(copy)
    return out


def _title(text: str) -> str:
    return " ".join(text.split())[:MAX_TITLE]


def _heading_title(heading: Tag) -> str:
    """The heading's words, leaving out page-number markers like `{ix}` and footnote superscripts."""
    def in_marker(string) -> bool:
        node = string.parent
        while node is not None and node is not heading:
            if node.name == "sup" or PAGE_NUMBER.intersection(node.get("class") or []):
                return True
            node = node.parent
        return False

    return _title(" ".join(str(s) for s in heading.find_all(string=True) if not in_marker(s)))


def _text_len(nodes: list[PageElement]) -> int:
    return sum(len(n.get_text() if isinstance(n, Tag) else str(n).strip()) for n in nodes)


def _bytes(node: PageElement) -> int:
    """The node's size on its page, counting the page name a same-book link may gain (`#x` -> `001#x`)."""
    text = str(node)
    return len(text.encode()) + 3 * text.count('href="#')


def _size(nodes: list[PageElement]) -> int:
    return sum(_bytes(n) for n in nodes)


def _heading_only(part: _Part) -> bool:
    """True when the part's only text is its headings, like a bare "PART I" page."""
    for node in part.nodes:
        strings = [node] if isinstance(node, NavigableString) else node.find_all(string=True)
        if any(s.strip() and not any(p.name in HEADINGS for p in s.parents) for s in strings):
            return False
    return True


def _merge_heading_only(groups: list[_Part]) -> list[_Part]:
    """Folds each heading-only part into the part after it, so Next never lands on a page with no text."""
    out: list[_Part] = []
    carry = None
    for i, part in enumerate(groups):
        if carry:
            part = _Part(_title(f"{carry.title} — {part.title}"), carry.nodes + part.nodes)
            carry = None
        if i < len(groups) - 1 and _heading_only(part):
            carry = part
        else:
            out.append(part)
    return out


def _split_level(nodes: list[PageElement], level: int, parent: str | None, max_bytes: int) -> list[_Part] | None:
    nodes = _flatten(nodes, level)
    front: list[PageElement] = []
    groups: list[_Part] = []
    for node in nodes:
        if _has(node, level):
            heading = node if node.name == f"h{level}" else node.find(f"h{level}")
            groups.append(_Part(_heading_title(heading) or f"Part {len(groups) + 1}", [node]))
        elif groups:
            groups[-1].nodes.append(node)
        else:
            front.append(node)
    if not groups:
        return None
    groups = _merge_heading_only(groups)
    if parent:
        groups = [_Part(f"{parent} — {g.title}", g.nodes) for g in groups]
    if _text_len(front) > FRONT_MATTER_CHARS:
        groups.insert(0, _Part(parent or "Front matter", front))
    else:
        groups[0].nodes[:0] = front
    return [piece for part in groups for piece in _fit(part, max_bytes, level + 1)]


def _fit(part: _Part, max_bytes: int, next_level: int) -> list[_Part]:
    """The part itself if it's small enough, else its pieces at the next heading level or by size."""
    if _size(part.nodes) <= max_bytes:
        return [part]
    for level in range(next_level, 7):
        if any(_has(n, level) for n in part.nodes):
            pieces = _split_level(part.nodes, level, part.title or None, max_bytes)
            if pieces and len(pieces) > 1:
                return pieces
    return _by_size(part, max_bytes)


def _by_size(part: _Part, max_bytes: int) -> list[_Part]:
    """Packs the part's nodes into pages, opening any node too big for one; its pieces go back into copies of it."""
    runs: list[list[PageElement]] = [[]]
    size = 0
    opened: list[tuple[Tag, Tag]] = []  # the wrappers open on the current page and their copies, outermost first
    first_copy: set[int] = set()
    pending: list[tuple[PageElement, tuple[Tag, ...]]] = [(node, ()) for node in part.nodes]
    while pending:
        node, wrappers = pending.pop(0)
        n = _bytes(node)
        if n + sum(_bytes(_shell(w, True)) for w in wrappers) > max_bytes:
            if isinstance(node, Tag) and node.contents:
                pending[:0] = [(child, (*wrappers, node)) for child in node.contents]
                continue
            pending[:0] = [(piece, wrappers) for piece in _split_text(node, max_bytes)]
            continue
        same = 0
        while same < min(len(opened), len(wrappers)) and opened[same][0] is wrappers[same]:
            same += 1
        shells = sum(_bytes(_shell(w, True)) for w in wrappers[same:])
        if size + n + shells > max_bytes and runs[-1]:
            term = _trailing_term(opened, wrappers)
            if term is not None and term is not node:  # a dt goes over with its dd
                pending[:0] = [(term.extract(), wrappers), (node, wrappers)]
                runs.append([])
                size = 0
                del opened[:]
                continue
            runs.append([])
            size, same = 0, 0
            shells = sum(_bytes(_shell(w, True)) for w in wrappers)
        del opened[same:]
        for wrapper in wrappers[same:]:
            copy = _shell(wrapper, keep_id=id(wrapper) not in first_copy)
            first_copy.add(id(wrapper))
            (opened[-1][1] if opened else runs[-1]).append(copy)
            opened.append((wrapper, copy))
        if opened:
            opened[-1][1].append(node.extract() if node.parent is not None else node)
        else:
            runs[-1].append(node)
        size += n + shells
    name = part.title
    return [_Part(f"{name} (part {k})" if name else f"Part {k}", run) for k, run in enumerate(runs, 1)]


def _trailing_term(opened: list[tuple[Tag, Tag]], wrappers: tuple[Tag, ...]) -> Tag | None:
    """The dt ending the current page's copy of the definition list the next node also goes into, if any."""
    if not opened or not wrappers or opened[-1][0] is not wrappers[-1] or wrappers[-1].name != "dl":
        return None
    last = next((c for c in reversed(opened[-1][1].contents) if not (isinstance(c, NavigableString) and not c.strip())),
                None)
    return last if isinstance(last, Tag) and last.name == "dt" and len(opened[-1][1].contents) > 1 else None


def _split_text(node: PageElement, max_bytes: int) -> list[PageElement]:
    """Cuts one huge text node into pieces at whitespace (a limit in characters is safe for the byte limit /4)."""
    text, limit, pieces = str(node), max(1, max_bytes // 4), []
    while text:
        cut = text.rfind(" ", 0, limit) if len(text) > limit else len(text)
        cut = cut if cut > 0 else limit
        pieces.append(NavigableString(text[:cut]))
        text = text[cut:]
    return pieces


def _finish(parts: list[_Part], page_href: Callable[[int], str]) -> list[Chapter]:
    holder = BeautifulSoup("", "lxml")
    pages = []
    for part in parts:
        div = holder.new_tag("div")
        for node in part.nodes:
            div.append(node.extract() if node.parent is not None else node)
        pages.append(div)
    anchors: dict[str, int] = {}
    for index, div in enumerate(pages):
        for tag in div.find_all(True):
            for attr in ("id", "name"):
                if tag.get(attr) and not (attr == "name" and tag.name == "meta"):
                    anchors.setdefault(tag[attr], index)
    for index, div in enumerate(pages):
        for link in div.find_all("a", href=True):
            href = link["href"].strip()
            if SCHEME.match(href):
                continue
            fragment = href.split("#", 1)[1] if "#" in href else ""
            if fragment in anchors:
                target = anchors[fragment]
                link["href"] = f"#{fragment}" if target == index else f"{page_href(target)}#{fragment}"
            else:
                unwrap_link(link)
    return [Chapter(part.title, div.decode_contents()) for part, div in zip(parts, pages)]
