import sqlite3
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "sidecar"))
import build  # noqa: E402
import meta  # noqa: E402
from zim_articles import articles  # noqa: E402


def make_zim(tmp_path):
    from libzim.writer import Creator, Hint, Item, StringProvider

    class Page(Item):
        def __init__(self, path, title, html):
            super().__init__(); self.p, self.t, self.h = path, title, html
        def get_path(self): return self.p
        def get_title(self): return self.t
        def get_mimetype(self): return "text/html"
        def get_contentprovider(self): return StringProvider(self.h)
        def get_hints(self): return {Hint.FRONT_ARTICLE: True}

    body = "<html><body>" + "x" * 800 + "</body></html>"
    stub = '<html><head><meta http-equiv="refresh" content="0;URL=\'Jane_Austen\'"></head></html>'
    zim = tmp_path / "wikipedia_en_test_maxi_2026-11.zim"
    with Creator(str(zim)).config_indexing(False, "eng") as c:
        c.set_mainpath("Jane_Austen")
        for p, title, html in [("Jane_Austen", "Jane Austen", body), ("Pride_and_Prejudice", "Pride and Prejudice", body),
                               ("London", "London", body), ("No_item", "No item", body), ("Austen", "Austen", stub)]:
            c.add_item(Page(p, title, html))
    return zim


ITEMS = [
    {"q": "Q36322", "t": "Jane_Austen", "sl": 150, "es": "Jane Austen", "P31": ["Q5"], "P800": ["Q170583"]},
    {"q": "Q170583", "t": "Pride_and_Prejudice", "sl": 90, "es": "Orgullo y prejuicio", "P50": ["Q36322"], "P2034": ["1342", "42671"],
     "P1899": ["45"]},
    {"q": "Q84", "t": "London", "sl": 300, "P625": [51.50722, -0.1275]},
    {"q": "Q999", "t": "Not_in_the_pack", "sl": 5},
    {"q": "Q36322", "t": "Austen", "sl": 150},   # the stub's path: not a real article, so not indexed
]


def test_build_maps_articles_links_and_coords(tmp_path):
    from libzim.reader import Archive
    zim = make_zim(tmp_path)
    out = tmp_path / "x.gqid"
    counts = build.build([p for p, _ in articles(Archive(zim))], iter(ITEMS), out, zim, meta.sha256(zim))
    assert {k: counts[k] for k in ("articles", "matched", "links", "coords")} == \
        {"articles": 4, "matched": 3, "links": 5, "coords": 1}
    db = sqlite3.connect(out)
    assert db.execute("SELECT path, qid, rank, other FROM articles ORDER BY path").fetchall() == [
        ("Jane_Austen", 36322, 150, "Jane Austen"), ("London", 84, 300, None),
        ("Pride_and_Prejudice", 170583, 90, "Orgullo y prejuicio")]
    assert db.execute("SELECT qid FROM links WHERE prop = 'P2034' AND value = '1342'").fetchall() == [(170583,)]
    assert sorted(db.execute("SELECT prop, value FROM links WHERE qid = 170583").fetchall()) == \
        [("P1899", "45"), ("P2034", "1342"), ("P2034", "42671"), ("P50", "Q36322")]
    assert db.execute("SELECT lat, lon FROM coords WHERE qid = 84").fetchone() == (51.50722, -0.1275)
    db.close()
    assert meta.check(out, zim) == []
    m = meta.read_meta(out)
    assert m["kind"] == "gqid" and m["matched"] == "3" and m["builder"] == "tools/qidindex/build.py"


def test_the_spanish_side_carries_the_english_title():
    (_, _, other), _, _ = build.rows_of({"q": "Q31", "t": "Bélgica", "sl": 383, "en": "Belgium"})
    assert other == "Belgium"


