"""Builds a phone-readable EPUB 3 edition of a book from one or more PDFs, via their Docling documents.

    python edition.py [book id ...] [--books books.json] [--work DIR]

A PDF's pages are paper layouts; the edition reflows them: the text in reading order, figures cut out and set
full width under their captions, tables kept as tables. Structure comes from the most authoritative place there
is: the PDF's outline, else its printed contents page, else its "Chapter"/"Section" headings. A book made of
several PDFs (a guide in parts) takes one chapter per PDF.

The edition opens on a page of its own: what the book is for, where to start, and an index of what is inside
(a grid of pictures for a book of knots, a list grouped by part for a book of recipes). Running heads, page
numbers, printed contents pages and indexes are dropped; "see Figure 2-2" becomes a link to the figure.
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
MINOR = 9               # the level of a heading the outline doesn't know: shown, but not in the contents
CHAPTER = re.compile(r"^(chapter|part|appendix|annex|lesson)\b", re.I)
CONTENTS = re.compile(r"^(table of )?contents$", re.I)
WARNING = re.compile(r"^(warning|caution|note|danger)\b[:.]?\s", re.I)
LEADERS = re.compile(r"(\.\s?){4,}|\.\s\.\s|…{2,}")
SECTION = re.compile(r"^section\b", re.I)
BARE_HEAD = re.compile(r"^(chapter|part|appendix|annex|section)\s+([\dIVXLC]+|[A-Z])\.?$", re.I)
FIGURE_REF = re.compile(r"\b(Figure|Fig\.)\s+(\d+(?:[-–.]\d+)?)", re.I)
PRINTED_ENTRY = re.compile(r"^(.*?)\s*\.{3,}\s*([\dA-Z]+-\d+|\d+|[ivxlc]+|not numbered)\s*$", re.I)
# Outline entries that are the PDF's furniture, not the book's: they and what follows them go.
FURNITURE = re.compile(r"^(cover|change page|(table of )?contents|list of (figures|tables|illustrations)|index"
                       r"|authentication|authorization letter|distribution:?)$", re.I)
STYLE = """body{margin:0 4%;line-height:1.5}
h1{font-size:1.5em;margin:1.2em 0 .6em;line-height:1.25}
h2{font-size:1.25em;margin:1.2em 0 .5em;line-height:1.25}
h3,h4,h5{font-size:1.05em;margin:1em 0 .4em}
figure{margin:1em 0;text-align:center;page-break-inside:avoid}
figure img{max-width:100%;height:auto}
figcaption{font-size:.9em;font-style:italic;margin-top:.3em}
table{border-collapse:collapse;width:100%;font-size:.82em;margin:1em 0;line-height:1.3}
caption{caption-side:top;text-align:left;font-weight:bold;font-size:1.1em;margin-bottom:.3em}
th,td{border:1px solid currentColor;padding:.2em .3em;vertical-align:top;text-align:left}
.note{font-size:.85em}
.wide{overflow-x:auto}
.warn{border-left:.3em solid currentColor;padding:.3em .6em;margin:1em 0}
.epigraph{font-style:italic;margin:1em 8%}
.front{text-align:center;font-weight:bold}
.opening h1{margin-top:.4em}
.byline{font-style:italic;margin-top:-.4em}
.about{font-size:1.05em}
.start li{margin:.35em 0}
.tiles{text-align:center}
.tile{display:inline-block;width:46%;margin:.6em 1%;vertical-align:top;text-decoration:none;color:inherit}
.tile img{width:100%;height:auto}
.tile span{display:block;font-size:.9em;margin-top:.2em}
.entries{columns:2;column-gap:1.2em;padding-left:1.1em}
.entries li{break-inside:avoid;margin:.15em 0}
"""


@dataclass
class Source:
    pdf: str                       # relative to WORK
    title: str | None = None       # set, this PDF is one chapter of the book, so titled
    probe: str | None = None       # its Docling document's folder under WORK/probe; default <book id>/<n>
    skip_pages: list[int] = field(default_factory=list)
    # The contents, given: [[level, title, PDF page], …] in place of the PDF's outline, for a printed contents page too
    # odd to read (a cookbook's lists every page a subject appears on). Titles are matched to headings by their words.
    contents: list[list] = field(default_factory=list)


@dataclass
class Index:
    title: str                     # "Knots", "Recipes"
    level: int                     # the heading level of an entry (outline depth: 1 is a chapter)
    within: list[str] = field(default_factory=list)  # only in chapters whose title has one of these
    pictures: bool = False         # a grid of each entry's first figure, else a list grouped by chapter
    group_level: int | None = None  # entries under headings of this level are grouped by them (pictures)


@dataclass
class Book:
    id: str
    title: str
    author: str
    language: str
    rights: str
    sources: list[Source]
    made_from: str                 # the colophon's sentence on where the PDFs came from
    about: str = ""                # the opening page's few words on what the book is and when to reach for it
    notice: str = ""               # a content notice shown first, in a box (Plan BK: old works shown as printed, not cut)
    start_here: list[str] = field(default_factory=list)  # headings to read first, by their words
    index: Index | None = None
    picture_first: bool = False    # an index entry's first figure moves up under its heading

    @staticmethod
    def of(d: dict) -> "Book":
        d = dict(d)
        if "pdf" in d:  # the pilot's one-PDF form
            d["sources"] = [{"pdf": "pdfs/" + d.pop("pdf"), "probe": d["id"]}]
            d["made_from"] = f"the PDF {d.pop('source')} in the Kiwix pack {d.pop('pack')}"
            d.pop("outline_level", None)
            d.pop("skip_pages", None)
        d["sources"] = [Source(**s) for s in d["sources"]]
        if d.get("index"):
            d["index"] = Index(**d["index"])
        return Book(**d)


@dataclass
class Chapter:
    title: str
    parts: list[str] = field(default_factory=list)
    words: int = 0
    subheads: list[tuple[str, str, int]] = field(default_factory=list)  # (anchor, title, level 2+)
    anchor_figure: dict[str, str] = field(default_factory=dict)        # anchor -> its figure's image
    known: set[str] = field(default_factory=set)                       # anchors of headings the outline knows
    figures: list[tuple[str, str]] = field(default_factory=list)       # (image, caption)


def norm(text: str) -> str:
    return re.sub(r"[^a-z0-9]+", " ", text.lower()).strip()


def key(text: str) -> str:
    """A heading's identity, blind to spacing and to OCR's "Section 1" for "Section I"."""
    text = re.sub(r"\b1\b", "i", text.lower())
    return re.sub(r"[^a-z0-9]+", "", text)


def clean(text: str) -> str:
    # Letter-spaced display type ("L a d d e r s") comes out as single letters: close them up.
    text = re.sub(r"\b(?:[A-Za-z] ){2,}[A-Za-z]\b", lambda m: m.group(0).replace(" ", ""), text)  # never digits
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
        tokens = layer.split()
        if len(tokens) >= 4 and sum(len(t) == 1 for t in tokens) > len(tokens) / 2:
            return ocr  # letter-spaced display type: OCR sees the words, the text layer only letters
        return layer


@dataclass
class Outline:
    """The book's own structure: each title's depth (1 a chapter), and the words after "Chapter 3" for pages
    that print only those."""
    depth: dict[str, int] = field(default_factory=dict)
    tails: dict[str, str] = field(default_factory=dict)
    furniture: set[str] = field(default_factory=set)
    chapter_pages: dict[int, str] = field(default_factory=dict)  # page -> the chapter that starts on it
    titles: dict[str, str] = field(default_factory=dict)         # key -> the outline's own wording

    def __bool__(self) -> bool:
        return bool(self.depth)


NUMBER = re.compile(r"\d+(?:[.,/]\d+)*")


def table_html(item, doc, layer: "TextLayer", problems: list[str], images: dict[str, tuple[bytes, str]]) -> str:
    """A table rebuilt from Docling's rows and columns, every cell filled from the PDF's own words.

    Docling finds a table's shape well but its OCR can leave cells empty: a canning guide's "20 | 20" in a row of
    process times came out blank. Here each word of the PDF's text layer inside the table goes to the row and column
    band it sits in, so no printed word goes missing; then every number printed in the table must appear in the
    rebuilt one, or the build says so. A scanned table (no text layer) keeps Docling's cells.
    """
    data = item.data
    cells = data.table_cells
    head_words = set(norm(" ".join(c.text for c in cells if c.start_row_offset_idx <= 1)).split())
    if {"guide", "page"} <= head_words or {"page"} == head_words & {"page", "time", "process"}:
        return ""  # a printed index ("Guide | Page"): its page numbers mean nothing in the edition
    nrows, ncols = data.num_rows, data.num_cols
    prov = item.prov[0]
    page = layer.pdf[prov.page_no - 1]
    h = layer.heights[prov.page_no]
    b = prov.bbox
    top, bottom = (h - b.t, h - b.b) if b.coord_origin.name == "BOTTOMLEFT" else (b.t, b.b)
    rect = pymupdf.Rect(b.l - 2, top - 2, b.r + 2, bottom + 2)
    words, seen_words = [], set()
    for w in page.get_text("words", clip=rect, sort=True):
        spot = (w[4], round(w[0]), round(w[1]))
        if spot in seen_words:
            continue  # bold made by printing the text twice
        seen_words.add(spot)
        if rect.contains(pymupdf.Point((w[0] + w[2]) / 2, (w[1] + w[3]) / 2)):
            words.append(w)

    def tl(c):  # a cell's box, top-left origin
        cb = c.bbox
        if cb.coord_origin.name == "BOTTOMLEFT":
            return cb.l, h - cb.t, cb.r, h - cb.b
        return cb.l, cb.t, cb.r, cb.b

    owner: dict[tuple[int, int], tuple[int, int]] = {}  # (row, col) -> the cell that covers it
    spans: dict[tuple[int, int], tuple[int, int]] = {}
    texts: dict[tuple[int, int], list[str]] = {}
    for c in cells:
        r0, c0 = c.start_row_offset_idx, c.start_col_offset_idx
        spans[(r0, c0)] = (c.row_span, c.col_span)
        for r in range(r0, r0 + c.row_span):
            for k in range(c0, c0 + c.col_span):
                owner[(r, k)] = (r0, c0)
    for r in range(nrows):
        for k in range(ncols):
            if (r, k) not in owner:
                owner[(r, k)] = (r, k)
                spans[(r, k)] = (1, 1)
    if not words:
        for c in cells:
            texts[(c.start_row_offset_idx, c.start_col_offset_idx)] = [c.text]
    else:
        rows = [[] for _ in range(nrows)]
        cols = [[] for _ in range(ncols)]
        body_cols = [[] for _ in range(ncols)]
        heads = {c.start_row_offset_idx for c in cells if c.column_header} or {0, 1}
        for c in cells:
            x0, y0, x1, y1 = tl(c)
            if c.row_span == 1:
                rows[c.start_row_offset_idx].append((y0, y1))
            if c.col_span == 1:
                cols[c.start_col_offset_idx].append((x0, x1))
                if c.start_row_offset_idx not in heads and c.text.strip():
                    body_cols[c.start_col_offset_idx].append((x0, x1))
        # Columns are placed by their body cells: a wide header ("Process Time at Altitudes of 0-1,000 ft")
        # would otherwise stretch its column over the next ones and pull their numbers into it.
        cols = [b if b else a for a, b in zip(cols, body_cols)]
        rband = [(min(a for a, _ in v), max(z for _, z in v)) if v else None for v in rows]
        cband = [(min(a for a, _ in v), max(z for _, z in v)) if v else None for v in cols]

        def nearest(bands, v):
            best, dist = 0, None
            for i, band in enumerate(bands):
                if band is None:
                    continue
                d = 0 if band[0] <= v <= band[1] else min(abs(v - band[0]), abs(v - band[1]))
                if dist is None or d < dist:
                    best, dist = i, d
            return best
        for w in words:
            r = nearest(rband, (w[1] + w[3]) / 2)
            k = nearest(cband, (w[0] + w[2]) / 2)
            texts.setdefault(owner[(r, k)], []).append(w[4])
    header_rows = {c.start_row_offset_idx for c in cells if c.column_header}
    caption = ""
    first = owner.get((0, 0))
    if ncols > 1 and spans.get(first, (1, 1))[1] == ncols:
        caption = clean(" ".join(texts.get(first, [])))
    out = [f"<caption>{html.escape(caption)}</caption>" if caption else ""]
    for r in range(1 if caption else 0, nrows):
        row = []
        for k in range(ncols):
            if owner[(r, k)] != (r, k):
                continue
            rs, cs = spans[(r, k)]
            tag = "th" if r in header_rows or (not header_rows and r == (1 if caption else 0)) else "td"
            attrs = (f' rowspan="{rs}"' if rs > 1 else "") + (f' colspan="{cs}"' if cs > 1 else "")
            row.append(f"<{tag}{attrs}>{html.escape(clean(' '.join(texts.get((r, k), []))))}</{tag}>")
        out.append(f"<tr>{''.join(row)}</tr>")
    rebuilt = "".join(out)
    before = len(problems)
    if words:
        # Where Docling read a body cell itself, the rebuilt cell must say the same: numbers in the right column.
        for c in cells:
            r0, c0 = c.start_row_offset_idx, c.start_col_offset_idx
            if c.column_header or r0 in header_rows or not NUMBER.search(c.text):
                continue
            mine = NUMBER.findall(" ".join(texts.get((r0, c0), [])))
            digits = lambda t: re.sub(r"\D", "", re.sub(r"\b1b\b", "lb", t))  # noqa: E731 (OCR's "1b" is "lb")
            if digits(c.text) != digits(" ".join(mine)) and digits(c.text.replace("/", "")) != digits(" ".join(mine)):
                problems.append(f"table on page {prov.page_no}: row {r0} column {c0} reads {c.text!r} "
                                f"but the rebuilt cell has {mine}")
        printed = sorted(NUMBER.findall(" ".join(w[4] for w in words)))
        shown = sorted(NUMBER.findall(re.sub("<[^>]+>", " ", rebuilt)))
        if printed != shown:
            problems.append(f"table on page {prov.page_no}: printed {printed} but shows {shown}")
    if len(problems) > before:
        pix = page.get_pixmap(clip=rect, dpi=220)
        image = Image.frombytes("RGB", (pix.width, pix.height), pix.samples)
        data, ext = figure_bytes(image)
        name = f"images/t{len(images) + 1:03d}.{ext}"
        images[name] = (data, ext)
        alt = html.escape(caption or "Table")
        return f'<figure class="table"><img src="../{name}" alt="{alt}"/></figure>'
    return f'<div class="wide"><table>{rebuilt}</table></div>'


def outline_of(pdf: Path, as_chapter: bool, contents: list | None = None) -> Outline:
    """From the PDF's outline, else its printed contents page. `as_chapter`: the PDF is one chapter, so its
    entries sit one level down."""
    doc = pymupdf.open(pdf)
    out = Outline()
    given = [tuple(e) for e in contents] if contents else doc.get_toc()
    toc = [(lv, t.strip()) for lv, t, _ in given if t.strip()]
    pages = [(lv, t.strip(), pg) for lv, t, pg in given if t.strip()]
    if toc:
        for lv, t in toc:
            if FURNITURE.match(t):
                out.furniture.add(norm(t))
        chapterish = [lv for lv, t in toc if CHAPTER.match(t) or t.lower() in ("preface", "glossary", "references")]
        top = min(chapterish) if chapterish else min(lv for lv, _ in toc)
        for lv, t in toc:
            if lv >= top and not FURNITURE.match(t):
                out.depth.setdefault(norm(t), lv - top + 1 + as_chapter)
                out.titles.setdefault(key(t), t)
                m = re.match(r"^((?:chapter|appendix|annex|part)\s+(?:[\dIVXLC]+|[A-Z]))\.?\s+(.+)$", t, re.I)
                if m:
                    out.tails[norm(m.group(2))] = f"{m.group(1)} {m.group(2)}"
                    out.depth.setdefault(norm(m.group(2)), lv - top + 1 + as_chapter)
        for lv, t, pg in pages:
            if lv == top and pg > 0 and not FURNITURE.match(t) and not as_chapter:
                out.chapter_pages.setdefault(pg, t)
        return out
    rows = []
    for pno in range(min(8, doc.page_count)):
        for b in doc[pno].get_text("dict")["blocks"]:
            for line in b.get("lines", []):
                text = "".join(s["text"] for s in line["spans"]).strip()
                m = PRINTED_ENTRY.match(text)
                if m and m.group(1).strip() and not m.group(1).lower().startswith("section"):
                    rows.append((round(line["bbox"][0] / 6), m.group(1).strip()))
    indents = sorted({x for x, _ in rows})
    for x, t in rows:
        out.depth.setdefault(norm(t), indents.index(x) + 1 + as_chapter)
        out.titles.setdefault(key(t), t)
    return out


PARA = re.compile(r"<p>(.*?)</p>", re.S)


def drop_repeats(page_html: str) -> str:
    """
    A paragraph that starts by repeating the end of the one before (a page break the text layer read twice, the same
    words in two boxes) loses the repeat, and what is left joins the paragraph before. Paragraphs only: tables are
    checked against the page as they are.
    """
    out, prev = [], None  # prev: index in out of the last <p>, with its text
    pos = 0
    for m in PARA.finditer(page_html):
        out.append(page_html[pos:m.start()])
        text = m.group(1)
        if prev is not None and page_html[prev[1]:m.start()].strip() == "":
            before = out[prev[0]]
            head = text[:40]
            i = before.find(head) if len(head) >= 30 else -1
            if i >= 0 and text.startswith(before[i:]):
                rest = text[len(before) - i:].strip()
                if rest:
                    out[prev[0]] = before + " " + rest
                pos = m.end()
                prev = (prev[0], m.end())
                continue
        out.append(text)
        out[-1] = text
        out.insert(len(out) - 1, "<p>")
        out.append("</p>")
        prev = (len(out) - 2, m.end())
        pos = m.end()
    out.append(page_html[pos:])
    return "".join(out)


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
        rest = " ".join(o if coded(i) or coded(i - 1) or coded(i + 1) else c
                        for i, (o, c) in enumerate(zip(orig, cased)))
    if head:
        head = head.split()[0].capitalize() + " " + head.split()[1].upper()
        return f"{head}. {rest}" if rest else head
    return rest


def heading_level(text: str, outline: Outline) -> int:
    """1 starts a chapter, 2 is a section, 3 and down smaller; MINOR is a heading the outline doesn't know."""
    if outline:
        return outline.depth.get(norm(text), MINOR)
    if CHAPTER.match(text):
        return 1
    if SECTION.match(text) or (text.isupper() and len(text) > 3):
        return 2
    return 3


