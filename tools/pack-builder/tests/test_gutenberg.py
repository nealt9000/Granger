import pytest

import gutenberg
from conftest import fixture


def test_modern_template_keeps_only_the_work():
    c = gutenberg.clean(fixture("pg-modern.html"))
    text = c.body.get_text()
    assert "Chapter 1" in text and "Chapter 5" in text
    assert not gutenberg.BRANDING.search(str(c.body))
    assert "START OF THE PROJECT" not in text and "pgdp.net" not in str(c.body)
    assert "Updated editions" not in text
    assert "Johannes Gutenberg printed a Bible." in text
    assert c.branding_removed >= 1


def test_media_scripts_and_external_links_are_removed():
    body = str(gutenberg.clean(fixture("pg-modern.html")).body)
    assert "<img" not in body and "<script" not in body and "<link" not in body
    assert "example.com" not in body          # the link is unwrapped, its text kept
    assert "the link text" in body             # the fixture's external link reads "the link text"


def test_styles_are_kept_without_imports_or_urls():
    styles = gutenberg.clean(fixture("pg-modern.html")).styles
    assert ".stanza" in styles                 # the fixture's style block defines .stanza
    assert "@import" not in styles and "url(" not in styles


def test_old_text_markers_are_cut():
    c = gutenberg.clean(fixture("pg-old.html"))
    text = c.body.get_text()
    assert "First line of the old book." in text and "Third chapter." in text
    assert "should be named" not in text
    assert not gutenberg.BRANDING.search(str(c.body))


def test_book_without_markers_is_rejected():
    with pytest.raises(gutenberg.NotAGutenbergBook):
        gutenberg.clean(fixture("pg-nomarkers.html"))


def test_heading_images_become_their_alt_text():
    html = fixture("pg-old.html").replace(b"<h2>II</h2>", b'<h2><img alt="CHAPTER II." src="i.jpg"></h2>')
    c = gutenberg.clean(html)
    assert c.body.find_all("h2")[1].get_text() == "CHAPTER II."


def test_later_edition_notices_are_found():
    html = fixture("pg-old.html").replace(
        b"<h2>I</h2>", b"<p>This paperback edition Published 1958 by E. P. Dutton. Copyright, 1890.</p><h2>I</h2>")
    c = gutenberg.clean(html)
    assert gutenberg.later_edition_notices(c.body) == ["This paperback edition Published 1958 by E. P. Dutton."]
    assert gutenberg.later_edition_notices(gutenberg.clean(fixture("pg-old.html")).body) == []


def test_branding_across_a_line_break_is_removed():
    html = fixture("pg-old.html").replace(
        b"<p>Second chapter.</p>",
        b"<p>Second chapter.</p><p>See the Project\nGutenberg E-Text of another play.</p><p>This Gutenberg eText.</p>")
    c = gutenberg.clean(html)
    assert "another play" not in c.body.get_text() and "This Gutenberg" not in c.body.get_text()
    assert "Second chapter." in c.body.get_text()


def test_style_comments_and_gutenberg_rules_are_dropped():
    html = fixture("pg-old.html").replace(
        b"p { text-indent: 1em; }",
        b"/* classless css copied from https://www.pgdp.net/ */ p { text-indent: 1em; }"
        b" #pg-footer #project-gutenberg-license { font-size: 80%; }"
        b" @media print { .pg-boilerplate { display: none } .verse { margin: 0 } }")
    styles = gutenberg.clean(html).styles
    assert "pgdp" not in styles and "pg-" not in styles and "gutenberg" not in styles.lower()
    assert "text-indent: 1em" in styles and ".verse" in styles


def test_both_text_markers_in_one_pre_keep_the_work():
    html = (b"<html><body><pre>The Project Gutenberg EBook of X\n"
            b"*** START OF THIS PROJECT GUTENBERG EBOOK X ***\nThe whole work, in one block.\n"
            b"*** END OF THIS PROJECT GUTENBERG EBOOK X ***\nProject Gutenberg-tm License</pre></body></html>")
    c = gutenberg.clean(html)
    assert c.body.find("pre").get_text().strip() == "The whole work, in one block."


