"""Builds the two tiny ZIMs tools/bookpacks' tests read (Plan KB2):

    ~/.venvs/granger-pack/bin/python tools/test-zim/make_bookpacks_zim.py tools/bookpacks/testdata

- `zimit-books.zim`, a scraped site in the shapes the audit met:
  - a table of PDFs whose links say "PDF 1 mb" (Survivor Library);
  - a download page serving one file, reached from a book page (Milne);
  - a manual in two parts with no whole file (Military Medicine);
  - a generic link under a heading (SMBC);
  - a PDF named only by its own Info title;
  - a small PDF that `minBytes` leaves out.
- `kolibri-books.zim`: two stories in two languages with levels (African Storybook).

Clusters are stored uncompressed so the tests need no zstandard.
"""
import sys
from pathlib import Path

from libzim.writer import Compression, Creator

from make_test_zim import Entry, tiny_pdf


def pdf(text, info_title=None, pad=0):
    body = tiny_pdf(text)
    if info_title:  # an Info dictionary, as authoring tools write it
        body += b"6 0 obj\n<< /Title (%s) /Producer (Test) >>\nendobj\n" % info_title.encode()
    return body + b"%" + b"x" * pad + b"\n"


def html(title, body):
    return f'<!DOCTYPE html><html><head><meta charset="utf-8"><title>{title}</title></head><body>{body}</body></html>'


SITE = "www.example.org"
CATEGORY = html("Library-Farming_and_Gardens", """
<table><thead><tr><th>DateAdded</th><th>Title</th><th>Size</th><th>Link</th></tr></thead><tbody>
<tr><td>2026-07-01</td><td>A_Treatise_On_Hedges_1856</td><td>1 mb</td>
    <td><a href="../../library/a_treatise_on_hedges_1856.pdf" download>PDF 1 mb</a></td></tr>
<tr><td>2026-07-01</td><td>Bee_Keeping_For_All_1902</td><td>2 mb</td>
    <td><a href="../../library/bee_keeping_for_all_1902.pdf" download>PDF 2 mb</a></td></tr>
</tbody></table>""")
BOOK_PAGE = html("Field Radio Basics – Example Press", """
<h1>Field Radio Basics</h1><p>An open textbook.</p>
<a href="../../download/field-radio-basics/">Download PDF (2MB)</a>""")
DOWNLOAD_PAGE = html("Download Page – Example Press", """
<p>Your download should start. If it doesn't, <a href="https://www.example.org/files/field-radio.pdf?tmstv=1786&nonce=ab12">please click here to start it manually</a>.</p>""")
MANUALS = html("Manuals", """
<h2>Water Purification</h2>
<ul><li><a href="files/water-pt1.pdf">Part 1</a></li><li><a href="files/water-pt2.pdf">Part 2</a></li></ul>
<h2>Knots: A Visual Guide</h2><p><a href="files/knots.pdf">Download PDF</a></p>
<h2>Leaflets</h2><p><a href="files/leaflet.pdf">Leaflet</a></p>""")


def build_zimit(out: Path) -> None:
    out.unlink(missing_ok=True)
    with Creator(out).config_compression(Compression.none) as c:
        c.set_mainpath(f"{SITE}/index.php/library-farming_and_gardens/")
        c.add_item(Entry(f"{SITE}/index.php/library-farming_and_gardens/", "Library", "text/html", CATEGORY, True))
        c.add_item(Entry(f"{SITE}/books/field-radio/", "Field Radio", "text/html", BOOK_PAGE, True))
        c.add_item(Entry(f"{SITE}/download/field-radio-basics/", "Download", "text/html", DOWNLOAD_PAGE, True))
        c.add_item(Entry(f"{SITE}/manuals/", "Manuals", "text/html", MANUALS, True))
        for path, body in [
            ("library/a_treatise_on_hedges_1856.pdf", pdf("Hedges.", pad=3000)),
            ("library/bee_keeping_for_all_1902.pdf", pdf("Bees.", pad=3000)),
            ("files/field-radio.pdf?tmstv=1786&nonce=ab12", pdf("Radio.", pad=3000)),
            ("manuals/files/water-pt1.pdf", pdf("Boil.", info_title="Water Purification (Part 1 of 2)", pad=3000)),
            ("manuals/files/water-pt2.pdf", pdf("Filter.", info_title="Water Purification (Part 2 of 2)", pad=3000)),
            ("manuals/files/knots.pdf", pdf("Bowline.", pad=3000)),
            ("manuals/files/leaflet.pdf", pdf("Small.")),
            ("unlinked/sea-survival.pdf", pdf("Sea.", info_title="Sea Survival Handbook", pad=3000)),
        ]:
            c.add_item(Entry(f"{SITE}/{path}", "", "application/pdf", body, False))
        for name, value in [("Title", "Example Library"), ("Language", "eng"), ("Creator", "Example"),
                            ("Name", "example.org_en_all"), ("Date", "2026-10-01"),
                            ("Scraper", "warc2zim 2.3.1,zimit 3.1.3")]:
            c.add_metadata(name, value)


def story(node, title, desc, author, language, level, epub):
    return html(title, f"""
<div class="container"><nav><ol class="breadcrumb">
<li class="breadcrumb-item"><a href="./root0000000000000000000000000000">Storybooks</a></li>
<li class="breadcrumb-item"><a href="./lang">{language}</a></li>
<li class="breadcrumb-item"><a href="./lvl">{level}</a></li>
<li class="breadcrumb-item active" aria-current="page">{title}</li></ol></nav>
<h1>{title}</h1><p>{desc}</p><p>Author: {author}</p><p>License: CC BY by Example Storybooks</p>
<ul class="nav nav-tabs"><li class="nav-item"><a class="nav-link active" href="{node}">Main document (EPUB)</a></li>
<li class="nav-item nav-btn"><a class="btn btn-primary" href="{epub}">Download EPUB</a></li></ul></div>""")


def build_kolibri(out: Path) -> None:
    out.unlink(missing_ok=True)
    epub = b"PK\x03\x04" + b"mimetypeapplication/epub+zip" + b"0" * 2000
    with Creator(out).config_compression(Compression.none) as c:
        c.set_mainpath("root0000000000000000000000000000")
        c.add_item(Entry("root0000000000000000000000000000", "Storybooks", "text/html",
                         html("Storybooks", '<a href="./lang">English</a>'), True))
        c.add_item(Entry("a1111111111111111111111111111111", "The Hungry Goat", "text/html",
                         story("a1111111111111111111111111111111", "The Hungry Goat", "A goat eats everything.",
                               "Ann Writer", "English", "Level 1", "e1.epub"), True))
        c.add_item(Entry("b2222222222222222222222222222222", "Mbuzi Mwenye Njaa", "text/html",
                         story("b2222222222222222222222222222222", "Mbuzi Mwenye Njaa", "Mbuzi anakula kila kitu.",
                               "Ann Writer", "Kiswahili", "Level 2", "e2.epub"), True))
        c.add_item(Entry("e1.epub", "", "application/epub+zip", epub, False))
        c.add_item(Entry("e2.epub", "", "application/epub+zip", epub + b"1", False))
        for name, value in [("Title", "Example Storybooks"), ("Language", "mul"), ("Creator", "Kolibri"),
                            ("Name", "storybooks_mul_all"), ("Date", "2025-01-11")]:
            c.add_metadata(name, value)


if __name__ == "__main__":
    folder = Path(sys.argv[1])
    folder.mkdir(parents=True, exist_ok=True)
    build_zimit(folder / "zimit-books.zim")
    build_kolibri(folder / "kolibri-books.zim")
