"""Writes one Project Gutenberg work as an EPUB 3 file.

The caller splits the work with `chapters.split(..., page_href=chapter_href)`, so cross-chapter links already
name the `cNNN.xhtml` files written here. Output is deterministic: fixed zip timestamps and a modified date
taken from the build date.
"""
import re
import sys
import zipfile
from dataclasses import dataclass
from html import escape
from pathlib import Path

import lxml.html
from bs4 import BeautifulSoup
from lxml import etree

import covers
import verse
from chapters import PAGE_NUMBER, clean_title
from zimpack import Work

COLLECTION = "Granger Classics"
RIGHTS = "Public domain in the USA."
TAGLINE = "The world’s knowledge. Yours to keep."
ZIP_TIME = (1980, 1, 1, 0, 0, 0)
NS = 'xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"'

# No colours or font families: the reader's own theme and typeface apply.
BOOK_CSS = """
body { line-height: 1.5; margin: 0; padding: 0 .5em; }
h1, h2, h3, h4, h5, h6 { line-height: 1.25; margin: 1.5em 0 .6em; page-break-after: avoid; }
p { margin: 0 0 .8em; }
blockquote { margin: 1em 1.5em; }
pre { white-space: pre-wrap; }
table { border-collapse: collapse; }
td, th { padding: .2em .5em; vertical-align: top; }
img { max-width: 100%; }
.cover { text-align: center; margin: 0; padding: 0; }
.cover img { max-height: 100vh; }
.colophon { text-align: center; margin: 3em 0 0; }
.colophon svg { width: 5em; height: 5em; }
.colophon .collection { font-variant: small-caps; letter-spacing: .14em; margin: .4em 0 2.5em; }
.colophon h1 { margin: 0 0 .4em; }
.colophon .author { font-style: italic; }
.colophon .facts { margin-top: 2.5em; font-size: .9em; }
.colophon .facts p { margin: 0 0 .3em; }
.colophon .tagline { margin-top: 2.5em; font-style: italic; }
""".strip()

LINE_NUMBER = {"linenum", "linenumber", "lineno", "line-number", "linenr"}

_RULE = re.compile(r"([^{}]+)\{([^{}]*)\}")
_DROPPED_PROPERTY = re.compile(r"^(color|background(-.*)?|font-family)$", re.I)


def _class_selector(classes: set[str]) -> re.Pattern:
    return re.compile(r"\.(" + "|".join(map(re.escape, sorted(classes))) + r")(?![\w-])", re.I)


_PAGE_NUMBER_SELECTOR = _class_selector(PAGE_NUMBER)
_LINE_NUMBER_SELECTOR = _class_selector(LINE_NUMBER)
_SIDES = {"margin-left", "margin-right", "padding-left", "padding-right"}
_PERCENT = re.compile(r"(\d*\.?\d+)%")
_EM = re.compile(r"(\d*\.?\d+)em")
_IMPORTANT = re.compile(r"\s*!\s*important\s*$", re.I)
# Where an absolutely placed mark (a line number at `left: 60%`) goes instead of on top of the words.
_MARGIN_NOTE = "float: right; font-size: .75em; opacity: .65; margin-left: .75em"
_PLACEMENT = {"position", "left", "right", "top", "bottom"} | {d.split(":")[0].strip() for d in _MARGIN_NOTE.split(";")}
# Readium sets every <p> to the reader's alignment with `text-align: inherit !important` at specificity
# (0,3,3); four :root pseudo-classes outrank it, so a work's centred or right-aligned paragraphs stay so.
_BOOST = ":root:root:root:root"
_PARAGRAPH = re.compile(r"(^|[\s>+~])p([.#:\[]|$)")
_BARE_CLASS = re.compile(r"\.[\w-]+")


@dataclass
class BookMeta:
    id: str
    title: str
    author: str
    translator: str | None
    published: int
    section_title: str
    language: str
    source_url: str
    date: str
    source_line: str = ""  # the colophon's credit, e.g. "From Project Gutenberg ebook #1342"


def book_id(slug: str) -> str:
    return f"gc-{slug}"


def chapter_href(index: int) -> str:
    return f"c{index + 1:03d}.xhtml"


def display_title(work: Work) -> str:
    if not work.author and work.translator:
        return f"{work.title} (tr. {work.translator})"
    return work.title