def blocks_of(doc, source: Source, pdf: Path, book: Book, images: dict[str, tuple[bytes, str]],
              problems: list[str]) -> list[tuple]:
    """One PDF as a flat run of blocks: ("h", level, text), ("li", kind, markup, words), ("b", markup, words),
    or ("fig", markup, words, image name, figure number)."""
    from docling_core.types.doc.labels import DocItemLabel as L

    outline = outline_of(pdf, as_chapter=bool(source.title), contents=source.contents)
    layer = TextLayer(pdf, doc)
    words_of = lambda it: clean(layer.text(it))  # noqa: E731
    caption_refs = {ref.cref for item, _ in doc.iterate_items() for ref in (getattr(item, "captions", []) or [])}
    blocks: list[tuple] = [("h", 1, source.title)] if source.title else []
    known = 1  # the level of the last heading the outline knows
    started: set[int] = set()  # pages whose outline chapter has been opened
    used_figures: set[str] = set()
    for item, _ in doc.iterate_items():
        label = item.label
        page = item.prov[0].page_no if getattr(item, "prov", None) else 0
        if page in outline.chapter_pages and page not in started:
            # The outline says a chapter starts on this page: open it here, whatever the page's heading reads.
            started.add(page)
            blocks.append(("h", 1, outline.chapter_pages[page]))
            known = 1
        chapter_title = outline.chapter_pages.get(page)
        if page in source.skip_pages or item.self_ref in caption_refs or label == L.DOCUMENT_INDEX:
            continue
        if label in (L.TITLE, L.SECTION_HEADER):
            text = words_of(item)
            if not text:
                continue
            if chapter_title and (re.match(r"^(chapter|appendix|annex|part)\s*([\dIVXLC]+|[A-Z])?\.?$", text, re.I)
                                  or key(text) in key(chapter_title)):
                continue  # the page's own "CHAPTER 2" / title: the outline's heading stands for it
            if re.fullmatch(r"page|pages", text, re.I):
                continue
            if key(text) in outline.titles:
                text = outline.titles[key(text)]  # the outline's wording: "Section I. Fiber Rope", spaced right
            prev = blocks[-1] if blocks else None
            # "CHAPTER 2" over "BANDAGING", or "28." over "Fastening the Bandage": one heading.
            if prev and prev[0] == "h" and prev[2] and (BARE_HEAD.match(prev[2]) or re.fullmatch(r"\d+\.?", prev[2])):
                joined = f"{prev[2]} {text}" if prev[2][-1:] == "." else f"{prev[2]}. {text}"
                level = heading_level(joined, outline)
                if level == MINOR and BARE_HEAD.match(prev[2]) and CHAPTER.match(prev[2]):
                    level = 1
                blocks[-1] = ("h", level, joined)
                continue
            if norm(text) in outline.tails:
                text = outline.tails[norm(text)]
            if norm(text) in outline.furniture:
                blocks.append(("furniture",))
                continue
            if text.isdigit() or (page <= 3 and norm(text) in (norm(book.title), norm(source.title or ""))):
                continue
            if WARNING.match(text) or re.fullmatch(r"(warning|caution|note|notes|danger)[:.]?", text, re.I):
                word, rest = text.split(None, 1) if " " in text else (text, "")
                blocks.append(("b", f'<p class="warn"><strong>{html.escape(word.upper())}</strong> '
                                    f"{html.escape(rest)}</p>", len(text.split())))
                continue
            if text[:1] in "\"“‘'":
                blocks.append(("b", f'<p class="epigraph">{html.escape(text)}</p>', len(text.split())))
                continue
            level = heading_level(text, outline)
            if BARE_HEAD.match(text) and CHAPTER.match(text):
                level = 1  # joined with its title by the next heading
            if outline and level == MINOR and (page <= 2 or (source.title and known == 1)):
                continue  # a cover or title page's lines ("Complete Guide to Home Canning", "Guide 1")
            is_known = not outline or level != MINOR
            if outline:
                if level == MINOR:
                    level = min(known + 1, MINOR - 1)  # "Overhand Knot" under "Knots at the End of Rope"
                else:
                    known = level
            if (page <= 2 and level > 1 and not SECTION.match(text) and not outline
                    and not any(b[0] not in ("h", "furniture") and b[2] > 30 for b in blocks if len(b) > 2)):
                blocks.append(("b", f'<p class="front">{html.escape(tidy_heading(text))}</p>', 0))
                continue
            blocks.append(("h", level, text, is_known))
        elif label == L.LIST_ITEM:
            text = words_of(item)
            kind = "ol" if getattr(item, "enumerated", False) else "ul"
            blocks.append(("li", kind, f"<li>{html.escape(text)}</li>", len(text.split())))
        elif label == L.TABLE:
            table = table_html(item, doc, layer, problems, images)
            if table:
                blocks.append(("b", table, len(re.sub("<[^>]+>", " ", table).split())))
        elif label in (L.PICTURE, L.CHART):
            image = item.get_image(doc)
            if image is None or min(image.size) < SMALLEST_FIGURE * 2:
                continue
            data, ext = figure_bytes(image)
            name = f"images/f{len(images) + 1:03d}.{ext}"
            images[name] = (data, ext)
            caption = clean(" ".join(words_of(doc_item(doc, ref.cref)) for ref in item.captions))
            number = None
            m = re.match(r"^(?:Figure|Fig\.)\s+(\d+(?:[-–.]\d+)?)", caption, re.I)
            if m:
                number = m.group(1).replace("–", "-")
            fid = f' id="fig-{number}"' if number and number not in used_figures else ""
            if number:
                used_figures.add(number)
            cap = f"<figcaption>{html.escape(caption)}</figcaption>" if caption else ""
            alt = html.escape(caption or "Figure")
            blocks.append(("fig", f'<figure{fid}><img src="../{name}" alt="{alt}"/>{cap}</figure>',
                           len(caption.split()), name, number, caption))
        elif label in (L.FOOTNOTE, L.CAPTION):
            text = words_of(item)
            if text:
                blocks.append(("b", f'<p class="note">{html.escape(text)}</p>', len(text.split())))
        elif hasattr(item, "text"):
            text = words_of(item)
            if text and not re.fullmatch(r"[\d\W]{1,6}", text):
                blocks.append(("b", f"<p>{html.escape(text)}</p>", len(text.split())))
    return tidy_blocks(blocks, outline, as_chapter=bool(source.title))


