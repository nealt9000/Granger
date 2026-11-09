"""Cuts one Project Gutenberg HTML book down to the work itself.

Everything before the start marker and after the end marker goes (the Gutenberg header, license and footer),
and so does every remaining block that names Project Gutenberg: their trademark license lets anyone
redistribute a public-domain text freely once every reference to Project Gutenberg is removed. Images,
scripts and external links go too, since packs are text-only and the reader never touches the network.
So do the shells those cuts leave: empty boilerplate headers and footers, blocks with nothing in them (an
emptied figure, a `<p>&nbsp;</p>` spacer), and a transcriber's note that only speaks of the cover art.
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
NO_PHRASING_CHILDREN = {"ul", "ol", "dl", "table", "thead", "tbody", "tfoot", "tr", "colgroup"}
NOTE_HEADING = re.compile(r"^\W*transcriber[’'`]?s?\s+notes?\W*$", re.I)
COVER_ART = re.compile(r"\bcover[\s-]+(art|image|picture|illustration|design)|\bpublic domain\b", re.I)
NOTE_CHARS = 400
# Faded Page (Distributed Proofreaders Canada) opens its books with terms of use, a Canadian copyright warning
# and a box of posting dates; Gutenberg copies them in. Only these exact phrases, near the start, count.
FADED_PAGE = re.compile(r"made available at no cost and with very few\s+restrictions|in the Canadian public domain"
                        r"|Faded Page eBook #|IF THE BOOK IS UNDER COPYRIGHT\s+IN YOUR COUNTRY")
FADED_PAGE_BLOCK_CHARS = 600


class CleaningError(ValueError):
    """The book can't be cleaned safely, so it must not ship."""


class NotAGutenbergBook(CleaningError):
    """The HTML has no Gutenberg start and end markers, so there's no safe place to cut."""


@dataclass
class Cleaned:
    body: Tag
    styles: str
    branding_removed: int


def clean(html: bytes, cut_after: str | None = None) -> Cleaned:
    """The work alone. `cut_after` (the manifest's `cutAfter`) is the work's last words: anything after them, such
    as a publisher's catalogue, goes too. They must appear exactly once, so a changed source fails the build."""
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
    if cut_after:
        _cut_after(soup, root, cut_after)

    for image in root.select("h1 img[alt], h2 img[alt], h3 img[alt], h4 img[alt], h5 img[alt], h6 img[alt]"):
        image.replace_with(image["alt"])
    for tag in root.find_all(REMOVED_TAGS):
        tag.decompose()
    for link in root.find_all("a", href=True):
        if SCHEME.match(link["href"].strip()):
            unwrap_link(link)
    removed, removed_chars = _drop_branding(root)
    _drop_leftovers(root)

    body = soup.new_tag("div")
    for child in list(root.contents):
        body.append(child.extract())
    kept = len(" ".join(body.get_text().split()))
    if kept == 0 or removed_chars > max(kept, GUTTED_CHARS):
        raise CleaningError(f"cleaning left {kept} characters and removed {removed_chars} as branding")
    return Cleaned(body=body, styles=styles, branding_removed=removed)


def unwrap_link(link: Tag) -> None:
    """Drops the link but keeps its text, and keeps an id or name it carried as a span so TOC links still land."""
    anchor, other = link.get("id") or link.get("name"), link.get("name")
    if anchor:
        if other and other != anchor:  # both were targets: keep both
            link.insert_before(BeautifulSoup("", "lxml").new_tag("span", attrs={"id": other}))
        link.attrs = {"id": anchor}
        link.name = "span"
    else:
        link.unwrap()


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


def _cut_after(soup: BeautifulSoup, root: Tag, words: str) -> None:
    pattern = re.compile(r"\s+".join(map(re.escape, words.split())))
    found = root.find_all(string=pattern)
    if len(found) != 1 or len(pattern.findall(str(found[0]))) != 1:
        raise CleaningError(f"cutAfter {words!r} found {sum(len(pattern.findall(str(f))) for f in found)} times, "
                            "not once")
    text = str(found[0])
    match = pattern.search(text)
    marker = soup.new_tag("span")
    found[0].replace_with(NavigableString(text[:match.end()]), marker, NavigableString(text[match.end():]))
    _drop_after(marker, root)


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


