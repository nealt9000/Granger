"""Builds a phone-readable EPUB 3 edition of one PDF from its Docling document.

    python edition.py <book id> [--books books.json] [--work DIR]

The PDF's pages are paper layouts; the edition reflows them: the text in reading order, chapters from the PDF's
outline (or its chapter headings), figures cut out and set full width under their captions, tables kept as
tables. Running heads, page numbers and the PDF's own contents pages are dropped; the edition makes its own.
Output is deterministic: fixed zip timestamps, files in a fixed order.
"""
import argparse
import hashlib
import html
import io
import json
import re
import sys
import zipfile
from dataclasses import dataclass, field
from pathlib import Path

import pymupdf
from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "pack-builder"))
import covers  # noqa: E402
from chapters import shouting, title_case  # noqa: E402

HERE = Path(__file__).resolve().parent
WORK = Path.home() / "Developer/Granger/vectors-work/readable-2026-11-17"
FIGURE_WIDTH = 1200     # px; wide enough for a phone at 3x, small enough to keep books light
SMALLEST_FIGURE = 48    # px at 1x; anything smaller is a rule, a bullet or a speck
CHAPTER_WORDS = 250     # a "chapter" shorter than this joins the one before it
CHAPTER = re.compile(r"^(chapter|part|appendix|annex|lesson)\b", re.I)
CONTENTS = re.compile(r"^(table of )?contents$", re.I)
WARNING = re.compile(r"^(warning|caution|note|danger)\b[:.]?\s", re.I)
LEADERS = re.compile(r"(\.\s?){4,}|\.\s\.\s|…{2,}")
SECTION = re.compile(r"^section\b", re.I)
BARE_HEAD = re.compile(r"^(chapter|part|appendix|annex|section)\s+([\dIVXLC]+|[A-Z])\.?$", re.I)
STYLE = """body{margin:0 4%;line-height:1.5}
h1{font-size:1.5em;margin:1.2em 0 .6em;line-height:1.25}
h2{font-size:1.25em;margin:1.2em 0 .5em;line-height:1.25}
h3,h4{font-size:1.05em;margin:1em 0 .4em}
figure{margin:1em 0;text-align:center;page-break-inside:avoid}
figure img{max-width:100%;height:auto}
figcaption{font-size:.9em;font-style:italic;margin-top:.3em}
table{border-collapse:collapse;width:100%;font-size:.9em;margin:1em 0}
th,td{border:1px solid currentColor;padding:.25em .4em;vertical-align:top;text-align:left}
.note{font-size:.85em}
.wide{overflow-x:auto}
.warn{border-left:.3em solid currentColor;padding:.3em .6em;margin:1em 0}
.epigraph{font-style:italic;margin:1em 8%}
.front{text-align:center;font-weight:bold}
"""


@dataclass
class Book:
    id: str
    pdf: str            # relative to WORK/pdfs
    title: str
    author: str
    language: str
    source: str         # where the PDF came from, said on the colophon
    rights: str
    pack: str
    outline_level: int | None = None   # the outline level chapters start at; None picks the first with 2+ entries
    skip_pages: list[int] = field(default_factory=list)


@dataclass
class Chapter:
    title: str
    parts: list[str] = field(default_factory=list)
    words: int = 0
    subheads: list[tuple[str, str, int]] = field(default_factory=list)  # (anchor, title, level 2 or 3)


def norm(text: str) -> str:
    return re.sub(r"[^a-z0-9]+", " ", text.lower()).strip()


def clean(text: str) -> str:
    # Letter-spaced display type ("L a d d e r s") comes out as single letters: close them up.
    text = re.sub(r"\b(?:\w ){2,}\w\b", lambda m: m.group(0).replace(" ", ""), text)
    text = re.sub(r"(\w)-\s+(\w)", lambda m: m.group(1) + m.group(2) if m.group(2).islower() else m.group(0), text)
    return re.sub(r"\s+", " ", text).strip()