def tidy_blocks(blocks: list[tuple], outline: Outline, as_chapter: bool) -> list[tuple]:
    """The PDF's own contents pages, lists of figures and index go (the edition makes its own), and so do running
    heads read as headings; with an outline, everything before the first chapter (cover, change pages) goes."""
    seen: dict[str, int] = {}
    for b in blocks:
        if b[0] == "h" and b[2]:
            seen[norm(b[2])] = seen.get(norm(b[2]), 0) + 1
    # skipping: None, "front" (until the first chapter) or "contents" (until the first heading the outline knows)
    kept, skipping = [], ("front" if outline and not as_chapter else None)
    for b in blocks:
        if b[0] == "furniture":
            skipping = "front"
            continue
        if b[0] == "h":
            text = b[2] or ""
            if re.fullmatch(r"index-?\d*", text, re.I) or CONTENTS.match(text):
                skipping = "contents"  # a printed index or contents points at pages the edition no longer has
                continue
            if LEADERS.search(text) or (seen.get(norm(text), 0) >= 3 and b[1] > 1):
                continue
            if skipping == "front" and b[1] != 1:
                continue
            if skipping == "contents" and outline and b[1] == MINOR:
                continue
            skipping = None
        elif skipping or (b[0] in ("b", "li") and LEADERS.search(b[-2] if b[0] == "li" else b[1])):
            continue
        kept.append(b)
    # A book of one chapter (or none) is chaptered by its sections instead.
    if not outline and sum(1 for b in kept if b[0] == "h" and b[1] == 1) <= 1:
        kept = [("h", 1, b[2], True) if b[0] == "h" and SECTION.match(b[2] or "") else b for b in kept]
    return kept


