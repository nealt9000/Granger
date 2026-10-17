import hashlib
import json

import pytest
from libzim.reader import Archive

from conftest import fixture
from gutenberg import BRANDING
from manifest import load
from pack_builder import build, describe
from sources import FetchError


class FakeSource:
    def __init__(self, failing=None):
        self.failing = failing

    def html(self, book_id):
        if book_id == self.failing:
            raise FetchError(f"book {book_id}: https://mirror.test/{book_id}: 404")
        return fixture("pg-modern.html")


def two_works(tmp_path):
    works = [{"gutenberg": 1342, "slug": "modern-book", "title": "The Modern Book", "author": "A. Writer",
              "translator": None, "published": 1900, "section": "novels"},
             {"gutenberg": 2800, "slug": "koran-rodwell", "title": "The Koran", "author": "",
              "translator": "J. M. Rodwell", "published": 1861, "section": "scripture"}]
    data = {"name": "granger_test", "title": "Granger Test", "language": "eng", "description": "Test pack",
            "sections": [{"id": "scripture", "title": "Scripture & religion"}, {"id": "novels", "title": "Novels"}],
            "works": works}
    path = tmp_path / "manifest.json"
    path.write_text(json.dumps(data))
    return load(path)


def test_build_writes_a_branding_free_zim(tmp_path):
    zim = build(two_works(tmp_path), FakeSource(), tmp_path, "2026-10-16")
    assert zim.name == "granger_test_2026-10-16.zim"
    a = Archive(zim)
    assert a.has_entry_by_path("koran-rodwell/001") and a.has_entry_by_path("modern-book")
    for i in range(a.all_entry_count):
        item = a._get_entry_by_id(i).get_item()
        if item.mimetype.startswith("text/"):
            assert not BRANDING.search(bytes(item.content).decode()), item.path


def test_failed_fetch_names_the_work_and_leaves_no_zim(tmp_path):
    with pytest.raises(FetchError, match="2800"):
        build(two_works(tmp_path), FakeSource(failing=2800), tmp_path, "2026-10-16")
    assert list(tmp_path.glob("*.zim*")) == []


def test_describe_reports_what_the_catalog_needs(tmp_path):
    zim = build(two_works(tmp_path), FakeSource(), tmp_path, "2026-10-16")
    d = describe(zim, "https://github.com/nealt9000/Granger/releases/download/classics-2026-10-16/" + zim.name)
    assert d["id"] == d["zimName"] == "granger_test"
    assert d["zimUuid"] == str(Archive(zim).uuid) and d["snapshotDate"] == "2026-10-16"
    assert d["sizeBytes"] == zim.stat().st_size and d["sha256"] == hashlib.sha256(zim.read_bytes()).hexdigest()
    assert d["hasFulltextIndex"] is True and d["language"] == "eng" and len(d["urls"]) == 1
    assert d["description"] == "Test pack"


def test_book_without_markers_names_the_work(tmp_path):
    from gutenberg import NotAGutenbergBook

    class NoMarkers(FakeSource):
        def html(self, book_id):
            return fixture("pg-nomarkers.html") if book_id == 1342 else super().html(book_id)

    with pytest.raises(NotAGutenbergBook, match="modern-book"):
        build(two_works(tmp_path), NoMarkers(), tmp_path, "2026-10-16")


def test_every_page_stays_under_the_page_limit(tmp_path):
    class Big(FakeSource):
        def html(self, book_id):
            css = b"<style>" + b"".join(b".c%d { margin: 0 }\n" % i for i in range(400)) + b"</style>"
            chapters = b"".join(b"<h2>Chapter %d</h2>" % i + b"<p>" + b"word " * 29_900 + b"</p>" for i in range(3))
            return (b"<html><head>" + css + b"</head><body><div id='pg-start-separator'></div>" + chapters
                    + b"<div id='pg-end-separator'></div></body></html>")

    a = Archive(build(two_works(tmp_path), Big(), tmp_path, "2026-10-16"))
    for i in range(a.all_entry_count):
        item = a._get_entry_by_id(i).get_item()
        assert len(bytes(item.content)) <= 150_000, item.path


def test_build_epubs_writes_one_file_per_work(tmp_path):
    from pack_builder import build_epubs
    out = tmp_path / "out"
    out.mkdir()
    files = build_epubs(two_works(tmp_path), FakeSource(), out, "2026-10-17")
    assert sorted(f.name for f in files) == ["gc-koran-rodwell_2026-10-17.epub", "gc-modern-book_2026-10-17.epub"]
    import zipfile
    for f in files:
        names = zipfile.ZipFile(f).namelist()
        assert names[0] == "mimetype" and "OEBPS/content.opf" in names and "OEBPS/c001.xhtml" in names


def test_build_failure_names_the_work(tmp_path, monkeypatch):
    import pack_builder
    from pack_builder import build_epubs

    def boom(*args, **kwargs):
        raise ValueError("no headings")
    monkeypatch.setattr(pack_builder.chapters, "split", boom)
    with pytest.raises(Exception) as caught:
        build_epubs(two_works(tmp_path), FakeSource(), tmp_path, "2026-10-17")
    assert str(caught.value).startswith("modern-book (#1342): ")
    assert "no headings" in str(caught.value)
    assert list(tmp_path.glob("*.epub*")) == []


def test_describe_epubs_hashes_and_lists(tmp_path):
    from pack_builder import build_epubs, describe_epubs
    m = two_works(tmp_path)
    build_epubs(m, FakeSource(), tmp_path, "2026-10-17")
    base = "https://github.com/nealt9000/Granger/releases/download/classics-2026-10-17"
    d = describe_epubs(tmp_path, base, m)
    assert d["releaseDate"] == "2026-10-17" and d["collection"] == "granger-classics"
    by_id = {b["id"]: b for b in d["books"]}
    assert list(by_id) == ["gc-modern-book", "gc-koran-rodwell"]
    koran = by_id["gc-koran-rodwell"]
    path = tmp_path / "gc-koran-rodwell_2026-10-17.epub"
    assert koran["title"] == "The Koran (tr. J. M. Rodwell)" and koran["author"] == ""
    assert koran["translator"] == "J. M. Rodwell" and koran["published"] == 1861
    assert koran["section"] == "Scripture & religion" and koran["language"] == "en"
    assert koran["sizeBytes"] == path.stat().st_size
    assert koran["sha256"] == hashlib.sha256(path.read_bytes()).hexdigest()
    assert koran["urls"] == [f"{base}/gc-koran-rodwell_2026-10-17.epub"]
    assert by_id["gc-modern-book"]["translator"] is None