def test_text_markers_directly_in_body_keep_the_work():
    html = (b"<html><body>Project Gutenberg header *** START OF THE PROJECT GUTENBERG EBOOK X ***"
            b"<h2>I</h2><p>The work itself.</p>*** END OF THE PROJECT GUTENBERG EBOOK X *** license</body></html>")
    text = gutenberg.clean(html).body.get_text()
    assert "The work itself." in text and "header" not in text and "license" not in text


def test_branding_in_a_wrapper_with_headings_removes_only_the_text():
    html = fixture("pg-old.html").replace(
        b"<h2>I</h2>", b'<div class="book">Produced for Project Gutenberg.<h2>I</h2>').replace(
        b"<p>Third chapter.</p>", b"<p>Third chapter.</p></div>")
    text = gutenberg.clean(html).body.get_text()
    assert "First line of the old book." in text and "Third chapter." in text and "Produced" not in text


def test_a_book_cleaned_down_to_nothing_is_refused():
    html = (b"<html><body><div>Project Gutenberg <p>pg-start</p></div><div id='pg-start-separator'></div>"
            b"<div>Transcribed for Project Gutenberg.<p>All of the work is here, in one wrapper.</p></div>"
            b"<div id='pg-end-separator'></div></body></html>")
    with pytest.raises(gutenberg.CleaningError):
        gutenberg.clean(html)


def test_unwrapped_scheme_link_keeps_its_id():
    html = fixture("pg-modern.html").replace(
        b'<a href="https://example.com/">the link text</a>',
        b'<a id="ext" href="https://example.com/">the link text</a>')
    body = str(gutenberg.clean(html).body)
    assert '<span id="ext">the link text</span>' in body and "example.com" not in body


def test_unwrap_link_keeps_both_id_and_name_as_targets():
    from bs4 import BeautifulSoup
    from gutenberg import unwrap_link
    soup = BeautifulSoup('<p><a id="a" name="b">x</a></p>', "lxml")
    unwrap_link(soup.a)
    assert {t["id"] for t in soup.find_all(id=True)} == {"a", "b"}


def modern_body(**replacements):
    html = fixture("pg-modern.html")
    for old, new in replacements.items():
        html = html.replace(old.encode(), new.encode())
    return gutenberg.clean(html).body


def test_empty_boilerplate_header_and_footer_shells_are_removed():
    html = (b"<html><body><header class='pg-boilerplate pgheader' id='pg-header'><div id='pg-start-separator'>"
            b"</div></header><div style='text-align:center'></div><h2>I</h2><p>The work.</p>"
            b"<div style='text-align:center'></div><footer class='pg-boilerplate' id='pg-footer'>"
            b"<div id='pg-end-separator'></div></footer></body></html>")
    body = gutenberg.clean(html).body
    assert body.find(["header", "footer"]) is None and body.find(id=True) is None
    assert str(body) == "<div><h2>I</h2><p>The work.</p></div>"


def test_childless_textless_blocks_go_but_their_ids_stay():
    html = (b"<html><body><div id='pg-start-separator'></div><h2>I</h2>"
            b"<div class='figcenter' style='width: 500px'><img src='a.jpg' alt=''></div>"
            b"<p class='i20'>I.</p><p>&nbsp;</p><p id='stanza2'> \n</p><div id='fig1'><div></div></div>"
            b"<p>Line one<br>line two</p><p><br></p><table><tr><td></td></tr></table>"
            b"<div id='pg-end-separator'></div></body></html>")
    body = str(gutenberg.clean(html).body)
    assert "figcenter" not in body and "&nbsp;" not in body and "\xa0" not in body
    assert '<span id="stanza2"></span>' in body and '<span id="fig1"></span>' in body
    assert "<p>Line one<br/>line two</p>" in body and "<td></td>" in body and "<p><br/></p>" in body
    assert body.count("<p") == 3 and "<div" not in body.replace("<div>", "", 1)