def picture_first(blocks: list[tuple], index: Index) -> list[tuple]:
    """Each index entry's first figure moves up to sit under its heading, before the words about it."""
    out = list(blocks)
    chapter = ""
    i = 0
    while i < len(out):
        b = out[i]
        if b[0] == "h" and b[1] == 1:
            chapter = b[2] or ""
        if b[0] == "h" and b[1] >= index.level and b[1] != MINOR and in_scope(chapter, index) and leaf(out, i):
            words = norm(tidy_heading(b[2] or ""))
            j = None
            # The figure whose caption names this entry, if one comes before the chapter ends ...
            for k in range(i + 1, min(len(out), i + 80)):
                if out[k][0] == "h" and out[k][1] == 1:
                    break
                if out[k][0] == "fig" and len(words) >= 3 and re.search(
                        rf"(^|\s){re.escape(words)}(\s|$)", re.sub(r"^figure \d+ \d+ ", "", norm(out[k][5] or ""))):
                    j = k
                    break
            # ... else the first figure before the next heading.
            if j is None:
                k = i + 1
                while k < len(out) and out[k][0] not in ("h", "fig"):
                    k += 1
                j = k if k < len(out) and out[k][0] == "fig" else None
            if j is not None and j > i + 1:
                out.insert(i + 1, out.pop(j))
        i += 1
    return out


