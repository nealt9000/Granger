"""Lays out a pack's pages and writes them as one ZIM with the full-text index on.

Paths: `index` (main page), `about`, `<slug>` (a book's page), `<slug>/001`... (its chapters) and
`res/classics.css`. Pages are plain HTML and CSS with relative links only: no scripts, no network.
"""
import html
import os
import struct
import zlib
from dataclasses import dataclass, field
from pathlib import Path

from libzim.writer import Creator, Hint, Item, StringProvider

from chapters import Chapter, page_name
from manifest import language_code

PAGE_LIMIT = 150_000  # bytes per page, so the WebView and the Ask chunker never get one huge entry

CSS = """
:root { color-scheme: light dark; --ink: #222; --paper: #fdfbf7; --muted: #6b6257; --accent: #7a4b1e; }
@media (prefers-color-scheme: dark) { :root { --ink: #e8e2d8; --paper: #1b1915; --muted: #a59a8a; --accent: #d9a46a; } }
body { font-family: Georgia, "Times New Roman", serif; line-height: 1.6; color: var(--ink); background: var(--paper);
       max-width: 40em; margin: 0 auto; padding: 1em; }
a { color: var(--accent); }
h1, h2, h3, h4 { line-height: 1.25; }
.byline, .note { color: var(--muted); }
.nav { display: flex; justify-content: space-between; gap: 1em; margin: 1em 0; font-family: sans-serif; font-size: .9em; }
.toc li, .works li { margin: .3em 0; }
table { border-collapse: collapse; width: 100%; }
td, th { text-align: left; padding: .3em .5em; border-bottom: 1px solid var(--muted); vertical-align: top; }
pre { white-space: pre-wrap; }
""".strip()

# The pack's own words, by 2-letter language.
LABELS = {
    "en": {"translated_by": "translated by", "previous": "‹ Previous", "next": "Next ›", "all_works": "All works",
           "intro": "{description}. Every work here was published in 1930 or earlier and is in the public domain in "
                    "the United States.",
           "about_link": "About this pack", "about": "About {title}",
           "about_body": "{title} is a collection of {count} public-domain works, assembled for Granger. Each was "
                         "published in 1930 or earlier (for translations, the translation's own date), so it is in "
                         "the public domain in the United States. Terms in other countries may differ.",
           "built": "Built {date}. Images from the source editions are left out.",
           "columns": ("Work", "Author", "Translator", "Year")},
    "es": {"translated_by": "traducción de", "previous": "‹ Anterior", "next": "Siguiente ›", "all_works": "Todas las obras",
           "intro": "{description}. Todas las obras se publicaron en 1930 o antes, y sus autores, traductores y "
                    "editores murieron en 1945 o antes: son de dominio público en los Estados Unidos, en España y en "
                    "Argentina.",
           "about_link": "Acerca de esta colección", "about": "Acerca de {title}",
           "about_body": "{title} es una colección de {count} obras de dominio público, reunidas para Granger. Cada una "
                         "se publicó en 1930 o antes (en las traducciones, cuenta el año de la traducción), y todas las "
                         "personas nombradas murieron en 1945 o antes. Las leyes de otros países pueden ser distintas.",
           "built": "Creado el {date}. Se omiten las imágenes de las ediciones originales.",
           "columns": ("Obra", "Autor", "Traductor", "Año")},
}


def labels(language: str) -> dict:
    return LABELS.get(language_code(language), LABELS["en"])


@dataclass
class Work:
    slug: str
    title: str
    author: str
    translator: str | None
    published: int
    section: str
    chapters: list[Chapter]
    styles: str = ""
    line_numbers: str = "margin"  # "margin" or "drop", for EPUBs
    verse: bool = False  # lines of verse become hanging blocks, for EPUBs
    title_overrides: dict[str, str] = field(default_factory=dict)  # contents titles by page number, for EPUBs
    language: str = "en"


@dataclass
class PackInfo:
    name: str
    title: str
    description: str
    language: str
    date: str
    sections: list[tuple[str, str]] = field(default_factory=list)


class _Page(Item):
    def __init__(self, path: str, title: str, mimetype: str, content: str, front: bool):
        super().__init__()
        self.path, self.title, self.mimetype, self.content, self.front = path, title, mimetype, content, front

    def get_path(self):
        return self.path

    def get_title(self):
        return self.title

    def get_mimetype(self):
        return self.mimetype

    def get_contentprovider(self):
        return StringProvider(self.content)

    def get_hints(self):
        return {Hint.FRONT_ARTICLE: self.front}


def e(text: str) -> str:
    return html.escape(text, quote=True)


def _document(title: str, body: str, depth: int, styles: str = "", language: str = "en") -> str:
    up = "../" * depth
    extra = f"<style>{styles}</style>" if styles else ""
    return (
        f'<!DOCTYPE html><html lang="{e(language_code(language))}"><head><meta charset="utf-8">'
        '<meta name="viewport" content="width=device-width, initial-scale=1">'
        f'<title>{e(title)}</title><link rel="stylesheet" href="{up}res/classics.css">{extra}</head>'
        f"<body>{body}</body></html>"
    )


def _byline(work: Work) -> str:
    parts = [work.author] if work.author else []
    if work.translator:
        parts.append(f"{labels(work.language)['translated_by']} {work.translator}")
    parts.append(str(work.published))
    return ", ".join(parts)


