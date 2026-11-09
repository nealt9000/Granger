"""Turns a Standard Ebooks "compatible" EPUB into one cleaned body, like `gutenberg.clean` does for a Gutenberg book.

Standard Ebooks (SE) marks up its books with semantics (`epub:type`). Those are read here and written as the plain
markup the rest of the builder knows, then dropped, so the book carries no vocabulary epubcheck would need declared:

- Drama, which SE sets as a table of rows (`td` `z3998:persona`, then the speech), becomes speeches:
  `p.gd-speech.gd-prose.gd-run` with a run-in `span.gd-who` for prose, `div.gd-speech.gd-above` when it holds blocks
  (verse, several paragraphs); a row with no speaker becomes `div.gd-sd`, a stage direction. A direction inside a
  speech (`z3998:stage-direction`) becomes `span.gd-sdi`, in the parentheses SE's own stylesheet shows.
- Verse (`z3998:poem`, `z3998:verse`, `z3998:song`, `z3998:hymn`, `z3998:lyrics`), SE's `p` of `span` lines,
  becomes `p.gv` of `span.gv-l` lines, as the verse pass writes them; SE's `i1`, `i2`... indents become margins.
- An `hgroup` becomes its heading, with the title after the label ("Chapter I: The Beginning").

SE's own pages go: the title page, half-title, imprint, colophon and uncopyright (`copyright-page`), and the list
of illustrations. So do images and figures (packs are text-only). Whatever SE dedicated to the public domain is
recorded in our colophon instead (`SeBook.rights`, the EPUB's `dc:rights`). Links between the book's files become
in-book anchors; the chapter split points them at the right page.

The EPUB is untrusted: only the files its own package names are read, straight from the zip into memory.
"""
import io
import posixpath
import re
import zipfile
from dataclasses import dataclass, field

from bs4 import BeautifulSoup, NavigableString, Tag

from gutenberg import Cleaned, CleaningError, unwrap_link

OPF_NS = {"opf": "http://www.idpf.org/2007/opf", "dc": "http://purl.org/dc/elements/1.1/"}
SKIPPED = {"titlepage", "halftitlepage", "imprint", "colophon", "copyright-page", "loi", "toc", "seriespage"}
VERSE = {"z3998:poem", "z3998:verse", "z3998:song", "z3998:hymn", "z3998:lyrics"}
PERSONA = "z3998:persona"
DIRECTION = "z3998:stage-direction"
MAX_FILE_BYTES = 20_000_000
_INDENT = re.compile(r"^i(\d)$")
_SCHEME = re.compile(r"^[a-z][a-z0-9+.-]*:", re.I)
_BLOCKS = {"p", "div", "blockquote", "table", "ul", "ol", "dl", "section", "header", "footer", "hr"}


class NotAStandardEbook(CleaningError):
    """The EPUB isn't laid out the way Standard Ebooks builds them, so there's no safe way to read it."""


@dataclass
class SeBook:
    title: str
    authors: list[str]
    translators: list[str]
    rights: str
    identifier: str
    cleaned: Cleaned
    skipped: list[str] = field(default_factory=list)
    years: list[int] = field(default_factory=list)  # the years SE's colophon gives for the text and translation


def is_placeholder(page: bytes) -> bool:
    """An SE page for a book they haven't produced yet: it has no EPUB to download."""
    html = page.decode("utf-8", "replace")
    return not re.search(r'href="[^"]*/downloads/[^"]*\.epub', html) or 'class="placeholder' in html