def leaf(blocks: list[tuple], i: int) -> bool:
    """True when the heading at i has no smaller heading under it."""
    level = blocks[i][1]
    for b in blocks[i + 1:]:
        if b[0] == "h":
            return b[1] <= level or b[1] == MINOR
    return True


def in_scope(chapter: str, index: Index) -> bool:
    return not index.within or any(w.lower() in chapter.lower() for w in index.within)


def chapters_of(blocks: list[tuple], book: Book) -> list[Chapter]:
    """The blocks into chapters. Anchors are numbered across the book, so chapters can merge freely."""
    chapters = [Chapter(book.title)]
    anchors = 0
    open_list: str | None = None
    pending: list[tuple[str, int]] = []  # headings still waiting for their first figure: (anchor, level)
    for block in blocks:
        ch = chapters[-1]
        if open_list and not (block[0] == "li" and block[1] == open_list):
            ch.parts.append(f"</{open_list}>")
            open_list = None
        if block[0] == "h":
            level, text = block[1], block[2]
            text = tidy_heading(text)
            if not re.search(r"\w", text):
                continue
            pending = [(a, lv) for a, lv in pending if lv < level]
            if level == 1:
                chapters.append(Chapter(text, [f"<h1>{html.escape(text)}</h1>"]))
                continue
            anchors += 1
            tag = min(level, 5) if level != MINOR else 5
            if level != MINOR:
                ch.subheads.append((f"a{anchors}", text, level))
                pending.append((f"a{anchors}", level))
                if len(block) < 4 or block[3]:
                    ch.known.add(f"a{anchors}")
            ch.parts.append(f'<h{tag} id="a{anchors}">{html.escape(text)}</h{tag}>')
        elif block[0] == "li":
            if not open_list:
                ch.parts.append(f"<{block[1]}>")
                open_list = block[1]
            ch.parts.append(block[2])
            ch.words += block[3]
        else:
            if block[0] == "fig":
                for a, _ in pending:
                    ch.anchor_figure.setdefault(a, block[3])
                pending = []
                ch.figures.append((block[3], block[5]))
            ch.parts.append(block[1])
            ch.words += block[2]
    if open_list:
        chapters[-1].parts.append(f"</{open_list}>")
    chapters = merge_small(chapters)
    for ch in chapters:
        match_captions(ch)
    return chapters