def _chapter_page(work: Work, index: int) -> str:
    words = labels(work.language)
    links = [f'<a href="../{work.slug}">{e(work.title)}</a>']
    if index > 0:
        links.append(f'<a href="{page_name(index - 1)}">{words["previous"]}</a>')
    if index < len(work.chapters) - 1:
        links.append(f'<a href="{page_name(index + 1)}">{words["next"]}</a>')
    nav = '<nav class="nav">' + "".join(f"<span>{link}</span>" for link in links) + "</nav>"
    chapter = work.chapters[index]
    return _document(f"{work.title} — {chapter.title}", nav + chapter.html + nav, depth=1, styles=work.styles,
                     language=work.language)


def chapter_budget(title: str, styles: str, language: str = "en") -> int:
    """How many bytes of chapter HTML fit on one page once the head, the book's styles and both nav bars are added."""
    probe = Work(slug="x" * 80, title=title, author="", translator=None, published=0, section="",
                 chapters=[Chapter("x" * 120, "")] * 3, styles=styles, language=language)
    return PAGE_LIMIT - len(_chapter_page(probe, 1).encode())


def _book_page(work: Work, sections: dict[str, str]) -> str:
    toc = "".join(f'<li><a href="{work.slug}/{page_name(i)}">{e(c.title)}</a></li>'
                  for i, c in enumerate(work.chapters))
    body = (f'<nav class="nav"><a href="index">{labels(work.language)["all_works"]}</a></nav><h1>{e(work.title)}</h1>'
            f'<p class="byline">{e(_byline(work))}</p><p class="note">{e(sections.get(work.section, ""))}</p>'
            f'<ol class="toc">{toc}</ol>')
    return _document(work.title, body, depth=0, language=work.language)


def _index_page(info: PackInfo, works: list[Work]) -> str:
    words = labels(info.language)
    intro = words["intro"].format(description=e(info.description))
    body = [f'<h1>{e(info.title)}</h1><p>{intro} <a href="about">{words["about_link"]}</a></p>']
    for section_id, section_title in info.sections:
        listed = [w for w in works if w.section == section_id]
        if not listed:
            continue
        items = "".join(f'<li><a href="{w.slug}">{e(w.title)}</a>{" — " + e(w.author) if w.author else ""}</li>'
                        for w in listed)
        body.append(f'<h2>{e(section_title)}</h2><ul class="works">{items}</ul>')
    return _document(info.title, "".join(body), depth=0, language=info.language)


def _about_page(info: PackInfo, works: list[Work]) -> str:
    words = labels(info.language)
    rows = "".join(
        f'<tr><td><a href="{w.slug}">{e(w.title)}</a></td><td>{e(w.author)}</td>'
        f'<td>{e(words["translated_by"] + " " + w.translator) if w.translator else ""}</td><td>{w.published}</td></tr>'
        for w in works)
    about = words["about"].format(title=info.title)
    header = "".join(f"<th>{c}</th>" for c in words["columns"])
    body = (f'<nav class="nav"><a href="index">{words["all_works"]}</a></nav><h1>{e(about)}</h1>'
            f'<p>{words["about_body"].format(title=e(info.title), count=len(works))}</p>'
            f'<p>{words["built"].format(date=e(info.date))}</p>'
            f"<table><tr>{header}</tr>{rows}</table>")
    return _document(about, body, depth=0, language=info.language)


def _add_work(creator: Creator, work: Work, sections: dict[str, str]) -> None:
    creator.add_item(_Page(work.slug, work.title, "text/html", _book_page(work, sections), True))
    for index, chapter in enumerate(work.chapters):
        creator.add_item(_Page(f"{work.slug}/{page_name(index)}", f"{work.title} — {chapter.title}", "text/html",
                               _chapter_page(work, index), False))


def write_zim(out: Path, info: PackInfo, works: list[Work]) -> None:
    out = Path(out)
    tmp = out.with_name(out.name + ".tmp")
    tmp.unlink(missing_ok=True)
    sections = dict(info.sections)
    try:
        with Creator(tmp).config_indexing(True, info.language) as creator:
            creator.set_mainpath("index")
            creator.add_item(_Page("index", info.title, "text/html", _index_page(info, works), True))
            creator.add_item(_Page("about", labels(info.language)["about"].format(title=info.title), "text/html",
                                   _about_page(info, works), True))
            creator.add_item(_Page("res/classics.css", "", "text/css", CSS, False))
            for work in works:
                _add_work(creator, work, sections)
            creator.add_illustration(48, solid_png(48, (90, 60, 30)))
            for name, value in [("Name", info.name), ("Title", info.title), ("Description", info.description),
                                ("Language", info.language), ("Date", info.date), ("Creator", "Granger"),
                                ("Publisher", "Granger")]:
                creator.add_metadata(name, value)
    except BaseException:
        tmp.unlink(missing_ok=True)
        raise
    os.replace(tmp, out)


def solid_png(size: int, rgb: tuple[int, int, int]) -> bytes:
    def chunk(kind: bytes, data: bytes) -> bytes:
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)

    row = b"\x00" + bytes(rgb) * size
    header = struct.pack(">IIBBBBB", size, size, 8, 2, 0, 0, 0)
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", header) + chunk(b"IDAT", zlib.compress(row * size))
            + chunk(b"IEND", b""))
