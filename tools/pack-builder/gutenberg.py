"""Cuts one Project Gutenberg HTML book down to the work itself.

Everything before the start marker and after the end marker goes (the Gutenberg header, license and footer),
and so does every remaining block that names Project Gutenberg: their trademark license lets anyone
redistribute a public-domain text freely once every reference to Project Gutenberg is removed. Images,
scripts and external links go too, since packs are text-only and the reader never touches the network.
"""
import re
from dataclasses import dataclass

from bs4 import BeautifulSoup, Comment, NavigableString, Tag

BRANDING = re.compile(r"project[\s_-]+gutenberg|gutenberg[\s-]*e-?texts?|gutenberg\.(org|net)", re.I)
# The Distributed Proofreaders credit line always sits next to Gutenberg's own.
CREDITS = re.compile(r"pgdp\.net|distributed proofread", re.I)
START = re.compile(r"\*\*\*\s*START OF (THE|THIS) PROJECT GUTENBERG[^*]*\*\*\*", re.I)
END = re.compile(r"\*\*\*\s*END OF (THE|THIS) PROJECT GUTENBERG[^*]*\*\*\*", re.I)
REMOVED_TAGS = ["script", "link", "meta", "img", "svg", "object", "iframe", "form", "audio", "video", "picture",
                "noscript", "embed"]
BLOCKS = {"p", "div", "pre", "blockquote", "table", "li", "ul", "ol", "dl", "section", "header", "footer",
          "h1", "h2", "h3", "h4", "h5", "h6", "figure", "center"}
HEADINGS = re.compile(r"^h[1-6]$")
SCHEME = re.compile(r"^[a-z][a-z0-9+.-]*:", re.I)
NOTICE = re.compile(r"[Cc]opyright|[Pp]ublished|[Ee]dition|[Pp]rinted|[Rr]eprint")
FRONT_MATTER_CHARS = 12_000
# Branding removal that takes more text than it leaves, past this much, means a wrapper holding the book went.
GUTTED_CHARS = 10_000


class CleaningError(ValueError):
    """The book can't be cleaned safely, so it must not ship."""


class NotAGutenbergBook(CleaningError):
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
        start = _text_marker(soup, root, START)
        end = _text_marker(soup, root, END) if start is not None else None
    if start is None or end is None:
        raise NotAGutenbergBook("no Project Gutenberg start and end markers")
    _drop_after(end, root)
    _drop_before(start, root)

    for image in root.select("h1 img[alt], h2 img[alt], h3 img[alt], h4 img[alt], h5 img[alt], h6 img[alt]"):
        image.replace_with(image["alt"])
    for tag in root.find_all(REMOVED_TAGS):
        tag.decompose()
    for link in root.find_all("a", href=True):
        if SCHEME.match(link["href"].strip()):
            link.unwrap()
    removed, removed_chars = _drop_branding(root)

    body = soup.new_tag("div")
    for child in list(root.contents):
        body.append(child.extract())
    kept = len(" ".join(body.get_text().split()))
    if kept == 0 or removed_chars > max(kept, GUTTED_CHARS):
        raise CleaningError(f"cleaning left {kept} characters and removed {removed_chars} as branding")
    return Cleaned(body=body, styles=styles, branding_removed=removed)


def _styles(soup: BeautifulSoup) -> str:
    css = "\n".join(style.get_text() for style in soup.find_all("style"))
    for style in soup.find_all("style"):
        style.decompose()
    css = re.sub(r"/\*.*?\*/", "", css, flags=re.S)
    css = re.sub(r"[^;{}]*(pg-|gutenberg|pgdp)[^;{}]*\{[^{}]*\}", "", css, flags=re.I)  # Gutenberg's own rules
    css = re.sub(r"@import[^;]*;", "", css)
    css = re.sub(r"[^;{}]*url\([^)]*\)[^;{}]*;?", "", css)
    return css.strip()


def _text_marker(soup: BeautifulSoup, root: Tag, pattern: re.Pattern) -> Tag | None:
    """An empty marker element put exactly where the text marker was, so cutting at it keeps the text around it."""
    string = root.find(string=pattern)
    if string is None:
        return None
    text = str(string)
    match = pattern.search(text)
    marker = soup.new_tag("span")
    string.replace_with(NavigableString(text[:match.start()]), marker, NavigableString(text[match.end():]))
    return marker


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


def _drop_branding(root: Tag) -> tuple[int, int]:
    """Removes each block naming Project Gutenberg, or only the text when the block holds headings (a wrapper)."""
    removed = chars = 0
    for tag in root.find_all(True):
        for name, value in list(tag.attrs.items()):
            text = " ".join(value) if isinstance(value, list) else str(value)
            if BRANDING.search(text):
                del tag.attrs[name]
    while True:
        string = root.find(string=lambda s: isinstance(s, NavigableString)
                           and (BRANDING.search(s) or CREDITS.search(s)))
        if string is None:
            return removed, chars
        block = next((p for p in string.parents if p.name in BLOCKS), None)
        if block is None or block is root or block.find(HEADINGS):
            chars += len(string.strip())
            string.extract()
        else:
            chars += len(block.get_text().strip())
            block.decompose()
        removed += 1


def later_edition_notices(body: Tag, after: int = 1930) -> list[str]:
    """Sentences near the start that date this edition after the public-domain line, such as a modern introduction.

    The manifest's year can't catch these: the text is old, but the edition Gutenberg copied may add newer material.
    """
    text = " ".join(body.get_text(" ").split())[:FRONT_MATTER_CHARS]
    sentences = re.split(r"(?<!\b[A-Z]\.)(?<=[.!?])\s+", text)  # not after initials like "E. P."
    return [s for s in sentences if NOTICE.search(s) and any(int(y) > after for y in re.findall(r"\b(1[89]\d\d|20\d\d)\b", s))]
