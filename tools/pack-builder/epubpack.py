"""Writes one Project Gutenberg work as an EPUB 3 file.

The caller splits the work with `chapters.split(..., page_href=chapter_href)`, so cross-chapter links already
name the `cNNN.xhtml` files written here. Output is deterministic: fixed zip timestamps and a modified date
taken from the build date.
"""
import re
import zipfile
from dataclasses import dataclass
from html import escape
from pathlib import Path

import lxml.html
from bs4 import BeautifulSoup
from lxml import etree

from chapters import PAGE_NUMBER, clean_title
from zimpack import Work

COLLECTION = "Granger Classics"
RIGHTS = "Public domain in the USA."
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
""".strip()

_RULE = re.compile(r"([^{}]+)\{([^{}]*)\}")
_DROPPED_PROPERTY = re.compile(r"^(color|background(-.*)?|font-family)$", re.I)
_PAGE_NUMBER_SELECTOR = re.compile(r"\.(" + "|".join(map(re.escape, sorted(PAGE_NUMBER))) + r")(?![\w-])", re.I)


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


def book_id(slug: str) -> str:
    return f"gc-{slug}"


def chapter_href(index: int) -> str:
    return f"c{index + 1:03d}.xhtml"


def display_title(work: Work) -> str:
    if not work.author and work.translator:
        return f"{work.title} (tr. {work.translator})"
    return work.title


def scrub_css(css: str) -> str:
    """The work's own rules without colours, backgrounds, font families or page-number styling."""
    css = re.sub(r"/\*.*?\*/", "", css, flags=re.S)
    rules = []
    for selector, body in _RULE.findall(css):
        selector = selector.strip()
        if selector.startswith("@") or _PAGE_NUMBER_SELECTOR.search(selector):
            continue
        declarations = [d.strip() for d in body.split(";") if d.strip()]
        kept = [d for d in declarations if not _DROPPED_PROPERTY.match(d.split(":", 1)[0].strip())]
        if kept:
            rules.append(f"{selector} {{ {'; '.join(kept)} }}")
    return "\n".join(rules)


def to_xhtml(html: str) -> str:
    """HTML fragment as well-formed XML, for placing inside an XHTML body."""
    if not html.strip():
        return ""
    root = lxml.html.fragment_fromstring(html, create_parent="div")
    parts = [escape(root.text, quote=False)] if root.text else []
    parts += [etree.tostring(child, method="xml", encoding="unicode") for child in root]
    return "".join(parts)


def _without_page_numbers(html: str) -> str:
    soup = BeautifulSoup(html, "lxml")
    for tag in soup.find_all(class_=lambda c: c and PAGE_NUMBER.intersection(c.split())):
        tag.decompose()
    return soup.body.decode_contents() if soup.body else ""


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
            f'<li><a epub:type="bodymatter" href="{first}">Start of text</a></li></ol></nav>')
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
                '<item id="cover" href="cover.xhtml" media-type="application/xhtml+xml"/>')
    manifest += "".join(f'<item id="c{i + 1:03d}" href="{chapter_href(i)}" media-type="application/xhtml+xml"/>'
                        for i in range(count))
    spine = '<itemref idref="cover"/>' + "".join(f'<itemref idref="c{i + 1:03d}"/>' for i in range(count))
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
        ("OEBPS/book.css", BOOK_CSS + "\n" + scrub_css(work.styles) + "\n"),
        ("OEBPS/cover.jpg", cover_jpeg),
        ("OEBPS/cover.xhtml", _page(title, meta.language,
                                    f'<div class="cover"><img src="cover.jpg" alt="{escape(title)}"/></div>')),
    ]
    for i, chapter in enumerate(work.chapters):
        body = to_xhtml(_without_page_numbers(chapter.html))
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