def match_captions(ch: Chapter) -> None:
    """A heading's picture is the figure whose caption names it ("Figure 2-8. Bowline"), when there is one;
    the first figure after it is only a guess."""
    for anchor, title, _ in ch.subheads:
        words = norm(title)
        if len(words) < 3:
            continue
        hits = [img for img, cap in ch.figures
                if re.search(rf"(^|\s){re.escape(words)}(\s|$)", re.sub(r"^figure \d+ \d+ ", "", norm(cap)))]
        exact = [img for img, cap in ch.figures if re.sub(r"^figure [\d ]+", "", norm(cap)).strip() == words]
        if exact or hits:
            ch.anchor_figure[anchor] = (exact or hits)[0]


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
            prev.anchor_figure.update(ch.anchor_figure)
            prev.known |= ch.known
            prev.figures += ch.figures
            prev.words += ch.words
        else:
            out.append(ch)
    if len(out) > 1 and out[0].words < 40 and not any("<figure" in p for p in out[0].parts):
        out.pop(0)
    return out


def link_figures(chapters: list[Chapter]) -> int:
    """"see Figure 2-2" in the text becomes a link to the figure, in whichever chapter it is."""
    where = {}
    for i, ch in enumerate(chapters, 1):
        for p in ch.parts:
            for m in re.finditer(r'<figure id="fig-([^"]+)"', p):
                where.setdefault(m.group(1), i)
    linked = 0
    for i, ch in enumerate(chapters, 1):
        for k, p in enumerate(ch.parts):
            if not p.startswith(("<p", "<li")):
                continue

            def link(m: re.Match) -> str:
                nonlocal linked
                number = m.group(2).replace("–", "-")
                if number not in where:
                    return m.group(0)
                linked += 1
                href = "" if where[number] == i else f"c{where[number]:03d}.xhtml"
                return f'<a href="{href}#fig-{number}">{m.group(0)}</a>'
            ch.parts[k] = FIGURE_REF.sub(link, p)
    return linked