def _drop_leftovers(root: Tag) -> None:
    for shell in root.select("header.pg-boilerplate, footer.pg-boilerplate"):
        if not shell.get_text().strip():
            shell.decompose()
    _drop_cover_notes(root)
    _drop_faded_page_notices(root)
    _drop_illustration_lists(root)
    _drop_spacers(root)
    for block in reversed(root.find_all(["div", "p"])):  # innermost first, so emptied wrappers go too
        if (block.parent is None or block.parent.name in NO_PHRASING_CHILDREN or block.get_text().strip()
                or "gx-gap" in (block.get("class") or [])):
            continue
        children = [c for c in block.contents if not (isinstance(c, NavigableString) and not c.strip())]
        if all(isinstance(c, Tag) and c.name == "span" and set(c.attrs) == {"id"} and not c.contents
               for c in children):
            if block.get("id"):
                block.insert_before(BeautifulSoup("", "lxml").new_tag("span", attrs={"id": block["id"]}))
            for span in children:
                block.insert_before(span.extract())
            block.decompose()


GAP_STYLE = "height: 1em"
_SPACER_CHILDREN = {"br", "a", "span"}


def _spacer(block: Tag) -> bool:
    """A block with no words that only makes room: `<p><br/></p>`, `<div style="height: 4em"><br/>…</div>`, a
    `<p><a id="…"/></p>` holding a link target."""
    if block.get_text().strip() or "gx-gap" in (block.get("class") or []):
        return False
    return all(t.name in _SPACER_CHILDREN and not t.get("href") for t in block.find_all(True))


def _drop_spacers(root: Tag) -> None:
    """Spacer blocks go (formatting audit F6: 18,209 `<br>`-only blocks in 119 books, 6,572 anchor-only, 2,289
    height spacers), their ids kept as spans. Where two or more stood together (a stanza or scene break, as in
    Poems: Three Series, whose one-line divs are parted by one such block and its stanzas by two) one small gap
    stays, unless a heading or rule follows, which brings its own space."""
    holder = BeautifulSoup("", "lxml")
    for block in reversed(root.find_all(["p", "div"])):  # innermost first, so emptied wrappers go too
        if block.parent is None or block.parent.name in NO_PHRASING_CHILDREN or not _spacer(block):
            continue
        ids = [t["id"] for t in [block, *block.find_all(True)] if t.get("id")]
        ids += [t["name"] for t in block.find_all("a", attrs={"name": True}) if t.get("name") not in ids]
        marker = holder.new_tag("span", attrs={"data-spacer": "1"})
        block.insert_before(marker)
        for ident in dict.fromkeys(ids):
            marker.insert_before(holder.new_tag("span", attrs={"id": ident}))
        block.decompose()
    for marker in root.find_all("span", attrs={"data-spacer": True}):
        if marker.parent is None:
            continue
        run, node = [marker], marker.next_sibling
        while node is not None:
            if isinstance(node, NavigableString) and not node.strip() or (
                    isinstance(node, Tag) and node.name == "span" and set(node.attrs) == {"id"} and not node.contents):
                node = node.next_sibling
                continue
            if isinstance(node, Tag) and node.name == "span" and node.get("data-spacer"):
                run.append(node)
                node = node.next_sibling
                continue
            break
        following = node if isinstance(node, Tag) else None
        keep_gap = (len(run) >= 2 and following is not None and not HEADINGS.match(following.name)
                    and following.name != "hr" and _leads_somewhere(run[0]))
        if keep_gap:
            run[0].replace_with(holder.new_tag("div", attrs={"class": "gx-gap", "style": GAP_STYLE}))
            run = run[1:]
        for m in run:
            m.decompose()


def _leads_somewhere(marker: Tag) -> bool:
    """True when words come before the marker in its parent (a gap at a wrapper's start parts nothing)."""
    node = marker.previous_sibling
    while node is not None:
        if (node.get_text() if isinstance(node, Tag) else str(node)).strip():
            return True
        node = node.previous_sibling
    return False