def scrub_css(css: str, line_numbers: str = "margin") -> str:
    """The work's own rules without colours, backgrounds, font families or page-number styling, fitted to a phone.

    Wide side margins are clamped, absolutely placed marks move to the right margin (or, with line numbers
    dropped, their rules go), and centred or right-aligned paragraphs are restated so the reader keeps them.
    """
    css = re.sub(r"/\*.*?\*/", "", css, flags=re.S)
    rules, boosts = [], []
    for selector, body in _RULE.findall(css):
        selector = selector.strip()
        if (selector.startswith("@") or _PAGE_NUMBER_SELECTOR.search(selector)
                or (line_numbers == "drop" and _LINE_NUMBER_SELECTOR.search(selector))):
            continue
        declarations = [d.strip().partition(":") for d in body.split(";") if d.strip()]
        declarations = [(k.strip().lower(), v.strip()) for k, colon, v in declarations
                        if colon and not _DROPPED_PROPERTY.match(k.strip())]
        placed = any(k == "position" and re.match(r"(absolute|fixed)\b", v, re.I) for k, v in declarations)
        kept = [f"{k}: {_fit_sides(k, v)}" for k, v in declarations if not (placed and k in _PLACEMENT)]
        if placed:
            kept.append(_MARGIN_NOTE)
        for k, v in declarations:
            align = _IMPORTANT.sub("", v).lower()
            if k == "text-align" and align in ("center", "right", "end"):
                paragraphs = [s for s in map(str.strip, selector.split(","))
                              if _PARAGRAPH.search(s) or _BARE_CLASS.fullmatch(s)]
                boosts += [f"{_BOOST} {s} {{ text-align: {align} !important }}" for s in paragraphs]
        if kept:
            rules.append(f"{selector} {{ {'; '.join(kept)} }}")
    return "\n".join(rules + boosts)


def _book_css(work: Work) -> str:
    """Granger's rules, then the work's, then the verse rules, which must outrank the work's at equal weight
    (`p.poem { text-indent: 2em }` against `p.gv`)."""
    parts = [BOOK_CSS, scrub_css(work.styles, work.line_numbers), verse.CSS]
    if work.verse:
        parts.append(verse.WRAPPER_CSS)
    return "\n".join(p for p in parts if p) + "\n"


def _fit_sides(prop: str, value: str) -> str:
    """Side margins and paddings that won't squeeze a phone's column: over 5% to at most 2em, over 6em to 30%."""
    important = _IMPORTANT.search(value)
    plain = value[:important.start()] if important else value
    if prop in _SIDES:
        plain = _clamp(plain)
    elif prop in ("margin", "padding"):
        parts = plain.split()
        plain = " ".join(_clamp(p) if len(parts) == 1 or i % 2 == 1 else p for i, p in enumerate(parts))
    return plain + (" !important" if important else "")


def _clamp(length: str) -> str:
    if (m := _PERCENT.fullmatch(length)) and float(m.group(1)) > 5:
        return f"min({length}, 2em)"
    if (m := _EM.fullmatch(length)) and float(m.group(1)) > 6:
        return f"min({length}, 30%)"
    return length


def to_xhtml(html: str) -> str:
    """HTML fragment as well-formed XML, for placing inside an XHTML body."""
    if not html.strip():
        return ""
    root = lxml.html.fragment_fromstring(html, create_parent="div")
    parts = [escape(root.text, quote=False)] if root.text else []
    parts += [etree.tostring(child, method="xml", encoding="unicode") for child in root]
    return "".join(parts)


# Presentational attributes EPUB 3 XHTML no longer allows; the reader's own styling replaces them.
_OBSOLETE_ATTRIBUTES = ("align", "valign", "bgcolor", "border", "cellpadding", "cellspacing", "clear", "compact",
                        "frame", "hspace", "vspace", "noshade", "nowrap", "rules", "summary", "char", "charoff",
                        "text", "link", "vlink", "alink", "background")
_NO_PHRASING_CHILDREN = {"ul", "ol", "dl", "table", "thead", "tbody", "tfoot", "tr", "colgroup", "select"}
_MEDIA = {"img", "svg", "canvas", "video", "iframe", "object", "embed", "input", "source"}
_SCHEME = re.compile(r"^[a-z][a-z0-9+.-]*:", re.I)


