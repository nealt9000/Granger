"""Turns an English Wikisource work, a page and the subpages it links to, into one cleaned body.

A page that links to subpages of its own (`Title/Book 1`, in the order its text links them) is a contents page:
its own text is dropped and each subpage is followed in turn, under a heading made from the link's words
(`h2` for the work's subpages, `h3` for theirs, and so on). A page without subpages is the text itself; its own
headings move below the heading it was given. A subpage named in the manifest's `wsSkip` (its last part, such as
"Index") isn't followed. A contents page that links a subpage Wikisource doesn't have yet (a red link) fails the
build: the work isn't all there.

What goes: Wikisource's header and navigation, page-number markers, edit links, licence boxes, everything marked
`ws-noexport` or `noprint`, advertisement boxes, images, spacers, the brackets around note numbers, and its
editors' "External links" and "See also" sections. Notes stay,
as numbered links to the notes list at the end of their page. A title set as centred lines at the start of a page
("CHAPTER I", then the chapter's name) is dropped when the heading already says it, and its label ("Chapter I")
leads the heading. Every id is prefixed with its page's number, since each page numbers its notes from 1, and a
link to another page of the work points at that page's anchor; any other link becomes plain text.

Rights: the work's own page must carry a licence template that makes it public domain in the US (`PD-US`,
`PD-old`, whose text says "published before January 1, 1931", or a US government one, `PD-USGov`,
`PD-EdictGov`), and the licence box must say so; subpages share the work's licence.
"""
import re
import urllib.parse
from dataclasses import dataclass, field

from bs4 import BeautifulSoup, Comment, NavigableString, Tag

from chapters import shouting, title_case
from gutenberg import Cleaned, CleaningError, unwrap_link

LICENCES = re.compile(r"^Template:(PD-US(?![a-z])\S*|PD/US\S*|PD-old\S*|PD-USGov\S*|PD-EdictGov\S*|PD-1923|PD-19[23]\d)$")
GOVERNMENT = re.compile(r"^Template:(PD-USGov|PD-EdictGov)")
US_PUBLIC_DOMAIN = re.compile(r"public domain in the United States|published before January 1, 19[3]1")
NOISE = (".ws-noexport, .noprint, .mw-editsection, #ws-data, .wst-header, .ws-header, .licenseContainer, "
         ".mw-cite-backlink, .cite-bracket, span.pagenum, .ws-pagenum, .wst-pagebreak, .similar, .sisitem, "
         "#toc, .toc, .mw-references-columns > .mw-references-caption, [style*='display:none'], "
         "[style*='display: none'], img, figure, picture, audio, video, script, style, link, meta, noscript")
LABEL = re.compile(r"^(chapter|book|part|section|article|canto|letter|essay|act|scene|lecture)\s+"
                   r"([ivxlcdm]+|\d+|[a-z]+)\.?$", re.I)
CSS = "p.ws-notes { font-weight: bold; margin-top: 1.5em; }"
EDITORS_SECTION = re.compile(r"^(external links|see also)$", re.I)
NOTES_HEADING = re.compile(r"^(foot ?notes|notes|references|select critical notes)$", re.I)
TITLE_CHARS = 300
MAX_DEPTH = 4
_ID = re.compile(r"[^A-Za-z0-9_.-]")


class NotPublicDomain(CleaningError):
    """The work's page carries no US public-domain licence template, so it can't ship."""


class Incomplete(CleaningError):
    """A contents page links a subpage nobody has transcribed yet (a red link), so the work isn't all there."""


@dataclass
class Licence:
    templates: list[str]
    government: bool
    text: str


@dataclass
class WsBook:
    title: str
    licence: Licence
    pages: list[str] = field(default_factory=list)
    cleaned: Cleaned | None = None