def test_check_passes_a_good_index_and_fails_low_coverage(tmp_path, capsys):
    from libzim.reader import Archive
    import check
    zim = make_zim(tmp_path)
    out = tmp_path / "wikipedia_en_test_maxi_2026-11.gqid"
    build.build([p for p, _ in articles(Archive(zim))], iter(ITEMS), out, zim, meta.sha256(zim))
    assert check.main([str(out), "--source", str(zim), "--min-share", "0.7"]) == 0
    assert "3/4 articles have an item (75.00%)" in capsys.readouterr().out
    assert check.main([str(out), "--min-share", "0.9"]) == 1
    assert "floor 90%" in capsys.readouterr().out


def test_path_of_reads_the_path_without_parsing_and_handles_escapes(tmp_path):
    import json
    lines = [json.dumps(r, ensure_ascii=False) for r in
             [{"q": "Q1", "t": 'Guerra_de_los_"Cien_Años"', "sl": 3}, {"q": "Q2", "t": "C:\\\\", "sl": 1}, {"q": "Q3", "t": "Plain"}]]
    assert [build.path_of(l) for l in lines] == ['Guerra_de_los_"Cien_Años"', "C:\\\\", "Plain"]
    f = tmp_path / "items.jsonl"
    f.write_text("\n".join(lines) + "\n", encoding="utf8")
    assert [r["q"] for r in build.read_items(f, {"Plain", 'Guerra_de_los_"Cien_Años"'})] == ["Q1", "Q3"]


def make_subjects(tmp_path, rows):
    f = tmp_path / "subjects.sqlite"
    db = sqlite3.connect(f)
    db.execute("CREATE TABLE subjects(qid INTEGER, ord INTEGER, subject TEXT, PRIMARY KEY(qid, ord))")
    db.execute("CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT)")
    db.executemany("INSERT INTO subjects VALUES (?,?,?)", rows)
    db.executemany("INSERT INTO meta VALUES (?,?)", [("subjectsVersion", "1"), ("builtAt", "2026-11-16T00:00:00Z")])
    db.commit(); db.close()
    return f


def test_subjects_are_attached_for_the_index_items_only_and_schema_stays_1(tmp_path):
    from libzim.reader import Archive
    zim = make_zim(tmp_path)
    out = tmp_path / "x.gqid"
    subjects = make_subjects(tmp_path, [(36322, 0, "literature"), (36322, 1, "history"), (170583, 0, "literature"),
                                        (999, 0, "science")])
    counts = build.build([p for p, _ in articles(Archive(zim))], iter(ITEMS), out, zim, meta.sha256(zim), subjects)
    assert counts["subjects"] == 2
    db = sqlite3.connect(out)
    assert db.execute("SELECT qid, ord, subject FROM subjects ORDER BY qid, ord").fetchall() == [
        (36322, 0, "literature"), (36322, 1, "history"), (170583, 0, "literature")]
    db.close()
    m = meta.read_meta(out)
    assert m["schemaVersion"] == "1" and m["builderVersion"] == "2" and m["subjectsVersion"] == "1"
    assert m["withSubjects"] == "2" and m["packSubject"] == ""


def test_a_subject_pack_gives_its_subject_to_items_the_rules_leave_without_one(tmp_path):
    db = sqlite3.connect(tmp_path / "wikipedia_en_chemistry_maxi_2026-10.gqid")
    db.executescript(build.SCHEMA)
    db.executemany("INSERT INTO articles VALUES (?,?,?,?)", [("Niobium", 1046, 9, None), ("Law_of_mass_action", 5, 3, None)])
    db.execute("CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT NOT NULL)")
    db.execute("INSERT INTO meta VALUES ('sourceZim', 'wikipedia_en_chemistry_maxi_2026-10.zim')")
    n = build.add_subjects(db, make_subjects(tmp_path, [(1046, 0, "science"), (1046, 1, "technology")]))
    assert n == 2
    assert db.execute("SELECT qid, ord, subject FROM subjects ORDER BY qid, ord").fetchall() == [
        (5, 0, "science"), (1046, 0, "science"), (1046, 1, "technology")]
    assert build.pack_subject("wikipedia_es_movies_nopic_2026-10.zim") == "film"
    assert build.pack_subject("wikipedia_en_top_nopic_2026-09-14.zim") is None
    assert build.pack_subject("wikipedia_en_all_maxi_2026-08.zim") is None