def _drop_page_numbers(soup: BeautifulSoup, classes: set[str] = PAGE_NUMBER) -> None:
    """Removes page-number (or line-number) marks but keeps the ids on them, so links to a page still land."""
    for tag in soup.find_all(lambda t: bool(classes.intersection(t.get("class") or ()))):
        if tag.decomposed:
            continue
        ids = [t["id"] for t in [tag, *tag.find_all(id=True)] if t.get("id")]
        ids += [t["name"] for t in [tag, *tag.find_all("a", attrs={"name": True})] if t.get("name")]
        parent = tag.parent
        for ident in dict.fromkeys(ids):
            if parent.name in _NO_PHRASING_CHILDREN:
                if parent.attrs.setdefault("id", ident) != ident:
                    print(f"note: page-number id {ident!r} dropped; <{parent.name}> already has an id",
                          file=sys.stderr)
            else:
                tag.insert_before(soup.new_tag("span", attrs={"id": ident}))
        tag.decompose()


def _fix_attributes(soup: BeautifulSoup) -> None:
    for tag in soup.find_all(True):
        for attr in _OBSOLETE_ATTRIBUTES:
            if tag.name != "meta":
                tag.attrs.pop(attr, None)
        media = tag.name in _MEDIA
        if not media:
            tag.attrs.pop("width", None)
            tag.attrs.pop("height", None)
        if tag.name == "a" and "name" in tag.attrs:
            name = tag.attrs.pop("name")
            if not tag.get("id"):
                tag["id"] = name
            elif tag["id"] != name:
                tag.insert_before(soup.new_tag("span", attrs={"id": name}))
        if tag.get("style"):
            # A fixed width (an emptied figure's `width: 500px`) would only narrow the column on a phone.
            declarations = [d for d in tag["style"].split(";") if d.strip() and d.count("'") % 2 == 0
                            and d.count('"') % 2 == 0 and (media or d.split(":", 1)[0].strip().lower() != "width")]
            if tag.name == "p":  # like a centred class, inline centring must outrank the reader's alignment
                declarations = [d + " !important" if re.match(r"\s*text-align\s*:\s*(center|right|end)\s*$", d, re.I)
                                else d for d in declarations]
            if declarations:
                tag["style"] = "; ".join(d.strip() for d in declarations)
            else:
                del tag["style"]


def _wrap_stray_definitions(soup: BeautifulSoup) -> None:
    """A definition list split across chapters leaves dt/dd runs without their dl."""
    wrapper, created = None, []
    for item in soup.find_all(["dt", "dd"]):
        if item.parent is None or item.parent.name == "dl":
            continue
        previous = item.previous_sibling
        while isinstance(previous, str) and not previous.strip():
            previous = previous.previous_sibling
        if wrapper is None or previous is not wrapper:
            wrapper = soup.new_tag("dl")
            item.insert_before(wrapper)
            created.append(wrapper)
        wrapper.append(item.extract())
    for wrapper in created:
        items = wrapper.find_all(["dt", "dd"], recursive=False)
        if items[0].name == "dd":  # its dt ended the previous chapter
            wrapper.insert(0, soup.new_tag("dt"))
        if items[-1].name == "dt":  # its dd starts the next one
            wrapper.append(soup.new_tag("dd"))


def _prepare(html: str, line_numbers: str = "margin", reads_as_verse: bool = False) -> BeautifulSoup:
    soup = BeautifulSoup(html, "lxml")
    _drop_page_numbers(soup)
    if line_numbers == "drop":
        _drop_page_numbers(soup, LINE_NUMBER)
    if reads_as_verse:
        verse.mark(soup)
    _fix_attributes(soup)
    _wrap_stray_definitions(soup)
    return soup


def _body(soup: BeautifulSoup) -> str:
    return soup.body.decode_contents() if soup.body else ""


