import io
import re
import zipfile

import pytest
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


def test_fixed_widths_in_inline_styles_are_dropped_except_on_media(tmp_path):
    page = chapter1(tmp_path, '<div class="figcenter" style="width: 500px">a</div>'
                              '<p style="width:45%; margin-left: 2em">b</p><table style="WIDTH: 80%"><tr><td>c</td>'
                              '</tr></table><svg style="width: 10em"></svg>')
    assert 'class="figcenter">a' in page and 'style="margin-left: 2em"' in page
    assert "<table>" in page and "width: 10em" in page
    assert "500px" not in page and "45%" not in page and "80%" not in page


# The rules the verse audit found, as Gutenberg's HTML has them (Paradise Lost #20, Hamlet #1524,
# Faerie Queene #15272).
PARADISE_LOST_CSS = """
body { margin-left: 10%; margin-right: 10% }
p { margin-top: 0.2em; text-align: justify; margin-bottom: 0.2em; text-indent: 4% }
p.poem { text-indent: 3%; margin-left: 30% }
p.center { text-align: center; text-indent: 0; margin-top: 1em; margin-bottom: 1em }
"""
HAMLET_CSS = """
h1, h2, h3, h4, h5 { text-align: center; font-style: normal }
p.right { text-align: right }
p.scenedesc { font-style: italic; text-align: center; margin-left: 1em; margin-right: 1em }
div.fig { display: block; margin: 0 auto; text-align: center }
"""
FAERIE_QUEENE_CSS = """
.poem { margin-left: 10%; margin-right: 10%; margin-bottom: 1em; text-align: left }
.poem .stanza { margin: 1em 0 1em 0 }
.poem p { margin: 0; padding-left: 3em; text-indent: -3em }
.poem p.i12 { margin-left: 6em }
.poem p.i20 { margin-left: 10em }
.author { text-align: right; margin-top: -1em; margin-right: 5% }
.center { text-align: center }
.linenum { position: absolute; top: auto; left: 60% }
.sidenote { position: absolute; left: 92%; font-size: smaller; text-align: left }
"""
BOOST = ":root:root:root:root"


def rules(css):
    return [line for line in css.splitlines() if line]


def test_wide_percentage_side_margins_are_clamped():
    css = rules(epubpack.scrub_css(PARADISE_LOST_CSS))
    assert "body { margin-left: min(10%, 2em); margin-right: min(10%, 2em) }" in css
    assert "p.poem { text-indent: 3%; margin-left: min(30%, 2em) }" in css
    assert "p { margin-top: 0.2em; text-align: justify; margin-bottom: 0.2em; text-indent: 4% }" in css
    fq = rules(epubpack.scrub_css(FAERIE_QUEENE_CSS))
    assert (".poem { margin-left: min(10%, 2em); margin-right: min(10%, 2em); margin-bottom: 1em; text-align: left }"
            in fq)
    assert ".author { text-align: right; margin-top: -1em; margin-right: 5% }" in fq       # 5% is narrow enough


def test_shorthand_side_margins_and_paddings_are_clamped():
    css = rules(epubpack.scrub_css("blockquote { margin: 0 20% } .a { padding: 1em 8% 2em 3% } .b { margin: 15% }"
                                   " .c { margin: 1em auto !important } .d { margin-left: 25% !important }"))
    assert css == ["blockquote { margin: 0 min(20%, 2em) }", ".a { padding: 1em min(8%, 2em) 2em 3% }",
                   ".b { margin: min(15%, 2em) }", ".c { margin: 1em auto !important }",
                   ".d { margin-left: min(25%, 2em) !important }"]


def test_deep_em_indents_are_capped():
    css = rules(epubpack.scrub_css(FAERIE_QUEENE_CSS))
    assert ".poem p.i20 { margin-left: min(10em, 30%) }" in css
    assert ".poem p.i12 { margin-left: 6em }" in css
    assert ".poem p { margin: 0; padding-left: 3em; text-indent: -3em }" in css
    assert ".poem .stanza { margin: 1em 0 1em 0 }" in css


def test_centred_and_right_aligned_paragraphs_win_over_the_readers_alignment():
    css = rules(epubpack.scrub_css(HAMLET_CSS))
    assert f"{BOOST} p.right {{ text-align: right !important }}" in css
    assert f"{BOOST} p.scenedesc {{ text-align: center !important }}" in css
    assert not any(BOOST in line and ("h1" in line or "div.fig" in line) for line in css)
    assert "p.right { text-align: right }" in css                       # the rule itself stays
    fq = rules(epubpack.scrub_css(FAERIE_QUEENE_CSS))
    assert f"{BOOST} .center {{ text-align: center !important }}" in fq
    assert f"{BOOST} .author {{ text-align: right !important }}" in fq
    assert not any(BOOST in line and ".poem" in line for line in fq)   # left isn't boosted
    pl = rules(epubpack.scrub_css(PARADISE_LOST_CSS + ".x p, td.c, p:first-child { text-align: end }"))
    assert f"{BOOST} p.center {{ text-align: center !important }}" in pl
    assert f"{BOOST} .x p {{ text-align: end !important }}" in pl
    assert f"{BOOST} p:first-child {{ text-align: end !important }}" in pl
    assert not any(BOOST in line and "td.c" in line for line in pl)
    assert pl.index(f"{BOOST} p.center {{ text-align: center !important }}") > pl.index("p.poem { text-indent: 3%; "
                                                                                     "margin-left: min(30%, 2em) }")


