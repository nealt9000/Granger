import json
import sqlite3
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import build  # noqa: E402
import check  # noqa: E402
import classics_qid  # noqa: E402


def item(q, t, l, **kw):
    return json.dumps(dict(q=q, t=t, sl=kw.pop("sl", 50), l=l, **kw))


def test_a_classic_takes_its_works_year_or_its_authors_death():
    books = [dict(id="pp", title="Pride and Prejudice", author="Jane Austen", published=1813),
             dict(id="dq", title="Don Quijote", author="Miguel de Cervantes Saavedra", published=1605),
             dict(id="bw", title="Beowulf", author="", published=1910),
             dict(id="ct", title="The Canterbury Tales (modern English)", author="Geoffrey Chaucer", published=1912)]
    lines = [item("Q36322", "Jane_Austen", "Jane Austen", P31=["Q5"], P570=[["+1817-07-18T00:00:00Z", 11, "x"]]),
             item("Q170583", "Pride_and_Prejudice", "Pride and Prejudice", P31=["Q7725634"], P50=["Q36322"],
                  P577=[["+1813-01-28T00:00:00Z", 11, "x"]]),
             item("Q99", "Pride_and_Prejudice_(film)", "Pride and Prejudice", P50=["Q1"], P577=[["+2005-01-01T00:00:00Z", 9, "x"]]),
             item("Q5682", "Miguel_de_Cervantes", "Miguel de Cervantes", P31=["Q5"]),
             item("Q480", "Don_Quijote_de_la_Mancha", "Don Quixote", P50=["Q5682"],
                  P577=[["+1605-00-00T00:00:00Z", 9, "x"], ["+1615-00-00T00:00:00Z", 9, "x"]]),
             item("Q644933", "Beowulf_(2007)", "Beowulf", P577=[["+2007-01-01T00:00:00Z", 9, "x"]]),
             item("Q5683", "Geoffrey_Chaucer", "Geoffrey Chaucer", P31=["Q5"], P570=[["+1400-10-25T00:00:00Z", 11, "x"]])]
    got = classics_qid.match(books, lines)
    assert got["pp"] == {"qid": 170583, "label": "Pride and Prejudice", "year": 1813, "how": "work"}
    assert got["dq"]["qid"] == 480 and got["dq"]["year"] == 1605           # a title prefix, the author agreeing
    assert got["bw"] == {"qid": None, "label": "", "year": 1910, "how": "published"}   # the 2007 film is after the book
    assert got["ct"] == {"qid": None, "label": "", "year": 1400, "how": "author-death"}


def rows():
    def r(start, qid, sl, word, alts=()):
        return dict(book="b", href="c1.xhtml", start=start, len=6, quote="Moscow", before="in ", word=word, qid=qid,
                    sl=sl, alts=list(alts))
    return [r(0, 649, 300, 0), r(10, 649, 300, 5), r(20, 2, 20, 10), r(40, 4, 100, 299, alts=[[9, "x", 30]])]


def test_tiers_mark_light_and_more_and_confidence():
    got = {(m["start"], m["tier"], m["conf"]) for m in build.tiers(rows())}
    assert got == {(0, 1, 1.0), (40, 1, 0.7), (20, 2, 1.0)}


def built(tmp_path, mentions):
    epub = tmp_path / "gc-x_2026-11-09.epub"
    epub.write_bytes(b"not really an epub")
    sha = build.meta.sha256(epub)
    out = build.write_glink(tmp_path / "gc-x_2026-11-09.glink", epub, sha, "gc-x", 170583, 1813, mentions)
    return out, epub


def test_a_built_file_keeps_the_contract_and_scores_the_graded_links(tmp_path):
    ms = [dict(m, quote="Moscow", before="In burned and devastated ") for m in build.tiers(rows())]
    out, epub = built(tmp_path, ms)
    assert check.file_problems(out, tmp_path) == []
    db = sqlite3.connect(out)
    assert db.execute("SELECT * FROM book").fetchone() == ("gc-x", 170583, 1813)
    assert db.execute("SELECT value FROM meta WHERE key = 'kind'").fetchone() == ("glink",)
    book, light = check.light_mentions(out)
    graded = [dict(book="gc-x", quote="Moscow", before="In burned and devastated ", qid=649, grade="right"),
              dict(book="gc-x", quote="Moscow", before="devastated ", qid=4, grade="right"),
              dict(book="gc-x", quote="Paris", before="to ", qid=90, grade="right"),
              dict(book="gc-y", quote="Moscow", before="", qid=649, grade="wrong")]
    c = check.score(graded, {book: light})["gc-x"]
    assert c == {"right": 2, "lost": 1}
    assert check.precision(c) == (1.0, 2)
    epub.write_bytes(b"another file")
    assert any("another file" in p for p in check.file_problems(out, tmp_path))
