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