def licence(page: dict) -> Licence | None:
    """The page's US public-domain licence, or None when it has none (or its box doesn't say US)."""
    templates = [t for t in page["templates"] if LICENCES.match(t)]
    soup = BeautifulSoup(page["text"], "lxml")
    text = " ".join(" ".join(box.get_text(" ").split()) for box in soup.select(".licenseContainer"))
    if not templates or not US_PUBLIC_DOMAIN.search(text):
        return None
    return Licence(templates=[t.removeprefix("Template:") for t in templates],
                   government=any(GOVERNMENT.match(t) for t in templates), text=text)


def read(source, title: str, skip: list[str] | tuple = ()) -> WsBook:
    """The work at `title` (a `sources.WikisourceSource`), followed through its subpages."""
    root = source.parse(title)
    found = licence(root)
    if found is None:
        raise NotPublicDomain(f"{root['title']!r} has no US public-domain licence template")
    book = WsBook(title=root["title"], licence=found)
    holder = BeautifulSoup("", "lxml")
    body = holder.new_tag("div")
    styles: dict[str, None] = {}
    numbers: dict[str, int] = {}
    pages: list[tuple[int, BeautifulSoup]] = []

    def visit(page: dict, depth: int, heading: str) -> None:
        number = numbers.setdefault(_key(page["title"]), len(numbers) + 1)
        book.pages.append(page["title"])
        soup = BeautifulSoup(page["text"], "lxml")
        for style in soup.find_all("style"):
            styles.setdefault(style.get_text().strip(), None)
        _strip(soup)
        children = _children(soup, page["title"], skip)
        if children and depth < MAX_DEPTH:
            if depth:
                body.append(_heading(holder, depth, heading, number))
            for child_title, words in children:
                if _key(child_title) in numbers:
                    continue
                child = source.parse(child_title)
                visit(child, depth + 1, words or child_title.rsplit("/", 1)[-1])
            return
        content = soup.select_one(".mw-parser-output") or soup.body or soup
        heading = _leading_title(content, heading) if depth else heading
        if depth:
            body.append(_heading(holder, depth, heading, number))
        _shift_headings(content, depth)
        wrapper = holder.new_tag("div", attrs={"class": "ws-page"})
        for child in list(content.contents):
            wrapper.append(child.extract())
        pages.append((number, wrapper))
        body.append(wrapper)

    visit(root, 0, root["title"])
    for number, wrapper in pages:
        _prefix_ids(wrapper, number, numbers)
        _fix_nesting(wrapper)
    _drop_empty(body)
    if not body.get_text().strip():
        raise CleaningError(f"{root['title']!r}: no text left once Wikisource's own parts are gone")
    book.cleaned = Cleaned(body=body, styles=_css("\n".join(styles), body) + "\n" + CSS, branding_removed=0)
    return book


def _key(title: str) -> str:
    return " ".join(urllib.parse.unquote(title).replace("_", " ").split())


def _strip(soup: BeautifulSoup) -> None:
    for comment in soup.find_all(string=lambda s: isinstance(s, Comment)):
        comment.extract()
    for tag in soup.select(NOISE):
        if not tag.decomposed:
            tag.decompose()
    for box in soup.select(".mw-collapsible, .wst-collapsible-box"):
        title = box.select_one(".wst-collapsible-box-title, .mw-collapsible-toggle")
        if title is not None and re.search(r"advert", title.get_text(), re.I):
            box.decompose()
        elif title is not None:
            title.decompose()
    for wrapper in soup.select(".mw-heading"):
        wrapper.unwrap()
    for heading in soup.find_all(re.compile(r"^h[1-6]$")):  # Wikisource's own "External links" go, to the next heading
        if heading.parent is not None and EDITORS_SECTION.match(heading.get_text(" ").strip()):
            level = int(heading.name[1])
            for sibling in list(heading.find_next_siblings()):
                if re.match(r"^h[1-6]$", sibling.name or "") and int(sibling.name[1]) <= level:
                    break
                sibling.decompose()
            heading.decompose()
    for heading in soup.find_all(re.compile(r"^h[1-6]$")):  # a notes list's heading isn't a section to split at
        if NOTES_HEADING.match(heading.get_text(" ").strip()):
            heading.name, heading.attrs = "p", {"class": "ws-notes"}
    for sup in soup.select("sup.reference"):
        sup.attrs = {"id": sup["id"]} if sup.get("id") else {}
    for span in soup.find_all("span"):
        if not span.attrs and not span.contents:
            span.decompose()
    for tag in soup.find_all(True):
        for attr in [a for a in tag.attrs if a.startswith("data-") or a in ("title", "typeof", "about", "rel",
                                                                          "property", "resource", "decoding")]:
            del tag.attrs[attr]


