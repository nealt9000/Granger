"""The library's chunk of a book that holds a dataset answer's quote, from the EPUB on the Mac (Plan R2 LoRA, 2026-11-04).

Mirrors core/books Chunking.kt (windows of paragraphs: close at 400 characters, never over 2,000) and AnswerPrompt's
1,200-character cut, so the passage is what the app would have put in the prompt had retrieval chosen it.
"""
import glob
import html
import os
import re
import zipfile
from html.parser import HTMLParser

MIN_CHUNK, MAX_CHUNK, TEXT_CHARS = 400, 2000, 1200
BOOKS = [os.path.expanduser("~/.cache/granger/eval-books/by-ref"), os.path.expanduser("~/.cache/granger/eval-books/own"),
         os.path.expanduser("~/.cache/granger/eval-books/dl")]
BLOCKS = {"p", "h1", "h2", "h3", "h4", "h5", "h6", "li", "div", "blockquote", "td", "th", "dd", "dt", "pre", "tr", "section", "br"}


class _Blocks(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.blocks, self.cur, self.skip = [], [], 0

    def handle_starttag(self, tag, attrs):
        if tag in ("script", "style", "head", "title"):
            self.skip += 1
        if tag in BLOCKS:
            self._flush()

    def handle_endtag(self, tag):
        if tag in ("script", "style", "head", "title"):
            self.skip = max(0, self.skip - 1)
        if tag in BLOCKS:
            self._flush()

    def handle_data(self, data):
        if not self.skip:
            self.cur.append(data)

    def _flush(self):
        text = re.sub(r"\s+", " ", "".join(self.cur)).strip()
        if text:
            self.blocks.append(text)
        self.cur = []

    def close(self):
        super().close()
        self._flush()


def cut_at_spaces(text, max_len):
    out = []
    while len(text) > max_len:
        head = text[:max_len]
        sp = head.rfind(" ")
        if sp <= 0:
            sp = max_len
        out.append(text[:sp].rstrip())
        text = text[sp:].lstrip()
    out.append(text)
    return out


def windows(blocks):
    texts, buf = [], ""
    for own in blocks:
        if not own:
            continue
        for piece in cut_at_spaces(own, MAX_CHUNK):
            buf = piece if not buf else buf + "\n" + piece
            if len(buf) > MAX_CHUNK:
                parts = cut_at_spaces(buf, MAX_CHUNK)
                texts.extend(parts[:-1])
                buf = parts[-1]
            if len(buf) >= MIN_CHUNK:
                texts.append(buf)
                buf = ""
    if buf:
        texts.append(buf)
    return texts


def cut(text, max_len=TEXT_CHARS):
    if len(text) <= max_len:
        return text
    head = text[:max_len]
    sp = max(i for i, ch in enumerate(head) if ch.isspace()) if any(ch.isspace() for ch in head) else -1
    return (head[:sp] if sp > 0 else head).rstrip() + "…"


def epub_for(source):
    """`book:granger:gc-x` -> by-ref/gc-x.epub; `book:import:<sha>` and gutenberg ids are looked up by name in own/ and dl/."""
    kind, _, name = source.partition(":")[2].partition(":")
    for d in BOOKS:
        for p in glob.glob(os.path.join(d, "*")):
            base = os.path.basename(p)
            if base == name + ".epub" or base.startswith(name + ".") or base.startswith(name + "-") or base == name:
                return p
    return None


def chapter_blocks(epub, location):
    with zipfile.ZipFile(epub) as z:
        names = [n for n in z.namelist() if n == location or n.endswith("/" + location) or n.endswith(location)]
        if not names:
            return None
        parser = _Blocks()
        parser.feed(z.read(names[0]).decode("utf-8", "replace"))
        parser.close()
        return parser.blocks


def _norm(s):
    return re.sub(r"[^a-z0-9]+", " ", html.unescape(s).lower()).strip()


def chunk_with_quote(source, location, quote):
    """(chunk text as the prompt shows it, index, quote within the shown text) or None."""
    epub = epub_for(source)
    if not epub:
        return None
    blocks = chapter_blocks(epub, location)
    if blocks is None:
        return None
    q = _norm(quote)
    for i, w in enumerate(windows(blocks)):
        if q in _norm(w):
            shown = cut(w)
            return shown, i, q in _norm(shown)
    return None