def opening(book: Book, chapters: list[Chapter]) -> str:
    """The page the book opens on: what it is, where to start, and what is inside."""
    where = {}  # heading words -> (href, title, level, chapter index)
    for i, ch in enumerate(chapters, 1):
        where.setdefault(norm(ch.title), (f"c{i:03d}.xhtml", ch.title, 1, i))
        for anchor, title, level in ch.subheads:
            where.setdefault(norm(title), (f"c{i:03d}.xhtml#{anchor}", title, level, i))
    body = [f'<div class="opening"><h1>{html.escape(book.title)}</h1>',
            f'<p class="byline">{html.escape(book.author)}</p>']
    if book.notice:
        body.append(f'<div class="warn notice"><p>{html.escape(book.notice)}</p></div>')
    if book.about:
        body.append(f'<p class="about">{html.escape(book.about)}</p>')
    starts = [where[norm(t)] for t in book.start_here if norm(t) in where]
    if starts:
        body.append('<h2>Read first</h2><ul class="start">')
        body += [f'<li><a href="{h}">{html.escape(t)}</a></li>' for h, t, _, _ in starts]
        body.append("</ul>")
    ix = book.index
    if ix:
        body.append(f"<h2>{html.escape(ix.title)}</h2>")
        if ix.pictures:
            group = None
            tiles: list[str] = []
            for i, ch in enumerate(chapters, 1):
                if not in_scope(ch.title, ix):
                    continue
                heads = ch.subheads
                for k, (anchor, title, level) in enumerate(heads):
                    is_leaf = k + 1 == len(heads) or heads[k + 1][2] <= level
                    if ix.group_level and level == ix.group_level:
                        if tiles:
                            body.append(f'<div class="tiles">{"".join(tiles)}</div>')
                            tiles = []
                        group = title
                        body.append(f"<h3>{html.escape(group)}</h3>")
                    if level < ix.level or not is_leaf or anchor not in ch.anchor_figure:
                        continue
                    tiles.append(f'<a class="tile" href="c{i:03d}.xhtml#{anchor}"><img src="../'
                                 f'{ch.anchor_figure[anchor]}" alt=""/><span>{html.escape(title)}</span></a>')
            if tiles:
                body.append(f'<div class="tiles">{"".join(tiles)}</div>')
        else:
            for i, ch in enumerate(chapters, 1):
                if not in_scope(ch.title, ix):
                    continue
                heads = [(a, t, lv) for a, t, lv in ch.subheads if a in ch.known]
                entries = [(a, t) for k, (a, t, lv) in enumerate(heads)
                           if lv >= ix.level and (k + 1 == len(heads) or heads[k + 1][2] <= lv)
                           and not re.fullmatch(r"general|introduction", t, re.I)]
                seen: set[str] = set()  # a heading the book prints twice ("SALADS" over two pages) is listed once
                entries = [(a, t) for a, t in entries if not (norm(t) in seen or seen.add(norm(t)))]
                if not entries:
                    continue
                body.append(f'<h3><a href="c{i:03d}.xhtml">{html.escape(ch.title)}</a></h3><ul class="entries">')
                body += [f'<li><a href="c{i:03d}.xhtml#{a}">{html.escape(t)}</a></li>' for a, t in entries]
                body.append("</ul>")
    body.append("</div>")
    return "".join(body)


