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