class TextLayer:
    """The PDF's own words, read inside a box Docling found by sight.

    A page read by OCR has its paragraphs in the right places but OCR's misreadings ("trom" for "from"); the text
    layer has the right words but may run its lines across columns. Clipping the text layer to each OCR box gives
    both. A box whose text layer is empty or much shorter than the OCR text (a scan) keeps the OCR text.
    """

    def __init__(self, pdf: Path, doc) -> None:
        self.pdf = pymupdf.open(pdf)
        self.heights = {no: p.size.height for no, p in doc.pages.items()}

    def text(self, item) -> str:
        ocr = getattr(item, "text", "") or ""
        if not getattr(item, "prov", None):
            return ocr
        words = []
        for prov in item.prov:  # a paragraph that runs on into the next column has a box in each
            page = self.pdf[prov.page_no - 1]
            b = prov.bbox
            h = self.heights[prov.page_no]
            top, bottom = (h - b.t, h - b.b) if b.coord_origin.name == "BOTTOMLEFT" else (b.t, b.b)
            clip = pymupdf.Rect(b.l - 1, top - 1, b.r + 1, bottom + 1)
            # A word belongs to the box only when its middle is inside: neighbours that graze the edge stay out.
            words += [w for w in page.get_text("words", clip=clip, sort=True)
                      if clip.contains(pymupdf.Point((w[0] + w[2]) / 2, (w[1] + w[3]) / 2))]
        layer = " ".join(w[4] for w in words)
        if len(layer.split()) < 0.7 * len(ocr.split()):
            return ocr
        return layer


def outline_tails(pdf: Path) -> dict[str, str]:
    """Outline titles by their words after "Chapter 3": a page that prints only the title still starts it."""
    tails = {}
    for _, title, _ in pymupdf.open(pdf).get_toc():
        m = re.match(r"^((?:chapter|appendix|annex|part)\s+(?:[\dIVXLC]+|[A-Z]))\.?\s+(.+)$", title.strip(), re.I)
        if m:
            tails[norm(m.group(2))] = f"{m.group(1)} {m.group(2)}"
    return tails


def outline_titles(pdf: Path, level: int | None) -> set[str]:
    """The normalised titles of the outline entries chapters begin at."""
    toc = pymupdf.open(pdf).get_toc()
    if not toc:
        return set()
    if level is None:
        counts = {}
        for lv, _, _ in toc:
            counts[lv] = counts.get(lv, 0) + 1
        level = next((lv for lv in sorted(counts) if counts[lv] >= 2), 1)
    return {norm(t) for lv, t, _ in toc if lv == level and norm(t)}


def figure_bytes(image: Image.Image) -> tuple[bytes, str]:
    """A figure as PNG when it is line art (few colours), else JPEG; at most FIGURE_WIDTH wide."""
    if image.width > FIGURE_WIDTH:
        image = image.resize((FIGURE_WIDTH, round(image.height * FIGURE_WIDTH / image.width)), Image.LANCZOS)
    rgb = image.convert("RGB")
    small = rgb.resize((64, 64))
    colours = len(set(small.getdata()))
    grey = all(abs(r - g) < 12 and abs(g - b) < 12 for r, g, b in small.getdata())
    out = io.BytesIO()
    if grey and colours < 900:
        rgb.convert("L").quantize(16).save(out, "PNG", optimize=True)
        return out.getvalue(), "png"
    rgb.save(out, "JPEG", quality=80, optimize=True)
    return out.getvalue(), "jpg"