def _children(soup: BeautifulSoup, title: str, skip) -> list[tuple[str, str]]:
    """The page's own subpages (`<title>/<name>`, one level down), in the order its text links them."""
    prefix = _key(title) + "/"
    found, seen = [], set()
    for link in soup.find_all("a", href=True):
        href = link["href"]
        missing = re.match(r"^/w/index\.php\?title=([^&]+)&action=edit&redlink=1", href)
        if missing and _key(missing.group(1)).startswith(prefix):
            raise Incomplete(f"{title!r} links {_key(missing.group(1))!r}, which Wikisource doesn't have yet")
        if not href.startswith("/wiki/"):
            continue
        target = _key(href[len("/wiki/"):].split("#", 1)[0])
        if not target.startswith(prefix) or "/" in target[len(prefix):] or target in seen:
            continue
        name = target[len(prefix):]
        if name in skip:
            continue
        seen.add(target)
        found.append((target, " ".join(link.get_text(" ").split()).rstrip(".:")))
    return found


def _heading(soup: BeautifulSoup, depth: int, text: str, number: int) -> Tag:
    tag = soup.new_tag(f"h{min(depth + 1, 6)}", attrs={"id": f"w{number}"})
    tag.string = _quiet(text)
    return tag


def _quiet(text: str) -> str:
    text = " ".join(text.split())
    return title_case(text)[0] if shouting(text) else text


def _leading_title(content: Tag, heading: str) -> str:
    """Drops the centred lines at the page's start that repeat its heading or are only a label ("CHAPTER I"); a
    label leads the heading ("Chapter I: Of Progress"). Other centred lines there (the work's title) stay."""
    label = None
    for block in _leading_blocks(content):
        text = " ".join(block.get_text(" ").split())
        if not _centred(block, content) or len(text) > TITLE_CHARS:
            break
        plain = _plain(text)
        if LABEL.match(text.rstrip(".")):
            label = label or _quiet(text.rstrip("."))
            block.decompose()
        elif plain and (plain in _plain(heading) or _plain(heading) in plain):
            block.decompose()
    if label and _plain(label) not in _plain(heading):
        return f"{label}: {_quiet(heading)}"
    return heading


def _leading_blocks(content: Tag):
    """The page's first blocks with words, innermost, in reading order, up to its first `TITLE_CHARS` characters."""
    seen = 0
    for block in content.find_all(["p", "div", "center"]):
        if block.find(["p", "div", "center", "table"]) is not None:
            continue
        text = " ".join(block.get_text(" ").split())
        if not text:
            continue
        yield block
        seen += len(text)
        if seen > TITLE_CHARS:
            return


def _centred(block: Tag, content: Tag) -> bool:
    node = block
    while node is not None and node is not content:
        if node.name == "center" or "center" in " ".join(node.get("class") or []) or \
                re.search(r"text-align:\s*center", node.get("style") or ""):
            return True
        node = node.parent
    return False


def _plain(text: str) -> str:
    return " ".join(re.sub(r"[^\w]+", " ", text.lower()).split())