def test_absolutely_positioned_marks_move_to_the_right_margin():
    css = rules(epubpack.scrub_css(FAERIE_QUEENE_CSS))
    note = "float: right; font-size: .75em; opacity: .65; margin-left: .75em"
    assert f".linenum {{ {note} }}" in css
    assert f".sidenote {{ text-align: left; {note} }}" in css
    assert not any("position" in line or "left: 60%" in line or "92%" in line for line in css)
    assert ".x { position: relative; left: 1em }" in epubpack.scrub_css(".x { position: relative; left: 1em }")


def test_dropped_line_numbers_lose_their_rules():
    css = epubpack.scrub_css(FAERIE_QUEENE_CSS + "span.linenum.mckay:before { content: '[' }", line_numbers="drop")
    assert "linenum" not in css and ".sidenote { text-align: left; float: right" in css


def test_inline_centring_on_a_paragraph_wins_over_the_readers_alignment(tmp_path):
    page = chapter1(tmp_path, '<p style="text-align:center">a</p><p style="text-align: right !important">b</p>'
                              '<p style="text-align: left">c</p><div style="text-align: center">d</div>')
    assert 'style="text-align:center !important"' in page and 'style="text-align: right !important"' in page
    assert 'style="text-align: left"' in page and '<div style="text-align: center">' in page


def test_line_numbers_stay_in_the_margin_or_are_dropped(tmp_path):
    html = ('<h2>Canto I</h2><div class="stanza"><a id="l4"></a><p class="i2">fourth line</p>'
            '<span class="linenum" id="n5">5</span><p class="i2">fifth line</p></div>')
    kept = chapter1(tmp_path, html)
    assert '<span class="linenum" id="n5">5</span>' in kept
    w = work(html=html)
    w.line_numbers = "drop"
    dropped = build(tmp_path, w).read("OEBPS/c001.xhtml").decode()
    assert ">5<" not in dropped and "linenum" not in dropped and 'id="n5"' in dropped
    assert "fifth line" in dropped


def test_colophon_is_last_in_the_spine_a_backmatter_landmark_and_not_in_the_contents(tmp_path):
    w = work(translator="T. Ranslator")
    z = build(tmp_path, w, source_line="From Project Gutenberg ebook #1342")
    opf = etree.fromstring(z.read("OEBPS/content.opf"))
    items = {i.get("id"): i for i in opf.iter(OPF + "item")}
    assert items["colophon"].get("href") == "colophon.xhtml" and items["colophon"].get("properties") == "svg"
    spine = [r.get("idref") for r in opf.iter(OPF + "itemref")]
    assert spine[0] == "cover" and spine[1] == "c001" and spine[-1] == "colophon"

    nav = etree.fromstring(z.read("OEBPS/nav.xhtml"))
    navs = {n.get("{http://www.idpf.org/2007/ops}type"): n for n in nav.iter(XHTML + "nav")}
    toc_links = [a.get("href") for a in navs["toc"].iter(XHTML + "a")]
    landmarks = {a.get("{http://www.idpf.org/2007/ops}type"): a.get("href") for a in navs["landmarks"].iter(XHTML + "a")}
    assert "colophon.xhtml" not in toc_links
    assert landmarks["backmatter"] == "colophon.xhtml" and landmarks["bodymatter"] == "c001.xhtml"
    assert "colophon.xhtml" not in z.read("OEBPS/toc.ncx").decode()


def test_colophon_names_the_book_and_its_source(tmp_path):
    w = work(translator="T. Ranslator")
    z = build(tmp_path, w, source_line="From Project Gutenberg ebook #1342")
    page = etree.fromstring(z.read("OEBPS/colophon.xhtml"))
    text = " ".join(" ".join(page.itertext()).split())
    for expected in ("Granger Classics", "The Modern Book", "A. Writer", "Translated by T. Ranslator",
                     "This translation first published 1920", "From Project Gutenberg ebook #1342",
                     "Public domain in the USA.", "The world’s knowledge. Yours to keep."):
        assert expected in text, expected
    svg = page.find(".//{http://www.w3.org/2000/svg}svg")
    assert svg is not None and len(svg.findall("{http://www.w3.org/2000/svg}path")) == 3