def doc_item(doc, cref: str):
    return doc.texts[int(cref.rsplit("/", 1)[1])]


def to_xhtml_fragment(markup: str) -> str:
    import lxml.html
    from lxml import etree
    root = lxml.html.fragment_fromstring(markup, create_parent="div")
    return "".join(etree.tostring(child, method="xml", encoding="unicode") for child in root)


def nav_list(href: str, subheads: list[tuple[str, str, int]]) -> str:
    """The headings under a chapter, nested by level."""
    if not subheads:
        return ""
    top = min(level for _, _, level in subheads)
    out: list[str] = []
    i = 0
    while i < len(subheads):
        anchor, title, level = subheads[i]
        j = i + 1
        while j < len(subheads) and subheads[j][2] > level:
            j += 1
        inner = nav_list(href, subheads[i + 1:j]) if j > i + 1 else ""
        if level == top or not out:
            out.append(f'<li><a href="{href}#{anchor}">{html.escape(title)}</a>{inner}</li>')
        else:
            out.append(f'<li><a href="{href}#{anchor}">{html.escape(title)}</a>{inner}</li>')
        i = j
    return f"<ol>{''.join(out)}</ol>"


def page(title: str, lang: str, body: str) -> str:
    return (f'<?xml version="1.0" encoding="utf-8"?>\n<!DOCTYPE html>\n'
            f'<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" '
            f'lang="{lang}" xml:lang="{lang}"><head><meta charset="utf-8"/><title>{html.escape(title)}</title>'
            f'<link rel="stylesheet" type="text/css" href="../style.css"/></head><body>{body}</body></html>')


def build(book: Book, work: Path, out: Path) -> dict:
    from docling_core.types.doc.document import DoclingDocument
    images: dict[str, tuple[bytes, str]] = {}
    blocks: list[tuple] = []
    problems: list[str] = []
    for n, source in enumerate(book.sources):
        probe = work / "probe" / (source.probe or f"{book.id}/{n}") / "doc.json"
        doc = DoclingDocument.load_from_json(probe)
        blocks += blocks_of(doc, source, work / source.pdf, book, images, problems)
    if book.picture_first and book.index:
        blocks = picture_first(blocks, book.index)
    chapters = chapters_of(blocks, book)
    linked = link_figures(chapters)
    uid = "urn:granger:edition:" + book.id
    stamp = "2026-11-17T00:00:00Z"
    files: list[tuple[str, bytes, str, str]] = []  # (href, data, media type, properties)
    files.append(("cover.jpg", covers.cover_jpeg(book.title, book.author, None, "manuals", "Granger Manuals",
                                                 language=book.language), "image/jpeg", "cover-image"))
    opens = bool(book.notice or book.about or book.start_here or book.index)
    if opens:
        files.append(("text/c000.xhtml", page(book.title, book.language, opening(book, chapters)).encode(),
                      "application/xhtml+xml", ""))
    for i, ch in enumerate(chapters, 1):
        files.append((f"text/c{i:03d}.xhtml", page(ch.title, book.language, drop_repeats("".join(ch.parts))).encode(),
                      "application/xhtml+xml", ""))
    colophon = (f"<h1>About this edition</h1><p>{html.escape(book.title)}, {html.escape(book.author)}.</p>"
                f"<p>Granger made this reading edition from {html.escape(book.made_from)}. The text was reflowed "
                f"for a phone screen and the figures set full width.</p><p>{html.escape(book.rights)}</p>")
    files.append(("text/colophon.xhtml", page("About this edition", book.language, colophon).encode(),
                  "application/xhtml+xml", ""))
    for name, (data, ext) in images.items():
        files.append((name, data, "image/png" if ext == "png" else "image/jpeg", ""))
    files.append(("style.css", STYLE.encode(), "text/css", ""))

    items = []
    if opens:
        items.append('<li><a href="text/c000.xhtml">Start here</a></li>')
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
    for p in problems:
        print(f"{book.id}: {p}", file=sys.stderr)
    return {"id": book.id, "chapters": len(chapters), "figures": len(images), "figure_links": linked,
            "table_problems": len(problems),
            "words": sum(c.words for c in chapters), "bytes": out.stat().st_size,
            "sha256": hashlib.sha256(out.read_bytes()).hexdigest()}


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("ids", nargs="*")
    ap.add_argument("--books", default=str(HERE / "books.json"))
    ap.add_argument("--work", default=str(WORK))
    a = ap.parse_args()
    for d in json.load(open(a.books)):
        book = Book.of(d)
        if a.ids and book.id not in a.ids:
            continue
        print(json.dumps(build(book, Path(a.work), Path(a.work) / "editions" / f"{book.id}.epub")))


if __name__ == "__main__":
    main()
