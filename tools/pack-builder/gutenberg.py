"""Cuts one Project Gutenberg HTML book down to the work itself.

Everything before the start marker and after the end marker goes (the Gutenberg header, license and footer),
and so does every remaining block that names Project Gutenberg: their trademark license lets anyone
redistribute a public-domain text freely once every reference to Project Gutenberg is removed. Images,
scripts and external links go too, since packs are text-only and the reader never touches the network.
"""
import re
from dataclasses import dataclass

from bs4 import BeautifulSoup, Comment, NavigableString, Tag

BRANDING = re.compile(r"project gutenberg|gutenberg\.(org|net)", re.I)
# The Distributed Proofreaders credit line always sits next to Gutenberg's own.
CREDITS = re.compile(r"pgdp\.net|distributed proofread", re.I)
START = re.compile(r"\*\*\*\s*START OF (THE|THIS) PROJECT GUTENBERG[^*]*\*\*\*", re.I)
END = re.compile(r"\*\*\*\s*END OF (THE|THIS) PROJECT GUTENBERG[^*]*\*\*\*", re.I)
REMOVED_TAGS = ["script", "link", "meta", "img", "svg", "object", "iframe", "form", "audio", "video", "picture",
                "noscript", "embed"]
BLOCKS = {"p", "div", "pre", "blockquote", "table", "li", "ul", "ol", "dl", "section", "header", "footer",
          "h1", "h2", "h3", "h4", "h5", "h6", "figure", "center"}
SCHEME = re.compile(r"^[a-z][a-z0-9+.-]*:", re.I)


class NotAGutenbergBook(ValueError):
    """The HTML has no Gutenberg start and end markers, so there's no safe place to cut."""


@dataclass
class Cleaned:
    body: Tag
    styles: str
    branding_removed: int


def clean(html: bytes) -> Cleaned:
    soup = BeautifulSoup(html, "lxml")
    styles = _styles(soup)
    for comment in soup.find_all(string=lambda s: isinstance(s, Comment)):
        comment.extract()
    root = soup.body or soup
    start, end = root.find(id="pg-start-separator"), root.find(id="pg-end-separator")
    if start is None or end is None:
        start, end = _text_marker(root, START, keep="after"), _text_marker(root, END, keep="before")
    if start is None or end is None:
        raise NotAGutenbergBook("no Project Gutenberg start and end markers")
    _drop_after(end, root)
    _drop_before(start, root)

    for tag in root.find_all(REMOVED_TAGS):
        tag.decompose()
    for link in root.find_all("a", href=True):
        if SCHEME.match(link["href"].strip()):
            link.unwrap()
    removed = _drop_branding(root)

    body = soup.new_tag("div")
    for child in list(root.contents):
        body.append(child.extract())
    return Cleaned(body=body, styles=styles, branding_removed=removed)


def _styles(soup: BeautifulSoup) -> str:
    css = "\n".join(style.get_text() for style in soup.find_all("style"))
    for style in soup.find_all("style"):
        style.decompose()
    css = re.sub(r"@import[^;]*;", "", css)
    css = re.sub(r"[^;{}]*url\([^)]*\)[^;{}]*;?", "", css)
    return css.strip()


def _text_marker(root: Tag, pattern: re.Pattern, keep: str) -> Tag | None:
    """The element holding a text marker. Text on the kept side of it, in the same element, moves out first."""
    string = root.find(string=pattern)
    if string is None:
        return None
    element = string.parent
    before, after = pattern.split(str(string), maxsplit=1)[0], pattern.split(str(string), maxsplit=1)[-1]
    rest = after if keep == "after" else before
    if rest.strip() and element.name == "pre":
        moved = BeautifulSoup("", "lxml").new_tag("pre")
        moved.string = rest.strip("\n")
        if keep == "after":
            element.insert_after(moved)
        else:
            element.insert_before(moved)
    return element


def _drop_before(marker: Tag, root: Tag) -> None:
    node = marker
    while node is not None and node is not root:
        for sibling in list(node.previous_siblings):
            sibling.extract()
        node = node.parent
    marker.decompose()


def _drop_after(marker: Tag, root: Tag) -> None:
    node = marker
    while node is not None and node is not root:
        for sibling in list(node.next_siblings):
            sibling.extract()
        node = node.parent
    marker.decompose()


def _drop_branding(root: Tag) -> int:
    removed = 0
    for tag in root.find_all(True):
        for name, value in list(tag.attrs.items()):
            text = " ".join(value) if isinstance(value, list) else str(value)
            if BRANDING.search(text):
                del tag.attrs[name]
    while True:
        string = root.find(string=lambda s: isinstance(s, NavigableString)
                           and (BRANDING.search(s) or CREDITS.search(s)))
        if string is None:
            return removed
        block = next((p for p in string.parents if p.name in BLOCKS), None)
        if block is None or block is root:
            string.extract()
        else:
            block.decompose()
        removed += 1