def _unwrap_dangling_links(soups: list[BeautifulSoup]) -> None:
    """Links whose target isn't in the book (a lost anchor, a dropped page) become plain text."""
    ids = {chapter_href(i): {t["id"] for t in soup.find_all(id=True)} for i, soup in enumerate(soups)}
    for i, soup in enumerate(soups):
        for link in soup.find_all("a", href=True):
            href = link["href"].strip()
            if _SCHEME.match(href):
                continue
            file, _, fragment = href.partition("#")
            file = file or chapter_href(i)
            if file not in ids or (fragment and fragment not in ids[file]):
                link.unwrap()


def _page(title: str, language: str, body: str) -> str:
    return (f'<?xml version="1.0" encoding="utf-8"?>\n<!DOCTYPE html>\n'
            f'<html {NS} lang="{escape(language)}" xml:lang="{escape(language)}"><head><meta charset="utf-8"/>'
            f'<title>{escape(title, quote=False)}</title><link rel="stylesheet" type="text/css" href="book.css"/>'
            f"</head><body>{body}</body></html>")


def _titles(work: Work) -> list[str]:
    return [clean_title(c.title, work.title, work.author, i) for i, c in enumerate(work.chapters)]


def _nav(work: Work, meta: BookMeta, titles: list[str]) -> str:
    items = "".join(f'<li><a href="{chapter_href(i)}">{escape(t, quote=False)}</a></li>' for i, t in enumerate(titles))
    first = chapter_href(0) if titles else "cover.xhtml"
    body = (f'<nav epub:type="toc" id="toc"><h1>Contents</h1><ol>{items}</ol></nav>'
            '<nav epub:type="landmarks" hidden="hidden"><h1>Landmarks</h1><ol>'
            '<li><a epub:type="cover" href="cover.xhtml">Cover</a></li>'
            f'<li><a epub:type="bodymatter" href="{first}">Start of text</a></li>'
            '<li><a epub:type="backmatter" href="colophon.xhtml">Colophon</a></li></ol></nav>')
    return _page(display_title(work), meta.language, body)


def _ncx(work: Work, meta: BookMeta, titles: list[str]) -> str:
    points = "".join(
        f'<navPoint id="n{i + 1}" playOrder="{i + 1}"><navLabel><text>{escape(t, quote=False)}</text></navLabel>'
        f'<content src="{chapter_href(i)}"/></navPoint>' for i, t in enumerate(titles))
    return ('<?xml version="1.0" encoding="utf-8"?>\n<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">'
            f'<head><meta name="dtb:uid" content="urn:granger:{escape(meta.id)}"/></head>'
            f'<docTitle><text>{escape(display_title(work), quote=False)}</text></docTitle><navMap>{points}</navMap></ncx>')


def _opf(work: Work, meta: BookMeta, count: int) -> str:
    def contributor(tag: str, ident: str, name: str, role: str) -> str:
        return (f'<dc:{tag} id="{ident}">{escape(name, quote=False)}</dc:{tag}>'
                f'<meta refines="#{ident}" property="role" scheme="marc:relators">{role}</meta>')

    modified = meta.date + "T00:00:00Z" if len(meta.date) == 10 else meta.date
    people = ""
    if meta.author:
        people += contributor("creator", "author", meta.author, "aut")
    if meta.translator:
        people += contributor("contributor", "translator", meta.translator, "trl")
    manifest = ('<item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>'
                '<item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>'
                '<item id="css" href="book.css" media-type="text/css"/>'
                '<item id="cover-image" href="cover.jpg" media-type="image/jpeg" properties="cover-image"/>'
                '<item id="cover" href="cover.xhtml" media-type="application/xhtml+xml"/>'
                '<item id="colophon" href="colophon.xhtml" media-type="application/xhtml+xml" properties="svg"/>')
    manifest += "".join(f'<item id="c{i + 1:03d}" href="{chapter_href(i)}" media-type="application/xhtml+xml"/>'
                        for i in range(count))
    spine = ('<itemref idref="cover"/>' + "".join(f'<itemref idref="c{i + 1:03d}"/>' for i in range(count))
             + '<itemref idref="colophon"/>')
    return (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="book-id">'
        '<metadata xmlns:dc="http://purl.org/dc/elements/1.1/">'
        f'<dc:identifier id="book-id">urn:granger:{escape(meta.id)}</dc:identifier>'
        f'<dc:title>{escape(display_title(work), quote=False)}</dc:title>{people}'
        f'<dc:date>{meta.published}</dc:date><dc:subject>{escape(meta.section_title, quote=False)}</dc:subject>'
        f'<dc:language>{escape(meta.language)}</dc:language><dc:publisher>Granger</dc:publisher>'
        f'<dc:source>{escape(meta.source_url, quote=False)}</dc:source><dc:rights>{RIGHTS}</dc:rights>'
        f'<meta property="dcterms:modified">{escape(modified)}</meta>'
        f'<meta property="belongs-to-collection" id="collection">{COLLECTION}</meta>'
        '<meta refines="#collection" property="collection-type">set</meta>'
        f'</metadata><manifest>{manifest}</manifest><spine toc="ncx">{spine}</spine>'
        '<guide><reference type="cover" title="Cover" href="cover.xhtml"/></guide></package>')