def test_cover_art_transcribers_note_is_dropped():
    note = ('<div style="margin-top: 5%; border: 1px solid;"><div style="text-align: center;">'
            '<b>Transcriber’s Notes</b></div><ul><li>New original cover art included with this eBook is granted '
            'to the public domain.</li></ul></div>')
    body = modern_body(**{"<h2>Chapter 5</h2>": note + "<h2>Chapter 5</h2>"})
    assert "Transcriber" not in body.get_text() and "Chapter 5" in body.get_text()
    short = '<div class="tnote"><p>Transcriber\'s Note:</p><p>The cover image was created by the transcriber.</p></div>'
    assert "Transcriber" not in modern_body(**{"<h2>Chapter 5</h2>": short + "<h2>Chapter 5</h2>"}).get_text()


def test_transcribers_note_with_real_content_is_kept():
    note = ('<div class="tnote"><p><b>Transcriber’s Note:</b></p><p>Obvious printer’s errors have been corrected. '
            'The cover image was created by the transcriber and is placed in the public domain.</p></div>')
    assert "Obvious printer" in modern_body(**{"<h2>Chapter 5</h2>": note + "<h2>Chapter 5</h2>"}).get_text()
    inline = "<div><p>Transcriber's Note:</p><p>The cover of the first edition reads 'Tales'.</p></div>"
    assert "first edition" in modern_body(**{"<h2>Chapter 5</h2>": inline + "<h2>Chapter 5</h2>"}).get_text()


FADED_PAGE = ('<p class="pindent">This ebook is made available at no cost and with very few\nrestrictions. These '
              'restrictions apply only if (1) you make a change in the ebook, or (2) you are making commercial use of '
              'the ebook. If either of these conditions applies, please contact a FP administrator before proceeding.</p>'
              '<p class="pindent">This work is in the Canadian public domain, but may be under copyright in some '
              "countries. If you live outside Canada, check your country's copyright laws. IF THE BOOK IS UNDER "
              'COPYRIGHT IN YOUR COUNTRY, DO NOT DOWNLOAD OR REDISTRIBUTE THIS FILE.</p>'
              '<div class="lgl"><p class="line0"><span class="it">Title:</span> Cakes and Ale</p>'
              '<p class="line0"><span class="it">Date of first publication:</span> 1930</p>'
              '<p class="line0"><span class="it">Date first posted:</span> July 31, 2017</p>'
              '<p class="line0">Faded Page eBook #20170731</p></div>')


def test_faded_page_notices_at_the_start_are_dropped():
    body = modern_body(**{"<h2>Chapter 1</h2>": FADED_PAGE + "<h2>Chapter 1</h2>"})
    text = body.get_text()
    for words in ("made available", "Canadian", "DO NOT DOWNLOAD", "Date first posted", "Faded Page"):
        assert words not in text
    assert "Chapter 1" in text


def test_faded_pages_in_the_text_are_kept():
    line = "<p>Then we turn back to these faded pages, and read the quaintly earnest words.</p>"
    assert "faded pages" in modern_body(**{"<h2>Chapter 1</h2>": line + "<h2>Chapter 1</h2>"}).get_text()


def test_cut_after_drops_everything_after_the_works_last_words():
    html = fixture("pg-modern.html")
    c = gutenberg.clean(html.replace(b"<h2>Chapter 5</h2>", b"<p>He said, \xe2\x80\x9cbury us all!\xe2\x80\x9d Then "
                                     b"silence.</p><div><p>THE MODERN LIBRARY</p></div><h2>Chapter 5</h2>"),
                        cut_after="bury us all!”")
    text = c.body.get_text()
    assert text.rstrip().endswith("bury us all!”") and "MODERN LIBRARY" not in text and "Chapter 5" not in text
    assert "Chapter 1" in text


def test_cut_after_text_that_isnt_there_fails_the_build():
    with pytest.raises(gutenberg.CleaningError, match="cutAfter"):
        gutenberg.clean(fixture("pg-modern.html"), cut_after="words the book doesn't have")