ILLUSTRATIONS = re.compile(r"^\W*(list of )?(illustrations|plates)\W*$", re.I)
ILLUSTRATION_LIST_CHARS = 20_000
PAGE_MARKS = {"pagenum", "pageno", "page-num", "pagenumber", "tei-pb"}


def _drop_illustration_lists(root: Tag) -> None:
    """A "List of Illustrations" heading and the list under it, up to the next heading, since packs keep no
    pictures (audit F10: 17 books). Only when what follows is a short list of entries."""
    for heading in root.find_all(["h1", "h2", "h3", "h4", "h5", "h6"]):
        words = "".join(t for t in heading.find_all(string=True)
                        if not any(PAGE_MARKS & set(p.get("class") or []) for p in t.parents))
        if heading.parent is None or not ILLUSTRATIONS.match(" ".join(words.split())):
            continue
        level = int(heading.name[1])

        def ends(node) -> bool:  # a heading at the list's level or above; its own "In Colour" sub-heads go too
            heads = [node] if HEADINGS.match(node.name) else node.find_all(HEADINGS)
            return any(int(h.name[1]) <= level for h in heads) or (bool(heads) and not HEADINGS.match(node.name))

        run, node = [], heading.next_sibling
        while node is not None and not (isinstance(node, Tag) and ends(node)):
            run.append(node)
            node = node.next_sibling
        text = "".join(n.get_text() if isinstance(n, Tag) else str(n) for n in run)
        entries = [n for n in run if isinstance(n, Tag) and n.get_text().strip() and not HEADINGS.match(n.name)]
        if len(text) > ILLUSTRATION_LIST_CHARS or not entries or any(
                len(" ".join(e.get_text().split())) > 300 and e.name not in ("table", "ul", "ol", "dl", "div")
                for e in entries):
            continue
        for n in run:
            n.extract()
        heading.decompose()


def _drop_faded_page_notices(root: Tag) -> None:
    """Removes Faded Page's notices near the start: each phrase's widest enclosing block of at most 600 characters."""
    seen, found = 0, []
    for string in root.find_all(string=True):
        if seen > FRONT_MATTER_CHARS:
            break
        seen += len(string.strip())
        if FADED_PAGE.search(" ".join(string.split())):
            found.append(string)
    for string in found:
        if string.parent is None:
            continue
        block = None
        for parent in string.parents:
            if parent is root or len(parent.get_text().strip()) > FADED_PAGE_BLOCK_CHARS:
                break
            block = parent
        if block is not None and not block.decomposed and FADED_PAGE.search(" ".join(block.get_text(" ").split())):
            block.decompose()


def _drop_cover_notes(root: Tag) -> None:
    """Removes a transcriber's note box whose every sentence is about the cover art; a note on the text stays."""
    for heading in root.find_all(string=lambda s: isinstance(s, NavigableString) and NOTE_HEADING.match(s)):
        box = next((p for p in heading.parents if " ".join(p.get_text().split()) != " ".join(heading.split())),
                   None)
        if box is None or box is root or box.decomposed:
            continue
        text = " ".join(box.get_text(" ").split())
        rest = text.replace(" ".join(heading.split()), "", 1)
        sentences = [s for s in re.split(r"(?<=[.!?])\s+", rest) if s.strip(" .:")]
        if len(text) <= NOTE_CHARS and sentences and all(COVER_ART.search(s) for s in sentences):
            box.decompose()


def later_edition_notices(body: Tag, after: int = 1930) -> list[str]:
    """Sentences near the start that date this edition after the public-domain line, such as a modern introduction.

    The manifest's year can't catch these: the text is old, but the edition Gutenberg copied may add newer material.
    """
    text = " ".join(body.get_text(" ").split())[:FRONT_MATTER_CHARS]
    sentences = re.split(r"(?<!\b[A-Z]\.)(?<=[.!?])\s+", text)  # not after initials like "E. P."
    return [s for s in sentences if NOTICE.search(s) and any(int(y) > after for y in re.findall(r"\b(1[89]\d\d|20\d\d)\b", s))]
