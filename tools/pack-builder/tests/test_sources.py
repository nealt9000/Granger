import pytest

from sources import FetchError, GutenbergSource


def test_second_read_comes_from_cache(tmp_path):
    calls = []

    def fetch(url):
        calls.append(url)
        return b"<html/>"

    s = GutenbergSource(tmp_path, fetch=fetch, delay_s=0)
    assert s.html(10) == b"<html/>" and s.html(10) == b"<html/>"
    s.rdf(10)
    assert calls == ["https://aleph.pglaf.org/cache/epub/10/pg10-images.html",
                     "https://aleph.pglaf.org/cache/epub/10/pg10.rdf"]


def test_cache_writes_are_atomic(tmp_path):
    def broken(url):
        raise OSError("reset")

    with pytest.raises(FetchError, match="10"):
        GutenbergSource(tmp_path, fetch=broken, delay_s=0).html(10)
    assert not any(p.is_file() for p in tmp_path.rglob("*"))
