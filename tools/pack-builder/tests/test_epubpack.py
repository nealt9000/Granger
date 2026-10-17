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


def chapter1(tmp_path, html):
    return build(tmp_path, work(html=html)).read("OEBPS/c001.xhtml").decode()


def test_named_anchors_become_ids(tmp_path):
    page = chapter1(tmp_path, '<p><a name="x"></a>hi <a href="#x">go</a> <a name="p" id="q">both</a> '
                              '<a href="#p">p</a></p>')
    assert 'id="x"' in page and "name=" not in page
    assert 'id="p"' in page and 'id="q"' in page and 'href="#p"' in page


def test_ids_inside_page_numbers_survive_so_links_to_them_resolve(tmp_path):
    page = chapter1(tmp_path, '<p>one <span class="pagenum"><a name="Page_5" id="Page_5">[5]</a></span> two</p>'
                              '<p><a href="#Page_5">page 5</a></p>')
    assert "[5]" not in page and "pagenum" not in page
    assert 'id="Page_5"' in page and 'href="#Page_5"' in page


def test_page_number_id_inside_a_list_moves_to_the_list(tmp_path):
    page = chapter1(tmp_path, '<ul><li>a</li><span class="pagenum" id="Page_9">9</span><li>b</li></ul>'
                              '<p><a href="#Page_9">9</a></p>')
    assert "<ul id=\"Page_9\"" in page and "<ul><span" not in page


def test_links_to_missing_targets_are_unwrapped(tmp_path):
    from chapters import Chapter
    w = work(html="<p>x</p>")
    w.chapters = [Chapter("A", '<p>see <a href="c002.xhtml#gone">Z</a> and <a href="c002.xhtml#here">H</a> '
                               '<a href="c009.xhtml">N</a> <a href="https://example.org/">E</a></p>'),
                  Chapter("B", '<p id="here">b</p>')]
    page = build(tmp_path, w).read("OEBPS/c001.xhtml").decode()
    assert "#gone" not in page and "see Z and" in page and "c009" not in page
    assert 'href="c002.xhtml#here"' in page and 'href="https://example.org/"' in page


def test_obsolete_presentational_attributes_are_dropped(tmp_path):
    page = chapter1(tmp_path, '<p align="center">a</p><table border="1" cellpadding="3"><colgroup>'
                              '<col align="left"/></colgroup><tr><td valign="top" width="50%">c</td></tr></table>')
    for attr in ("align=", "border=", "cellpadding=", "valign=", "width="):
        assert attr not in page


def test_stray_definition_items_are_wrapped_in_a_list(tmp_path):
    page = chapter1(tmp_path, "<dt>1.</dt><dd>one</dd><dt>2.</dt><dd>two</dd><p>after</p>")
    body = page.split("<body>")[1]
    assert body.startswith("<dl><dt>1.</dt><dd>one</dd><dt>2.</dt><dd>two</dd></dl><p>after</p>")
    chapter = chapter1(tmp_path, "<dl><dt>a</dt><dd>b</dd></dl>")
    assert chapter.count("<dl>") == 1


def test_broken_inline_styles_lose_only_the_broken_declaration(tmp_path):
    page = chapter1(tmp_path, "<p><span style=\"font-style: 'italic\">x</span> "
                              "<span style=\"margin-left: 2em; font-style: 'italic\">y</span></p>")
    assert "'italic" not in page and 'style="margin-left: 2em"' in page


def test_definition_list_cut_between_term_and_definition_stays_valid(tmp_path):
    ends_with_term = chapter1(tmp_path, "<dt>1.</dt><dd>one</dd><dt>2.</dt>").split("<body>")[1]
    assert ends_with_term.startswith("<dl><dt>1.</dt><dd>one</dd><dt>2.</dt><dd/></dl>")
    starts_with_definition = chapter1(tmp_path, "<dd>two</dd><dt>3.</dt><dd>three</dd>").split("<body>")[1]
    assert starts_with_definition.startswith("<dl><dt/><dd>two</dd><dt>3.</dt><dd>three</dd></dl>")