def tidy_heading(text: str) -> str:
    """A heading as a contents list shows it: no shouting, no "(fig. 2)", "Chapter 2. Bandaging"."""
    text = re.sub(r"\s*\((?:fig|figure|table)s?\.?\s[^)]*\)\s*$", "", text, flags=re.I)
    m = re.match(r"^((?:chapter|part|appendix|annex|section)\s+(?:[\dIVXLC]+|[A-Z]\b))\.?\s*(.*)$", text, re.I)
    head, rest = (m.group(1), m.group(2)) if m else ("", text)
    if shouting(rest):
        cased = title_case(rest)[0].split()
        orig = rest.split()
        coded = lambda i: 0 <= i < len(orig) and re.search(r"[\d/]", orig[i])  # noqa: E731
        # "FM 4-25.11": a code and the letters beside it stay as printed.
        rest = " ".join(o if coded(i) or coded(i - 1) or coded(i + 1) else c for i, (o, c) in enumerate(zip(orig, cased)))
    if head:
        head = head.split()[0].capitalize() + " " + head.split()[1].upper()
        return f"{head}. {rest}" if rest else head
    return rest


def heading_level(text: str, starts: set[str]) -> int:
    """1 starts a chapter, 2 is a section, 3 anything smaller."""
    if norm(text) in starts or CHAPTER.match(text):
        return 1
    if SECTION.match(text) or (text.isupper() and len(text) > 3):
        return 2
    return 3


