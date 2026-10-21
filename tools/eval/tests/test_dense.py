import pytest

pytest.importorskip("bs4")
pytest.importorskip("lxml")

import dense


def test_cut_at_spaces_keeps_words_whole():
    parts = dense.cut_at_spaces("aaaa bbbb cccc", 9)
    assert parts == ["aaaa bbbb", "cccc"]
    assert dense.cut_at_spaces("x" * 12, 5) == ["xxxxx", "xxxxx", "xx"]


def test_chapter_chunks_join_blocks_to_the_minimum_and_keep_the_tail():
    para = "<p>%s</p>" % ("word " * 50).strip()  # 249 characters
    xhtml = "<html xmlns='http://www.w3.org/1999/xhtml'><body>%s</body></html>" % (para * 3)
    chunks = dense.chapter_chunks(xhtml.encode())
    assert len(chunks) == 2
    assert chunks[0].count("\n") == 1 and len(chunks[0]) >= dense.MIN_CHUNK
    assert len(chunks[1]) < dense.MIN_CHUNK


def test_container_blocks_give_only_their_own_text():
    xhtml = "<html xmlns='http://www.w3.org/1999/xhtml'><body><div>intro <p>inner</p></div></body></html>"
    assert dense.chapter_chunks(xhtml.encode()) == ["intro\ninner"]


def test_source_ids(tmp_path):
    from pathlib import Path
    assert dense.source_id(Path("x/granger/gc-king-james-bible_2026-10-17.epub")) == "book:granger:gc-king-james-bible"
    assert dense.source_id(Path("x/archive-org/goodytwoshoes00newyiala.epub")) == "book:archive-org:goodytwoshoes00newyiala"
