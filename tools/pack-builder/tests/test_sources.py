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


def usfm_zip(members: dict[str, str]) -> bytes:
    import io
    import zipfile
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w") as z:
        for name, text in members.items():
            z.writestr(name, text)
    return buffer.getvalue()


def test_ebible_books_come_cached_in_order_and_only_flat_usfm_members_are_read(tmp_path):
    from sources import EBibleSource
    calls = []
    data = usfm_zip({"70-MATengtest.usfm": "\\id MAT", "02-GENengtest.usfm": "﻿\\id GEN",
                     "106-GLOengtest.usfm": "\\id GLO", "copr.htm": "<p>Public Domain</p>",
                     "../00-FRTengtest.usfm": "\\id FRT", "sub/03-EXOengtest.usfm": "\\id EXO"})

    def fetch(url):
        calls.append(url)
        return data

    s = EBibleSource(tmp_path, fetch=fetch, delay_s=0)
    assert s.books("engtest") == [("GEN", "\\id GEN"), ("MAT", "\\id MAT"), ("GLO", "\\id GLO")]
    s.books("engtest")
    assert calls == ["https://eBible.org/Scriptures/engtest_usfm.zip"]
    assert (tmp_path / "engtest" / "engtest_usfm.zip").read_bytes() == data
    assert [p.name for p in tmp_path.rglob("*") if p.is_file()] == ["engtest_usfm.zip"]


def test_ebible_details_page_and_bad_ids(tmp_path):
    from sources import EBibleSource
    calls = []
    s = EBibleSource(tmp_path, fetch=lambda url: calls.append(url) or b"Public Domain", delay_s=0)
    assert s.details("engbsb") == b"Public Domain"
    assert calls == ["https://eBible.org/find/details.php?id=engbsb"]
    with pytest.raises(FetchError, match="ebible id"):
        s.usfm_zip("../etc")
    broken = EBibleSource(tmp_path / "b", fetch=lambda url: (_ for _ in ()).throw(OSError("reset")), delay_s=0)
    with pytest.raises(FetchError, match="engwebu"):
        broken.usfm_zip("engwebu")
    assert not (tmp_path / "b").exists()