def read(epub: bytes) -> SeBook:
    with zipfile.ZipFile(io.BytesIO(epub)) as z:
        names = {info.filename: info for info in z.infolist() if not info.is_dir()}

        def member(name: str) -> bytes:
            info = names.get(name)
            if info is None:
                raise NotAStandardEbook(f"the EPUB has no {name}")
            if info.file_size > MAX_FILE_BYTES:
                raise NotAStandardEbook(f"{name} is {info.file_size} bytes")
            return z.read(info)

        container = BeautifulSoup(member("META-INF/container.xml"), "lxml-xml")
        rootfile = container.find("rootfile")
        if rootfile is None or not rootfile.get("full-path"):
            raise NotAStandardEbook("container.xml names no package")
        opf_path = rootfile["full-path"]
        opf = BeautifulSoup(member(opf_path), "lxml-xml")
        base = posixpath.dirname(opf_path)
        items = {i["id"]: i for i in opf.find_all("item") if i.get("id") and i.get("href")}

        def path_of(href: str) -> str:
            path = posixpath.normpath(posixpath.join(base, href))
            if path.startswith("../") or path.startswith("/"):
                raise NotAStandardEbook(f"package path {href!r} leaves the EPUB")
            return path

        meta = _metadata(opf)
        spine = [items[ref["idref"]] for ref in opf.find_all("itemref") if ref.get("idref") in items]
        if not spine:
            raise NotAStandardEbook("the package has no spine")
        css = "\n".join(_local_css(member(path_of(i["href"])).decode("utf-8", "replace"))
                        for i in items.values() if i["href"].endswith("local.css"))
        holder = BeautifulSoup("", "lxml")
        body = holder.new_tag("div")
        skipped, years = [], []
        for item in spine:
            name = posixpath.basename(item["href"])
            page = BeautifulSoup(member(path_of(item["href"])), "lxml-xml")
            if page.body is None:
                continue
            kinds = _tokens(page.body) | {t for s in page.body.find_all(["section", "article", "nav"], recursive=False)
                                          for t in _tokens(s)}
            if kinds & SKIPPED:
                skipped.append(name)
                if "colophon" in kinds:
                    years = colophon_years(page.body.get_text(" "))
                continue
            part = holder.new_tag("div", attrs={"id": file_anchor(name)})
            for child in list(page.body.contents):
                part.append(child.extract())
            _convert(holder, part, drama="z3998:drama" in kinds)
            body.append(part)
    if not body.get_text().strip():
        raise NotAStandardEbook("nothing left once SE's own pages are gone")
    for string in body.find_all(string=True):
        if "﻿" in string:  # SE's compatible build joins dashes with U+FEFF; U+2060 is the word joiner
            string.replace_with(NavigableString(str(string).replace("﻿", "⁠")))
    return SeBook(title=meta["title"], authors=meta["authors"], translators=meta["translators"],
                  rights=meta["rights"], identifier=meta["identifier"], skipped=skipped, years=years,
                  cleaned=Cleaned(body=body, styles=css,  # the gd-* look is drama.CSS, which epubpack adds
                                  branding_removed=len(skipped)))


def colophon_years(text: str) -> list[int]:
    """The years SE's colophon gives for the work: "was published in 1926", "translated from German in 1930",
    "published between 1852 and 1911", "translated ... between 1887 and 1920" (not when SE released the ebook)."""
    text = " ".join(text.split())
    text = text.split("This ebook was produced", 1)[0]
    return sorted({int(y) for y in re.findall(r"\b(?:in|between|and|circa)\s+(\d{4})\b", text)})


def file_anchor(name: str) -> str:
    return "se-" + re.sub(r"[^A-Za-z0-9_-]", "-", posixpath.splitext(name)[0])


def _metadata(opf: BeautifulSoup) -> dict:
    roles: dict[str, set[str]] = {}
    for meta in opf.find_all("meta", attrs={"property": "role"}):
        roles.setdefault(meta.get("refines", "").lstrip("#"), set()).add(meta.get_text(strip=True))
    people = opf.find_all(["dc:creator", "creator", "dc:contributor", "contributor"])

    def named(role: str) -> list[str]:
        return [p.get_text(strip=True) for p in people if role in roles.get(p.get("id", ""), set())]

    def text(tag: str) -> str:
        found = opf.find(["dc:" + tag, tag])
        return " ".join(found.get_text().split()) if found else ""

    return {"title": text("title"), "rights": text("rights"), "identifier": text("identifier"),
            "authors": named("aut"), "translators": named("trl")}


def _local_css(css: str) -> str:
    """SE's per-book rules, less the at-rules the builder's CSS scrubber can't read and any rule still written
    against `epub|type` (the compatible build already turned those into classes)."""
    css = re.sub(r"/\*.*?\*/", "", css, flags=re.S)
    css = re.sub(r"@(charset|namespace|import)[^;]*;", "", css)
    rules = re.findall(r"([^{}]+)\{([^{}]*)\}", css)
    return "\n".join(f"{s.strip()} {{{b}}}" for s, b in rules if "|" not in s and not s.strip().startswith("@"))


def _classes(tag: Tag) -> list[str]:
    """A tag's classes as a list (the XML parser leaves `class` one string)."""
    value = tag.get("class") or []
    return value.split() if isinstance(value, str) else list(value)


def _tokens(tag: Tag) -> set[str]:
    return set((tag.get("epub:type") or "").split())


def _convert(soup: BeautifulSoup, part: Tag, drama: bool) -> None:
    for tag in part.find_all(["img", "svg", "picture", "audio", "video", "script", "style", "link"]):
        tag.decompose()
    for figure in part.find_all("figure"):
        if not figure.decomposed:
            figure.decompose()
    for tag in part.find_all(lambda t: "z3998:illustration" in _tokens(t) and not t.get_text(strip=True)):
        tag.decompose()
    for group in part.find_all("hgroup"):
        _hgroup(soup, group)
    for container in part.find_all(lambda t: bool(_tokens(t) & VERSE)):
        _verse(soup, container)
    for table in part.find_all("table"):
        if not table.decomposed and (drama or table.find(lambda t: PERSONA in _tokens(t))):
            _drama_table(soup, table)
    for direction in part.find_all(lambda t: DIRECTION in _tokens(t)):
        _direction(soup, direction)
    for link in part.find_all("a", href=True):
        href = link["href"].strip()
        if _SCHEME.match(href):
            unwrap_link(link)
        elif not href.startswith("#"):
            file, _, fragment = href.partition("#")
            link["href"] = "#" + (fragment or file_anchor(posixpath.basename(file)))
    for tag in part.find_all(True):
        for attr in ("epub:type", "role", "xml:lang", "epub:prefix", "aria-label"):
            tag.attrs.pop(attr, None)
        if tag.get("class"):
            classes = [c for c in _classes(tag) if c != "epub-type"]
            if classes:
                tag["class"] = classes
            else:
                del tag["class"]


