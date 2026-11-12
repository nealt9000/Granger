"""Plan LL1: a Classics EPUB's chapters as plain text with stable offsets (Mac only).

A chapter's text is its block elements' text (whitespace collapsed) joined by newlines; mentions are offsets into it,
and the phone places them by quote + the words before (plan Decision 4), so the exact joining doesn't matter there.
"""
import re
import zipfile
from pathlib import PurePosixPath

from lxml import etree

NS = {"opf": "http://www.idpf.org/2007/opf", "x": "http://www.w3.org/1999/xhtml"}
BLOCKS = {"p", "h1", "h2", "h3", "h4", "h5", "h6", "li", "dd", "dt", "td", "pre"}
SKIP = ("cover", "nav", "colophon", "toc", "titlepage", "imprint", "copyright")


def spine(z: zipfile.ZipFile):
    container = etree.fromstring(z.read("META-INF/container.xml"))
    opf_path = container.find(".//{urn:oasis:names:tc:opendocument:xmlns:container}rootfile").get("full-path")
    opf = etree.fromstring(z.read(opf_path))
    base = PurePosixPath(opf_path).parent
    items = {i.get("id"): i.get("href") for i in opf.findall(".//opf:manifest/opf:item", NS)}
    for ref in opf.findall(".//opf:spine/opf:itemref", NS):
        href = items[ref.get("idref")]
        if not any(s in href.lower() for s in SKIP):
            yield str(base / href) if str(base) != "." else href


def blocks(xhtml: bytes):
    """The text of each block element, in order; a block inside another block (a <p> in an <li>) counts once."""
    root = etree.fromstring(xhtml, etree.XMLParser(recover=True, resolve_entities=False))
    for el in root.iter():
        tag = etree.QName(el).localname if isinstance(el.tag, str) else None
        if tag not in BLOCKS:
            continue
        if any(etree.QName(a).localname in BLOCKS for a in el.iterancestors() if isinstance(a.tag, str)):
            continue
        t = re.sub(r"\s+", " ", "".join(el.itertext())).strip()
        if t:
            yield tag, t


def chapters(epub_path):
    """(href, [(tag, block text)…]) per spine document with text."""
    with zipfile.ZipFile(epub_path) as z:
        for href in spine(z):
            bs = list(blocks(z.read(href)))
            if bs:
                yield href, bs


def title_of(epub_path):
    """The book's dc:title."""
    with zipfile.ZipFile(epub_path) as z:
        container = etree.fromstring(z.read("META-INF/container.xml"))
        opf_path = container.find(".//{urn:oasis:names:tc:opendocument:xmlns:container}rootfile").get("full-path")
        t = etree.fromstring(z.read(opf_path)).find(".//{http://purl.org/dc/elements/1.1/}title")
        return (t.text or "").strip() if t is not None else ""
