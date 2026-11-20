#!/usr/bin/env python3
"""
A reading edition from a Project Gutenberg EPUB (Plan BK): the transcribed text with its pictures, which beats OCR of a
PDF made from it, published as Granger's own public-domain book.

  gutenberg_edition.py PG_EPUB OUT_EPUB --id ID --title T --author A --published YEAR [--note TEXT]

Project Gutenberg's licence lets anyone use the public-domain text, but its name and trademark come only with the
licence. So the edition carries neither: the header, the licence (its own file), the generator tag, the "Project
Gutenberg eBook" line and every other mention of the name go, and it fails if any is left. The transcribers are thanked
by name in a short note at the front instead. Deterministic: entries in a fixed order with a fixed time.
"""
import argparse
import re
import sys
import zipfile
from pathlib import Path

FIXED = (2026, 1, 1, 0, 0, 0)
NAME = re.compile(r"gutenberg", re.I)


def strip_part(html, book_id=""):
    html = re.sub(r'<header class="pg-boilerplate[^"]*".*?</header>', "", html, flags=re.S)
    html = re.sub(r'<meta name="dtb:generator"[^>]*/>', "", html)
    # epubcheck refuses aria-label on the contents nav (as Gutenberg writes it); the nav's own type says what it is.
    html = re.sub(r'(<nav [^>]*?)\s+aria-label="[^"]*"', r"\1", html)
    html = re.sub(r'(<meta name="dtb:uid" content=")[^"]*(")', rf"\g<1>urn:granger:{book_id}\g<2>", html)
    html = re.sub(r'<meta name="generator"[^>]*/>', "", html)
    html = re.sub(r"<p>\s*The Project Gutenberg eBook,.*?</p>", "", html, flags=re.S | re.I)
    return html


def note_page(title, author, published, note, css):
    links = "".join(f'<link rel="stylesheet" type="text/css" href="{c}"/>' for c in css)
    return f"""<?xml version="1.0" encoding="utf-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xml:lang="en" lang="en"><head><meta charset="utf-8"/><title>About this edition</title>{links}</head>
<body><section id="granger-note"><h2>About this edition</h2>
<p><i>{title}</i>, by {author} ({published}): the whole text with its drawings, as a Granger reading edition.</p>
<p>{note}</p>
<p>The book is in the public domain.</p></section></body></html>
"""


def build(src, out, book_id, title, author, published, note):
    zin = zipfile.ZipFile(src)
    names = zin.namelist()
    opf_name = next(n for n in names if n.endswith(".opf"))
    opf = zin.read(opf_name).decode("utf-8")
    base = opf_name.rsplit("/", 1)[0] + "/" if "/" in opf_name else ""
    # The licence is its own spine item ("pg-footer"): it goes, with its manifest entry and file.
    drop = set()
    for m in re.finditer(r'<item [^>]*id="(pg-footer[^"]*)"[^>]*/>', opf):
        href = re.search(r'href="([^"]+)"', m.group(0)).group(1)
        drop.add(base + href)
        opf = opf.replace(m.group(0), "")
        opf = re.sub(rf'<itemref idref="{re.escape(m.group(1))}"\s*/>', "", opf)
    opf = re.sub(r"<dc:title>.*?</dc:title>", f"<dc:title>{title}</dc:title>", opf, flags=re.S)
    opf = re.sub(r"<dc:identifier id=\"id\">.*?</dc:identifier>", f'<dc:identifier id="id">urn:granger:{book_id}</dc:identifier>', opf)
    opf = re.sub(r"<dc:source>.*?</dc:source>\s*", "", opf)
    opf = re.sub(r"<dc:date>.*?</dc:date>", f"<dc:date>{published}</dc:date>", opf)
    opf = re.sub(r"<dc:publisher>.*?</dc:publisher>\s*", "", opf)
    opf = opf.replace("</metadata>", "<dc:publisher>Granger</dc:publisher></metadata>", 1)
    opf = re.sub(r"<meta[^>]*generator[^>]*/>\s*", "", opf)
    css = [re.search(r'href="([^"]+)"', m.group(0)).group(1)
           for m in re.finditer(r'<item [^>]*media-type="text/css"[^>]*/>', opf) if "pgepub" not in m.group(0)]
    # The note page goes after the cover.
    opf = opf.replace("</manifest>", '<item id="granger-note" href="granger-note.xhtml" media-type="application/xhtml+xml"/></manifest>', 1)
    opf = re.sub(r'(<spine[^>]*>\s*<itemref idref="coverpage-wrapper"\s*/>)', r'\1<itemref idref="granger-note"/>', opf, count=1)
    if 'idref="granger-note"' not in opf:
        opf = re.sub(r"(<spine[^>]*>)", r'\1<itemref idref="granger-note"/>', opf, count=1)
    parts = {}
    for n in names:
        if n in drop or n == opf_name:
            continue
        data = zin.read(n)
        if n.endswith((".xhtml", ".html", ".htm", ".ncx")):
            text = strip_part(data.decode("utf-8"), book_id)
            # Contents entries that pointed at the licence file go too.
            for d in drop:
                leaf = re.escape(d.rsplit("/", 1)[-1])
                text = re.sub(rf'<li[^>]*>\s*<a[^>]*href="{leaf}[^"]*"[^>]*>.*?</a>\s*</li>', "", text, flags=re.S)
                text = re.sub(rf'<navPoint[^>]*>(?:(?!</navPoint>).)*?{leaf}.*?</navPoint>', "", text, flags=re.S)
            data = text.encode("utf-8")
        if n.endswith(".css"):  # rules styling the header and licence, gone with them
            data = re.sub(r"[^{}]*gutenberg[^{}]*\{[^}]*\}\s*", "", data.decode("utf-8"), flags=re.I).encode("utf-8")
        parts[n] = data
    parts[base + "granger-note.xhtml"] = note_page(title, author, published, note, css).encode("utf-8")
    parts[opf_name] = opf.encode("utf-8")
    left = [n for n, d in parts.items() if n.endswith((".xhtml", ".opf", ".ncx", ".css")) and NAME.search(d.decode("utf-8", "replace"))]
    if left:
        for n in left:
            for line in parts[n].decode("utf-8").splitlines():
                if NAME.search(line):
                    print(f"  {n}: {line.strip()[:120]}", file=sys.stderr)
        raise SystemExit("the Gutenberg name is still in the edition (above): strip it first")
    with zipfile.ZipFile(out, "w") as z:
        info = zipfile.ZipInfo("mimetype", FIXED)
        z.writestr(info, b"application/epub+zip", zipfile.ZIP_STORED)
        for n in sorted(k for k in parts if k != "mimetype"):
            z.writestr(zipfile.ZipInfo(n, FIXED), parts[n], zipfile.ZIP_DEFLATED)


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("src")
    ap.add_argument("out")
    ap.add_argument("--id", required=True)
    ap.add_argument("--title", required=True)
    ap.add_argument("--author", required=True)
    ap.add_argument("--published", required=True)
    ap.add_argument("--note", default="")
    a = ap.parse_args(argv)
    build(a.src, a.out, a.id, a.title, a.author, a.published, a.note)
    print(a.out)


if __name__ == "__main__":
    main()