def _hgroup(soup: BeautifulSoup, group: Tag) -> None:
    heading = group.find(re.compile(r"^h[1-6]$"))
    if heading is None:
        group.name = "div"
        return
    rest = [c for c in group.find_all(["p", "h1", "h2", "h3", "h4", "h5", "h6"], recursive=False)
            if c is not heading and c.get_text(strip=True)]
    for extra in rest:
        last = heading.find_all(string=True)[-1] if heading.find_all(string=True) else None
        if last is not None:
            last.replace_with(NavigableString(str(last).rstrip()))
        heading.append(NavigableString(": "))
        for child in list(extra.contents):
            heading.append(child.extract())
    heading.attrs.pop("epub:type", None)
    group.replace_with(heading.extract())


def _verse(soup: BeautifulSoup, container: Tag) -> None:
    """SE's `p` of `span` lines (or a `td` of them, in a verse play) as `.gv` blocks of `span.gv-l` lines."""
    blocks = container.find_all("p")
    if not blocks and container.find("span", recursive=False) is not None:
        blocks = [container]
    for p in blocks:
        lines = [c for c in p.children if isinstance(c, Tag) and c.name == "span"]
        if not lines or any(isinstance(c, Tag) and c.name not in ("span", "br") for c in p.children):
            continue
        for child in list(p.children):
            if isinstance(child, Tag) and child.name == "br" or isinstance(child, NavigableString) and not child.strip():
                child.extract()
        for line in lines:
            indent = next((int(m.group(1)) for c in _classes(line) if (m := _INDENT.match(c))), 0)
            line["class"] = ["gv-l"]
            if indent:
                line["style"] = f"margin-left: {indent}em"
        p["class"] = [c for c in _classes(p) if c not in ("first-child", "last-child")] + ["gv"]


def _drama_table(soup: BeautifulSoup, table: Tag) -> None:
    out = []
    for row in table.find_all("tr"):
        if row.find_parent("table") is not table:
            continue
        cells = row.find_all(["td", "th"], recursive=False)
        who = next((c for c in cells if PERSONA in _tokens(c)), None)
        contents = [child.extract() for c in cells if c is not who for child in list(c.contents)]
        while contents and isinstance(contents[0], NavigableString) and not contents[0].strip():
            contents.pop(0)
        if who is None:
            direction = _all_direction(contents)
            block = soup.new_tag("div", attrs={"class": "gd-sd"} if direction else {})
            for child in contents:
                block.append(child)
            if direction:
                for child in block.find_all(lambda t: DIRECTION in _tokens(t)):
                    child.unwrap()
            if block.get_text(strip=True):
                out.append(block)
            continue
        blocky = any(isinstance(c, Tag) and c.name in _BLOCKS for c in contents)
        name = " ".join(who.get_text(" ").split())
        if blocky:
            speech = soup.new_tag("div", attrs={"class": "gd-speech gd-above"})
        else:
            speech = soup.new_tag("p", attrs={"class": "gd-speech gd-prose gd-run"})
        label = soup.new_tag("span", attrs={"class": "gd-who"})
        label.string = name
        speech.append(label)
        if not blocky:
            speech.append(NavigableString(" "))
        for child in contents:
            speech.append(child)
        out.append(speech)
    table.replace_with(*out) if out else table.decompose()


def _all_direction(contents: list) -> bool:
    words = [c for c in contents if not (isinstance(c, NavigableString) and not c.strip())]
    return bool(words) and all(isinstance(c, Tag) and DIRECTION in _tokens(c) for c in words)


def _direction(soup: BeautifulSoup, tag: Tag) -> None:
    """A stage direction outside a speaker's row: a paragraph of one is `div.gd-sd`, one inside text `span.gd-sdi`."""
    if tag.decomposed or tag.parent is None:
        return
    parent = tag.parent
    if tag.name in ("p", "div") or (parent.name == "p" and _all_direction(list(parent.contents))):
        block = parent if tag.name not in ("p", "div") else tag
        block.name = "div"
        block["class"] = ["gd-sd"]
        for inner in block.find_all(lambda t: DIRECTION in _tokens(t)):
            inner.unwrap()
        block.attrs.pop("epub:type", None)
        return
    tag.name = "span"
    tag["class"] = ["gd-sdi"]
    tag.attrs.pop("epub:type", None)
    tag.insert(0, NavigableString("("))
    tag.append(NavigableString(")"))
