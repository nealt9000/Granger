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