def test_colophon_source_line_comes_from_the_caller(tmp_path):
    w = work(author="", title="The World English Bible")
    z = build(tmp_path, w, source_line="From the World English Bible at eBible.org")
    text = z.read("OEBPS/colophon.xhtml").decode()
    assert "From the World English Bible at eBible.org" in text and "Gutenberg" not in text
    assert "This edition first published 1920" in text and 'class="author"' not in text


def test_a_verse_work_gets_verse_lines_and_the_verse_rules_after_its_own(tmp_path):
    html = '<h2>Book I</h2><p class="poem">\nOf Mans First Disobedience<br/>\nOf that Forbidden Tree<br/>\n</p>'
    w = work(html=html, styles="p.poem { margin-left: 30%; text-indent: 2em }")
    w.verse = True
    z = build(tmp_path, w)
    page = z.read("OEBPS/c001.xhtml").decode()
    assert '<p class="poem gv"><span class="gv-l">Of Mans First Disobedience</span>\n<span class="gv-l">' in page
    css = z.read("OEBPS/book.css").decode()
    assert css.index("p.poem {") < css.index(".gv {") < css.index("div.stanza > p")


def test_a_prose_work_keeps_its_line_breaks_and_gets_no_wrapper_rules(tmp_path):
    html = '<h2>Chapter I</h2><p>\nTwinkle, twinkle, little bat!<br/>\nHow I wonder what you’re at!<br/>\n</p>'
    z = build(tmp_path, work(html=html))
    assert "gv" not in z.read("OEBPS/c001.xhtml").decode()
    css = z.read("OEBPS/book.css").decode()
    assert ".gv-l {" in css and "div.stanza" not in css


def test_headings_in_capitals_are_quietened_in_the_page(tmp_path):
    page = chapter1(tmp_path, '<h2><a id="b1"></a><b>BOOK I.</b></h2><h3>SPENSER’S<br/>THE FAERIE QUEENE</h3>'
                              '<h3>SCENE I. A room.</h3><p>THE END.</p>')
    assert '<h2><a id="b1"/><b>Book I</b></h2>' in page
    assert "<h3>Spenser’s<br/>The Faerie Queene</h3>" in page
    assert "<h3>SCENE I. A room.</h3>" in page and "<p>THE END.</p>" in page


def test_title_overrides_name_pages_in_the_contents(tmp_path):
    w = work(html="".join(f"<h2>CANTO {n}</h2><p>t</p>" for n in ("I", "II", "III")))
    w.title_overrides = {"2": "Cantos II–IV"}
    z = build(tmp_path, w)
    nav = z.read("OEBPS/nav.xhtml").decode()
    assert ">Canto I</a>" in nav and ">Cantos II–IV</a>" in nav and ">Canto III</a>" in nav
    assert "<title>The Modern Book — Cantos II–IV</title>" in z.read("OEBPS/c002.xhtml").decode()
    w.title_overrides = {"4": "Canto IV"}  # the book has three pages: the manifest is out of date
    with pytest.raises(ValueError, match=r"pages \['4'\], but the book has 3"):
        build(tmp_path, w)


def test_a_split_definition_list_copy_is_made_valid(tmp_path):
    ends_with_term = chapter1(tmp_path, '<dl class="n"><dt>1.</dt><dd>one</dd><dt>2.</dt></dl>')
    assert "<dt>2.</dt><dd/></dl>" in ends_with_term
    starts_with_definition = chapter1(tmp_path, '<dl class="n"><dd>two</dd><dt>3.</dt><dd>three</dd></dl>')
    assert '<dl class="n"><dt/><dd>two</dd>' in starts_with_definition


def landmark(z):
    nav = etree.fromstring(z.read("OEBPS/nav.xhtml"))
    return next(a.get("href") for a in nav.iter(XHTML + "a") if a.get("{http://www.idpf.org/2007/ops}type") == "bodymatter")


def test_start_of_text_skips_front_matter_pages(tmp_path):
    html = ("<h2>CONTENTS</h2><p>a</p><h2>Translator’s Dedication</h2><p>b</p><h2>Chapter 1</h2><p>c</p>"
            "<h2>Chapter 2</h2><p>d</p>")
    w = work(html=html)
    assert landmark(build(tmp_path, w)) == "c003.xhtml"
    w.title_overrides = {"1": "Title page", "3": "Front matter"}
    assert landmark(build(tmp_path, w)) == "c004.xhtml"
    w = work(html="<h2>Contents</h2><p>a</p><h2>Dedication</h2><p>b</p><h2>Contents</h2><p>c</p>")
    assert landmark(build(tmp_path, w)) == "c001.xhtml"  # nothing but front matter: the first page
