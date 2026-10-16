"""Splits a cleaned book body into reader-sized chapter pages.

A book splits at the first heading level (h1-h4) it uses at least three times. A page still over the size
limit splits again at the next heading level found inside it, then between block elements. Pages are
served as siblings (`<slug>/001`, `<slug>/002`, ...), so links to anchors are rewritten to point at the
page that now holds the anchor.
"""
import re
from dataclasses import dataclass

from bs4 import BeautifulSoup, NavigableString, PageElement, Tag

MAX_TITLE = 120
FRONT_MATTER_CHARS = 200
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


def split(body: Tag, split_at: str | None = None, max_bytes: int = 150_000) -> list[Chapter]:
    nodes = list(body.contents)
    level = int(split_at[1]) if split_at else _pick_level(body)
    parts = _split_level(nodes, level, None, max_bytes) if level else None
    if not parts:
        parts = _fit(_Part("", nodes), max_bytes, 1)
    return _finish(parts)


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
            out.extend(_flatten(list(node.contents), level))
        else:
            out.append(node)
    return out


def _title(text: str) -> str:
    return " ".join(text.split())[:MAX_TITLE]


def _heading_title(heading: Tag) -> str:
    """The heading's words, leaving out page-number markers like `{ix}` that Gutenberg files put in headings."""
    def in_page_number(string) -> bool:
        node = string.parent
        while node is not None and node is not heading:
            if PAGE_NUMBER.intersection(node.get("class") or []):
                return True
            node = node.parent
        return False

    return _title(" ".join(str(s) for s in heading.find_all(string=True) if not in_page_number(s)))


def _text_len(nodes: list[PageElement]) -> int:
    return sum(len(n.get_text() if isinstance(n, Tag) else str(n).strip()) for n in nodes)


def _size(nodes: list[PageElement]) -> int:
    return sum(len(str(n).encode()) for n in nodes)


def _split_level(nodes: list[PageElement], level: int, parent: str | None, max_bytes: int) -> list[_Part] | None:
    nodes = _flatten(nodes, level)
    front: list[PageElement] = []
    groups: list[_Part] = []
    for node in nodes:
        if _has(node, level):
            heading = node if node.name == f"h{level}" else node.find(f"h{level}")
            title = _heading_title(heading) or f"Part {len(groups) + 1}"
            groups.append(_Part(f"{parent} — {title}" if parent else title, [node]))
        elif groups:
            groups[-1].nodes.append(node)
        else:
            front.append(node)
    if not groups:
        return None
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
    runs: list[list[PageElement]] = [[]]
    size = 0
    pending = list(part.nodes)
    while pending:
        node = pending.pop(0)
        n = len(str(node).encode())
        if n > max_bytes:
            if isinstance(node, Tag) and node.contents:
                pending[:0] = list(node.contents)
                continue
            pending[:0] = _split_text(node, max_bytes)
            continue
        if size + n > max_bytes and runs[-1]:
            runs.append([])
            size = 0
        runs[-1].append(node)
        size += n
    name = part.title
    return [_Part(f"{name} (part {k})" if name else f"Part {k}", run) for k, run in enumerate(runs, 1)]


def _split_text(node: PageElement, max_bytes: int) -> list[PageElement]:
    """Cuts one huge text node into pieces at whitespace (a limit in characters is safe for the byte limit /4)."""
    text, limit, pieces = str(node), max(1, max_bytes // 4), []
    while text:
        cut = text.rfind(" ", 0, limit) if len(text) > limit else len(text)
        cut = cut if cut > 0 else limit
        pieces.append(NavigableString(text[:cut]))
        text = text[cut:]
    return pieces


def _finish(parts: list[_Part]) -> list[Chapter]:
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
                link["href"] = f"#{fragment}" if target == index else f"{page_name(target)}#{fragment}"
            else:
                link.unwrap()
    return [Chapter(part.title, div.decode_contents()) for part, div in zip(parts, pages)]