def chapters_of(doc, book: Book, pdf: Path, images: dict[str, tuple[bytes, str]]) -> list[Chapter]:
    from docling_core.types.doc.labels import DocItemLabel as L

    starts = outline_titles(pdf, book.outline_level)
    tails = outline_tails(pdf)
    layer = TextLayer(pdf, doc)
    words_of = lambda it: clean(layer.text(it))  # noqa: E731
    caption_refs = set()
    for item, _ in doc.iterate_items():
        for ref in getattr(item, "captions", []) or []:
            caption_refs.add(ref.cref)

    # First the book as a flat run of blocks: ("h", level, text) or ("b", markup, words).
    blocks: list[tuple] = []
    for item, _ in doc.iterate_items():
        label = item.label
        page = item.prov[0].page_no if getattr(item, "prov", None) else 0
        if page in book.skip_pages or item.self_ref in caption_refs or label == L.DOCUMENT_INDEX:
            continue
        if label in (L.TITLE, L.SECTION_HEADER):
            text = words_of(item)
            if not text:
                continue
            prev = blocks[-1] if blocks else None
            # "CHAPTER 2" over "BANDAGING", or "28." over "Fastening the Bandage": one heading.
            if prev and prev[0] == "h" and (BARE_HEAD.match(prev[2]) or re.fullmatch(r"\d+\.?", prev[2])):
                joined = f"{prev[2]} {text}" if prev[2][-1:] == "." else f"{prev[2]}. {text}"
                blocks[-1] = ("h", heading_level(joined, starts), joined)
                continue
            if norm(text) in tails and not (prev and prev[0] == "h" and BARE_HEAD.match(prev[2])):
                text = tails[norm(text)]
            if text.isdigit() or (page <= 3 and norm(text) == norm(book.title)):
                continue
            if WARNING.match(text) or re.fullmatch(r"(warning|caution|note|notes|danger)[:.]?", text, re.I):
                word, rest = text.split(None, 1) if " " in text else (text, "")
                blocks.append(("b", f'<p class="warn"><strong>{html.escape(word.upper())}</strong> '
                                    f"{html.escape(rest)}</p>", len(text.split())))
                continue
            if text[:1] in "\"“‘'":
                blocks.append(("b", f'<p class="epigraph">{html.escape(text)}</p>', len(text.split())))
                continue
            level = heading_level(text, starts)
            if (page <= 2 and level > 1 and not SECTION.match(text)
                    and not any(b[0] != "h" and b[-1] > 30 for b in blocks)):
                blocks.append(("b", f'<p class="front">{html.escape(tidy_heading(text))}</p>', 0))
                continue
            blocks.append(("h", level, text))
        elif label == L.LIST_ITEM:
            text = words_of(item)
            kind = "ol" if getattr(item, "enumerated", False) else "ul"
            blocks.append(("li", kind, f"<li>{html.escape(text)}</li>", len(text.split())))
        elif label == L.TABLE:
            table = item.export_to_html(doc, add_caption=True)
            table = re.sub(r"<(/?)th\b[^>]*>", r"<\1th>", table)
            blocks.append(("b", f'<div class="wide">{to_xhtml_fragment(table)}</div>',
                           len(re.sub("<[^>]+>", " ", table).split())))
        elif label in (L.PICTURE, L.CHART):
            image = item.get_image(doc)
            if image is None or min(image.size) < SMALLEST_FIGURE * 2:
                continue
            data, ext = figure_bytes(image)
            name = f"images/f{len(images) + 1:03d}.{ext}"
            images[name] = (data, ext)
            caption = clean(" ".join(words_of(doc_item(doc, ref.cref)) for ref in item.captions))
            cap = f"<figcaption>{html.escape(caption)}</figcaption>" if caption else ""
            alt = html.escape(caption or "Figure")
            blocks.append(("b", f'<figure><img src="../{name}" alt="{alt}"/>{cap}</figure>', len(caption.split())))
        elif label in (L.FOOTNOTE, L.CAPTION):
            text = words_of(item)
            if text:
                blocks.append(("b", f'<p class="note">{html.escape(text)}</p>', len(text.split())))
        elif hasattr(item, "text"):
            text = words_of(item)
            if text and not re.fullmatch(r"[\d\W]{1,6}", text):
                blocks.append(("b", f"<p>{html.escape(text)}</p>", len(text.split())))

    # The PDF's own contents page goes (the edition makes its own), and so do running heads read as headings.
    seen: dict[str, int] = {}
    for b in blocks:
        if b[0] == "h":
            seen[norm(b[2])] = seen.get(norm(b[2]), 0) + 1
    kept, in_contents = [], False
    for b in blocks:
        if b[0] == "h":
            if re.fullmatch(r"index-?\d*", b[2], re.I):
                in_contents = True  # a printed index points at pages the edition no longer has
                continue
            if CONTENTS.match(b[2]):
                in_contents = True
                continue
            if LEADERS.search(b[2]) or (seen[norm(b[2])] >= 3 and b[1] > 1):
                continue
            in_contents = False
        elif in_contents or (b[0] in ("b", "li") and LEADERS.search(b[-2] if b[0] == "li" else b[1])):
            continue
        kept.append(b)
    blocks = kept

    # A book of one chapter (or none) is chaptered by its sections instead.
    if not starts and sum(1 for b in blocks if b[0] == "h" and b[1] == 1) <= 1:
        blocks = [("h", 1, b[2]) if b[0] == "h" and SECTION.match(b[2]) else b for b in blocks]

    # Then the blocks into chapters. Anchors are numbered across the book, so chapters can merge freely.
    chapters = [Chapter(book.title)]
    anchors = 0
    open_list: str | None = None
    for block in blocks:
        ch = chapters[-1]
        if open_list and not (block[0] == "li" and block[1] == open_list):
            ch.parts.append(f"</{open_list}>")
            open_list = None
        if block[0] == "h":
            _, level, text = block
            text = tidy_heading(text)
            if not re.search(r"\w", text):
                continue
            if level == 1:
                chapters.append(Chapter(text, [f"<h1>{html.escape(text)}</h1>"]))
                continue
            anchors += 1
            ch.subheads.append((f"a{anchors}", text, level))
            ch.parts.append(f'<h{level} id="a{anchors}">{html.escape(text)}</h{level}>')
        elif block[0] == "li":
            if not open_list:
                ch.parts.append(f"<{block[1]}>")
                open_list = block[1]
            ch.parts.append(block[2])
            ch.words += block[3]
        else:
            ch.parts.append(block[1])
            ch.words += block[2]
    if open_list:
        chapters[-1].parts.append(f"</{open_list}>")
    return merge_small(chapters)


