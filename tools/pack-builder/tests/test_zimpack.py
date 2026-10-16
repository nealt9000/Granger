import struct

import pytest
from libzim.reader import Archive
from libzim.search import Query, Searcher
from libzim.suggestion import SuggestionSearcher

import zimpack
from chapters import Chapter
from zimpack import PackInfo, Work

INFO = PackInfo(name="granger_test_pack", title="Granger Test Pack", description="Two classics for tests",
                language="eng", date="2026-10-16", sections=[("novels", "Novels & stories"), ("poetry", "Epic & poetry")])
WORKS = [
    Work(slug="moby-dick", title="Moby-Dick", author="Herman Melville", translator=None, published=1851,
         section="novels", styles=".stanza { margin: 1em; }",
         chapters=[Chapter("CHAPTER 1. Loomings", "<h2>CHAPTER 1. Loomings</h2><p>Call me Ishmael.</p>"),
                   Chapter("CHAPTER 2. The Carpet-Bag", "<h2>CHAPTER 2.</h2><p>The leviathan &amp; the sea.</p>")]),
    Work(slug="aeneid", title="The Æneid", author="Virgil", translator="John Dryden", published=1697,
         section="poetry", chapters=[Chapter("Café — Book I", "<h2>Book I</h2><p>Arms, and the man I sing.</p>")]),
]


def pack(tmp_path):
    out = tmp_path / "p.zim"
    zimpack.write_zim(out, INFO, WORKS)
    return Archive(out)


def text(archive, path):
    return bytes(archive.get_entry_by_path(path).get_item().content).decode()


def test_entries_and_main_page(tmp_path):
    a = pack(tmp_path)
    assert a.main_entry.get_item().path == "index"
    for path in ["index", "about", "moby-dick", "moby-dick/001", "moby-dick/002", "aeneid/001", "res/classics.css"]:
        assert a.has_entry_by_path(path), path
    assert a.get_metadata("Name").decode() == "granger_test_pack"
    assert a.get_metadata("Language").decode() == "eng"
    assert a.get_metadata("Date").decode() == "2026-10-16"
    assert a.has_illustration(48)


def test_full_text_index_finds_chapter_text(tmp_path):
    a = pack(tmp_path)
    assert a.has_fulltext_index
    hits = list(Searcher(a).search(Query().set_query("leviathan")).getResults(0, 10))
    assert "moby-dick/002" in hits


def test_title_suggestions_list_books_not_chapters(tmp_path):
    a = pack(tmp_path)
    hits = list(SuggestionSearcher(a).suggest("moby").getResults(0, 10))
    assert "moby-dick" in hits and not any("/" in h for h in hits)


def test_non_ascii_titles_survive(tmp_path):
    a = pack(tmp_path)
    assert a.get_entry_by_path("aeneid").title == "The Æneid"
    assert a.get_entry_by_path("aeneid/001").title == "The Æneid — Café — Book I"
    assert "The Æneid" in text(a, "index")


def test_chapter_pages_link_back_and_across(tmp_path):
    a = pack(tmp_path)
    html = text(a, "moby-dick/001")
    assert 'href="../moby-dick"' in html and 'href="002"' in html
    assert 'href="../res/classics.css"' in html and ".stanza" in html
    assert "<script" not in html and "http" not in html
    assert 'href="moby-dick/001"' in text(a, "moby-dick")


def test_about_lists_every_work_with_its_year(tmp_path):
    about = text(pack(tmp_path), "about")
    assert "Herman Melville" in about and "1851" in about and "translated by john dryden" in about.lower()


def test_failed_write_leaves_nothing_at_out(tmp_path, monkeypatch):
    out = tmp_path / "p.zim"

    def boom(*args):
        raise RuntimeError("boom")

    monkeypatch.setattr(zimpack, "_add_work", boom)
    with pytest.raises(RuntimeError):
        zimpack.write_zim(out, INFO, WORKS)
    assert not out.exists()


def test_solid_png_is_a_png():
    png = zimpack.solid_png(48, (90, 60, 30))
    assert png.startswith(b"\x89PNG\r\n\x1a\n") and struct.unpack(">II", png[16:24]) == (48, 48)