def _owl_svg() -> str:
    """The owl inline: the mark in the text colour so it suits any reader theme, lantern eyes, ink pupils."""
    view, (eyes, pupils, mark) = covers.owl_paths()
    paths = "".join(f'<path fill="{fill}" fill-rule="evenodd" d="{d}"/>'
                    for d, fill in ((mark, "currentColor"), (eyes, covers.LANTERN), (pupils, "#1F1813")))
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {view:g} {view:g}" role="img" '
            f'aria-label="Granger">{paths}</svg>')


def _colophon(work: Work, meta: BookMeta) -> str:
    """The last page: the owl, the collection, the book and where its text came from."""
    lines = [f"<h1>{escape(work.title, quote=False)}</h1>"]
    if meta.author:
        lines.append(f'<p class="author">{escape(meta.author, quote=False)}</p>')
    if meta.translator:
        lines.append(f"<p>Translated by {escape(meta.translator, quote=False)}</p>")
    facts = [f"This {'translation' if meta.translator else 'edition'} first published {meta.published}"]
    if meta.source_line:
        facts.append(meta.source_line)
    facts.append(RIGHTS)
    body = (f'<section class="colophon" epub:type="colophon">{_owl_svg()}'
            f'<p class="collection">{COLLECTION}</p>{"".join(lines)}'
            f'<div class="facts">{"".join(f"<p>{escape(f, quote=False)}</p>" for f in facts)}</div>'
            f'<p class="tagline">{escape(TAGLINE, quote=False)}</p></section>')
    return _page(f"{display_title(work)} — Colophon", meta.language, body)


def write_epub(out: Path, work: Work, meta: BookMeta, cover_jpeg: bytes) -> None:
    title = display_title(work)
    titles = _titles(work)
    files: list[tuple[str, bytes | str]] = [
        ("META-INF/container.xml",
         '<?xml version="1.0" encoding="utf-8"?>\n<container version="1.0" '
         'xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles>'
         '<rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>'
         "</rootfiles></container>"),
        ("OEBPS/content.opf", _opf(work, meta, len(work.chapters))),
        ("OEBPS/nav.xhtml", _nav(work, meta, titles)),
        ("OEBPS/toc.ncx", _ncx(work, meta, titles)),
        ("OEBPS/book.css", _book_css(work)),
        ("OEBPS/cover.jpg", cover_jpeg),
        ("OEBPS/cover.xhtml", _page(title, meta.language,
                                    f'<div class="cover"><img src="cover.jpg" alt="{escape(title)}"/></div>')),
        ("OEBPS/colophon.xhtml", _colophon(work, meta)),
    ]
    soups = [_prepare(chapter.html, work.line_numbers, work.verse) for chapter in work.chapters]
    _unwrap_dangling_links(soups)
    for i, soup in enumerate(soups):
        body = to_xhtml(_body(soup))
        files.append((f"OEBPS/{chapter_href(i)}", _page(f"{title} — {titles[i]}", meta.language, body)))

    out = Path(out)
    tmp = out.with_name(out.name + ".tmp")
    try:
        with zipfile.ZipFile(tmp, "w") as z:
            z.writestr(zipfile.ZipInfo("mimetype", ZIP_TIME), "application/epub+zip", zipfile.ZIP_STORED)
            for name, content in files:
                z.writestr(zipfile.ZipInfo(name, ZIP_TIME), content, zipfile.ZIP_DEFLATED)
        tmp.replace(out)
    except BaseException:
        tmp.unlink(missing_ok=True)
        raise