def merge_small(chapters: list[Chapter]) -> list[Chapter]:
    """Front matter with no words is dropped; a chapter too short to stand alone joins the one before it."""
    out: list[Chapter] = []
    for n, ch in enumerate(chapters):
        if out and ch.words < CHAPTER_WORDS and not any("<figure" in p for p in ch.parts[1:]):
            prev = out[-1]
            anchor = f"m{n}"
            prev.subheads.append((anchor, ch.title, 2))
            first = ch.parts[0].replace("<h1>", f'<h2 id="{anchor}">', 1).replace("</h1>", "</h2>", 1)
            prev.parts += [first] + ch.parts[1:]
            prev.subheads += ch.subheads
            prev.words += ch.words
        else:
            out.append(ch)
    if len(out) > 1 and out[0].words < 40 and not any("<figure" in p for p in out[0].parts):
        out.pop(0)
    return out


def doc_item(doc, cref: str):
    return doc.texts[int(cref.rsplit("/", 1)[1])]


def to_xhtml_fragment(markup: str) -> str:
    import lxml.html
    from lxml import etree
    root = lxml.html.fragment_fromstring(markup, create_parent="div")
    return "".join(etree.tostring(child, method="xml", encoding="unicode") for child in root)


def nav_list(href: str, subheads: list[tuple[str, str, int]]) -> str:
    """Sections under a chapter, and the smaller headings under each section (or the chapter, if it has none)."""
    if not subheads:
        return ""
    top = min(level for _, _, level in subheads)
    out, inner = [], []

    def close() -> None:
        if inner and out:
            out[-1] = out[-1].replace("</li>", f"<ol>{''.join(inner)}</ol></li>")
        elif inner:
            out.extend(inner)
        inner.clear()

    for anchor, title, level in subheads:
        li = f'<li><a href="{href}#{anchor}">{html.escape(title)}</a></li>'
        if level == top:
            close()
            out.append(li)
        else:
            inner.append(li)
    close()
    return f"<ol>{''.join(out)}</ol>"


def page(title: str, lang: str, body: str) -> str:
    return (f'<?xml version="1.0" encoding="utf-8"?>\n<!DOCTYPE html>\n'
            f'<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" '
            f'lang="{lang}" xml:lang="{lang}"><head><meta charset="utf-8"/><title>{html.escape(title)}</title>'
            f'<link rel="stylesheet" type="text/css" href="../style.css"/></head><body>{body}</body></html>')