def _shift_headings(content: Tag, depth: int) -> None:
    if not depth:
        return
    headings = content.find_all(re.compile(r"^h[1-6]$"))
    if not headings:
        return
    top = min(int(h.name[1]) for h in headings)
    for h in headings:
        h.name = f"h{min(int(h.name[1]) - top + depth + 2, 6)}"


def _prefix_ids(wrapper: Tag, number: int, numbers: dict[str, int]) -> None:
    for tag in wrapper.find_all(id=True):
        tag["id"] = f"w{number}-{_ID.sub('_', tag['id'])}"
    for link in wrapper.find_all("a", href=True):
        href = link["href"].strip()
        if href.startswith("#"):
            link["href"] = f"#w{number}-{_ID.sub('_', urllib.parse.unquote(href[1:]))}"
        elif href.startswith("/wiki/") and _key(href[6:].split("#", 1)[0]) in numbers:
            target = numbers[_key(href[6:].split("#", 1)[0])]
            fragment = href.split("#", 1)[1] if "#" in href else ""
            link["href"] = f"#w{target}-{_ID.sub('_', urllib.parse.unquote(fragment))}" if fragment else f"#w{target}"
        else:
            unwrap_link(link)
        link.attrs.pop("class", None)


_BLOCK_TAGS = ["p", "div", "ul", "ol", "dl", "table", "blockquote", "center", "h1", "h2", "h3", "h4", "h5", "h6",
               "hr", "pre", "section"]
_PHRASING = {"span", "i", "b", "em", "strong", "small", "big", "sup", "sub", "font", "u", "s", "cite", "abbr", "p"}


def _fix_nesting(wrapper: Tag) -> None:
    """Blocks inside phrasing (a note's `span` holding a quoted poem, a `p` around a `div`), which the browser
    tolerates and XHTML doesn't: the holder becomes a `div`, innermost first, until none is left."""
    changed = True
    while changed:
        changed = False
        for tag in reversed(wrapper.find_all(_PHRASING)):
            if tag.find(_BLOCK_TAGS, recursive=False) is not None or (
                    tag.name != "p" and tag.find(_BLOCK_TAGS) is not None):
                tag.name = "div"
                changed = True
        for link in wrapper.find_all("a"):
            if link.find(_BLOCK_TAGS) is not None:
                link.unwrap()
                changed = True


def _drop_empty(body: Tag) -> None:
    """Spacers (`<p><br></p>`, an emptied centring div) go; ids they held stay as empty spans, so links still land."""
    maker = BeautifulSoup("", "lxml")
    for block in reversed(body.find_all(["p", "div"])):
        if block.decomposed or block.parent is None or block.get_text().strip(" ​⁠ \n\t") \
                or block.find(["hr", "table", "ul", "ol", "dl"]) or block.name == "div" and block.get("class") == ["ws-page"]:
            continue
        for tag in [block, *block.find_all(id=True)]:
            if tag.get("id"):
                block.insert_before(maker.new_tag("span", attrs={"id": tag["id"]}))
        block.decompose()


def _css(styles: str, body: Tag) -> str:
    """Wikisource's template styles for the classes the text uses (centring, small capitals, indents)."""
    used = {c for tag in body.find_all(class_=True) for c in tag["class"]}
    css = re.sub(r"/\*.*?\*/", "", styles, flags=re.S)
    css = re.sub(r"@media[^{]*\{((?:[^{}]*\{[^{}]*\})*)\s*\}", "", css)
    rules = []
    for selector, declarations in re.findall(r"([^{}]+)\{([^{}]*)\}", css):
        selector = selector.replace(".mw-parser-output ", "").replace(".mw-parser-output", "").strip()
        if not selector or selector.startswith("@"):
            continue
        kept = [s.strip() for s in selector.split(",")
                if re.findall(r"\.([\w-]+)", s) and set(re.findall(r"\.([\w-]+)", s)) <= used]
        if kept:
            rules.append(f"{', '.join(kept)} {{ {declarations.strip()} }}")
    return "\n".join(dict.fromkeys(rules))
