import io
import zipfile

import pytest

import se_fixture
import standardebooks
from sources import FetchError, StandardEbooksSource


def book():
    return standardebooks.read(se_fixture.epub())


def test_metadata_rights_and_colophon_years_are_read():
    b = book()
    assert (b.title, b.authors, b.translators) == ("The Test Play", ["A. Writer"], ["B. Writer"])
    assert "United States public domain" in b.rights and "CC0" in b.rights
    assert b.identifier == "https://standardebooks.org/ebooks/a-writer/the-test-play/b-writer"
    assert b.years == [1926, 1929]  # not 2026, the ebook's own release


def test_se_pages_go_and_no_se_wording_or_semantics_is_left():
    b = book()
    assert b.skipped == ["titlepage.xhtml", "imprint.xhtml", "colophon.xhtml", "uncopyright.xhtml"]
    html = b.cleaned.body.decode_contents()
    for gone in ("Standard Ebooks", "Imprint", "Uncopyright", "Colophon", "epub:type", "role=", "<img", "<figure",
                 "A drawing", "﻿", 'class="epub-type"'):
        assert gone not in html, gone
    assert "word⁠—and" in html  # SE's joiner kept, as U+2060


def test_drama_rows_become_speeches_and_directions():
    body = book().cleaned.body
    assert body.find("table") is None
    direction = body.select("div.gd-sd")
    assert [d.get_text(" ", strip=True) for d in direction] == ["Lane enters.", "Curtain."]
    prose = body.select_one("p.gd-speech.gd-prose")
    assert prose.select_one("span.gd-who").get_text() == "Algernon"
    assert prose.select_one("span.gd-sdi").get_text() == "(Sits.)"
    assert " ".join(prose.get_text().split()) == "Algernon Did you hear? (Sits.) Well."
    verse = body.select_one("div.gd-speech")
    assert verse.select_one("span.gd-who").get_text() == "Lane"
    lines = verse.select("p.gv > span.gv-l")
    assert [line.get_text() for line in lines] == ["First line,", "and the second."]
    assert lines[1]["style"] == "margin-left: 1em" and verse.find("br") is None


def test_verse_hgroup_and_links():
    body = book().cleaned.body
    poem = body.select("p.gv span.gv-l")[-2:]
    assert [line.get_text() for line in poem] == ["Sing high,", "sing low."] and poem[1]["style"] == "margin-left: 2em"
    assert [" ".join(h.get_text().split()) for h in body.find_all("h2")] == ["Preface", "Act I: The Garden", "A Song",
                                                                       "Endnotes"]
    hrefs = [a["href"] for a in body.find_all("a")]
    assert hrefs == ["#note-1", "#se-act-1", "#noteref-1"]  # the external link became text
    assert body.find(id="se-act-1") is not None and body.find(id="note-1") is not None


def test_local_css_is_kept_without_at_rules_or_epub_type_selectors():
    css = book().cleaned.styles
    assert "section.epub-type-contains-word-z3998-scene > p" in css
    assert "@namespace" not in css and "@charset" not in css and "epub|type" not in css


def test_a_package_path_leaving_the_epub_is_refused():
    bad = se_fixture.OPF.replace('href="text/preface.xhtml"', 'href="../../preface.xhtml"')
    with pytest.raises(standardebooks.NotAStandardEbook, match="leaves"):
        standardebooks.read(se_fixture.epub(opf=bad))


def test_a_zip_without_a_container_is_not_a_standard_ebook():
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w") as z:
        z.writestr("mimetype", "application/epub+zip")
    with pytest.raises(standardebooks.NotAStandardEbook):
        standardebooks.read(buffer.getvalue())


def test_placeholder_pages_are_recognised():
    assert standardebooks.is_placeholder(se_fixture.PLACEHOLDER)
    assert not standardebooks.is_placeholder(se_fixture.PAGE)


def test_source_caches_the_page_and_the_compatible_epub(tmp_path):
    calls, data = [], se_fixture.epub()

    def fetch(url):
        calls.append(url)
        return se_fixture.PAGE if url.endswith("/b-writer") else data

    s = StandardEbooksSource(tmp_path, fetch=fetch, delay_s=0)
    assert s.epub("a-writer/the-test-play/b-writer") == data
    assert s.epub("a-writer/the-test-play/b-writer") == data
    assert calls == ["https://standardebooks.org/ebooks/a-writer/the-test-play/b-writer",
                     "https://standardebooks.org/ebooks/a-writer/the-test-play/b-writer/downloads/"
                     "a-writer_the-test-play_b-writer.epub?source=download"]
    assert sorted(p.name for p in tmp_path.rglob("*") if p.is_file()) == ["book.epub", "page.html"]


def test_source_refuses_bad_ids_placeholders_and_non_epubs(tmp_path):
    s = StandardEbooksSource(tmp_path, fetch=lambda url: se_fixture.PLACEHOLDER, delay_s=0)
    with pytest.raises(FetchError, match="author/title"):
        s.page("../etc")
    with pytest.raises(FetchError, match="links no EPUB"):
        s.epub("a-writer/the-test-play")
    s = StandardEbooksSource(tmp_path / "2", fetch=lambda url: se_fixture.PAGE if "downloads" not in url else b"<html>",
                             delay_s=0)
    with pytest.raises(FetchError, match="isn't an EPUB"):
        s.epub("a-writer/the-test-play/b-writer")