def build(book: Book, doc_json: Path, out: Path) -> dict:
    from docling_core.types.doc.document import DoclingDocument
    pdf = WORK / "pdfs" / book.pdf
    doc = DoclingDocument.load_from_json(doc_json)
    images: dict[str, tuple[bytes, str]] = {}
    chapters = chapters_of(doc, book, pdf, images)
    uid = "urn:granger:edition:" + book.id
    stamp = "2026-11-17T00:00:00Z"
    files: list[tuple[str, bytes, str, str]] = []  # (href, data, media type, properties)
    files.append(("cover.jpg", covers.cover_jpeg(book.title, book.author, None, "manuals", "Granger Manuals",
                                                 language=book.language), "image/jpeg", "cover-image"))
    for i, ch in enumerate(chapters, 1):
        files.append((f"text/c{i:03d}.xhtml", page(ch.title, book.language, "".join(ch.parts)).encode(),
                      "application/xhtml+xml", ""))
    colophon = (f"<h1>About this edition</h1><p>{html.escape(book.title)}, {html.escape(book.author)}.</p>"
                f"<p>Granger made this reading edition from the PDF {html.escape(book.source)}, found in the "
                f"Kiwix pack {html.escape(book.pack)}. The text was reflowed for a phone screen and the figures "
                f"set full width; the original pages are in the pack.</p><p>{html.escape(book.rights)}</p>")
    files.append(("text/colophon.xhtml", page("About this edition", book.language, colophon).encode(),
                  "application/xhtml+xml", ""))
    for name, (data, ext) in images.items():
        files.append((name, data, "image/png" if ext == "png" else "image/jpeg", ""))
    files.append(("style.css", STYLE.encode(), "text/css", ""))

    items = []
    for i, ch in enumerate(chapters, 1):
        href = f"text/c{i:03d}.xhtml"
        items.append(f'<li><a href="{href}">{html.escape(ch.title)}</a>{nav_list(href, ch.subheads)}</li>')
    items.append('<li><a href="text/colophon.xhtml">About this edition</a></li>')
    nav = (f'<?xml version="1.0" encoding="utf-8"?>\n<!DOCTYPE html>\n<html xmlns="http://www.w3.org/1999/xhtml" '
           f'xmlns:epub="http://www.idpf.org/2007/ops" lang="{book.language}" xml:lang="{book.language}"><head>'
           f'<meta charset="utf-8"/><title>Contents</title></head><body><nav epub:type="toc" id="toc">'
           f'<h1>Contents</h1><ol>{"".join(items)}</ol></nav></body></html>')
    manifest = ['<item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>']
    spine = []
    for n, (href, _, media, props) in enumerate(files):
        pid = f"i{n:04d}"
        manifest.append(f'<item id="{pid}" href="{href}" media-type="{media}"'
                        + (f' properties="{props}"' if props else "") + "/>")
        if href.startswith("text/"):
            spine.append(f'<itemref idref="{pid}"/>')
    opf = (f'<?xml version="1.0" encoding="utf-8"?>\n<package xmlns="http://www.idpf.org/2007/opf" version="3.0" '
           f'unique-identifier="uid" xml:lang="{book.language}"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/">'
           f'<dc:identifier id="uid">{uid}</dc:identifier><dc:title>{html.escape(book.title)}</dc:title>'
           f'<dc:creator>{html.escape(book.author)}</dc:creator><dc:language>{book.language}</dc:language>'
           f'<dc:publisher>Granger</dc:publisher><dc:rights>{html.escape(book.rights)}</dc:rights>'
           f'<meta property="dcterms:modified">{stamp}</meta><meta name="cover" content="i0000"/></metadata>'
           f'<manifest>{"".join(manifest)}</manifest><spine>{"".join(spine)}</spine></package>')
    container = ('<?xml version="1.0"?>\n<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:'
                 'container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package'
                 '+xml"/></rootfiles></container>')
    out.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(out, "w") as z:
        def put(name: str, data: bytes, method=zipfile.ZIP_DEFLATED) -> None:
            info = zipfile.ZipInfo(name, (2026, 11, 17, 0, 0, 0))
            info.compress_type = method
            z.writestr(info, data)
        put("mimetype", b"application/epub+zip", zipfile.ZIP_STORED)
        put("META-INF/container.xml", container.encode())
        put("OEBPS/content.opf", opf.encode())
        put("OEBPS/nav.xhtml", nav.encode())
        for href, data, _, _ in files:
            put("OEBPS/" + href, data)
    return {"id": book.id, "chapters": len(chapters), "figures": len(images),
            "words": sum(c.words for c in chapters), "bytes": out.stat().st_size,
            "sha256": hashlib.sha256(out.read_bytes()).hexdigest()}


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("ids", nargs="*")
    ap.add_argument("--books", default=str(HERE / "pilot.json"))
    ap.add_argument("--work", default=str(WORK))
    a = ap.parse_args()
    books = [Book(**b) for b in json.load(open(a.books))]
    for book in books:
        if a.ids and book.id not in a.ids:
            continue
        doc_json = Path(a.work) / "probe" / book.id / "doc.json"
        print(json.dumps(build(book, doc_json, Path(a.work) / "editions" / f"{book.id}.epub")))


if __name__ == "__main__":
    main()
