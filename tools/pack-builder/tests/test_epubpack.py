import io
import re
import zipfile

from lxml import etree

import epubpack
from chapters import split
from conftest import fixture
from epubpack import BookMeta
from gutenberg import clean
from zimpack import Work

OPF = "{http://www.idpf.org/2007/opf}"
DC = "{http://purl.org/dc/elements/1.1/}"
XHTML = "{http://www.w3.org/1999/xhtml}"


def work(slug="modern", title="The Modern Book", author="A. Writer", translator=None, html=None, styles=""):
    if html is None:
        cleaned = clean(fixture("pg-modern.html"))
        chapters = split(cleaned.body, page_href=epubpack.chapter_href)
        styles = cleaned.styles
    else:
        from bs4 import BeautifulSoup
        chapters = split(BeautifulSoup(f"<div>{html}</div>", "lxml").div, page_href=epubpack.chapter_href)
    return Work(slug=slug, title=title, author=author, translator=translator, published=1920, section="novels",
                chapters=chapters, styles=styles)


def meta(w, **kw):
    fields = dict(id=epubpack.book_id(w.slug), title=w.title, author=w.author, translator=w.translator,
                  published=w.published, section_title="Novels", language="en",
                  source_url="https://www.gutenberg.org/ebooks/1", date="2026-10-17")
    fields.update(kw)
    return BookMeta(**fields)


def build(tmp_path, w, **kw):
    out = tmp_path / "book.epub"
    epubpack.write_epub(out, w, meta(w, **kw), b"\xff\xd8jpeg")
    return zipfile.ZipFile(out)


def test_two_korans_have_distinct_titles():
    def koran(translator):
        return Work("k", "The Koran", "", translator, 1880, "scripture", [])
    assert epubpack.display_title(koran("J. M. Rodwell")) == "The Koran (tr. J. M. Rodwell)"
    assert epubpack.display_title(koran("George Sale")) == "The Koran (tr. George Sale)"
    assert epubpack.display_title(Work("i", "Iliad", "Homer", "Pope", 1900, "x", [])) == "Iliad"


def test_scrub_css_drops_colours_fonts_and_pagenum():
    css = ("body { background: initial; color: initial } a { color: blue } .pagenum { position: absolute } "
           "p { margin: 0 }")
    assert epubpack.scrub_css(css) == "p { margin: 0 }"


def test_scrub_css_keeps_other_declarations_and_drops_background_variants():
    css = "h1 { font-family: serif; background-color: red; text-align: center; font-size: 2em }"
    assert epubpack.scrub_css(css) == "h1 { text-align: center; font-size: 2em }"


def test_to_xhtml_is_well_formed():
    out = epubpack.to_xhtml("<p>a<br>b &nbsp;&amp; <img src=x alt=y></p>tail <b>bold")
    etree.fromstring(f"<div>{out}</div>")


def test_book_id():
    assert epubpack.book_id("hamlet") == "gc-hamlet"


def test_epub_layout(tmp_path):
    w = work()
    w.chapters[0].html += '<span class="pagenum">[7]</span>'
    z = build(tmp_path, w)
    first = z.infolist()[0]
    assert first.filename == "mimetype" and first.compress_type == zipfile.ZIP_STORED
    assert z.read("mimetype") == b"application/epub+zip"
    assert "META-INF/container.xml" in z.namelist()

    opf = etree.fromstring(z.read("OEBPS/content.opf"))
    assert opf.get("version") == "3.0"
    text = lambda tag: [e.text for e in opf.iter(DC + tag)]
    assert text("identifier") == ["urn:granger:gc-modern"]
    assert text("title") == ["The Modern Book"]
    assert text("creator") == ["A. Writer"] and text("date") == ["1920"]
    assert text("subject") == ["Novels"] and text("language") == ["en"]
    assert text("publisher") == ["Granger"] and text("rights") == ["Public domain in the USA."]
    assert text("source") == ["https://www.gutenberg.org/ebooks/1"]
    props = {m.get("property"): m.text for m in opf.iter(OPF + "meta") if m.get("property")}
    assert props["belongs-to-collection"] == "Granger Classics"
    assert props["dcterms:modified"].startswith("2026-10-17T")
    assert props["role"] == "aut"
    cover = [i for i in opf.iter(OPF + "item") if i.get("properties") == "cover-image"]
    assert len(cover) == 1 and z.read("OEBPS/" + cover[0].get("href")) == b"\xff\xd8jpeg"

    nav = z.read("OEBPS/nav.xhtml").decode()
    for chapter in w.chapters:
        assert chapter.title in nav
    assert 'epub:type="landmarks"' in nav
    assert "OEBPS/toc.ncx" in z.namelist() and "OEBPS/cover.xhtml" in z.namelist()
    assert "book.css" in z.read("OEBPS/c001.xhtml").decode()

    pages = [n for n in z.namelist() if re.fullmatch(r"OEBPS/c\d{3}\.xhtml", n)]
    assert len(pages) == len(w.chapters) == 5
    for name in pages:
        page = z.read(name).decode()
        etree.fromstring(page.encode())
        assert 'class="nav"' not in page and "pagenum" not in page


def test_translator_is_a_contributor_and_no_author_means_no_creator(tmp_path):
    w = work(title="The Koran", author="", translator="George Sale")
    opf = etree.fromstring(build(tmp_path, w).read("OEBPS/content.opf"))
    assert [e.text for e in opf.iter(DC + "title")] == ["The Koran (tr. George Sale)"]
    assert [e.text for e in opf.iter(DC + "contributor")] == ["George Sale"]
    assert not list(opf.iter(DC + "creator"))
    roles = [m.text for m in opf.iter(OPF + "meta") if m.get("property") == "role"]
    assert roles == ["trl"]


def test_book_css_holds_scrubbed_work_styles(tmp_path):
    w = work(styles=".stanza { margin: 1em 0; color: red } .pagenum { display: none }")
    css = build(tmp_path, w).read("OEBPS/book.css").decode()
    assert epubpack.BOOK_CSS in css and ".stanza { margin: 1em 0 }" in css
    assert "red" not in css and "pagenum" not in css


def test_cross_chapter_links_point_at_xhtml_files(tmp_path):
    w = work(html='<h2>A</h2><p><a href="#z">to Z</a></p><h2>B</h2><p>b</p><h2 id="z">C</h2><p>c</p>')
    z = build(tmp_path, w)
    assert 'href="c003.xhtml#z"' in z.read("OEBPS/c001.xhtml").decode()
    assert 'id="z"' in z.read("OEBPS/c003.xhtml").decode()


def test_chapter_titles_in_nav_are_clean(tmp_path):
    w = work(html="".join(f"<h2>The Modern Book — Part {i} &lt;{i}&gt;</h2><p>t</p>" for i in range(1, 4)))
    nav = build(tmp_path, w).read("OEBPS/nav.xhtml").decode()
    assert "Part 1</a>" in nav and "&lt;1&gt;" not in nav and "The Modern Book —" not in nav
